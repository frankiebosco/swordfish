package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The WIDE centre column's vertical stack must not collide.
 *
 * Found 2026-08-24 by the desktop layout harness: rendering the real ND2
 * geometry (800x400, stable area 24,88-776,388) with a realistic state put
 * the START/units line and the SPEED label 1.2px apart -- overlapping.
 *
 * It survived months of drives because `tripStartDeltaV` is null until the
 * fuel tracker seeds, so the START line only appears once already moving,
 * which is the worst time to study the screen.
 */
class CentreColumnSpacingTest {

    /** The real head unit, from SwordfishGeom on the 2026-08-24 drive. */
    private val nd2Top = 8f
    private val nd2Height = 384f

    /**
     * Clearance needed between two stacked text baselines.
     *
     * NOT a full line height of the upper text: baselines only collide once
     * the upper line's DESCENDERS reach the lower line's ASCENDERS, so the
     * honest measure is descender(upper) + ascent(lower). Requiring a whole
     * line height rejects layouts that are actually fine.
     */
    private fun clearanceNeeded(upperTextPx: Float, lowerTextPx: Float): Float =
        upperTextPx * DESCENDER_RATIO + lowerTextPx * ASCENT_RATIO

    @Test
    fun `units line and SPEED label do not overlap on the ND2`() {
        val gap = PanelLayout.unitsToSpeedGapPx(
            nd2Top, nd2Height, PanelLayout.Mode.WIDE
        )
        val needed = clearanceNeeded(
            nd2Height * PanelLayout.UNITS_LINE_TEXT_FRACTION,
            nd2Height * PanelLayout.SECTION_LABEL_FRACTION
        )

        assertTrue(
            gap >= needed,
            "the units/START line and the SPEED label are ${"%.1f".format(gap)}px " +
                "apart but need ${"%.1f".format(needed)}px to clear each other's " +
                "descenders and ascenders."
        )
    }

    @Test
    fun `the stack stays clear across plausible head-unit heights`() {
        // Every surface height the app might plausibly meet, not just the
        // ND2 -- the panel must degrade, not overlap, on an unfamiliar car.
        // Both breakpoints, because NARROW has its own proportions.
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            for (h in 300..600 step 20) {
                val gap = PanelLayout.unitsToSpeedGapPx(0f, h.toFloat(), mode)
                val needed = clearanceNeeded(
                    h * PanelLayout.UNITS_LINE_TEXT_FRACTION,
                    h * PanelLayout.SECTION_LABEL_FRACTION
                )
                assertTrue(
                    gap >= needed,
                    "$mode at ${h}px tall: units/SPEED gap is " +
                        "${"%.1f".format(gap)}px, needs ${"%.1f".format(needed)}px"
                )
            }
        }
    }

    @Test
    fun `the units line sits below the delta-V segments, not inside them`() {
        val dvTop = nd2Top + nd2Height * PanelLayout.DV_TOP_WIDE
        val segBottom = dvTop + nd2Height *
            PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE)
        val start = PanelLayout.unitsLineBaselinePx(
            nd2Top, nd2Height, PanelLayout.Mode.WIDE
        )
        assertTrue(
            start > segBottom,
            "the units line must clear the headline segments it sits under"
        )
    }

    companion object {
        /** Rough descender depth as a fraction of text size. */
        const val DESCENDER_RATIO = 0.25f
        /** Rough cap height as a fraction of text size. */
        const val ASCENT_RATIO = 0.75f
    }
}
