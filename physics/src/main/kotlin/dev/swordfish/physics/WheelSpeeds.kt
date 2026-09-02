package dev.swordfish.physics

import kotlin.math.abs

/**
 * The car's own four wheel-speed sensors, decoded from MS-CAN arbitration ID
 * `215`.
 *
 * ## What this replaces
 *
 * [Traction] estimates grip from the phone's IMU because, for most of this
 * project's life, the car's wheel sensors were believed unreachable. They are
 * not: the 2026-08-27 captures decoded `215` and it carries all four wheels at
 * ~10 Hz. This is the car's own view -- rigidly attached to the vehicle, immune
 * to the phone sliding on the passenger seat, and available at speeds and in
 * places where GPS heading is useless (low speed, tunnels, tree cover).
 *
 * It does NOT replace the IMU path. See [curvature] for why: this is a RATE
 * with no absolute heading, and it is blind below walking pace.
 *
 * ## Frame layout (CONFIRMED 2026-08-27)
 *
 * Four 16-bit big-endian values in one 8-byte frame, each offset by
 * [STATIONARY_OFFSET]:
 *
 * ```
 * 27 10 27 10 27 10 27 10  ->  [10000, 10000, 10000, 10000]   stationary
 * 2A 91 2A CF 2A 97 2A CF  ->  [10897, 10959, 10903, 10959]   moving
 * ```
 *
 * `0x2710` = 10000 is the stationary sentinel, so a raw value BELOW it is not
 * a negative speed -- it is a malformed frame, and is rejected.
 *
 * **Positions 0 and 2 are one side of the car; 1 and 3 are the other.** The
 * layout alternates by side rather than running axle-by-axle. This was
 * determined by which pairing spreads most through turns (86.3 stdev against
 * 20.7 and 5.0 for the alternatives) and independently confirmed against GPS
 * yaw rate at r = +0.781.
 *
 * ## What is still UNKNOWN, and why this class does not pretend otherwise
 *
 * Two sign questions are unresolved, and no amount of analysis of existing
 * captures settles them -- every drive so far mixed left and right turns, so
 * the sign never separates:
 *
 *  - **Which side is left.** [sideDifference] is therefore signed but
 *    UNLABELLED. [curvature] inherits that ambiguity.
 *  - **Which position is front.** Needed for slip, since the MX-5 is RWD and
 *    only the rear axle is driven.
 *
 * Both are answered by ONE deliberate maneuver: a slow, wide circle in a known
 * direction, then a firm straight-line pull. Until that is driven,
 * [SideConvention] carries the unknown explicitly rather than guessing, so a
 * wrong assumption cannot silently propagate into a navball reading backwards.
 */
object WheelSpeeds {

    /** The value all four wheels report at a standstill (`0x2710`). */
    const val STATIONARY_OFFSET = 10_000

    /**
     * Smallest resolvable difference between the two sides, in counts.
     *
     * Measured from the captures: differences quantise to 0.5 because a side
     * is the mean of two integer wheels. Anything below this is noise, not a
     * turn.
     */
    const val SIDE_DIFF_RESOLUTION = 0.5

    /**
     * Side difference above which the car is definitely CORNERING.
     *
     * [SIDE_DIFF_RESOLUTION] (0.5) is the sensor's resolution floor -- the
     * smallest difference that is not a rounding artefact. It is NOT a
     * cornering threshold, and using it as one is what defeated three
     * side-convention fits.
     *
     * Measured on straight-line running (2026-08-29, n=200): the side
     * difference scatters with a standard deviation of **15.7 counts** and a
     * median of exactly 0. Wheels on a straight road do not read equal --
     * tyre circumference, pressure and load all differ slightly -- so any
     * threshold below that scatter admits mostly noise, and noise splits
     * ~50/50 and drowns the real turns.
     *
     * 20 counts sits just above that scatter. On the circle runs it retains
     * ~76-81 genuinely cornering samples out of ~340 frames and lifts
     * agreement from ~54% to ~80%.
     */
    const val CORNERING_THRESHOLD = 20.0

    /**
     * Below this aggregate count, treat the car as stopped.
     *
     * Not zero: at a crawl the wheels dither by a count or two, and
     * [curvature] divides by speed, so a near-zero denominator would produce
     * enormous phantom yaw.
     */
    const val MIN_MOVING_COUNTS = 50.0

