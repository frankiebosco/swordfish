package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Answers the two open questions about [WheelSpeeds] from a saved capture.
 *
 * ## Why this is a class and not a one-off script
 *
 * Both remaining unknowns -- the speed scale factor, and which side is left --
 * need a specific drive, and neither can be settled by staring at existing
 * data. Encoding the analysis here means the moment a suitable capture exists
 * the answers fall out, with no fresh code written under time pressure and
 * with the acceptance criteria fixed in advance rather than after seeing the
 * numbers.
 *
 * It runs offline against files already on disk, in the spirit of
 * [MsCanReplay]: a better hypothesis should never require driving again.
 *
 * ## The two calibrations
 *
 * **Scale** ([fitScale]): needs a capture whose drive log has trustworthy GPS
 * speed. Fits counts-per-m/s by least squares through the origin -- through
 * the origin because zero counts must mean zero speed, and a free intercept
 * would happily fit a nonzero speed at a standstill.
 *
 * **Side** ([fitSide]): needs a sustained turn in a KNOWN direction. A
 * counter-clockwise circle is a continuous left turn, so the outside (right)
 * wheels run faster throughout. That single fact resolves the sign.
 *
 * ## Why GPS speed needs guarding
 *
 * The first attempt at fitting scale produced impossible values -- up to
 * 65 m/s (145 mph) -- because it differenced GPS positions across the 10 s
 * gaps the capture then had. Every fit here therefore rejects samples whose
 * supporting GPS interval is too long or whose implied speed is absurd,
 * and reports how many it dropped. A fit that silently uses bad reference
 * data produces a confident wrong constant, which is worse than no constant.
 */
object WheelCalibration {

    /** Fastest speed treated as physically possible, for reference sanity. */
    const val MAX_PLAUSIBLE_MPS = 60.0

    /** Longest GPS interval that may support a derived speed. */
    const val MAX_GPS_INTERVAL_MS = 3_000L

    /** Shortest GPS interval; below this, position noise dominates. */
    const val MIN_GPS_INTERVAL_MS = 500L

    /** Minimum speed for a sample to inform the scale fit. */
    const val MIN_SPEED_FOR_SCALE_MPS = 3.0

    /** How near in time a wheel frame and a GPS speed must be to pair. */
    const val PAIR_TOLERANCE_MS = 750L

    /**
     * Result of fitting counts to m/s.
     *
     * @param countsPerMps the fitted constant; multiply m/s by this to get
     *   counts, or divide counts by it to get m/s.
     * @param samples how many paired points informed the fit.
     * @param rejected how many candidate points were discarded as untrustworthy.
     * @param residualStdevMps scatter about the fit, in m/s. THIS is the number
     *   that says whether to believe the constant.
     */
    data class ScaleFit(
        val countsPerMps: Double,
        val samples: Int,
        val rejected: Int,
        val residualStdevMps: Double
    ) {
        /** Speed in m/s for a raw count, using this fit. */
        fun mps(counts: Double): Double = counts / countsPerMps

        /**
         * Whether this fit is good enough to build on.
         *
         * Deliberately strict, and fixed BEFORE seeing tonight's data: at
         * least 200 points and under 1.5 m/s of scatter. A fit that fails
         * this should be re-driven, not rounded up.
         */
        val isTrustworthy: Boolean
            get() = samples >= 200 && residualStdevMps < 1.5
    }

    /**
     * Result of resolving which frame positions are the left-hand wheels.
     *
     * @param convention what the data says.
     * @param confidence fraction of turning samples agreeing with it, 0.5 =
     *   coin flip, 1.0 = unanimous.
     * @param turningSamples how many samples showed a resolvable turn.
     */
    data class SideFit(
        val convention: WheelSpeeds.SideConvention,
        val confidence: Double,
        val turningSamples: Int
    ) {
        /**
         * Whether to accept this as settled.
         *
         * A sustained single-direction circle should be near-unanimous. Any
         * real ambiguity means the drive was not what it was supposed to be --
         * a lot loop with a bend the wrong way, say -- and the answer should
         * be thrown out rather than believed at 60%.
         */
        val isTrustworthy: Boolean
            get() = turningSamples >= 100 && confidence >= 0.90
    }

