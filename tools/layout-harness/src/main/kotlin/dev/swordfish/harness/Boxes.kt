package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import dev.swordfish.physics.RadarLayout

/**
 * Draggable BOXES around the circular instruments.
 *
 * ## Why boxes and not more lines
 *
 * The line handles in [Knob] move one value along one axis, which suits a
 * vertical stack of text. The navball and the scope are two-dimensional
 * objects with a size: describing them as three separate sliders (x, y,
 * radius) is exactly the indirection this tool exists to remove. A box you
 * drag by the middle and resize by a corner IS the mental model.
 *
 * ## What a box actually writes
 *
 * Nothing about the pair rule changes. [PanelLayout.pairRadiusPx] still
 * gives BOTH circles one radius -- two sizing formulas is what produced
 * navR=91.5 against scopeR=103.7 and a rim in the readout row.
 *
 * A box instead edits a SCALE and an OFFSET applied on top of that radius:
 * `NAVBALL_SCALE/DX/DY` and `SCOPE_SCALE/DX/DY`, all defaulting to no
 * change. So the pair stays coherent until someone deliberately breaks it,
 * and when they do, the deviation is a named constant rather than a second
 * formula competing with the first.
 *
 * dx/dy are fractions of panel WIDTH and HEIGHT respectively, so a tuned
 * value means the same thing on a surface of another size.
 */
class InstrumentBox(
    val label: String,
    val scaleName: String,
    val dxName: String,
    val dyName: String,
    val scaleDefault: Float,
    val dxDefault: Float,
    val dyDefault: Float,
    /** Base bounds, before scale/offset, for the current surface. */
    val base: (geom: Geometry, radar: Boolean) -> Bounds?
) {
    var scale: Float = scaleDefault
    var dx: Float = dxDefault
    var dy: Float = dyDefault

    /** Changed in the breakpoint currently being edited. */
    fun changedIn(narrowMode: Boolean): Boolean = if (narrowMode) {
        kotlin.math.abs(scaleN - 1.0f) > 1e-4f ||
            kotlin.math.abs(dxN) > 1e-5f || kotlin.math.abs(dyN) > 1e-5f
    } else {
        kotlin.math.abs(scale - scaleDefault) > 1e-4f ||
            kotlin.math.abs(dx - dxDefault) > 1e-5f ||
            kotlin.math.abs(dy - dyDefault) > 1e-5f
    }

    fun reset() {
        scale = scaleDefault; dx = dxDefault; dy = dyDefault
        scaleN = 1.0f; dxN = 0.0f; dyN = 0.0f
    }

    /** Live value for the breakpoint being edited. */
    fun curScale(n: Boolean) = if (n) scaleN else scale
    fun curDx(n: Boolean) = if (n) dxN else dx
    fun curDy(n: Boolean) = if (n) dyN else dy

    fun setScale(n: Boolean, v: Float) { if (n) scaleN = v else scale = v }
    fun setDx(n: Boolean, v: Float) { if (n) dxN = v else dx = v }
    fun setDy(n: Boolean, v: Float) { if (n) dyN = v else dy = v }

    /**
     * WIDE and NARROW hold SEPARATE values on the same box.
     *
     * The box object is reused across surfaces, so it keeps one set per
     * breakpoint and swaps which it is editing when the surface changes.
     * Without this, dragging on the collapsed surface would overwrite the
     * full-width tuning -- which is the bug this whole split exists to fix.
     */
    var scaleN: Float = 1.0f
    var dxN: Float = 0.0f
    var dyN: Float = 0.0f

    /** Suffix for the breakpoint currently being edited. */
    private fun suffix(narrowMode: Boolean) = if (narrowMode) "_N" else ""

    fun push(narrowMode: Boolean) {
        val sfx = suffix(narrowMode)
        LayoutOverride.values[scaleName + sfx] = if (narrowMode) scaleN else scale
        LayoutOverride.values[dxName + sfx] = if (narrowMode) dxN else dx
        LayoutOverride.values[dyName + sfx] = if (narrowMode) dyN else dy
    }

    /**
     * Current drawn bounds: centre x, centre y, half-width, half-height.
     *
     * Circular instruments have half-width == half-height; the text blocks
     * are wider than they are tall, so a square box round them would be a
     * lie about what you are grabbing.
     */
    fun bounds(geom: Geometry, radar: Boolean): Bounds? {
        val b = base(geom, radar) ?: return null
        val n = geom.isNarrow
        val panelH = (geom.height - (geom.height * SURFACE_EDGE_INSET).toInt() * 2)
        return Bounds(
            b.cx + geom.width * curDx(n),
            b.cy + panelH * curDy(n),
            b.halfW * curScale(n),
            b.halfH * curScale(n)
        )
    }

    /** Backwards-compatible circle view, for the round instruments. */
    fun circle(geom: Geometry, radar: Boolean): Triple<Float, Float, Float>? {
        val b = bounds(geom, radar) ?: return null
        return Triple(b.cx, b.cy, b.halfW)
    }

    /** Kotlin for both breakpoints, only for values that actually moved. */
    fun kotlinLines(): List<String> = buildList {
        if (kotlin.math.abs(scale - scaleDefault) > 1e-4f) add(line(scaleName, scale))
        if (kotlin.math.abs(dx - dxDefault) > 1e-5f) add(line(dxName, dx))
        if (kotlin.math.abs(dy - dyDefault) > 1e-5f) add(line(dyName, dy))
        if (kotlin.math.abs(scaleN - 1.0f) > 1e-4f) add(line(scaleName + "_N", scaleN))
        if (kotlin.math.abs(dxN) > 1e-5f) add(line(dxName + "_N", dxN))
        if (kotlin.math.abs(dyN) > 1e-5f) add(line(dyName + "_N", dyN))
    }

    /** Every tuned value, for the save button. */
    fun allValues(): Map<String, Float> = mapOf(
        scaleName to scale, dxName to dx, dyName to dy,
        scaleName + "_N" to scaleN, dxName + "_N" to dxN, dyName + "_N" to dyN
    )

    private fun line(name: String, v: Float): String =
        "    const val " + name + " = " +
            "%.4f".format(v).trimEnd('0').trimEnd('.').let {
                if (it.endsWith("-") || it.isEmpty() || it == "-0") "0" else it
            } + "f"
}

