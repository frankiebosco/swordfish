package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SevenSegmentTest {

    // --- Digit definitions ---

    @Test
    fun `every digit is defined`() {
        for (c in '0'..'9') {
            assertNotNull(SevenSegment.segmentsFor(c), "no glyph for '$c'")
        }
    }

    @Test
    fun `eight lights every segment and one lights only two`() {
        // The two extremes, which between them catch most mask errors.
        assertEquals(SevenSegment.ALL, SevenSegment.segmentsFor('8'))
        assertEquals(
            SevenSegment.B or SevenSegment.C,
            SevenSegment.segmentsFor('1')
        )
    }

    @Test
    fun `zero lights the outline but not the middle bar`() {
        assertTrue(SevenSegment.isLit('0', SevenSegment.A))
        assertTrue(SevenSegment.isLit('0', SevenSegment.D))
        assertFalse(SevenSegment.isLit('0', SevenSegment.G))
    }

    @Test
    fun `seven is three segments, not four`() {
        // A classic off-by-one: adding F turns a 7 into something like an
        // upside-down 4.
        val mask = SevenSegment.segmentsFor('7')!!
        assertEquals(3, SevenSegment.SEGMENTS.count { (mask and it) != 0 })
        assertFalse(SevenSegment.isLit('7', SevenSegment.F))
    }

    @Test
    fun `six and nine differ in exactly one segment`() {
        val six = SevenSegment.segmentsFor('6')!!
        val nine = SevenSegment.segmentsFor('9')!!
        val differing = SevenSegment.SEGMENTS.count {
            ((six and it) != 0) != ((nine and it) != 0)
        }
        assertEquals(2, differing)  // 6 has E not B; 9 has B not E
    }

    @Test
    fun `no two digits share a segment pattern`() {
        // Two digits with the same mask would be indistinguishable on screen.
        val masks = ('0'..'9').map { SevenSegment.segmentsFor(it)!! }
        assertEquals(masks.size, masks.toSet().size, "duplicate digit patterns")
    }

    @Test
    fun `every digit lights at least two segments`() {
        // A single-segment digit would be unreadable.
        for (c in '0'..'9') {
            val mask = SevenSegment.segmentsFor(c)!!
            val lit = SevenSegment.SEGMENTS.count { (mask and it) != 0 }
            assertTrue(lit >= 2, "'$c' lights only $lit segment(s)")
        }
    }

    // --- Space and separator ---

    @Test
    fun `space lights nothing but is still renderable`() {
        assertEquals(0, SevenSegment.segmentsFor(' '))
        assertTrue(SevenSegment.canRender(" "))
    }

    @Test
    fun `minus is the middle bar alone`() {
        assertEquals(SevenSegment.G, SevenSegment.segmentsFor('-'))
    }

    // --- Refusal rather than approximation ---

    @Test
    fun `unrenderable characters are refused, not approximated`() {
        // A mangled letter reads as a fault; better to admit we cannot show it.
        assertNull(SevenSegment.segmentsFor('W'))
        assertNull(SevenSegment.segmentsFor('m'))
        assertNull(SevenSegment.segmentsFor('%'))
        assertNull(SevenSegment.segmentsFor('Δ'))
    }

    @Test
    fun `canRender is honest about labels`() {
        assertTrue(SevenSegment.canRender("7501"))
        assertTrue(SevenSegment.canRender("31 564"))
        // The panel's labels genuinely cannot be segment-rendered, which is
        // why they use ordinary type.
        assertFalse(SevenSegment.canRender("Δv REMAINING"))
        assertFalse(SevenSegment.canRender("80% TO ORBIT"))
    }

    @Test
    fun `sanitise blanks what it cannot draw rather than substituting`() {
        // A gap reads as "no data"; a wrong glyph reads as a value.
        assertEquals("7501", SevenSegment.sanitise("7501"))
        assertEquals("1 3 ", SevenSegment.sanitise("1%3W"))
    }

    @Test
    fun `isLit is false for characters with no glyph`() {
        assertFalse(SevenSegment.isLit('W', SevenSegment.A))
    }

    // --- Decimal points ---
    //
    // A decimal point is a dot in a digit cell's corner, not a character of
    // its own. Rendering it as a separate cell would waste a full digit width
    // and read as a gap -- the same failure the thousands separator had.

    @Test
    fun `a decimal point rides on the preceding digit`() {
        val cells = SevenSegment.cells("0.15")
        assertEquals(3, cells.size, "should cost three cells, not four")
        assertEquals('0' to true, cells[0])
        assertEquals('1' to false, cells[1])
        assertEquals('5' to false, cells[2])
    }

    @Test
    fun `a value with no point is unchanged`() {
        val cells = SevenSegment.cells("7501")
        assertEquals(4, cells.size)
        assertTrue(cells.none { it.second })
    }

    @Test
    fun `a leading point gets its own cell`() {
        // Nothing to attach it to, so it stands alone rather than being lost.
        val cells = SevenSegment.cells(".5")
        assertEquals(2, cells.size)
        assertEquals(' ' to true, cells[0])
        assertEquals('5' to false, cells[1])
    }

    @Test
    fun `a comma is treated as a decimal point`() {
        // Some locales use a comma; either way it is a corner dot, never a
        // full cell.
        assertEquals(SevenSegment.cells("0.15"), SevenSegment.cells("0,15"))
    }

    @Test
    fun `two points in a row do not stack on one digit`() {
        // Defensive: malformed input should still produce sane cells rather
        // than silently swallowing a character.
        val cells = SevenSegment.cells("1..5")
        assertEquals(3, cells.size)
        assertEquals('1' to true, cells[0])
        assertEquals(' ' to true, cells[1])
    }

    @Test
    fun `the panel's real decimal values are all displayable`() {
        // The exact strings the stat rows produce.
        assertTrue(SevenSegment.canDisplay("0.15"))
        assertTrue(SevenSegment.canDisplay("9.9"))
        assertTrue(SevenSegment.canDisplay("2499"))
        assertTrue(SevenSegment.canDisplay("21"))
    }

    @Test
    fun `canDisplay is stricter than canRender about content`() {
        // canRender knows nothing about points; canDisplay handles them.
        assertFalse(SevenSegment.canRender("0.15"))
        assertTrue(SevenSegment.canDisplay("0.15"))
    }

    // --- Themes ---

    @Test
    fun `green is the default`() {
        assertEquals(DisplayTheme.GREEN, DisplayTheme.DEFAULT)
        assertEquals(DisplayTheme.GREEN, DisplayTheme.fromName(null))
        assertEquals(DisplayTheme.GREEN, DisplayTheme.fromName("nonsense"))
    }

    @Test
    fun `themes round-trip through their stored name`() {
        for (t in DisplayTheme.entries) {
            assertEquals(t, DisplayTheme.fromName(t.name))
        }
    }

    @Test
    fun `every theme darkens from bright through to ghost`() {
        // The ghost layer must be dim enough to read as "off" but present
        // enough to be seen, which is what makes it look like a real display.
        for (t in DisplayTheme.entries) {
            assertTrue(luminance(t.bright) > luminance(t.mid), "${t.name}: bright vs mid")
            assertTrue(luminance(t.mid) > luminance(t.dim), "${t.name}: mid vs dim")
            assertTrue(luminance(t.dim) > luminance(t.ghost), "${t.name}: dim vs ghost")
            assertTrue(luminance(t.ghost) > 0.0, "${t.name}: ghost must be visible")
        }
    }

    @Test
    fun `ghost segments are faint but not invisible`() {
        // The window matters more than it looks. At 0.12 the off-segments
        // competed with the lit ones on the head unit and "7 501" read as
        // "78508". Below about 0.01 they vanish and the display stops looking
        // like a display.
        for (t in DisplayTheme.entries) {
            val ratio = luminance(t.ghost) / luminance(t.bright)
            assertTrue(ratio < 0.09, "${t.name}: ghost too bright ($ratio)")
            assertTrue(ratio > 0.01, "${t.name}: ghost too dark ($ratio)")
        }
    }

    @Test
    fun `every theme has a human-readable label`() {
        DisplayTheme.entries.forEach {
            assertTrue(it.label.isNotBlank(), "${it.name} has no label")
        }
    }

    @Test
    fun `the accent contrasts with the main colour`() {
        // The accent marks what the eye should find first, so it must not be
        // a near-match for the phosphor.
        for (t in DisplayTheme.entries) {
            val dr = kotlin.math.abs(red(t.accent) - red(t.bright))
            val dg = kotlin.math.abs(green(t.accent) - green(t.bright))
            val db = kotlin.math.abs(blue(t.accent) - blue(t.bright))
            assertTrue(dr + dg + db > 120, "${t.name}: accent too close to bright")
        }
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** Rough perceptual luminance, good enough for ordering checks. */
    private fun luminance(c: Int): Double =
        0.2126 * red(c) + 0.7152 * green(c) + 0.0722 * blue(c)
}