    /**
     * ND2 axle track in metres (front 1.495, rear 1.505 -- front used).
     *
     * Catalogue figure, not measured on this car. It scales yaw rate
     * linearly, so a 1% error in track is a 1% error in yaw.
     */
    const val ND2_TRACK_WIDTH_M = 1.495

    /**
     * Which frame position is the left side of the car.
     *
     * UNKNOWN until a known-direction circle is driven. Code that needs a
     * true left/right sign must handle [UNKNOWN] rather than assume.
     */
    enum class SideConvention {
        /** Not yet determined. [Reading.sideDifference] sign is meaningless. */
        UNKNOWN,

        /** Frame positions 0 and 2 are the left-hand wheels. */
        POS_0_2_IS_LEFT,

        /** Frame positions 1 and 3 are the left-hand wheels. */
        POS_1_3_IS_LEFT
    }

    /**
     * The measured convention for this car: **positions 1 and 3 are LEFT**,
     * so positions 0 and 2 are the RIGHT-hand wheels.
     *
     * ## How this was determined
     *
     * Two independent controlled left-turn runs at the same traffic circle,
     * both driven counter-clockwise (a sustained LEFT turn), selecting only
     * frames where the car was genuinely cornering (`|sideDifference| > 20`,
     * well clear of the +/-15.7 straight-line scatter):
     *
     * | drive | cornering samples | (0,2) faster |
     * |---|---|---|
     * | 2026-08-28 | 81 | 78% |
     * | 2026-08-29 | 76 | 82% |
     *
     * In a left turn the RIGHT wheels travel the larger radius and read
     * faster. Positions (0,2) were consistently the faster pair, so (0,2) is
     * the right side and (1,3) is the left.
     *
     * ## Why the earlier attempts failed, and what NOT to repeat
     *
     * Three prior analyses returned noise (45%, 33%, 57%) for reasons that
     * were all method, not data:
     *
     * 1. **GPS was used to decide which frames were "cornering."** It is not
     *    needed and actively harmful: GPS heading updates at ~1 Hz against
     *    wheel frames at 10 Hz, so most of the signal was discarded and
     *    replaced with interpolation noise. **The wheels identify cornering
     *    by themselves** -- that is what `sideDifference` IS.
     * 2. **Straight-line samples were included.** They dominate any capture
     *    and carry no side difference, diluting a real result to a coin flip.
     * 3. **The 2026-08-27 ridge-road-loop captures were pooled in.** Those are
     *    ordinary road driving containing turns in BOTH directions, so their
     *    sign is meaningless for this question. Only controlled
     *    single-direction runs can answer it.
     *
     * **Do not re-derive this from GPS.** If it is ever re-checked, use a
     * known-direction circle, filter to cornering frames by magnitude, and
     * count signs.
     */
    val MEASURED_CONVENTION = SideConvention.POS_1_3_IS_LEFT

