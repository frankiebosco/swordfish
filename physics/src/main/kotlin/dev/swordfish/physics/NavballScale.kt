package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tick marks and scale arithmetic for the artificial horizon.
 *
 * ## Why this is pure logic in `:physics`
 *
 * Deciding *which* graduations to draw, and where they fall, is arithmetic —
 * and it is arithmetic that is easy to get subtly wrong in ways a screenshot
 * will not reveal. Keeping it here means the spacing, labelling and clipping
 * rules are unit-tested on the JVM; the Android side only strokes lines at the
 * offsets this returns.
 *
 * ## Why a car needs a different scale from an aircraft
 *
 * An aircraft attitude indicator typically graduates pitch every 10° out to
 * ±90°, because an aircraft genuinely uses that range. A car does not: public
 * roads top out around 15° (a 27% grade), and body roll in a road car rarely
 * exceeds 5-7°.
 *
 * Using aviation spacing would leave the ball almost motionless in normal
 * driving. These scales are therefore deliberately fine — **5° pitch
 * graduations** and a roll scale marked at small angles — so that real driving
 * produces visible movement. The trade is that a genuinely steep hill will run
 * off the scale, which is the right way round for an instrument that spends its
 * life on ordinary roads.
 */
object NavballScale {

    /** Pitch graduation interval, degrees. */
    const val PITCH_STEP_DEG = 5.0

    /** Largest pitch graduation drawn either side of the horizon. */
    const val PITCH_RANGE_DEG = 30.0

    /**
     * Pitch angle that displaces the horizon by a full ball radius.
     *
     * Smaller than an aircraft's because a car's usable pitch range is small;
     * this is what makes a 3% grade actually visible.
     */
    const val PITCH_FULL_SCALE_DEG = 20.0

    /** Roll graduations on the outer arc, degrees. */
    val ROLL_TICKS_DEG = listOf(-30.0, -20.0, -10.0, 0.0, 10.0, 20.0, 30.0)

    /** Roll marks drawn longer, as primary references. */
    val ROLL_MAJOR_DEG = setOf(-30.0, 0.0, 30.0)

    /**
     * One pitch graduation.
     *
     * @param degrees The angle this line represents. Zero is the horizon.
     * @param offsetFraction Vertical offset from ball centre, as a fraction of
     *   radius. Positive is downward on screen (a nose-up attitude pushes the
     *   horizon down).
     * @param isMajor Every 10° gets a longer, labelled line.
     * @param widthFraction Line half-length as a fraction of radius.
     */
    data class PitchTick(
        val degrees: Double,
        val offsetFraction: Double,
        val isMajor: Boolean,
        val widthFraction: Double
    ) {
        /** Label text, or null for minor ticks which are drawn unlabelled. */
        val label: String? get() =
            if (isMajor && degrees != 0.0) "${abs(degrees).roundToInt()}" else null
    }

    /**
     * Pitch graduations visible for a given pitch attitude.
     *
     * Ticks that would fall outside the ball are omitted rather than clipped,
     * so the renderer never has to think about bounds.
     *
     * @param pitchRadians Current vehicle pitch, positive nose-up.
     */
    fun pitchTicks(pitchRadians: Double): List<PitchTick> {
        if (!pitchRadians.isFinite()) return emptyList()
        val pitchDeg = Math.toDegrees(pitchRadians)

        val ticks = mutableListOf<PitchTick>()
        var deg = -PITCH_RANGE_DEG
        while (deg <= PITCH_RANGE_DEG + 1e-9) {
            // Where this graduation sits once the current attitude shifts it.
            val relative = deg - pitchDeg
            val offset = relative / PITCH_FULL_SCALE_DEG

            if (abs(offset) <= 0.85) {
                val isMajor = (deg.roundToInt() % 10) == 0
                ticks.add(
                    PitchTick(
                        degrees = deg,
                        // Screen Y grows downward, and a positive pitch angle
                        // should appear ABOVE the centre marker.
                        offsetFraction = -offset,
                        isMajor = isMajor,
                        widthFraction = if (isMajor) 0.30 else 0.15
                    )
                )
            }
            deg += PITCH_STEP_DEG
        }
        return ticks
    }

    /**
     * Vertical offset of the horizon line itself, as a fraction of radius.
     *
     * Clamped so a steep attitude parks the horizon at the edge rather than
     * vanishing entirely — an off-scale horizon should still show which way is
     * up.
     */
    fun horizonOffsetFraction(pitchRadians: Double): Double {
        if (!pitchRadians.isFinite()) return 0.0
        val deg = Math.toDegrees(pitchRadians)
        return (deg / PITCH_FULL_SCALE_DEG).coerceIn(-1.2, 1.2)
    }

    /**
     * Compass heading formatted for display.
     *
     * @return e.g. `"N"`, `"NE"`, `"124°"`. Cardinal points get letters
     *   because they are read faster than numbers at a glance.
     */
    fun headingLabel(headingDegrees: Double?): String {
        if (headingDegrees == null || !headingDegrees.isFinite()) return "--"
        val h = ((headingDegrees % 360.0) + 360.0) % 360.0
        val cardinals = listOf(
            0.0 to "N", 45.0 to "NE", 90.0 to "E", 135.0 to "SE",
            180.0 to "S", 225.0 to "SW", 270.0 to "W", 315.0 to "NW"
        )
        for ((angle, name) in cardinals) {
            if (angularDistance(h, angle) < 11.25) return name
        }
        return "${h.roundToInt()}°"
    }

