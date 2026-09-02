package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The radar-mode bottom band must hold everything drawn in it.
 *
 * Found 2026-08-24 by the desktop layout harness: on the real ND2 surface
 * the START reference line was drawn 6.1px BELOW the panel bottom, so it
 * was simply clipped away. `WIDE_READOUT_BAND` reserved space for the
 * demoted readout row but not for the START line under it.
 */
class RadarReadoutBandTest {

    // The real head unit, from SwordfishGeom on the 2026-08-24 drive.
    private val top = 8f
    private val height = 384f
    private val navW = 272.0f
    private val centreW = 362.56f
    private val centreFraction = 0.45449173f

    private fun scopeRadius(): Float = PanelLayout.pairRadiusPx(
        navColumnWidthPx = navW,
        scopeColumnWidthPx = centreW,
        heightPx = height,
        centreFraction = centreFraction,
        bottomReservedFraction = PanelLayout.WIDE_READOUT_BAND.toFloat(),
        topReservedFraction = PanelLayout.WIDE_BANNER_BAND.toFloat()
    )

    @Test
    fun `the START line stays inside the panel on the ND2`() {
        val r = scopeRadius()
        val rowTop = top + RadarLayout.readoutTopWidePx(height, r, 0f)
        val rowH = height * 0.105f
        val start = RadarLayout.radarStartBaselinePx(rowTop, rowH, height)
        val panelBottom = top + height

        assertTrue(
            start <= panelBottom,
            "the START line's baseline (${"%.1f".format(start)}) falls below the " +
                "panel bottom (${"%.1f".format(panelBottom)}), so it is clipped. " +
                "WIDE_READOUT_BAND must reserve room for the row AND this line."
        )
    }

    @Test
    fun `the START line's descenders also clear the bottom`() {
        val r = scopeRadius()
        val rowTop = top + RadarLayout.readoutTopWidePx(height, r, 0f)
        val rowH = height * 0.105f
        val start = RadarLayout.radarStartBaselinePx(rowTop, rowH, height)
        // A baseline exactly on the edge still clips descenders.
        val descender = height * RadarLayout.RADAR_START_TEXT_FRACTION.toFloat() * 0.3f

        assertTrue(
            start + descender <= top + height,
            "the START line's descenders are clipped by " +
                "${"%.1f".format(start + descender - top - height)}px"
        )
    }

    @Test
    fun `the readout row itself never overlaps the scope`() {
        val r = scopeRadius()
        val centre = height * RadarLayout.scopeCentreWideFraction(0f, height)
        val scopeBottom = centre + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()
        val rowTop = top + RadarLayout.readoutTopWidePx(height, r, 0f)

        assertTrue(
            rowTop >= scopeBottom,
            "the demoted readout row starts above the scope's bottom edge"
        )
    }
}