    /**
     * One decoded frame.
     *
     * @param raw the four values exactly as transmitted, offset included.
     * @param counts the four values with [STATIONARY_OFFSET] removed.
     */
    data class Reading(
        val raw: List<Int>,
        val counts: List<Double>
    ) {
        /** Mean of all four wheels -- the vehicle's road speed, in counts. */
        val aggregate: Double get() = counts.sum() / 4.0

        /** True when the car is moving fast enough for the derived values to mean anything. */
        val isMoving: Boolean get() = aggregate >= MIN_MOVING_COUNTS

        /**
         * Mean of positions (0,2) minus mean of positions (1,3).
         *
         * This is the CORNERING signal: in a turn the outside wheels travel
         * further and read faster. The magnitude is meaningful now; the SIGN
         * only becomes left-or-right once [SideConvention] is known.
         */
        val sideDifference: Double
            get() = (counts[0] + counts[2]) / 2.0 - (counts[1] + counts[3]) / 2.0

        /**
         * Mean of positions (0,1) minus mean of positions (2,3).
         *
         * On the confirmed layout this pairs one wheel from each side, so it
         * is the AXLE difference -- driven versus undriven, i.e. wheelspin on
         * a rear-drive car. Small on dry tarmac (mean 11.7 counts over the
         * 2026-08-27 drive) and expected to grow sharply under a hard launch
         * on a low-grip surface.
         *
         * Which axle is which is not yet known; see [SideConvention].
         */
        val axleDifference: Double
            get() = (counts[0] + counts[1]) / 2.0 - (counts[2] + counts[3]) / 2.0

        /**
         * Cornering rate, normalised by speed.
         *
         * `sideDifference` alone is NOT yaw: a given steering angle produces a
         * side difference proportional to speed, so the raw difference
         * conflates "how hard am I turning" with "how fast am I going".
         * Dividing by speed gives a geometric quantity -- proportional to path
         * curvature, and therefore to yaw rate per unit speed.
         *
         * Null when stopped, because the division is meaningless and would
         * produce a huge phantom value from sensor dither.
         *
         * This is a RATE, not a heading. Integrating it accumulates drift, and
         * the 2026-08-27 capture showed why that matters in practice: slice
         * gaps left the longest continuous run at 9.8 s. Pair it with GPS
         * course for absolute reference, exactly as the barometer is paired
         * with surveyed elevation.
         */
        val curvature: Double?
            get() = if (isMoving) sideDifference / aggregate else null

        /** True when the sides differ by more than the sensor can resolve. */
        val isTurning: Boolean
            get() = isMoving && abs(sideDifference) > SIDE_DIFF_RESOLUTION

        /**
         * True when the car is unambiguously CORNERING.
         *
         * Stricter than [isTurning]: see [CORNERING_THRESHOLD]. Use this,
         * not `isTurning`, whenever the SIGN of the turn matters -- sign
         * questions asked of near-zero differences return coin flips.
         */
        val isCornering: Boolean
            get() = isMoving && abs(sideDifference) > CORNERING_THRESHOLD

        /**
         * Side difference with a TRUE sign: positive turning right.
         *
         * [sideDifference] is (0,2) minus (1,3), which is raw frame order.
         * With [MEASURED_CONVENTION] saying (1,3) is the left side, the
         * outside-faster rule gives: right turn -> left wheels outside ->
         * (1,3) faster -> [sideDifference] negative. So the true-signed value
         * is the negation.
         *
         * Positive = turning right, negative = turning left, matching the
         * sign convention of [Attitude.Reading.lateralG] (positive
         * rightward).
         */
        val signedSideDifference: Double
            get() = when (MEASURED_CONVENTION) {
                SideConvention.POS_1_3_IS_LEFT -> -sideDifference
                SideConvention.POS_0_2_IS_LEFT -> sideDifference
                SideConvention.UNKNOWN -> 0.0
            }

        /**
         * Yaw rate in radians per second, from the wheels alone.
         *
         * Two wheels on a common axle rolling round a corner differ in speed
         * by exactly `yawRate * track`, so `yawRate = deltaV / track`. That
         * is plane geometry -- no fusion, no integration, no drift.
         *
         * @param countToMps metres per second per raw count. This scale is
         *   NOT yet fitted (see `WheelCalibration.fitScale`), so callers must
         *   pass their best estimate and treat the result as provisional.
         * @param trackWidthM axle track. Defaults to the ND2 figure.
         *
         * Null when stopped, where the quantity is meaningless.
         *
         * **Positive is turning right**, matching [signedSideDifference].
         *
         * This is the quantity the navball wants in place of phone-derived
         * heading change: it comes from sensors bolted to the chassis and is
         * immune to the phone sliding on the seat. It does NOT provide roll
         * or pitch -- wheel speeds carry no information about the gravity
         * vector, so the horizon still needs the accelerometer.
         */
        fun yawRateRadPerSec(
            countToMps: Double,
            trackWidthM: Double = ND2_TRACK_WIDTH_M
        ): Double? {
            if (!isMoving) return null
            return signedSideDifference * countToMps / trackWidthM
        }

        /**
         * Speed in m/s from the four-wheel mean.
         *
         * @param countToMps see [yawRateRadPerSec]; the same provisional
         *   scale applies.
         */
        fun speedMps(countToMps: Double): Double = aggregate * countToMps
    }

    /**
     * Decode a `215` payload.
     *
     * @return null if this is not a well-formed wheel-speed frame. A frame is
     *   rejected when it is not exactly 8 bytes, or when any wheel reads below
     *   [STATIONARY_OFFSET] -- the sensors cannot report less than stopped, so
     *   such a frame is corrupt however plausible its bytes look.
     */
    fun decode(data: List<Int>): Reading? {
        if (data.size != 8) return null
        val raw = listOf(
            (data[0] shl 8) or data[1],
            (data[2] shl 8) or data[3],
            (data[4] shl 8) or data[5],
            (data[6] shl 8) or data[7]
        )
        if (raw.any { it < STATIONARY_OFFSET }) return null
        return Reading(raw, raw.map { (it - STATIONARY_OFFSET).toDouble() })
    }

    /** The arbitration ID this decoder reads. */
    const val CAN_ID = "215"
}