/** Centre and half-extents of a box, in panel px. */
data class Bounds(val cx: Float, val cy: Float, val halfW: Float, val halfH: Float)

const val PANEL_INSET_PX = 8f

/** Mirrors GaugeRenderer.SURFACE_EDGE_INSET. */
const val SURFACE_EDGE_INSET = 0.02f

/**
 * The geometry the renderer derives for the pair, reproduced here.
 *
 * This duplicates arithmetic from `drawWide`, which is a real cost: if the
 * renderer's own placement changes, these boxes drift from the circles they
 * are supposed to sit on. It is accepted because the alternative is
 * threading a reporting channel through the render path purely for a dev
 * tool, and the numbers below come from PanelLayout -- the same source the
 * renderer uses -- rather than being copied constants.
 *
 * `BoxOverlayTest` pins the box to the drawn circle so the drift is caught.
 */
private fun pairGeometry(geom: Geometry, radar: Boolean): PairGeom? {
    val stable = geom.stable
    val layout0 = PanelLayout.choose(stable.width(), stable.height())
    val layout = if (radar) PanelLayout.forRadar(layout0) else layout0
    if (layout.mode != PanelLayout.Mode.WIDE) return null

    // TWO different rects, exactly as drawWide receives them:
    //   area      = fullSurfaceArea(container) -- the FULL surface inset by
    //               SURFACE_EDGE_INSET (2% of height), so the circles are
    //               sized against the real height
    //   statsArea = the STABLE rect, which the stats column uses, because
    //               text must not stray under host chrome
    // Using one rect for both put the navball box centre at 136 against a
    // drawn 189. BoxOverlayTest caught it.
    val inset = (geom.height * SURFACE_EDGE_INSET).toInt()
    val areaLeft = 0f
    val top = inset.toFloat()
    val h = (geom.height - inset * 2).toFloat()
    val w = geom.width.toFloat()

    val navW = (w * layout.navballColumnFraction).toFloat()
    val statsW = (stable.width() * layout.statsColumnFraction).toFloat()
    val centreW = w - navW - statsW

    // WIDE_READOUT_BAND is reserved in BOTH modes, not only radar --
    // drawWide passes it to pairCentreFraction unconditionally. Zeroing it
    // for instruments mode put the box radius at 108.9 against a drawn 87.5,
    // which BoxOverlayTest caught.
    val bottomBand = PanelLayout.tuned(
        "WIDE_READOUT_BAND", PanelLayout.WIDE_READOUT_BAND.toFloat()
    )
    val cf = PanelLayout.pairCentreFraction(bottomBand, 0f)
    val r = PanelLayout.pairRadiusPx(
        navColumnWidthPx = navW,
        scopeColumnWidthPx = centreW,
        heightPx = h,
        centreFraction = cf,
        bottomReservedFraction = bottomBand,
        topReservedFraction = 0f
    )
    val packed = layout.shows(PanelLayout.Element.RADAR) &&
        layout.shows(PanelLayout.Element.NAVBALL)

    val navCx: Float
    val scopeCx: Float
    if (packed) {
        // pairSpanPx(statsArea.right - statsW, area.left)
        val span = (stable.right - statsW) - areaLeft
        val pair = PanelLayout.packInstrumentPair(span, r, r)
        navCx = areaLeft + pair.first
        scopeCx = areaLeft + pair.second
    } else {
        navCx = areaLeft + navW / 2f
        scopeCx = areaLeft + navW + centreW / 2f
    }
    return PairGeom(navCx, scopeCx, top + h * cf, r, layout)
}

