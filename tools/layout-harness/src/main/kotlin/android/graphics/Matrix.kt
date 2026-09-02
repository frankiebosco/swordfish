package android.graphics

import java.awt.geom.AffineTransform

/**
 * Only `setSkew` about a pivot, which is what SegmentDisplay uses to give
 * the seven-segment glyphs their forward lean. See Primitives.kt.
 *
 * Implemented rather than stubbed: the slant is visible, and a preview that
 * silently drew upright digits would misrepresent the panel.
 */
class Matrix {
    internal var awt: AffineTransform = AffineTransform()

    fun reset() { awt = AffineTransform() }

    /** Skew about (px, py), matching Android's signature. */
    fun setSkew(kx: Float, ky: Float, px: Float, py: Float) {
        val t = AffineTransform()
        t.translate(px.toDouble(), py.toDouble())
        t.shear(kx.toDouble(), ky.toDouble())
        t.translate(-px.toDouble(), -py.toDouble())
        awt = t
    }

    fun setSkew(kx: Float, ky: Float) {
        awt = AffineTransform().apply { shear(kx.toDouble(), ky.toDouble()) }
    }
}
