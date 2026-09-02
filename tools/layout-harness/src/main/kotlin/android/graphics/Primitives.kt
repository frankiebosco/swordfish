package android.graphics

/**
 * Java2D-backed stand-ins for the handful of android.graphics types the
 * gauge renderer touches.
 *
 * ## Why these exist
 *
 * `GaugeRenderer` is the real drawing code and it must stay the real drawing
 * code -- a mockup that reimplements the layout would drift from the app
 * within a week and be worse than useless for deciding where things go.
 *
 * It cannot simply be called from a JVM test: `:app` unit tests run with
 * `isReturnDefaultValues = true`, so `android.graphics` is a stub jar where
 * `drawText` does nothing and `measureText` returns 0. Rendering there
 * produces a blank image, not a preview.
 *
 * So this module puts *these* classes on the classpath under the real
 * package names, and compiles the untouched `GaugeRenderer.kt` source
 * against them. The renderer cannot tell the difference; the pixels land in
 * a `BufferedImage` instead of a `Surface`.
 *
 * ## Scope, deliberately minimal
 *
 * Only what the renderer actually calls, verified by grepping it: 10 Canvas
 * methods, 7 Paint members, 5 Path methods. This is NOT a general Android
 * emulation layer and must not grow into one. If the renderer starts using
 * a new primitive, add it here and note it -- an unimplemented method should
 * fail loudly rather than silently draw nothing, which is why the
 * unsupported paths throw instead of no-op'ing.
 *
 * ## Fidelity caveat
 *
 * Java2D is not Skia. Glyph advances and hinting differ by a fraction of a
 * pixel, so text may sit a hair off where the head unit puts it. That is
 * acceptable for deciding PLACEMENT, which is what this is for. It is not
 * evidence about anti-aliasing quality, and it is not a substitute for
 * looking at the real car for anything subtle.
 */

class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    /**
     * A PROPERTY, not a method: the renderer writes `stableArea.isEmpty`.
     * Kotlin resolves Android's `isEmpty()` getter that way, so the shim
     * must expose it as a property or the real source will not compile.
     */
    val isEmpty: Boolean get() = left >= right || top >= bottom
    fun set(l: Int, t: Int, r: Int, b: Int) { left = l; top = t; right = r; bottom = b }
    override fun toString() = "Rect($left, $top - $right, $bottom)"
}

class RectF(
    @JvmField var left: Float = 0f,
    @JvmField var top: Float = 0f,
    @JvmField var right: Float = 0f,
    @JvmField var bottom: Float = 0f
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f
    override fun toString() = "RectF($left, $top - $right, $bottom)"
}

/**
 * Colours are packed ARGB ints, exactly as on Android, so the renderer's
 * own bit-twiddling (`shr 16 and 0xFF`, etc.) works unchanged.
 */
object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRANSPARENT = 0

    @JvmStatic fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @JvmStatic fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    @JvmStatic fun red(c: Int): Int = (c shr 16) and 0xFF
    @JvmStatic fun green(c: Int): Int = (c shr 8) and 0xFF
    @JvmStatic fun blue(c: Int): Int = c and 0xFF
}

class Typeface private constructor(val family: String, val bold: Boolean) {
    companion object {
        @JvmField val DEFAULT = Typeface("SansSerif", false)
        @JvmField val MONOSPACE = Typeface("Monospaced", false)
        const val BOLD = 1
        const val NORMAL = 0

        @JvmStatic fun create(base: Typeface, style: Int): Typeface =
            Typeface(base.family, style and BOLD != 0)
    }
}