private data class PairGeom(
    val navCx: Float,
    val scopeCx: Float,
    val cy: Float,
    val radius: Float,
    val layout: PanelLayout.Resolved
)

/**
 * Centre-column block bounds, mirroring drawCentre.
 *
 * The blocks are addressed by the same fractions the renderer uses, so a
 * box follows the element when a red-line knob moves it.
 */
private fun centreBlock(
    geom: Geometry, radar: Boolean,
    topFraction: Float, heightFraction: Float, widthFactor: Float
): Bounds? {
    val g = pairGeometry(geom, radar) ?: return null
    val inset = (geom.height * SURFACE_EDGE_INSET).toInt()
    val top = inset.toFloat()
    val h = (geom.height - inset * 2).toFloat()
    val w = geom.width.toFloat()
    val navW = w * g.layout.navballColumnFraction.toFloat()
    val statsW = geom.stable.width() * g.layout.statsColumnFraction.toFloat()
    val centreW = w - navW - statsW
    // drawCentre is handed centreDrawLeft; in the packed case that is the
    // scope's centre minus half the column.
    val centreLeft = if (radar) g.scopeCx - centreW / 2f else navW
    val cx = centreLeft + centreW / 2f
    return Bounds(
        cx,
        top + h * (topFraction + heightFraction / 2f),
        centreW * widthFactor / 2f,
        h * heightFraction / 2f
    )
}

