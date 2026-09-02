package dev.swordfish.car

import kotlin.test.Test
import dev.swordfish.physics.Units
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the parts of the gauge that are pure logic.
 *
 * Number formatting is worth testing rather than eyeballing: a digit that
 * jitters in width, or a stray decimal that flickers, is genuinely
 * distracting on a screen read at 70 mph.
 */
class GaugeFormatTest {

    // --- Fuel flow, the idle readout ---
    //
    // The decimal point is the least legible mark on the panel: a ~10px dot
    // on a glossy screen at arm's length, and unlike every other glyph it is
    // a single point of failure — miss it and "0.8" reads as "08".
    //
    // These tests pin the fix: the unit scales so the value is ALWAYS a
    // whole number, exactly as TWR became "15%" and fuel became litres.

    private fun flowAtLitresPerHour(lph: Double): Double =
        Units.gallonsToKg(Units.litersToGallons(lph) / 3600.0)

    @Test
    fun `idle burn renders as whole millilitres`() {
        // The ND2's documented idle: 0.20 gal/h = 757 mL/h.
        val r = GaugeFormat.formatFuelFlow(Units.gallonsToKg(0.20 / 3600.0))
        assertEquals("757", r.value)
        assertEquals("mL/h", r.unit)
    }

    @Test
    fun `no burn rate anywhere in the range contains a decimal point`() {
        // The whole purpose of the auto-scale. If this fails, the panel has
        // regained the one illegible glyph it was rid of.
        val range = listOf(0.05, 0.2, 0.76, 1.0, 5.0, 9.9, 10.0, 25.0, 56.8, 120.0)
        for (lph in range) {
            val r = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(lph))
            assertFalse(
                r.value.contains('.'),
                "$lph L/h rendered as \"${r.value}\" — contains a decimal point"
            )
        }
    }

    @Test
    fun `high burn switches to whole litres`() {
        // 56.8 L/h under hard acceleration: five digits of millilitres would
        // be needlessly wide when the fraction carries nothing.
        val r = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(56.8))
        assertEquals("57", r.value)
        assertEquals("L/h", r.unit)
    }

    @Test
    fun `the scale switches at the documented threshold`() {
        val below = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(9.5))
        val above = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(10.5))
        assertEquals("mL/h", below.unit)
        assertEquals("L/h", above.unit)
    }

    @Test
    fun `idle resolution survives the switch to millilitres`() {
        // The readout has to show a real change when load shifts -- an A/C
        // compressor kicking in at idle should move the number visibly.
        val idle = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(0.76))
        val idleWithLoad = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(0.95))
        assertTrue(
            idle.value != idleWithLoad.value,
            "a 25% load change was invisible: both read ${idle.value}"
        )
    }

    @Test
    fun `no burn rate renders as an unreadable zero`() {
        // Rounding to whole litres would make every idle condition read "1"
        // or "0" and say nothing. Millilitres keep three real digits.
        for (lph in listOf(0.1, 0.5, 0.76, 2.0)) {
            val r = GaugeFormat.formatFuelFlow(flowAtLitresPerHour(lph))
            assertTrue(r.value.toInt() > 0, "$lph L/h rendered as ${r.value}")
        }
    }

    @Test
    fun `a nonsensical flow renders as no data rather than a negative`() {
        assertEquals(GaugeFormat.NO_DATA, GaugeFormat.formatFuelFlow(-1.0).value)
    }

    @Test
    fun `zero flow renders as zero not a dash`() {
        // Distinct from unknown: DFCO genuinely is zero flow.
        assertEquals("0", GaugeFormat.formatFuelFlow(0.0).value)
    }

    @Test
    fun `small values render without grouping`() {
        assertEquals("0", GaugeFormat.formatDeltaV(0.0))
        assertEquals("7", GaugeFormat.formatDeltaV(7.0))
        assertEquals("999", GaugeFormat.formatDeltaV(999.0))
    }

    @Test
    fun `segment values carry no thousands separator`() {
        // On a seven-segment display a space is a full-width blank CELL, not
        // a thin gap. Grouping "7501" as "7 501" rendered as a five-digit
        // number with a hole punched in it -- seen on the head unit as
        // "7 _608". Real displays never spend a digit position on a separator.
        assertEquals("1000", GaugeFormat.formatDeltaV(1000.0))
        assertEquals("1847", GaugeFormat.formatDeltaV(1847.0))
        assertEquals("12345", GaugeFormat.formatDeltaV(12345.0))
        assertEquals("7501", GaugeFormat.formatDeltaV(7501.0))
    }

    @Test
    fun `no segment-rendered value contains a space`() {
        // A blanket guard: any space reaching SegmentDisplay costs a whole
        // digit cell.
        for (v in listOf(0.0, 7.0, 999.0, 1000.0, 7501.0, 12345.0, 99999.0)) {
            assertEquals(false, GaugeFormat.formatDeltaV(v).contains(" "),
                "formatDeltaV($v) contains a space")
        }
        for (v in listOf(0L, 999L, 2661L, 31564L)) {
            assertEquals(false, GaugeFormat.formatInteger(v).contains(" "),
                "formatInteger($v) contains a space")
        }
    }

    @Test
    fun `grouping still exists for ordinary type`() {
        // The phone screen and text labels are drawn with a real font, where
        // a space genuinely is a thin gap.
        assertEquals("12 345", GaugeFormat.groupThousands(12345))
    }

    @Test
    fun `values are rounded, never truncated`() {
        assertEquals("1848", GaugeFormat.formatDeltaV(1847.6))
        assertEquals("1847", GaugeFormat.formatDeltaV(1847.4))
    }

    @Test
    fun `no decimals are shown`() {
        // A fractional digit on a delta-V readout would flicker constantly
        // and communicate nothing.
        val s = GaugeFormat.formatDeltaV(1847.55)
        assertEquals(false, s.contains("."))
    }

    @Test
    fun `non-finite and negative values fall back to the no-data marker`() {
        assertEquals(GaugeFormat.NO_DATA, GaugeFormat.formatDeltaV(Double.NaN))
        assertEquals(GaugeFormat.NO_DATA, GaugeFormat.formatDeltaV(Double.POSITIVE_INFINITY))
        assertEquals(GaugeFormat.NO_DATA, GaugeFormat.formatDeltaV(-1.0))
    }

    @Test
    fun `layout fractions leave room for the label and units`() {
        // The three text blocks plus their offsets must fit inside the
        // stable area, or the panel overflows on a short surface.
        val total = GaugeFormat.LABEL_TEXT_FRACTION +
            GaugeFormat.VALUE_TEXT_FRACTION +
            GaugeFormat.UNIT_TEXT_FRACTION +
            GaugeFormat.LABEL_OFFSET_FRACTION +
            GaugeFormat.UNIT_OFFSET_FRACTION
        assertEquals(true, total < 1.0, "layout fractions sum to $total, must be < 1.0")
    }

    @Test
    fun `the value is by far the largest element`() {
        // Glanceability rule from docs/INSTRUMENT_PANEL.md: one element must
        // be readable in half a second, and it is the delta-V figure.
        assertEquals(
            true,
            GaugeFormat.VALUE_TEXT_FRACTION > GaugeFormat.LABEL_TEXT_FRACTION * 3,
            "the delta-V figure must dominate the panel"
        )
        assertEquals(
            true,
            GaugeFormat.VALUE_TEXT_FRACTION > GaugeFormat.UNIT_TEXT_FRACTION * 3
        )
    }
}
