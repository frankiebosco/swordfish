package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import dev.swordfish.physics.RadarLayout

/**
 * Prints the panel's computed vertical stack for a given surface.
 *
 * `./gradlew :layout-harness:probe` -- the numeric companion to the PNGs.
 * A snapshot shows THAT something is wrong; this shows by how many pixels,
 * which is what a fix has to be written against.
 */
fun main() {
    val g = Geometry.ND2
    // Matches GaugeRenderer.drawWide: the drawn area, not the stable rect.
    val top = 8f
    val height = 384f
    val layout = PanelLayout.choose(g.stable.width(), g.stable.height())

    println("=== ${g.name}  ${g.width}x${g.height}  stable=${g.stable} ===")
    println("mode=${layout.mode}  drawn top=$top height=$height")
    println()

    val segH = height * PanelLayout.deltaVTextFraction(layout.mode)
    val dvTop = top + height * PanelLayout.DV_TOP_WIDE
    println("INSTRUMENTS centre column:")
    println("  dv segments      top=%.1f  bottom=%.1f".format(dvTop, dvTop + segH))
    println("  units+START line %.1f  (text %.1fpx)".format(
        PanelLayout.unitsLineBaselinePx(top, height, layout.mode),
        height * PanelLayout.UNITS_LINE_TEXT_FRACTION))
    println("  SPEED label      %.1f".format(PanelLayout.speedLabelBaselinePx(top, height)))
    println("  gap units->SPEED %.1f px".format(
        PanelLayout.unitsToSpeedGapPx(top, height, layout.mode)))
    println("  panel bottom     %.1f".format(top + height))
    println()

    // navW / centreW / cf are the values the REAL head unit logged in
    // SwordfishGeom on the 2026-08-24 drive, so this matches the car.
    val navW = 272.0f
    val centreW = 362.56f
    val cf = 0.45449173f
    val scopeR = PanelLayout.pairRadiusPx(
        navColumnWidthPx = navW,
        scopeColumnWidthPx = centreW,
        heightPx = height,
        centreFraction = cf,
        bottomReservedFraction = PanelLayout.WIDE_READOUT_BAND.toFloat(),
        topReservedFraction = PanelLayout.WIDE_BANNER_BAND.toFloat()
    )
    println("RADAR centre column:")
    println("  scope radius     %.1f".format(scopeR))
    val rowTop = top + RadarLayout.readoutTopWidePx(height, scopeR, 0f)
    val rSegH = height * 0.105f
    val rStart = rowTop + rSegH + height * 0.048f
    println("  readout rowTop   %.1f".format(rowTop))
    println("  readout bottom   %.1f".format(rowTop + rSegH))
    println("  START baseline   %.1f  (text %.1fpx)".format(rStart, height * 0.036f))
    println("  panel bottom     %.1f".format(top + height))
    val overflow = rStart - (top + height)
    println(if (overflow > 0)
        "  ** START OVERFLOWS the panel by %.1f px **".format(overflow)
    else "  START clears the bottom by %.1f px".format(-overflow))
}
