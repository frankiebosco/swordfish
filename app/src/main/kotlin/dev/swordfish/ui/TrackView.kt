package dev.swordfish.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import dev.swordfish.physics.DriveTrack

/**
 * Draws a drive's GPS trace, coloured by how efficiently it was driven.
 *
 * ## No basemap, deliberately
 *
 * See [DriveTrack]. The shape of the route plus where the good and bad
 * stretches were is the interesting part; the road names are already known
 * to whoever drove it. A tile source would mean a key, a quota and a network
 * dependency for something the logbook does not need.
 *
 * ## Why segments and not a Path
 *
 * Each leg is drawn as its own line so it can carry its own colour. A single
 * `Path` would be one colour for the whole drive, which throws away the only
 * information the map adds over "you went somewhere".
 */
class TrackView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x50, 0x70, 0x5C)
        textSize = 26f
    }

    var track: DriveTrack.Track? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = track

        if (t == null || t.isEmpty) {
            canvas.drawText(
                "NO GPS TRACE IN THIS DRIVE", 24f, height / 2f, labelPaint
            )
            return
        }

        // Inset so a rounded stroke at the extreme of the track is not
        // clipped by the view's own edge.
        val pad = 18f
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return

        linePaint.strokeWidth = (minOf(w, h) * 0.012f).coerceIn(3f, 9f)

        val pts = t.points
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            // Colour from the leg's END: the efficiency reading belongs to
            // the sample, and the leg is how the car got there.
            linePaint.color = colourFor(b.efficiency)
            canvas.drawLine(
                pad + (a.x * w).toFloat(), pad + (a.y * h).toFloat(),
                pad + (b.x * w).toFloat(), pad + (b.y * h).toFloat(),
                linePaint
            )
        }

        // Start and end markers. Without them a loop drive is ambiguous
        // about which way it was driven.
        val first = pts.first()
        val last = pts.last()
        dotPaint.color = C_START
        canvas.drawCircle(
            pad + (first.x * w).toFloat(), pad + (first.y * h).toFloat(),
            linePaint.strokeWidth * 1.6f, dotPaint
        )
        dotPaint.color = C_END
        canvas.drawCircle(
            pad + (last.x * w).toFloat(), pad + (last.y * h).toFloat(),
            linePaint.strokeWidth * 1.6f, dotPaint
        )
    }

    /**
     * Efficiency to colour: red through amber to the panel's phosphor green.
     *
     * The same direction of travel as the gauge's own efficiency lamp, so
     * the map reads as the instrument does rather than inventing a second
     * vocabulary for the same idea.
     *
     * Null -- stopped, or in fuel cutoff -- draws dim grey. It is not bad
     * driving, it is an absence of the thing being measured, and painting
     * it red would be a lie about the drive.
     */
    private fun colourFor(eff: Double?): Int {
        if (eff == null) return C_UNKNOWN
        val e = eff.coerceIn(0.0, 1.0)
        return if (e < 0.5) {
            val k = (e / 0.5).toFloat()
            Color.rgb(
                lerp(0xC0, 0xD8, k), lerp(0x28, 0x9A, k), lerp(0x20, 0x22, k)
            )
        } else {
            val k = ((e - 0.5) / 0.5).toFloat()
            Color.rgb(
                lerp(0xD8, 0x39, k), lerp(0x9A, 0xE0, k), lerp(0x22, 0x7A, k)
            )
        }
    }

    private fun lerp(a: Int, b: Int, k: Float): Int =
        (a + (b - a) * k).toInt().coerceIn(0, 255)

    private companion object {
        val C_START = Color.rgb(0x39, 0xE0, 0x7A)
        val C_END = Color.rgb(0xFF, 0xB0, 0x30)
        val C_UNKNOWN = Color.rgb(0x2A, 0x33, 0x2D)
    }
}
