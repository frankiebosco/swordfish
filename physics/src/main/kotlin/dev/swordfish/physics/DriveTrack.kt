package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Projects a drive's GPS trace onto a canvas, with no basemap.
 *
 * ## Why there is no map under it
 *
 * A tile source means an API key, a quota and a network dependency, and it
 * answers a question the logbook is not asking. The shape of the drive plus
 * **how efficiently each stretch was driven** is the interesting part; the
 * road names are already known to whoever drove it.
 *
 * This is the same trade the radar scope makes deliberately, and it keeps
 * the logbook working with no signal.
 *
 * ## The projection
 *
 * Equirectangular, with longitude scaled by cos(latitude). Over one drive —
 * tens of miles at most — this is visually indistinguishable from a proper
 * projection, and it needs no library. Without the cosine the track is
 * stretched east-west by about 25% at these latitudes, which is enough to
 * make a familiar route look wrong.
 *
 * The aspect ratio is PRESERVED: a drive is a shape, and stretching it to
 * fill a box would make a straight motorway run look like a curve.
 */
object DriveTrack {

    /** One point of the drawn track. */
    data class Point(
        /** 0..1 across the canvas, left to right. */
        val x: Double,
        /** 0..1 down the canvas, top to bottom. */
        val y: Double,
        /**
         * How well this stretch was driven, 0..1, or null when unknown.
         *
         * Drives the colour. Null happens at the start of a drive before
         * the model has anything to say, and while stopped.
         */
        val efficiency: Double?
    )

    data class Track(
        val points: List<Point>,
        /** Span of the drive in metres, for a scale bar. */
        val widthMeters: Double,
        val heightMeters: Double
    ) {
        val isEmpty: Boolean get() = points.size < 2
    }

    /**
     * Build a drawable track.
     *
     * @param samples parsed drive samples, in order.
     * @param efficiencyOf maps a sample to 0..1, or null when it cannot be
     *   judged. Injected rather than hardcoded so the logbook can colour by
     *   Isp today and something better later without touching projection.
     */
    fun build(
        samples: List<DriveLog.Sample>,
        efficiencyOf: (DriveLog.Sample) -> Double? = ::ispEfficiency
    ): Track {
        // OUTLIERS ARE DROPPED BEFORE ANYTHING IS MEASURED.
        //
        // `FixGate` now stops bad positions being recorded, but every log
        // written before it still contains them -- and one bad point draws
        // TWO long legs, out and back. On the 2026-08-25 drive, 9 samples out
        // of 2274 sat ~12 km off-route and were the entire cause of the
        // straight lines across the retrace.
        //
        // Dropping them here also keeps the bounding box honest: a single
        // 12 km outlier stretches the box so far that the real route collapses
        // into a few pixels in the corner.
        val fixed = dropOutliers(samples.filter { it.hasFix })
        if (fixed.size < 2) return Track(emptyList(), 0.0, 0.0)

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (s in fixed) {
            val la = s.lat!!
            val lo = s.lon!!
            if (la < minLat) minLat = la
            if (la > maxLat) maxLat = la
            if (lo < minLon) minLon = lo
            if (lo > maxLon) maxLon = lo
        }

        val midLat = (minLat + maxLat) / 2.0
        val lonScale = cos(Math.toRadians(midLat)).coerceAtLeast(0.01)

        // Metres per degree, near enough over one drive.
        val mPerDegLat = 111_320.0
        val spanLatM = (maxLat - minLat) * mPerDegLat
        val spanLonM = (maxLon - minLon) * mPerDegLat * lonScale

        // A drive that never moved, or moved only north-south, still has to
        // produce a finite box.
        val span = max(max(spanLatM, spanLonM), 1.0)

        val pts = ArrayList<Point>(fixed.size)
        for (s in fixed) {
            val dxM = (s.lon!! - minLon) * mPerDegLat * lonScale
            val dyM = (s.lat!! - minLat) * mPerDegLat
            // Centre the shorter axis so the track sits in the middle rather
            // than hugging an edge. Y is flipped: north is up.
            val x = 0.5 + (dxM - spanLonM / 2.0) / span
            val y = 0.5 - (dyM - spanLatM / 2.0) / span
            pts += Point(x, y, efficiencyOf(s))
        }
        return Track(pts, spanLonM, spanLatM)
    }