    /** A time-stamped speed, in m/s, derived from consecutive GPS fixes. */
    data class RefSpeed(val atMs: Long, val mps: Double)

    /**
     * Derive a speed series from a drive log's GPS positions.
     *
     * Consecutive UNIQUE positions only: the fix is held between updates, so
     * differencing raw rows yields a string of zeros punctuated by jumps, and
     * the jumps get attributed to the wrong instant.
     */
    fun gpsSpeeds(driveLines: List<String>): List<RefSpeed> {
        data class Fix(val t: Long, val lat: Double, val lon: Double)

        val fixes = ArrayList<Fix>()
        for (raw in driveLines) {
            val s = raw.trim()
            if (!s.startsWith("{")) continue
            val t = DriveLog.num(s, "t")?.toLong() ?: continue
            val lat = DriveLog.num(s, "lat") ?: continue
            val lon = DriveLog.num(s, "lon") ?: continue
            val last = fixes.lastOrNull()
            if (last != null && last.lat == lat && last.lon == lon) continue
            fixes += Fix(t, lat, lon)
        }

        val out = ArrayList<RefSpeed>()
        for (i in 1 until fixes.size) {
            val dt = fixes[i].t - fixes[i - 1].t
            if (dt < MIN_GPS_INTERVAL_MS || dt > MAX_GPS_INTERVAL_MS) continue
            val d = haversineMetres(
                fixes[i - 1].lat, fixes[i - 1].lon,
                fixes[i].lat, fixes[i].lon
            )
            val v = d / (dt / 1000.0)
            if (v > MAX_PLAUSIBLE_MPS) continue
            out += RefSpeed((fixes[i].t + fixes[i - 1].t) / 2, v)
        }
        return out
    }

    /**
     * Fit counts-per-m/s from a capture and its drive log.
     *
     * Least squares through the origin: zero counts is zero speed by
     * construction, and allowing an intercept would let the fit absorb a
     * standstill offset that does not physically exist.
     */
    fun fitScale(captureLines: List<String>, driveLines: List<String>): ScaleFit? {
        val frames = MsCanReplay.parseFrames(captureLines)
            .filter { it.canId == WheelSpeeds.CAN_ID }
        val refs = gpsSpeeds(driveLines)
        if (frames.isEmpty() || refs.isEmpty()) return null

        var rejected = 0
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()

        for (f in frames) {
            val r = WheelSpeeds.decode(f.data)
            if (r == null) { rejected++; continue }
            if (!r.isMoving) continue
            val v = nearest(refs, f.atMs)
            if (v == null) { rejected++; continue }
            if (v < MIN_SPEED_FOR_SCALE_MPS) continue
            xs += v
            ys += r.aggregate
        }
        if (xs.size < 10) return null

        // slope through origin: sum(xy) / sum(x^2)
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += xs[i] * ys[i]
            sxx += xs[i] * xs[i]
        }
        if (sxx == 0.0) return null
        val k = sxy / sxx

        var acc = 0.0
        for (i in xs.indices) {
            val predicted = ys[i] / k
            acc += (predicted - xs[i]) * (predicted - xs[i])
        }
        val stdev = sqrt(acc / xs.size)