fun buildBoxes(): List<InstrumentBox> = listOf(
    InstrumentBox(
        "NAVBALL", "NAVBALL_SCALE", "NAVBALL_DX", "NAVBALL_DY",
        PanelLayout.NAVBALL_SCALE, PanelLayout.NAVBALL_DX, PanelLayout.NAVBALL_DY
    ) { geom, radar ->
        val g = pairGeometry(geom, radar) ?: return@InstrumentBox null
        if (!g.layout.shows(PanelLayout.Element.NAVBALL)) null
        else Bounds(g.navCx, g.cy, g.radius, g.radius)
    },
    InstrumentBox(
        "SCOPE", "SCOPE_SCALE", "SCOPE_DX", "SCOPE_DY",
        PanelLayout.SCOPE_SCALE, PanelLayout.SCOPE_DX, PanelLayout.SCOPE_DY
    ) { geom, radar ->
        if (!radar) return@InstrumentBox null
        val g = pairGeometry(geom, radar) ?: return@InstrumentBox null
        Bounds(g.scopeCx, g.cy, g.radius, g.radius)
    },
    InstrumentBox(
        "STATS", "STATS_SCALE", "STATS_DX", "STATS_DY",
        PanelLayout.STATS_SCALE, PanelLayout.STATS_DX, PanelLayout.STATS_DY
    ) { geom, radar ->
        val g = pairGeometry(geom, radar) ?: return@InstrumentBox null
        if (!g.layout.shows(PanelLayout.Element.STATS_BLOCK)) return@InstrumentBox null
        val statsW = geom.stable.width() * g.layout.statsColumnFraction.toFloat()
        // Mirrors drawWide: block height is the stats area, centred on the
        // instruments' shared centre.
        val blockH = geom.stable.height() * 0.82f
        Bounds(
            geom.stable.right - statsW / 2f,
            g.cy,
            statsW / 2f,
            blockH / 2f
        )
    },
    InstrumentBox(
        "ISP", "ISP_SCALE", "ISP_DX", "ISP_DY",
        PanelLayout.ISP_SCALE, PanelLayout.ISP_DX, PanelLayout.ISP_DY
    ) { geom, radar ->
        if (radar) return@InstrumentBox null
        centreBlock(geom, radar, 0.12f, 0.115f, 0.62f)
    },
    InstrumentBox(
        "DELTA-V", "DELTAV_SCALE", "DELTAV_DX", "DELTAV_DY",
        PanelLayout.DELTAV_SCALE, PanelLayout.DELTAV_DX, PanelLayout.DELTAV_DY
    ) { geom, radar ->
        if (radar) return@InstrumentBox null
        centreBlock(
            geom, radar,
            PanelLayout.tuned("DV_TOP_WIDE", PanelLayout.DV_TOP_WIDE),
            PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE),
            0.86f
        )
    },
    InstrumentBox(
        "SPEED", "SPEED_SCALE", "SPEED_DX", "SPEED_DY",
        PanelLayout.SPEED_SCALE, PanelLayout.SPEED_DX, PanelLayout.SPEED_DY
    ) { geom, radar ->
        if (radar) return@InstrumentBox null
        centreBlock(
            geom, radar,
            PanelLayout.tuned("SPEED_TOP", PanelLayout.SPEED_TOP),
            0.125f, 0.55f
        )
    },
    InstrumentBox(
        "READOUTS", "READOUTS_SCALE", "READOUTS_DX", "READOUTS_DY",
        PanelLayout.READOUTS_SCALE, PanelLayout.READOUTS_DX, PanelLayout.READOUTS_DY
    ) { geom, radar ->
        if (!radar) return@InstrumentBox null
        val g = pairGeometry(geom, radar) ?: return@InstrumentBox null
        val inset = (geom.height * SURFACE_EDGE_INSET).toInt()
        val h = (geom.height - inset * 2).toFloat()
        val w = geom.width.toFloat()
        val navW = w * g.layout.navballColumnFraction.toFloat()
        val statsW = geom.stable.width() * g.layout.statsColumnFraction.toFloat()
        val centreW = w - navW - statsW
        val rowTop = inset + RadarLayout.readoutTopWidePx(h, g.radius, 0f)
        val segH = h * 0.105f
        Bounds(
            g.scopeCx, rowTop + segH / 2f, centreW * 0.5f, segH / 2f
        )
    }
)
