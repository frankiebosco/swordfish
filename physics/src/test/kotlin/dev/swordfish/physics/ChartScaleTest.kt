package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Axis arithmetic.
 *
 * This is the part of hand-drawn charting that goes subtly wrong: ticks at
 * 0/3333/6667 look broken and no compiler objects. Tested here so the
 * awkward cases are settled before anything is drawn.
 */
class ChartScaleTest {

    @Test
    fun `ticks land on round numbers`() {
        val a = ChartScale.niceAxis(0.0, 9500.0)
        for (t in a.ticks) {
            val s = ChartScale.niceStep((a.max - a.min) / 4)
            assertEquals(
                0.0, (t / s) - Math.round(t / s), 1e-6,
                "tick $t is not a multiple of the step $s"
            )
        }
    }

    @Test
    fun `steps are 1, 2, 2point5 or 5 times a power of ten`() {
        val allowed = listOf(1.0, 2.0, 2.5, 5.0)
        for (raw in listOf(0.3, 1.1, 3.0, 7.0, 42.0, 380.0, 9_500.0, 120_000.0)) {
            val s = ChartScale.niceStep(raw)
            val mantissa = s / Math.pow(10.0, Math.floor(Math.log10(s)))
            assertTrue(
                allowed.any { abs(it - mantissa) < 1e-9 },
                "step $s for raw $raw has mantissa $mantissa"
            )
        }
    }

    @Test
    fun `2point5 exists so a four-tick request gets four ticks`() {
        // Without it, 0..9000 lands on 5000 (two gridlines) or 2000 (five).
        assertEquals(2500.0, ChartScale.niceStep(2400.0))
    }

    @Test
    fun `a bar axis includes zero`() {
        // A bar chart whose baseline is 28,000 turns a 5% difference into a
        // dramatic one. Truthful numbers, misleading picture.
        val a = ChartScale.niceAxis(28_000.0, 31_000.0, includeZero = true)
        assertEquals(0.0, a.min)
    }

    @Test
    fun `a line axis can omit zero`() {
        val a = ChartScale.niceAxis(28_000.0, 31_000.0, includeZero = false)
        assertTrue(a.min > 20_000.0, "expected a tight axis, got ${a.min}")
    }

    @Test
    fun `a flat series still yields a drawable axis`() {
        // Every sample identical -- a zero-height chart would divide by zero.
        val a = ChartScale.niceAxis(500.0, 500.0, includeZero = false)
        assertTrue(a.span > 0.0, "a flat series must still have a span")
        assertTrue(a.ticks.size >= 2)
    }

    @Test
    fun `an all-zero series does not divide by zero`() {
        val a = ChartScale.niceAxis(0.0, 0.0)
        assertTrue(a.span > 0.0)
        assertEquals(0.0, a.fraction(0.0), 1.0)   // finite, whatever it is
    }

    @Test
    fun `negative ranges work`() {
        // Gravity loss swings to -16 kW on a real descent.
        val a = ChartScale.niceAxis(-16_500.0, 23_900.0)
        assertTrue(a.min <= -16_500.0)
        assertTrue(a.max >= 23_900.0)
        assertTrue(a.ticks.any { it < 0 }, "expected negative ticks")
        assertTrue(a.ticks.any { abs(it) < 1e-9 }, "expected a zero tick")
    }

    @Test
    fun `a value maps to a fraction of the axis`() {
        val a = ChartScale.niceAxis(0.0, 100.0)
        assertEquals(0.0, a.fraction(0.0), 1e-9)
        assertEquals(0.5, a.fraction(50.0), 1e-9)
        assertEquals(1.0, a.fraction(100.0), 1e-9)
    }

    @Test
    fun `an outlier is clamped rather than drawn off the chart`() {
        val a = ChartScale.niceAxis(0.0, 100.0)
        assertEquals(1.0, a.fraction(1e9), 1e-9)
        assertEquals(0.0, a.fraction(-1e9), 1e-9)
    }

    @Test
    fun `the tick loop always terminates`() {
        // A pathological range must not spin. 100 is the guard.
        val a = ChartScale.niceAxis(0.0, 1e12)
        assertTrue(a.ticks.size < 100)
        assertTrue(a.ticks.isNotEmpty())
    }

    // --- formatting ---

    @Test
    fun `large ticks are abbreviated`() {
        // Isp runs to five digits and an axis label competes for width with
        // the chart itself.
        assertEquals("31k", ChartScale.formatTick(31_000.0))
        assertEquals("1.5k", ChartScale.formatTick(1_500.0))
        assertEquals("2M", ChartScale.formatTick(2_000_000.0))
    }

    @Test
    fun `zero prints as zero, not as minus zero or 0k`() {
        assertEquals("0", ChartScale.formatTick(0.0))
        assertEquals("0", ChartScale.formatTick(-0.0))
    }

    @Test
    fun `small values keep their precision`() {
        assertEquals("0.25", ChartScale.formatTick(0.25))
        assertEquals("42", ChartScale.formatTick(42.0))
    }

    @Test
    fun `negative ticks keep their sign`() {
        assertTrue(ChartScale.formatTick(-16_000.0).startsWith("-"))
    }

    // --- donut slices ---

    @Test
    fun `slices close the circle exactly`() {
        // Rounding each slice independently leaves a visible wedge of
        // background where the fractions do not quite sum to one.
        val slices = ChartScale.sliceAngles(listOf(0.333, 0.333, 0.333))
        val last = slices.last()
        assertEquals(1.0, last.first + last.second, 1e-12)
    }

    @Test
    fun `slices follow each other with no gap`() {
        val slices = ChartScale.sliceAngles(listOf(0.5, 0.3, 0.2))
        for (i in 1 until slices.size) {
            assertEquals(
                slices[i - 1].first + slices[i - 1].second,
                slices[i].first,
                1e-12,
                "gap before slice $i"
            )
        }
    }

    @Test
    fun `an empty or zero total does not produce NaN`() {
        assertTrue(ChartScale.sliceAngles(emptyList()).isEmpty())
        val zeros = ChartScale.sliceAngles(listOf(0.0, 0.0))
        assertTrue(zeros.all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test
    fun `unnormalised inputs are normalised`() {
        // Seconds, not fractions -- the caller should not have to divide.
        val slices = ChartScale.sliceAngles(listOf(60.0, 30.0, 10.0))
        assertEquals(0.6, slices[0].second, 1e-9)
        assertEquals(1.0, slices.last().first + slices.last().second, 1e-12)
    }
}
