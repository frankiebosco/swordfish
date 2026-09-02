package android.graphics

import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D

/** Only the five Path calls the renderer makes. See Primitives.kt. */
class Path {
    internal val awt = Path2D.Float()

    enum class Direction { CW, CCW }

    fun reset() { awt.reset() }
    fun moveTo(x: Float, y: Float) { awt.moveTo(x, y) }
    fun lineTo(x: Float, y: Float) { awt.lineTo(x, y) }
    fun close() { awt.closePath() }

    fun addCircle(cx: Float, cy: Float, r: Float, @Suppress("UNUSED_PARAMETER") dir: Direction) {
        awt.append(Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f), false)
    }

    /** Applies the matrix in place, as Android's Path.transform does. */
    fun transform(matrix: Matrix) {
        awt.transform(matrix.awt)
    }
}
