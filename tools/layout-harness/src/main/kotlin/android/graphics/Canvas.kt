package android.graphics

import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage

/**
 * Java2D-backed Canvas. See Primitives.kt for why this module exists.
 *
 * Implements exactly the 10 methods `GaugeRenderer` calls. Anything else
 * throws rather than silently drawing nothing: a preview that quietly omits
 * an element is worse than one that fails, because it looks like a layout
 * decision.
 */
class Canvas(private val g: Graphics2D, val width: Int, val height: Int) {

    constructor(image: BufferedImage) : this(
        image.createGraphics(), image.width, image.height
    ) {
        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON
        )
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        g.setRenderingHint(
            RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE
        )
        g.setRenderingHint(
            RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY
        )
    }

    private val stateStack = ArrayDeque<Pair<AffineTransform, java.awt.Shape?>>()

    private fun apply(paint: Paint) {
        g.color = java.awt.Color(paint.color, true)
        if (paint.style != Paint.Style.FILL) {
            g.stroke = BasicStroke(
                if (paint.strokeWidth <= 0f) 1f else paint.strokeWidth,
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER
            )
        }
    }

    fun save(): Int {
        stateStack.addLast(AffineTransform(g.transform) to g.clip)
        return stateStack.size
    }

    fun restore() {
        val (t, c) = stateStack.removeLast()
        g.transform = t
        g.clip = c
    }

    fun rotate(degrees: Float, px: Float, py: Float) {
        g.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())
    }

    fun translate(dx: Float, dy: Float) {
        g.translate(dx.toDouble(), dy.toDouble())
    }

    fun clipPath(path: Path) {
        g.clip(path.awt)
    }

    fun drawColor(color: Int) {
        val prev = g.color
        g.color = java.awt.Color(color, true)
        g.fillRect(0, 0, width, height)
        g.color = prev
    }

    fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
        apply(paint)
        val shape = Rectangle2D.Float(l, t, r - l, b - t)
        if (paint.style == Paint.Style.STROKE) g.draw(shape) else g.fill(shape)
    }

    fun drawRect(rect: RectF, paint: Paint) =
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        apply(paint)
        val shape = Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f)
        if (paint.style == Paint.Style.STROKE) g.draw(shape) else g.fill(shape)
    }

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) {
        apply(paint)
        // A line is always stroked, whatever the paint style says.
        g.stroke = BasicStroke(
            if (paint.strokeWidth <= 0f) 1f else paint.strokeWidth,
            BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER
        )
        g.draw(Line2D.Float(x0, y0, x1, y1))
    }

    fun drawPath(path: Path, paint: Paint) {
        apply(paint)
        if (paint.style == Paint.Style.STROKE) g.draw(path.awt) else g.fill(path.awt)
    }

    /**
     * Android's drawText takes the text ORIGIN (baseline-left/centre/right),
     * which is also Java2D's convention for drawString -- so only the
     * horizontal alignment has to be resolved here.
     */
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        apply(paint)
        g.font = paint.awtFont()
        val w = paint.measureText(text)
        val dx = when (paint.textAlign) {
            Paint.Align.LEFT -> 0f
            Paint.Align.CENTER -> -w / 2f
            Paint.Align.RIGHT -> -w
        }
        g.drawString(text, x + dx, y)
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, @Suppress("UNUSED_PARAMETER") paint: Paint?) {
        if (bitmap.isRecycled) {
            // Mirrors the real Canvas so the harness cannot be used to
            // "prove" a recycled bitmap is harmless. See the project notes.
            throw RuntimeException("Canvas: trying to use a recycled bitmap $bitmap")
        }
        val sx = src?.left ?: 0
        val sy = src?.top ?: 0
        val sw = src?.width() ?: bitmap.width
        val sh = src?.height() ?: bitmap.height
        g.drawImage(
            bitmap.image,
            dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt(),
            sx, sy, sx + sw, sy + sh,
            null
        )
    }

    fun dispose() = g.dispose()
}
