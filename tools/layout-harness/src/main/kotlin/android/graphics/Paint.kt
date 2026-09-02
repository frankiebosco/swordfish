package android.graphics

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

/**
 * The subset of Paint the gauge renderer sets. See Primitives.kt for why
 * this module exists at all.
 *
 * Text measurement is the one place fidelity actually matters: the renderer
 * calls `measureText` to centre and right-align readouts, so a wrong width
 * moves things. Java2D's metrics are close to Skia's but not identical --
 * treat placement as accurate to about a pixel, not exactly.
 */
class Paint(flags: Int = 0) {

    enum class Align { LEFT, CENTER, RIGHT }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    @JvmField var color: Int = Color.BLACK
    @JvmField var strokeWidth: Float = 0f
    @JvmField var textSize: Float = 12f
    @JvmField var textAlign: Align = Align.LEFT
    @JvmField var style: Style = Style.FILL
    @JvmField var isAntiAlias: Boolean = true
    @JvmField var typeface: Typeface = Typeface.DEFAULT

    var alpha: Int
        get() = Color.alpha(color)
        set(v) { color = Color.argb(v, Color.red(color), Color.green(color), Color.blue(color)) }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }

    /** The AWT font this paint currently describes. */
    internal fun awtFont(): Font {
        val style = if (typeface.bold) Font.BOLD else Font.PLAIN
        return Font(typeface.family, style, 1).deriveFont(textSize)
    }

    fun measureText(text: String): Float {
        if (text.isEmpty()) return 0f
        // Anti-aliased, fractional-metrics context: matches how the text is
        // actually rasterised below, so measuring and drawing agree.
        val frc = FontRenderContext(AffineTransform(), true, true)
        return awtFont().getStringBounds(text, frc).width.toFloat()
    }

    fun descent(): Float {
        val frc = FontRenderContext(AffineTransform(), true, true)
        return awtFont().getLineMetrics("Ay", frc).descent
    }

    fun ascent(): Float {
        val frc = FontRenderContext(AffineTransform(), true, true)
        return -awtFont().getLineMetrics("Ay", frc).ascent
    }

    fun getTextBounds(text: String, start: Int, end: Int, bounds: Rect) {
        val frc = FontRenderContext(AffineTransform(), true, true)
        val r = awtFont().getStringBounds(text.substring(start, end), frc)
        bounds.set(0, -r.height.toInt(), r.width.toInt(), 0)
    }
}
