package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Decides whether a location fix is good enough to believe.
 *
 * ## Why this exists
 *
 * `ImuSource` registers THREE providers -- FUSED, GPS and NETWORK -- and they
 * all call the same listener. Position was taken from whichever fired last,
 * with no filtering, on the reasoning that "every provider supplies it".
 *
 * They do. They just do not agree. `NETWORK_PROVIDER` derives position from
 * cell towers and wifi, accurate to kilometres, and one of those landing
 * between two good GPS fixes teleports the car and back.
 *
 * Measured on the 2026-08-25 drive: **9 samples out of 2274 (0.4%)** sat
 * ~12 km northwest of the real route. That was enough to draw the long
 * straight lines across the logbook's retrace, because each bad point draws
 * TWO legs -- out and back.
 *
 * ## The two rejections
 *
 * **Accuracy**: a fix claiming 3 km of uncertainty should never replace one
 * claiming 5 m. This alone catches most of it, but `Location.accuracy` is
 * optional and providers lie about it.
 *
 * **Implied speed**: a car that appears to have moved 12 km in one second
 * did not. This is the backstop, and it needs no accuracy metadata at all.
 *
 * Kept here rather than in `ImuSource` so the thresholds are arguable in a
 * test rather than in a car.
 */
object FixGate {

    /**
     * Fastest a road car can plausibly travel, m/s.
     *
     * 90 m/s is 201 mph -- far above anything an ND2 will do, and far below
     * the 6,800 m/s the bogus fixes implied. Deliberately generous: this is
     * a teleport detector, not a speed limit.
     */
    const val MAX_PLAUSIBLE_SPEED_MPS = 90.0

    /**
     * Accuracy beyond which a fix is treated as a rough guess, metres.
     *
     * GPS is typically 3-10 m and NETWORK 1-5 km, so anything past 200 m is
     * a tower triangulation rather than a satellite fix. Not an outright
     * reject -- a rough fix is better than none when there is nothing else.
     */
    const val ROUGH_ACCURACY_M = 200.0

    /**
     * How long a good fix stays authoritative, millis.
     *
     * After this a rough fix is accepted rather than leaving the position
     * frozen: driving into a tunnel or a parking structure should degrade
     * the reading, not strand it somewhere the car no longer is.
     */
    const val GOOD_FIX_HOLD_MS = 30_000L

    /** A candidate position. */
    data class Fix(
        val latDeg: Double,
        val lonDeg: Double,
        val tMs: Long,
        /** Reported horizontal accuracy in metres, or null when unknown. */
        val accuracyM: Double? = null
    )

    /** Why a fix was turned away, for logging. */
    enum class Verdict { ACCEPT, REJECT_TELEPORT, REJECT_ROUGH }

    /**
     * Should [candidate] replace [previous]?
     *
     * @param previous the last ACCEPTED fix, or null when there is none.
     * @param previousWasGood whether that fix came in under [ROUGH_ACCURACY_M].
     */
    fun judge(
        previous: Fix?,
        previousWasGood: Boolean,
        candidate: Fix
    ): Verdict {
        // Nothing to compare against: take it. A rough first fix still tells
        // the radar roughly where to look, and the alternative is no scope
        // at all until GPS locks.
        if (previous == null) return Verdict.ACCEPT

        // A rough fix must not displace a recent good one. Past the hold
        // window it is allowed through, because a frozen position is its own
        // kind of wrong.
        val rough = candidate.accuracyM != null && candidate.accuracyM > ROUGH_ACCURACY_M
        val age = candidate.tMs - previous.tMs
        if (rough && previousWasGood && age in 0 until GOOD_FIX_HOLD_MS) {
            return Verdict.REJECT_ROUGH
        }

        // The backstop, and the one that needs no metadata: a car that
        // appears to have crossed 12 km in a second did not.
        val dtSec = age / 1000.0
        if (dtSec > 0.0) {
            val d = metresBetween(
                previous.latDeg, previous.lonDeg, candidate.latDeg, candidate.lonDeg
            )
            if (d / dtSec > MAX_PLAUSIBLE_SPEED_MPS) return Verdict.REJECT_TELEPORT
        }

        return Verdict.ACCEPT
    }

    /**
     * Flat-earth distance in metres. Fine over the scale of a fix-to-fix
     * step; this is not navigation, it is an outlier test.
     */
    fun metresBetween(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double
    ): Double {
        val mPerDeg = 111_320.0
        val dLat = (lat2 - lat1) * mPerDeg
        val dLon = (lon2 - lon1) * mPerDeg * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot(abs(dLat), abs(dLon))
    }
}
