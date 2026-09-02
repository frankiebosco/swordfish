package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns captured MS-CAN frames into identified signals.
 *
 * ## What this is for
 *
 * `MsCanProbe` proved the bus is reachable -- 227 frames across 18 IDs on
 * 2026-08-20 -- but nothing in those frames is decoded. Every byte is
 * unlabelled. This scores each candidate byte-pair against a reference
 * signal the phone already measures, so a driving manoeuvre identifies the
 * bytes rather than a datasheet nobody has.
 *
 * ## No special manoeuvre is needed
 *
 * An early version of this assumed a steady circle in an empty car park.
 * That is a cleaner signal but an unreasonable ask -- it looks like someone
 * doing donuts, and it cannot be done discreetly in daylight.
 *
 * It is also unnecessary. Measured on an ordinary ridge-road coffee drive:
 *
 * | | |
 * |---|---|
 * | usable yaw samples | 1371 |
 * | yaw-rate std dev | 0.090 rad/s |
 * | corners above 0.15 rad/s | 44 |
 * | left / right turns | 24 / 20 |
 *
 * Correlation needs VARIANCE in the reference, not a particular shape of
 * manoeuvre. Ordinary cornering supplies plenty, and the drive already
 * turns both ways -- which matters, because a signed sensor whose negative
 * range is never exercised cannot have its sign convention resolved.
 *
 * So: drive normally. The longer the drive, the stronger the result.
 *
 * ## Why yaw is worth having
 *
 * The navball's heading comes from GPS bearing today: absolute and
 * drift-free, but only above 2 m/s and only as fast as fixes arrive. A
 * chassis yaw-rate signal is instant, works at any speed, and does not care
 * where the phone is lying. It is the difference between a compass that
 * lags a corner and one that leads it.
 *
 * Wheel-speed differential gives the same thing if a yaw sensor is absent:
 * `(v_right - v_left) / track_width`.
 *
 * **Roll cannot come from wheel speeds** -- the wheels stay level while the
 * body leans. If the stability-control module broadcasts lateral
 * acceleration, that is the better source, and this will find it the same
 * way.
 */
object MsCanIdentify {

    /** One decoded candidate: a byte offset within an ID, read one way. */
    data class Candidate(
        val canId: String,
        /** Byte offset of the first byte of the pair. */
        val offset: Int,
        val bigEndian: Boolean,
        val signed: Boolean,
        /** Pearson correlation against the reference, -1..1. */
        val correlation: Double,
        /** Least-squares scale: reference = scale * raw + offsetValue. */
        val scale: Double,
        val offsetValue: Double,
        val samples: Int
    ) {
        /** Strength regardless of sign -- an inverted signal is still a match. */
        val strength: Double get() = abs(correlation)

        fun describe(): String =
            "$canId[$offset..${offset + 1}] " +
                (if (bigEndian) "BE" else "LE") +
                (if (signed) " signed" else " unsigned") +
                " r=${"%.3f".format(correlation)}" +
                " scale=${"%.5f".format(scale)}"
    }

    /** A frame paired with whatever the phone measured at that instant. */
    data class Observation(
        val canId: String,
        /** Payload bytes, as captured. */
        val data: List<Int>,
        /** The reference value at this moment -- e.g. yaw rate from GPS. */
        val reference: Double
    )

    /**
     * Score every plausible byte-pair in every ID against the reference.
     *
     * Tries big and little endian, signed and unsigned, because MS-CAN
     * carries all four and guessing wrong makes a real signal look like
     * noise.
     *
     * @param minSamples an ID seen fewer times than this cannot be scored --
     *   a correlation over five points is an accident, not evidence.
     * @return candidates sorted strongest first.
     */
    fun identify(
        observations: List<Observation>,
        minSamples: Int = 30
    ): List<Candidate> {
        val byId = observations.groupBy { it.canId }
        val out = ArrayList<Candidate>()

        for ((id, obs) in byId) {
            if (obs.size < minSamples) continue
            val width = obs.minOf { it.data.size }
            if (width < 2) continue

            for (off in 0 until width - 1) {
                for (bigEndian in listOf(true, false)) {
                    for (signed in listOf(false, true)) {
                        val raw = obs.map {
                            decodePair(it.data, off, bigEndian, signed).toDouble()
                        }
                        val ref = obs.map { it.reference }
                        val r = correlation(raw, ref) ?: continue
                        val (scale, intercept) = leastSquares(raw, ref) ?: continue
                        out += Candidate(
                            canId = id,
                            offset = off,
                            bigEndian = bigEndian,
                            signed = signed,
                            correlation = r,
                            scale = scale,
                            offsetValue = intercept,
                            samples = obs.size
                        )
                    }
                }
            }
        }
        return out.sortedByDescending { it.strength }
    }

    /**
     * Read two bytes as one value.
     *
     * @param signed interpret as two's complement. Yaw rate and lateral
     *   acceleration are both signed -- they have a direction -- so an
     *   unsigned read makes a left turn look like a huge right one.
     */
    fun decodePair(
        data: List<Int>, offset: Int, bigEndian: Boolean, signed: Boolean
    ): Int {
        if (offset + 1 >= data.size) return 0
        val a = data[offset] and 0xFF
        val b = data[offset + 1] and 0xFF
        val v = if (bigEndian) (a shl 8) or b else (b shl 8) or a
        return if (signed && v >= 0x8000) v - 0x10000 else v
    }

