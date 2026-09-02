package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contrast floors for panel text, checked as numbers rather than by eye.
 *
 * ## Why this exists
 *
 * On the 2026-08-23 test drive the stat-row LABELS (GEAR, RPM, ALT, VOLTS)
 * were reported as "practically invisible" in the car. They were drawn in the
 * theme's `dim` phosphor, which measures **2.97:1** against the panel ground
 * in green and **1.75:1** in red — below even the WCAG large-text floor of
 * 3:1, while the value beside each label sat at 16:1.
 *
 * On a desk that reads as a pleasant hierarchy. In a moving car in daylight
 * it reads as nothing at all, and a car needs MORE contrast than a desk, not
 * less.
 *
 * A screenshot cannot catch this — the DHU renders on a bright monitor in a
 * dim room, which is the most flattering case there is. A ratio can.
 */
class StatLabelContrastTest {

    /** The panel background, `GaugeRenderer.C_GROUND`. */
    private val ground = 0x030504

    /**
     * WCAG AA for large text. The stat labels are small-ish but always
     * short, high-contrast glyphs on a fixed background, so this is the
     * right floor rather than the 4.5:1 body-text one.
     */
    private val minRatio = 3.0

    private fun relativeLuminance(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92
            else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `every theme's mid phosphor is readable against the panel`() {
        // `mid` is what the stat labels now use. All three themes must clear
        // the floor, not just the default green -- the red palette is the
        // worst case and would otherwise ship broken.
        for (theme in DisplayTheme.entries) {
            val ratio = contrast(theme.mid, ground)
            assertTrue(
                ratio >= minRatio,
                "${theme.name} mid is $ratio:1 against the panel, below $minRatio:1"
            )
        }
    }

    @Test
    fun `the dim phosphor is NOT used for text that must be read`() {
        // Documents WHY the labels moved off `dim`. If a future palette
        // change lifts dim above the floor this test starts failing, which
        // is the right moment to reconsider -- not a reason to delete it.
        val failing = DisplayTheme.entries.filter { contrast(it.dim, ground) < minRatio }
        assertTrue(
            failing.isNotEmpty(),
            "dim now clears $minRatio:1 in every theme; the label colour choice " +
                "can be revisited"
        )
    }

    @Test
    fun `labels stay subordinate to the values they name`() {
        // The dimming was never meant to hide the labels, only to rank them
        // below the numbers. `mid` under `bright` preserves that ordering at
        // a contrast that can actually be read.
        for (theme in DisplayTheme.entries) {
            val label = contrast(theme.mid, ground)
            val value = contrast(theme.bright, ground)
            assertTrue(
                value > label,
                "${theme.name}: the value ($value:1) must outrank the label ($label:1)"
            )
        }
    }

    @Test
    fun `the mid phosphor is a real improvement over dim`() {
        // Green went 2.97 -> 8.11, a 2.7x lift. Guard against a future
        // palette edit that quietly walks that back.
        for (theme in DisplayTheme.entries) {
            val dim = contrast(theme.dim, ground)
            val mid = contrast(theme.mid, ground)
            assertTrue(
                mid > dim * 1.5,
                "${theme.name}: mid ($mid:1) is barely better than dim ($dim:1)"
            )
        }
    }
}
