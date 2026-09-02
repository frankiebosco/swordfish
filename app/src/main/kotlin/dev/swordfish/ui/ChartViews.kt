package dev.swordfish.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import dev.swordfish.physics.ChartScale
import dev.swordfish.physics.DriveCharts
import dev.swordfish.physics.Units
import dev.swordfish.physics.UnitSystem

/**
 * The logbook's charts, drawn by hand.
 *
 * ## Why no charting library
 *
 * The requirements are unusually small: three static figures, no zoom, no
 * pan, no animation, no tooltips, no interaction at all. Every Android
 * charting library is built for the interactive-dashboard case, so adopting
 * one means importing a great deal to use very little -- and then fighting
 * its opinionated defaults to match the phosphor palette, which IS the
 * product here.
 *
 * `TrackView` already proved the approach: 129 lines produced a projected
 * GPS path with a colour ramp. Bars and a donut are easier than that.
 *
 * The arithmetic that actually goes wrong -- tick selection, value-to-pixel
 * mapping, closing a donut without a rounding gap -- lives in `ChartScale`
 * where it is tested. These views only draw.
 *
 * **If interaction is ever wanted** -- pinch to zoom a long drive, tap a bar
 * for detail -- hand-rolling stops being the right trade and the answer is
 * Vico plus Compose together. That is the line.
 */

/** Shared palette and helpers, so the three charts cannot drift apart. */
internal object ChartStyle {
    val PHOSPHOR = Color.rgb(0x39, 0xE0, 0x7A)
    val BRIGHT = Color.rgb(0xC8, 0xFF, 0xDC)
    val DIM = Color.rgb(0x50, 0x70, 0x5C)
    val GRID = Color.rgb(0x1A, 0x28, 0x20)
    val WARN = Color.rgb(0xFF, 0xB0, 0x30)
    val COOL = Color.rgb(0x39, 0xA0, 0xE0)
    val BAD = Color.rgb(0xC0, 0x40, 0x30)

    fun text(size: Float, colour: Int, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = colour
            this.textAlign = align
            typeface = Typeface.MONOSPACE
        }

    fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colour
    }

    fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colour
        strokeWidth = width
    }
}

/**
 * Mean Isp per speed band -- "where is my sweet spot".
 *
 * The app's whole argument in one picture. Real ridge-road data has structure rather
 * than a flat smear, which is the only reason this is worth drawing.
 */
class IspBySpeedView(context: Context) : View(context) {

    var bands: List<DriveCharts.SpeedBand> = emptyList()
        set(v) { field = v; invalidate() }

    var units: UnitSystem = UnitSystem.DEFAULT
        set(v) { field = v; invalidate() }

    private val label = ChartStyle.text(24f, ChartStyle.DIM)
    private val labelRight = ChartStyle.text(24f, ChartStyle.DIM, Paint.Align.RIGHT)
    private val centred = ChartStyle.text(24f, ChartStyle.DIM, Paint.Align.CENTER)
    private val grid = ChartStyle.stroke(ChartStyle.GRID, 1.5f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bands.isEmpty()) {
            canvas.drawText("NO DATA", 24f, height / 2f, label)
            return
        }

        // Room for the y labels on the left and the x labels below.
        val leftPad = 92f
        val bottomPad = 46f
        val topPad = 18f
        val plotW = width - leftPad - 16f
        val plotH = height - bottomPad - topPad
        if (plotW <= 0 || plotH <= 0) return

        // includeZero: a bar chart baselined at 28,000 turns a 5% difference
        // into a dramatic one -- truthful numbers, misleading picture.
        val axis = ChartScale.niceAxis(
            0.0, bands.maxOf { it.meanIspS }, targetTicks = 4, includeZero = true
        )

        for (t in axis.ticks) {
            val y = topPad + plotH * (1.0 - axis.fraction(t)).toFloat()
            canvas.drawLine(leftPad, y, leftPad + plotW, y, grid)
            canvas.drawText(ChartScale.formatTick(t), leftPad - 10f, y + 8f, labelRight)
        }

        val best = DriveCharts.sweetSpot(bands)
        val slot = plotW / bands.size
        val barW = slot * 0.66f

        for ((i, b) in bands.withIndex()) {
            val cx = leftPad + slot * (i + 0.5f)
            val h = (plotH * axis.fraction(b.meanIspS)).toFloat()
            val top = topPad + plotH - h

            // The sweet spot is the actionable part, so it is the only bar
            // that gets the full phosphor.
            val isBest = b === best
            canvas.drawRect(
                cx - barW / 2f, top, cx + barW / 2f, topPad + plotH,
                ChartStyle.fill(if (isBest) ChartStyle.PHOSPHOR else ChartStyle.GRID)
            )
            if (!isBest) {
                canvas.drawRect(
                    cx - barW / 2f, top, cx + barW / 2f, topPad + plotH,
                    ChartStyle.stroke(ChartStyle.DIM, 1.5f)
                )
            }

            val lo = speedLabel(b.fromMps)
            canvas.drawText(lo, cx, height - 14f, centred)
        }