    /**
     * Pearson correlation, or null when either series never varies.
     *
     * A constant byte correlates with nothing, and dividing by its zero
     * variance would produce a NaN that sorts to the top of the results.
     */
    fun correlation(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 3) return null
        val n = a.size
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        if (da < 1e-9 || db < 1e-9) return null
        return num / sqrt(da * db)
    }

    /** Least-squares fit of `reference = scale * raw + intercept`. */
    fun leastSquares(raw: List<Double>, ref: List<Double>): Pair<Double, Double>? {
        if (raw.size != ref.size || raw.size < 3) return null
        val n = raw.size
        val mr = raw.average()
        val mf = ref.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val x = raw[i] - mr
            num += x * (ref[i] - mf)
            den += x * x
        }
        if (den < 1e-9) return null
        val scale = num / den
        return scale to (mf - scale * mr)
    }

    /**
     * Yaw rate from GPS bearing, radians per second.
     *
     * The reference the circle manoeuvre is scored against. Crude -- bearing
     * is quantised and noisy -- but a steady circle produces a yaw rate an
     * order of magnitude above that noise, which is why the manoeuvre is a
     * circle and not gentle cornering.
     *
     * @return null when either bearing is missing or the interval is unusable.
     */
    fun yawRateFromBearings(
        bearingA: Double?, bearingB: Double?, dtSec: Double
    ): Double? {
        if (bearingA == null || bearingB == null) return null
        if (dtSec <= 0.0 || dtSec > 2.0) return null
        var d = bearingB - bearingA
        // Shortest way round: 359 -> 1 is +2 degrees, not -358.
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return Math.toRadians(d) / dtSec
    }

    /**
     * Yaw rate from wheel speeds, radians per second.
     *
     * The fallback if no yaw sensor is broadcast: a car turning has its
     * outside wheels travelling faster, and the difference over the track
     * width IS the yaw rate. Drift-free, unlike a gyro, and independent of
     * GPS entirely.
     *
     * @param trackWidthM centre-to-centre wheel separation. The ND2 is
     *   about 1.495 m at the rear.
     */
    fun yawRateFromWheels(
        leftMps: Double, rightMps: Double, trackWidthM: Double = ND2_TRACK_WIDTH_M
    ): Double {
        if (trackWidthM <= 0.0) return 0.0
        return (rightMps - leftMps) / trackWidthM
    }

    /** ND2 rear track width, metres. */
    const val ND2_TRACK_WIDTH_M = 1.495

    // ---------------------------------------------------------------
    // Traction
    // ---------------------------------------------------------------

    /**
     * Wheel slip: how much faster a driven wheel turns than the car moves.
     *
     * `Traction` already models the friction circle from lateral and
     * longitudinal g, and its own note concedes the limit: *"we cannot see
     * individual wheel slip"*. The car plainly can -- it has ABS and
     * stability control, and it intervenes -- so the signal exists on the
     * vehicle somewhere. Whether it is BROADCAST on MS-CAN is the open
     * question this is built to answer.
     *
     * On a rear-wheel-drive car the rears are driven and the fronts are not,
     * so the fronts are a reference of true road speed. A driven wheel
     * turning faster than that is slipping.
     *
     * @return slip as a fraction of road speed. 0.0 is gripping; 0.1 is a
     *   wheel turning 10% faster than the car is moving.
     */
    fun slipRatio(drivenMps: Double, referenceMps: Double): Double {
        if (referenceMps < 1.0) return 0.0   // meaningless from rest
        return (drivenMps - referenceMps) / referenceMps
    }

    /**
     * Reference signals worth searching the bus for, and what identifies them.
     *
     * Correlation needs something the phone ALREADY measures. That is what
     * makes yaw findable and traction harder: the phone has no independent
     * view of wheel slip, so there is nothing to score a candidate against
     * directly.
     *
     * The way in is indirect. Individual wheel speeds ARE findable, because
     * each correlates with road speed, which the OBD side already reports.
     * Find the four wheel-speed bytes and slip falls out of arithmetic --
     * no separate slip signal needed, and no reference required for it.
     */
    enum class Target(val description: String, val reference: String) {
        YAW_RATE(
            "chassis yaw rate",
            "GPS bearing change -- large through any corner"
        ),
        WHEEL_SPEED(
            "individual wheel speeds",
            "OBD road speed -- all four track it closely while gripping"
        ),
        LATERAL_G(
            "lateral acceleration",
            "phone accelerometer, once the mount is calibrated"
        ),
        SLIP(
            "wheel slip / traction event",
            "DERIVED from wheel speeds once those are identified; the phone " +
                "has no independent view of it, so it cannot be searched for " +
                "directly"
        )
    }

    /**
     * A verdict on a set of candidates, for the probe screen.
     *
     * Deliberately conservative about what counts as found. A correlation of
     * 0.9 over 30 samples of a steady circle is suggestive, not proven --
     * the honest next step is a second manoeuvre that the same bytes also
     * predict.
     */
    fun verdict(candidates: List<Candidate>): String {
        val best = candidates.firstOrNull()
            ?: return "NO CANDIDATES — no ID had enough samples to score"
        return when {
            best.strength >= 0.95 ->
                "STRONG: ${best.describe()} — confirm with a second manoeuvre"
            best.strength >= 0.80 ->
                "PROMISING: ${best.describe()} — needs a longer capture"
            else ->
                "WEAK: best r=${"%.2f".format(best.correlation)} — no byte tracks " +
                    "the reference; the signal may not be on this bus"
        }
    }
}