    /**
     * Remove positions that imply an impossible speed.
     *
     * Uses the same threshold as [FixGate] so a log written before the gate
     * existed draws the same route as one written after it.
     *
     * Deliberately compares against the last KEPT point rather than the
     * previous one: a run of bad fixes would otherwise validate each other,
     * and the outlier cluster on the 2026-08-25 drive was up to six samples
     * long.
     */
    fun dropOutliers(samples: List<DriveLog.Sample>): List<DriveLog.Sample> {
        if (samples.size < 2) return samples
        val out = ArrayList<DriveLog.Sample>(samples.size)
        var lastKept: DriveLog.Sample? = null
        for (s in samples) {
            val prev = lastKept
            if (prev == null) {
                out += s
                lastKept = s
                continue
            }
            val dtSec = (s.tMs - prev.tMs) / 1000.0
            val d = FixGate.metresBetween(prev.lat!!, prev.lon!!, s.lat!!, s.lon!!)

            // NO USABLE INTERVAL MEANS NO SPEED TEST, NOT A DROP.
            //
            // Samples can share a timestamp -- 1 Hz logging rounds, and
            // synthetic fixtures often use zero throughout. Treating that as
            // "infinitely fast" discarded every point after the first, which
            // is a far worse failure than letting an outlier through.
            val keep = if (dtSec <= 0.0) {
                true
            } else {
                d / dtSec <= FixGate.MAX_PLAUSIBLE_SPEED_MPS
            }
            if (keep) {
                out += s
                lastKept = s
            }
        }
        return out
    }

    /**
     * Efficiency from Isp, normalised to 0..1.
     *
     * Isp is the hero stat — it is what the panel rewards — so colouring the
     * track by it makes the map say the same thing the gauge said, which is
     * what makes a retrace worth looking at rather than merely pretty.
     *
     * Returns null when stopped or in fuel cutoff. Idling is genuinely
     * terrible efficiency and DFCO is infinite; painting either on the map
     * would swamp the range that the actual driving occupies.
     */
    fun ispEfficiency(s: DriveLog.Sample): Double? {
        if (s.dfco) return null
        val v = s.speedMps ?: return null
        if (v < DriveLog.MOVING_THRESHOLD_MPS) return null
        val isp = s.ispS ?: return null
        if (isp <= 0.0) return null
        return ((isp - ISP_FLOOR) / (ISP_CEILING - ISP_FLOOR)).coerceIn(0.0, 1.0)
    }

    /**
     * The Isp band the colour ramp spans.
     *
     * Taken from what the car actually produces: cruising sits near 31,500 s
     * and hard acceleration falls well below. A ramp anchored to theoretical
     * limits instead would leave every real drive the same colour.
     */
    const val ISP_FLOOR = 8_000.0
    const val ISP_CEILING = 40_000.0

    /**
     * Total path length of the drawn track, in metres.
     *
     * Not the same as [DriveLog.Summary.distanceMeters], which integrates
     * wheel speed. Comparing the two is a genuine check on the GPS: a large
     * disagreement means a poor fix, not a poor drive.
     */
    fun pathLengthMeters(track: Track): Double {
        if (track.isEmpty) return 0.0
        val span = max(max(track.widthMeters, track.heightMeters), 1.0)
        var total = 0.0
        for (i in 1 until track.points.size) {
            val a = track.points[i - 1]
            val b = track.points[i]
            val dx = (b.x - a.x) * span
            val dy = (b.y - a.y) * span
            total += kotlin.math.hypot(abs(dx), abs(dy))
        }
        return total
    }
}
