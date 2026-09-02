package android.graphics

import java.awt.image.BufferedImage

/**
 * Wraps a BufferedImage so radar imagery can be fed to the real renderer.
 *
 * `isRecycled` exists because `drawRadarImagery` checks it -- see the
 * bitmap-race note in the project docs. Nothing in the harness ever recycles.
 */
class Bitmap(internal val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    var isRecycled: Boolean = false
        private set

    fun recycle() { isRecycled = true }
}
