package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavballScaleTest {

    // --- Pitch ladder ---

    @Test
    fun `level attitude centres the ladder on the horizon`() {
        val ticks = NavballScale.pitchTicks(0.0)
        val zero = ticks.first { it.degrees == 0.0 }
        assertEquals(0.0, zero.offsetFraction, 1e-9)
    }

    @Test
    fun `nose-up pushes the horizon below centre`() {
        // Climbing a hill: the horizon should drop, as in an aircraft AI.
        val offset = NavballScale.horizonOffsetFraction(Math.toRadians(10.0))
        assertTrue(offset > 0.0, "horizon should move down-screen when climbing")
    }

    @Test
    fun `nose-down raises the horizon above centre`() {
        val offset = NavballScale.horizonOffsetFraction(Math.toRadians(-10.0))
        assertTrue(offset < 0.0)
    }

    @Test
    fun `an off-scale attitude parks the horizon at the edge, not off it`() {
        // A very steep pitch must still show which way is up.
        val extreme = NavballScale.horizonOffsetFraction(Math.toRadians(80.0))
        assertTrue(abs(extreme) <= 1.2, "clamped to $extreme")
        assertTrue(extreme > 1.0, "should be pinned near the edge")
    }

    @Test
    fun `the scale is fine enough for real road gradients`() {
        // The design point: a car sees small angles, so a 3 degree pitch --
        // roughly a 5 percent grade -- must produce visible movement.
        val offset = NavballScale.horizonOffsetFraction(Math.toRadians(3.0))
        assertTrue(offset > 0.10, "3 degrees only moved the horizon $offset radii")
    }

    @Test
    fun `every tenth degree is a major graduation`() {
        val ticks = NavballScale.pitchTicks(0.0)
        val majors = ticks.filter { it.isMajor }.map { it.degrees }
        assertTrue(majors.contains(10.0))
        assertTrue(majors.contains(-10.0))
        assertTrue(majors.contains(0.0))
        val five = ticks.first { it.degrees == 5.0 }
        assertTrue(!five.isMajor)
    }

    @Test
    fun `major graduations are wider than minor ones`() {
        val ticks = NavballScale.pitchTicks(0.0)
        val major = ticks.first { it.isMajor && it.degrees == 10.0 }
        val minor = ticks.first { !it.isMajor }
        assertTrue(major.widthFraction > minor.widthFraction)
    }

    @Test
    fun `the horizon line itself carries no label`() {
        val zero = NavballScale.pitchTicks(0.0).first { it.degrees == 0.0 }
        assertEquals(null, zero.label)
    }

    @Test
    fun `labels show magnitude without a sign`() {
        // A pitch ladder reads "10" above and below; the position conveys
        // direction, so a minus sign would be noise.
        val ticks = NavballScale.pitchTicks(0.0)
        val up = ticks.first { it.degrees == 10.0 }
        val down = ticks.first { it.degrees == -10.0 }
        assertEquals("10", up.label)
        assertEquals("10", down.label)
    }

    @Test
    fun `ticks outside the ball are omitted rather than clipped`() {
        // The renderer should never have to think about bounds.
        val ticks = NavballScale.pitchTicks(Math.toRadians(15.0))
        ticks.forEach {
            assertTrue(
                abs(it.offsetFraction) <= 0.85,
                "tick at ${it.degrees} would fall outside the ball"
            )
        }
    }

    @Test
    fun `the ladder scrolls as attitude changes`() {
        val level = NavballScale.pitchTicks(0.0).first { it.degrees == 0.0 }
        val climbing = NavballScale.pitchTicks(Math.toRadians(5.0))
            .firstOrNull { it.degrees == 0.0 }
        assertTrue(climbing != null)
        assertTrue(
            climbing!!.offsetFraction > level.offsetFraction,
            "the zero line should move down-screen as the nose rises"
        )
    }

    @Test
    fun `non-finite attitude yields no ticks rather than NaN positions`() {
        assertTrue(NavballScale.pitchTicks(Double.NaN).isEmpty())
        assertEquals(0.0, NavballScale.horizonOffsetFraction(Double.NaN), 1e-9)
    }

    // --- Roll scale ---

    @Test
    fun `the roll scale is symmetric about zero`() {
        val ticks = NavballScale.ROLL_TICKS_DEG
        assertTrue(ticks.contains(0.0))
        ticks.filter { it > 0 }.forEach {
            assertTrue(ticks.contains(-it), "missing mirror of $it")
        }
    }

    @Test
    fun `roll majors are a subset of roll ticks`() {
        NavballScale.ROLL_MAJOR_DEG.forEach {
            assertTrue(NavballScale.ROLL_TICKS_DEG.contains(it))
        }
    }

    // --- Compass ---

    @Test
    fun `cardinal headings read as letters`() {
        assertEquals("N", NavballScale.headingLabel(0.0))
        assertEquals("E", NavballScale.headingLabel(90.0))
        assertEquals("S", NavballScale.headingLabel(180.0))
        assertEquals("W", NavballScale.headingLabel(270.0))
        assertEquals("NE", NavballScale.headingLabel(45.0))
    }

    @Test
    fun `intermediate headings read as degrees`() {
        // Anything more than 11.25 degrees from a cardinal or intercardinal
        // point. 124 is NOT such a case -- it is 11 degrees from SE and
        // correctly reads "SE", which caught a bad test fixture.
        assertEquals("110°", NavballScale.headingLabel(110.0))
        assertEquals("30°", NavballScale.headingLabel(30.0))
        assertEquals("205°", NavballScale.headingLabel(205.0))
    }

    @Test
    fun `headings close to a cardinal snap to its letter`() {
        // The window is generous on purpose: letters are read faster than
        // numbers at a glance, and a car is rarely on an exact bearing.
        assertEquals("SE", NavballScale.headingLabel(124.0))
        assertEquals("N", NavballScale.headingLabel(8.0))
        assertEquals("W", NavballScale.headingLabel(265.0))
    }

    @Test
    fun `headings wrap correctly`() {
        assertEquals("N", NavballScale.headingLabel(360.0))
        assertEquals("N", NavballScale.headingLabel(-0.5))
        assertEquals("W", NavballScale.headingLabel(-90.0))
    }

    @Test
    fun `unknown heading reads as a dash`() {
        assertEquals("--", NavballScale.headingLabel(null))
        assertEquals("--", NavballScale.headingLabel(Double.NaN))
    }

    @Test
    fun `angular distance takes the short way round`() {
        assertEquals(10.0, NavballScale.angularDistance(355.0, 5.0), 0.001)
        assertEquals(180.0, NavballScale.angularDistance(0.0, 180.0), 0.001)
        assertEquals(0.0, NavballScale.angularDistance(90.0, 90.0), 0.001)
    }

    @Test
    fun `signed delta is negative going anticlockwise`() {
        assertEquals(10.0, NavballScale.shortestSignedDelta(355.0, 5.0), 0.001)
        assertEquals(-10.0, NavballScale.shortestSignedDelta(5.0, 355.0), 0.001)
    }

    @Test
    fun `the compass strip centres on the current heading`() {
        val ticks = NavballScale.compassTicks(90.0, spanDegrees = 90.0)
        assertTrue(ticks.isNotEmpty())
        val east = ticks.first { it.first == "E" }
        assertEquals(0.5, east.second, 0.02)
    }

    @Test
    fun `compass strip positions stay within the strip`() {
        for (heading in listOf(0.0, 45.0, 123.0, 270.0, 359.0)) {
            NavballScale.compassTicks(heading).forEach { (label, pos) ->
                assertTrue(pos in 0.0..1.0, "$label at $pos for heading $heading")
            }
        }
    }

    @Test
    fun `the compass strip wraps across north`() {
        // Heading 350 should show N to the right of centre, not vanish.
        val ticks = NavballScale.compassTicks(350.0, spanDegrees = 90.0)
        val north = ticks.firstOrNull { it.first == "N" }
        assertTrue(north != null, "N should be visible when heading 350")
        assertTrue(north!!.second > 0.5, "N should be right of centre")
    }

    // --- Compass label crowding ---
    //
    // The original spacing produced overlapping labels on the head unit --
    // observed as the unreadable smear "300NW330345N". Angle spacing alone is
    // not sufficient because labels differ in width: "N" is one character,
    // "330 degrees" is four.

    @Test
    fun `compass labels never crowd each other`() {
        for (heading in 0..359 step 7) {
            val ticks = NavballScale.compassTicks(heading.toDouble())
            val positions = ticks.map { it.second }.sorted()
            for (i in 0 until positions.size - 1) {
                val gap = positions[i + 1] - positions[i]
                assertTrue(
                    gap >= 0.17,
                    "heading $heading: labels only ${"%.3f".format(gap)} apart " +
                        "(${ticks.map { it.first }})"
                )
            }
        }
    }

    @Test
    fun `the strip stays sparse enough to read at a glance`() {
        for (heading in listOf(0.0, 47.0, 123.0, 271.0, 350.0)) {
            val n = NavballScale.compassTicks(heading).size
            assertTrue(n <= 5, "heading $heading produced $n labels")
        }
    }

    @Test
    fun `every strip label is short`() {
        // Stepping at 45 degrees means every tick lands on a cardinal or
        // intercardinal, so labels are one or two characters rather than a
        // four-character bearing.
        for (heading in 0..359 step 11) {
            NavballScale.compassTicks(heading.toDouble()).forEach { (label, _) ->
                assertTrue(
                    label.length <= 2,
                    "heading $heading produced a wide label: $label"
                )
            }
        }
    }

    @Test
    fun `the current heading is still represented`() {
        // Thinning must not remove the thing the strip exists to show.
        for (heading in listOf(0.0, 45.0, 90.0, 180.0, 315.0)) {
            val ticks = NavballScale.compassTicks(heading)
            assertTrue(ticks.isNotEmpty(), "no ticks at heading $heading")
            val nearest = ticks.minByOrNull { abs(it.second - 0.5) }!!
            assertTrue(
                abs(nearest.second - 0.5) < 0.2,
                "nothing near centre at heading $heading"
            )
        }
    }

    // --- Compass tick marks (separate row from the labels) ---

    @Test
    fun `tick marks are finer than the labels`() {
        // The whole point of separating them: a fine scale without label
        // collisions. Ticks every 10 degrees, letters every 45.
        val marks = NavballScale.compassTickMarks(90.0)
        val labels = NavballScale.compassTicks(90.0)
        assertTrue(marks.size > labels.size * 2,
            "expected many more ticks (${marks.size}) than labels (${labels.size})")
    }

    @Test
    fun `major ticks fall on the cardinal and intercardinal points`() {
        // Majors should coincide with where the letters are drawn, so the two
        // rows line up visually.
        val marks = NavballScale.compassTickMarks(0.0)
        val majors = marks.filter { it.second }
        assertTrue(majors.isNotEmpty(), "no major ticks found")
        // At heading 0, N sits dead centre and should be a major.
        assertTrue(majors.any { kotlin.math.abs(it.first - 0.5) < 0.02 },
            "no major tick at the centre index")
    }

    @Test
    fun `tick positions stay within the strip`() {
        for (heading in 0..359 step 13) {
            NavballScale.compassTickMarks(heading.toDouble()).forEach { (pos, _) ->
                assertTrue(pos in 0.0..1.0, "tick at $pos for heading $heading")
            }
        }
    }

    @Test
    fun `no ticks without a heading`() {
        assertTrue(NavballScale.compassTickMarks(null).isEmpty())
        assertTrue(NavballScale.compassTickMarks(Double.NaN).isEmpty())
    }

    @Test
    fun `ticks and labels share the same span`() {
        // If the two rows used different spans they would drift apart and the
        // letters would stop lining up with their graduations.
        val heading = 137.0
        val marks = NavballScale.compassTickMarks(heading)
        val labels = NavballScale.compassTicks(heading)
        for ((label, pos) in labels) {
            val nearest = marks.minByOrNull { kotlin.math.abs(it.first - pos) }
            assertTrue(nearest != null && kotlin.math.abs(nearest.first - pos) < 0.02,
                "label $label at $pos has no tick beneath it")
        }
    }

    // --- Bearing convention ---
    //
    // A compass that is 180 degrees wrong looks entirely plausible on screen.
    // Frank caught exactly that in testing: port side facing north reported
    // south. These pin the convention so it cannot silently regress.

    @Test
    fun `pointing north reads as north`() {
        assertEquals(0.0, NavballScale.bearingFromWorldVector(east = 0.0, north = 1.0), 0.001)
    }

    @Test
    fun `pointing east reads as ninety degrees`() {
        // The trap: atan2(y, x) would give 0 here, measuring anticlockwise
        // from east instead of clockwise from north.
        assertEquals(90.0, NavballScale.bearingFromWorldVector(east = 1.0, north = 0.0), 0.001)
    }

    @Test
    fun `pointing south reads as one eighty, not zero`() {
        assertEquals(180.0, NavballScale.bearingFromWorldVector(east = 0.0, north = -1.0), 0.001)
    }

    @Test
    fun `pointing west reads as two seventy`() {
        assertEquals(270.0, NavballScale.bearingFromWorldVector(east = -1.0, north = 0.0), 0.001)
    }

    @Test
    fun `intercardinals land where expected`() {
        assertEquals(45.0, NavballScale.bearingFromWorldVector(1.0, 1.0), 0.001)
        assertEquals(135.0, NavballScale.bearingFromWorldVector(1.0, -1.0), 0.001)
        assertEquals(225.0, NavballScale.bearingFromWorldVector(-1.0, -1.0), 0.001)
        assertEquals(315.0, NavballScale.bearingFromWorldVector(-1.0, 1.0), 0.001)
    }

    @Test
    fun `bearing is always within zero to three sixty`() {
        for (e in -3..3) for (n in -3..3) {
            val b = NavballScale.bearingFromWorldVector(e.toDouble(), n.toDouble())
            assertTrue(b in 0.0..360.0, "bearing($e,$n) = $b")
        }
    }

    @Test
    fun `a degenerate vector does not produce NaN`() {
        assertEquals(0.0, NavballScale.bearingFromWorldVector(0.0, 0.0), 1e-9)
        assertEquals(0.0, NavballScale.bearingFromWorldVector(Double.NaN, 1.0), 1e-9)
    }

    @Test
    fun `bearings round-trip through the label formatter`() {
        assertEquals("N", NavballScale.headingLabel(NavballScale.bearingFromWorldVector(0.0, 1.0)))
        assertEquals("E", NavballScale.headingLabel(NavballScale.bearingFromWorldVector(1.0, 0.0)))
        assertEquals("S", NavballScale.headingLabel(NavballScale.bearingFromWorldVector(0.0, -1.0)))
        assertEquals("W", NavballScale.headingLabel(NavballScale.bearingFromWorldVector(-1.0, 0.0)))
    }

    @Test
    fun `no compass ticks without a heading`() {
        assertTrue(NavballScale.compassTicks(null).isEmpty())
        assertTrue(NavballScale.compassTicks(Double.NaN).isEmpty())
    }
}