        best?.let {
            canvas.drawText(
                "best ${speedLabel(it.fromMps)}-${speedLabel(it.toMps)} " +
                    "${ChartScale.formatTick(it.meanIspS)}s",
                leftPad, topPad - 2f,
                ChartStyle.text(22f, ChartStyle.PHOSPHOR)
            )
        }
    }

    private fun speedLabel(mps: Double): String = when (units) {
        UnitSystem.IMPERIAL -> "%.0f".format(Units.mpsToMph(mps))
        UnitSystem.METRIC -> "%.0f".format(mps * 3.6)
    }
}

/**
 * Where the trip's energy went.
 *
 * The most KSP thing the logbook can show: a budget accounted for line by
 * line rather than one number that went down.
 */
class WaterfallView(context: Context) : View(context) {

    var data: DriveCharts.Waterfall? = null
        set(v) { field = v; invalidate() }

    private val label = ChartStyle.text(24f, ChartStyle.DIM)
    private val value = ChartStyle.text(24f, ChartStyle.BRIGHT, Paint.Align.RIGHT)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: run {
            canvas.drawText("NO DATA", 24f, height / 2f, label)
            return
        }
        if (d.totalJ <= 0.0) {
            canvas.drawText("NO WORK RECORDED", 24f, height / 2f, label)
            return
        }

        // Climb and descent are shown SEPARATELY, never netted. Netting them
        // to zero would hide the entire story of a drive up the ridge road and back.
        val rows = listOf(
            Triple("ROAD LOAD", d.roadLoadJ, ChartStyle.PHOSPHOR),
            Triple("CLIMBING", d.climbJ, ChartStyle.WARN),
            Triple("RECOVERED", -d.descentJ, ChartStyle.COOL)
        )

        val maxMag = rows.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(1.0)
        val rowH = height / rows.size.toFloat()
        val leftPad = 150f
        val rightPad = 130f
        val barMax = width - leftPad - rightPad

        for ((i, r) in rows.withIndex()) {
            val (name, joules, colour) = r
            val cy = rowH * (i + 0.5f)
            val w = (barMax * kotlin.math.abs(joules) / maxMag).toFloat()

            canvas.drawText(name, 12f, cy + 8f, label)
            canvas.drawRect(
                leftPad, cy - rowH * 0.22f, leftPad + w, cy + rowH * 0.22f,
                ChartStyle.fill(colour)
            )
            // Megajoules: a drive is tens of MJ and raw joules would be an
            // eight-digit number nobody reads.
            canvas.drawText(
                "%.1f MJ".format(kotlin.math.abs(joules) / 1e6),
                width - 12f, cy + 8f, value
            )
        }
    }
}

/**
 * How the drive divided between operating states.
 *
 * Coasting is the number worth surfacing: it is free distance and directly
 * improvable. One measured drive spent 20% of its samples in fuel cutoff.
 */
class StatePieView(context: Context) : View(context) {

    var slices: List<DriveCharts.StateSlice> = emptyList()
        set(v) { field = v; invalidate() }

    private val label = ChartStyle.text(24f, ChartStyle.DIM)
    private val rect = RectF()

    /**
     * A colour per state, fixed rather than assigned by order.
     *
     * A legend whose colours move between drives is worse than no legend.
     */
    private fun colourFor(state: String): Int = when (state) {
        "CRUISE" -> ChartStyle.PHOSPHOR
        "DESCENT" -> ChartStyle.COOL
        "IDLE" -> ChartStyle.WARN
        "DFCO" -> ChartStyle.BRIGHT
        else -> ChartStyle.DIM
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) {
            canvas.drawText("NO DATA", 24f, height / 2f, label)
            return
        }

        val d = minOf(width * 0.55f, height.toFloat()) * 0.82f
        val cx = d * 0.62f + 12f
        val cy = height / 2f
        rect.set(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)

        // Accumulated angles, so rounding cannot leave a wedge of background
        // where the fractions do not quite sum to one.
        val angles = ChartScale.sliceAngles(slices.map { it.fraction })
        for ((i, s) in slices.withIndex()) {
            val (start, sweep) = angles[i]
            canvas.drawArc(
                rect,
                (start * 360.0 - 90.0).toFloat(),
                (sweep * 360.0).toFloat(),
                true, ChartStyle.fill(colourFor(s.state))
            )
        }

        // Punch the middle out: a donut reads as proportions, a full pie
        // invites reading the area, which people do badly.
        canvas.drawCircle(cx, cy, d * 0.27f, ChartStyle.fill(LogbookActivity.WELL))

        var y = 34f
        val legendX = cx + d / 2f + 22f
        for (s in slices) {
            canvas.drawRect(
                legendX, y - 16f, legendX + 20f, y + 2f,
                ChartStyle.fill(colourFor(s.state))
            )
            canvas.drawText(
                "${s.state}  ${"%.0f".format(s.fraction * 100)}%",
                legendX + 30f, y, label
            )
            y += 36f
        }
    }
}