        return ScaleFit(k, xs.size, rejected, stdev)
    }

    /**
     * Resolve which frame positions are the left wheels, from a known-direction turn.
     *
     * @param turnedLeft true if the whole capture is a sustained LEFT turn --
     *   a counter-clockwise circle. In a left turn the RIGHT wheels are on the
     *   outside and travel further, so they read faster.
     *
     * Only samples with a resolvable turn are counted; straight-line running
     * carries no information about side and would dilute the result toward a
     * coin flip.
     */
    fun fitSide(captureLines: List<String>, turnedLeft: Boolean): SideFit {
        val frames = MsCanReplay.parseFrames(captureLines)
            .filter { it.canId == WheelSpeeds.CAN_ID }

        var pos02Faster = 0
        var pos13Faster = 0
        for (f in frames) {
            val r = WheelSpeeds.decode(f.data) ?: continue
            // CORNERING, not merely "turning". `isTurning` uses the sensor
            // resolution floor (0.5 counts), which on real data admits
            // straight-line scatter of +/-15.7 -- noise that splits ~50/50
            // and buries the answer. Three fits returned 45%/33%/57% this
            // way. See WheelSpeeds.CORNERING_THRESHOLD.
            if (!r.isCornering) continue
            if (r.sideDifference > 0) pos02Faster++ else pos13Faster++
        }

        val n = pos02Faster + pos13Faster
        if (n == 0) {
            return SideFit(WheelSpeeds.SideConvention.UNKNOWN, 0.0, 0)
        }

        // In a LEFT turn the right-hand wheels are outside and faster.
        // So whichever pair is faster is the RIGHT pair, making the other left.
        val outsideIs02 = pos02Faster > pos13Faster
        val agreeing = maxOf(pos02Faster, pos13Faster)
        val convention = if (turnedLeft) {
            if (outsideIs02) WheelSpeeds.SideConvention.POS_1_3_IS_LEFT
            else WheelSpeeds.SideConvention.POS_0_2_IS_LEFT
        } else {
            if (outsideIs02) WheelSpeeds.SideConvention.POS_0_2_IS_LEFT
            else WheelSpeeds.SideConvention.POS_1_3_IS_LEFT
        }
        return SideFit(convention, agreeing.toDouble() / n, n)
    }

    /**
     * Human-readable verdict for both fits.
     *
     * Written to be read from a terminal after a calibration drive, and to say
     * plainly when an answer should NOT be trusted -- the failure this whole
     * class exists to avoid is a confident wrong constant.
     */
    fun report(
        captureLines: List<String>,
        driveLines: List<String>,
        turnedLeft: Boolean?
    ): String = buildString {
        val scale = fitScale(captureLines, driveLines)
        appendLine("SCALE (counts per m/s)")
        if (scale == null) {
            appendLine("  no fit -- no usable wheel frames or no GPS reference")
        } else {
            appendLine("  countsPerMps   = %.2f".format(scale.countsPerMps))
            appendLine("  => 1 count     = %.5f m/s".format(1.0 / scale.countsPerMps))
            appendLine("  samples        = ${scale.samples} (rejected ${scale.rejected})")
            appendLine("  residual stdev = %.2f m/s".format(scale.residualStdevMps))
            appendLine(
                if (scale.isTrustworthy) "  VERDICT: usable"
                else "  VERDICT: DO NOT USE -- re-drive with a steady GPS-tracked cruise"
            )
        }

        appendLine()
        appendLine("SIDE (which frame positions are the left wheels)")
        if (turnedLeft == null) {
            appendLine("  skipped -- this capture is not a known-direction turn")
        } else {
            val side = fitSide(captureLines, turnedLeft)
            appendLine("  convention      = ${side.convention}")
            appendLine("  confidence      = %.1f%%".format(side.confidence * 100))
            appendLine("  turning samples = ${side.turningSamples}")
            appendLine(
                if (side.isTrustworthy) "  VERDICT: settled"
                else "  VERDICT: NOT settled -- needs a sustained single-direction circle"
            )
        }
    }

    private fun nearest(refs: List<RefSpeed>, atMs: Long): Double? {
        if (refs.isEmpty()) return null
        var lo = 0
        var hi = refs.size - 1
        if (atMs <= refs[0].atMs) {
            return if (refs[0].atMs - atMs <= PAIR_TOLERANCE_MS) refs[0].mps else null
        }
        if (atMs >= refs[hi].atMs) {
            return if (atMs - refs[hi].atMs <= PAIR_TOLERANCE_MS) refs[hi].mps else null
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (refs[mid].atMs <= atMs) lo = mid else hi = mid
        }
        val a = refs[lo]
        val b = refs[hi]
        val best = if (abs(a.atMs - atMs) <= abs(b.atMs - atMs)) a else b
        return if (abs(best.atMs - atMs) <= PAIR_TOLERANCE_MS) best.mps else null
    }

    private fun haversineMetres(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(sqrt(a))
    }
}
