package dev.swordfish.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import dev.swordfish.physics.SevenSegment

/**
 * Draws seven-segment numerals as actual bars.
 *
 * ## Why not a font
 *
 * The reference is the *Ghost in the Machine* sleeve — thick angled bars with
 * hard-cut ends, floating on black. A monospace typeface with a green tint
 * does not get there, because the character of a real display comes from two
 * things a font cannot provide:
 *
 *  1. **Segment geometry.** Bars with mitred ends that meet at the corners,
 *     not letterforms with strokes and curves.
 *  2. **The ghost layer.** On a real LCD the unlit segments remain faintly
 *     visible. That is the single detail that makes a readout look like a
 *     display rather than like text, and a font has no notion of a segment
 *     that exists but is off.
 *
 * So each glyph is drawn twice: once with every segment in the ghost colour,
 * then again with only the lit segments in the phosphor colour.
 *
 * ## Geometry
 *
 * Bars are hexagons — a rectangle with both ends mitred to 45° — so that
 * neighbouring segments meet cleanly at the corners without overlapping. The
 * italic slant is applied as a horizontal shear, exactly as a real display
 * does it.
 */
object SegmentDisplay {

    /** Width of a digit cell as a fraction of its height. */
    const val ASPECT = 0.58f

    /**
     * Gap between digit cells, as a fraction of cell width.
     *
     * Widened from 0.20: at small sizes adjacent digits ran together, which
     * compounded the ghost-brightness problem.
     */
    const val TRACKING = 0.30f

    /**
     * Segment thickness as a fraction of digit height.
     *
     * Thinner than the original 0.135. Fat bars in a small cell leave little
     * gap between segments, so a digit reads as a filled block rather than as
     * a shape -- the "8" effect.
     */
    const val THICKNESS = 0.115f

    /** Forward lean, as horizontal offset per unit height. */
    const val SLANT = 0.10f

    /**
     * Decimal point size, as a multiple of segment thickness.
     *
     * Deliberately LARGER than a bar. A dot the same width as a segment reads
     * as a stray bar rather than as punctuation — at stat-row size the first
     * attempt drew a 5px dot beside 4.8px bars and it vanished, turning "9.9"
     * into "99". Real displays oversize the point for exactly this reason.
     */
    const val DP_SIZE = 0.85f

    /**
     * Total advance width for a string, in pixels.
     *
     * Counts CELLS, not characters: a decimal point rides in the corner of the
     * digit before it and costs no width of its own.
     */
    fun measure(text: String, height: Float): Float {
        val cells = SevenSegment.cells(text)
        if (cells.isEmpty()) return 0f
        val cell = height * ASPECT
        return cell * cells.size + cell * TRACKING * (cells.size - 1)
    }

    /**
     * Draw [text] with its left baseline-box corner at ([x], [y]).
     *
     * @param y Top of the digit box, not a text baseline — segment displays
     *   have no descenders, so a box is the natural reference.
     * @param litPaint Colour for segments that are on.
     * @param ghostPaint Colour for segments that are off. Pass null to omit
     *   the ghost layer entirely.
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        height: Float,
        litPaint: Paint,
        ghostPaint: Paint?
    ) {
        val cell = height * ASPECT
        val advance = cell * (1f + TRACKING)
        var cx = x

        for ((ch, hasDp) in SevenSegment.cells(text)) {
            drawGlyph(canvas, ch, hasDp, cx, y, cell, height, litPaint, ghostPaint)
            cx += advance
        }
    }

    /** Draw [text] centred horizontally on [centreX]. */
    fun drawCentred(
        canvas: Canvas,
        text: String,
        centreX: Float,
        y: Float,
        height: Float,
        litPaint: Paint,
        ghostPaint: Paint?
    ) {
        val w = measure(text, height)
        draw(canvas, text, centreX - w / 2f, y, height, litPaint, ghostPaint)
    }

    private fun drawGlyph(
        canvas: Canvas,
        ch: Char,
        hasDecimalPoint: Boolean,
        x: Float,
        y: Float,
        cellW: Float,
        cellH: Float,
        litPaint: Paint,
        ghostPaint: Paint?
    ) {
        val t = cellH * THICKNESS
        val dpRadius = t * DP_SIZE

        // Sit the point just inside the cell's right edge, NOT out in the
        // tracking gap. Placing it at x + cellW put it midway between two
        // digits, so it read as debris between them rather than as belonging
        // to the digit before it.
        val dpCx = x + cellW - t * 0.20f
        val dpCy = y + cellH - t * 0.55f

        // Shear it with the glyphs. The digits lean; a dot that stays upright
        // sits visibly out of alignment with the character it annotates.
        val dpX = dpCx - SLANT * (cellH - (dpCy - y))
        val dpY = dpCy

        // Ghost layer first: every segment, faintly. This is what sells it.
        if (ghostPaint != null) {
            for (seg in SevenSegment.SEGMENTS) {
                segmentPath(seg, x, y, cellW, cellH, t)?.let {
                    canvas.drawPath(it, ghostPaint)
                }
            }
            canvas.drawCircle(dpX, dpY, dpRadius, ghostPaint)
        }

        for (seg in SevenSegment.SEGMENTS) {
            if (!SevenSegment.isLit(ch, seg)) continue
            segmentPath(seg, x, y, cellW, cellH, t)?.let {
                canvas.drawPath(it, litPaint)
            }
        }
        if (hasDecimalPoint) {
            canvas.drawCircle(dpX, dpY, dpRadius, litPaint)
        }
    }

    /**
     * Build the hexagonal path for one segment.
     *
     * Horizontal segments are wide hexagons, vertical ones tall. Both are
     * mitred at 45° so adjacent segments meet at a corner without overlap —
     * an overlap would show as a brighter notch where two bars stack.
     */
    private fun segmentPath(
        segment: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        t: Float
    ): Path? {
        // Inset so the glyph does not touch its cell edges.
        val pad = t * 0.35f
        val left = x + pad
        val right = x + w - pad
        val top = y + pad
        val bottom = y + h - pad
        val midY = (top + bottom) / 2f

        val p = Path()
        val half = t / 2f

        fun horizontal(cy: Float) {
            // Wide hexagon: mitred at both ends.
            p.moveTo(left + half, cy - half)
            p.lineTo(right - half, cy - half)
            p.lineTo(right, cy)
            p.lineTo(right - half, cy + half)
            p.lineTo(left + half, cy + half)
            p.lineTo(left, cy)
            p.close()
        }

        fun vertical(cx: Float, yTop: Float, yBottom: Float) {
            p.moveTo(cx - half, yTop + half)
            p.lineTo(cx, yTop)
            p.lineTo(cx + half, yTop + half)
            p.lineTo(cx + half, yBottom - half)
            p.lineTo(cx, yBottom)
            p.lineTo(cx - half, yBottom - half)
            p.close()
        }

        when (segment) {
            SevenSegment.A -> horizontal(top)
            SevenSegment.G -> horizontal(midY)
            SevenSegment.D -> horizontal(bottom)
            SevenSegment.F -> vertical(left, top, midY)
            SevenSegment.B -> vertical(right, top, midY)
            SevenSegment.E -> vertical(left, midY, bottom)
            SevenSegment.C -> vertical(right, midY, bottom)
            else -> return null
        }

        // Forward lean, sheared about the bottom of the glyph.
        if (SLANT != 0f) {
            val m = android.graphics.Matrix()
            m.setSkew(-SLANT, 0f, 0f, bottom)
            p.transform(m)
        }
        return p
    }
}