    /** Shortest angular distance between two headings, degrees. */
    fun angularDistance(a: Double, b: Double): Double {
        val diff = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * Compass tick marks visible in a heading strip.
     *
     * A car's heading changes constantly, so a full rose would be unreadable.
     * A strip showing the nearby arc is easier to follow, in the same way an
     * aircraft HSI tape is.
     *
     * @param headingDegrees Current heading.
     * @param spanDegrees Total arc shown across the strip's width.
     * @return pairs of (label, position across the strip 0..1).
     */
    fun compassTicks(
        headingDegrees: Double?,
        spanDegrees: Double = 140.0,
        stepDegrees: Double = 45.0,
        minSeparation: Double = 0.18
    ): List<Pair<String, Double>> {
        if (headingDegrees == null || !headingDegrees.isFinite()) return emptyList()
        val h = ((headingDegrees % 360.0) + 360.0) % 360.0
        val half = spanDegrees / 2.0

        val candidates = mutableListOf<Pair<String, Double>>()
        var tick = (Math.floor((h - half) / stepDegrees) * stepDegrees)
        while (tick <= h + half) {
            val delta = shortestSignedDelta(h, tick)
            if (abs(delta) <= half) {
                val pos = 0.5 + delta / spanDegrees
                if (pos in 0.0..1.0) {
                    candidates.add(headingLabel(tick) to pos)
                }
            }
            tick += stepDegrees
        }

        // Enforce a minimum gap between labels.
        //
        // Spacing by ANGLE alone is not enough, because the labels are not
        // equally wide: "N" is one character and "330 degrees" is four. On a
        // narrow strip they overlapped into an unreadable smear -- observed on
        // the head unit as "300NW330345N".
        //
        // Stepping at 45 degrees means every tick is a cardinal or
        // intercardinal, so all labels are one or two characters, and the
        // separation check catches whatever still crowds.
        val kept = mutableListOf<Pair<String, Double>>()
        for (candidate in candidates.sortedBy { abs(it.second - 0.5) }) {
            if (kept.none { abs(it.second - candidate.second) < minSeparation }) {
                kept.add(candidate)
            }
        }
        return kept.sortedBy { it.second }
    }

    /**
     * Compass bearing of a direction expressed in world coordinates.
     *
     * Android's world frame is X east, Y north, Z up. A bearing is measured
     * clockwise from north, so this is `atan2(east, north)` -- NOT the usual
     * `atan2(y, x)`, which would measure anticlockwise from east and read 90
     * degrees out.
     *
     * Extracted here so the convention is unit-tested. A compass that is
     * exactly 180 degrees wrong looks plausible on screen and is easy to miss;
     * one that is 90 degrees wrong even more so.
     */
    fun bearingFromWorldVector(east: Double, north: Double): Double {
        if (!east.isFinite() || !north.isFinite()) return 0.0
        if (east == 0.0 && north == 0.0) return 0.0
        val deg = Math.toDegrees(Math.atan2(east, north))
        return (deg + 360.0) % 360.0
    }

    /**
     * Graduation marks for the compass strip, independent of the labels.
     *
     * Ticks and labels are generated separately and drawn on **different rows**.
     * An earlier version tried to place a label at every graduation, which
     * overlapped badly ("300NW330345N" on the head unit) and was then fixed by
     * removing the ticks entirely — throwing away the scale reference along
     * with the problem.
     *
     * Separating them gets both: a fine tick every [stepDegrees] showing the
     * scale, and a sparse row of cardinal letters underneath showing where you
     * are.
     *
     * @return positions across the strip, 0..1, paired with whether each is a
     *   major graduation (a cardinal or intercardinal point).
     */
    fun compassTickMarks(
        headingDegrees: Double?,
        spanDegrees: Double = 140.0,
        // 15 degrees, NOT 10. The letters sit on 45 degree points, and a
        // 10 degree grid never lands on 45, 135, 225 or 315 -- so NE, SE, SW
        // and NW would have had no graduation beneath them. 15 divides 45
        // evenly, so every letter gets a major tick. A test pins this.
        stepDegrees: Double = 15.0
    ): List<Pair<Double, Boolean>> {
        if (headingDegrees == null || !headingDegrees.isFinite()) return emptyList()
        val h = ((headingDegrees % 360.0) + 360.0) % 360.0
        val half = spanDegrees / 2.0

        val out = mutableListOf<Pair<Double, Boolean>>()
        var tick = Math.floor((h - half) / stepDegrees) * stepDegrees
        while (tick <= h + half) {
            val delta = shortestSignedDelta(h, tick)
            if (abs(delta) <= half) {
                val pos = 0.5 + delta / spanDegrees
                if (pos in 0.0..1.0) {
                    // Majors fall on the 45 degree points that carry letters.
                    val normalised = ((tick % 360.0) + 360.0) % 360.0
                    val isMajor = abs(normalised % 45.0) < 0.001 ||
                        abs((normalised % 45.0) - 45.0) < 0.001
                    out.add(pos to isMajor)
                }
            }
            tick += stepDegrees
        }
        return out
    }

    /** Signed difference from [from] to [to], in -180..180. */
    fun shortestSignedDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
