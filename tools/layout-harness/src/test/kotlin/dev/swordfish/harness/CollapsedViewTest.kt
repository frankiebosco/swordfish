package dev.swordfish.harness

import android.graphics.Rect
import dev.swordfish.physics.PanelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The panel MUST follow the stable area when the driver collapses the view.
 *
 * ## The mistake this file exists to prevent
 *
 * v64 shipped a "latch" that kept the largest stable area ever seen and laid
 * out against that, on the theory that the head unit shrank the stable rect
 * transiently while its action strip was open.
 *
 * **That theory was wrong**, and the fix broke collapsed mode: tapping the
 * tile changed nothing, because the smaller rect was being ignored on
 * purpose. The full-size panel carried on drawing behind the host's
 * split-view chrome.
 *
 * What the head unit actually reports (SwordfishGeom, 2026-08-24):
 *
 * | Event | stableArea | visibleArea |
 * |---|---|---|
 * | Settled, full width | 752x300 | 752x300 |
 * | **Collapsed (real)** | **442x342** | **442x342** |
 * | Action strip open | 752x300 | 752x**364** |
 *
 * The stable rect NEVER shrinks for the action strip -- the strip changes the
 * VISIBLE rect, and GROWS it. So a smaller stable area always means a genuine
 * layout change and must always be honoured.
 *
 * If someone reintroduces a latch, these fail.
 */
class CollapsedViewTest {

    /** Settled, full width. */
    private val full = Rect(24, 88, 776, 388)

    /** The real collapsed surface -- a state the driver chooses. */
    private val collapsed = Rect(22, 34, 464, 376)

    private fun renderAt(areas: List<Rect>): List<java.awt.image.BufferedImage> {
        val geom = Geometry.ND2
        val renderer = dev.swordfish.car.GaugeRenderer(
            androidx.car.app.CarContext(isDarkMode = true)
        )
        val container = androidx.car.app.SurfaceContainer(geom.width, geom.height)
        return areas.map { area ->
            val img = java.awt.image.BufferedImage(
                geom.width, geom.height, java.awt.image.BufferedImage.TYPE_INT_ARGB
            )
            val canvas = android.graphics.Canvas(img)
            renderer.onStableAreaChanged(area)
            renderer.update(demoState())
            renderer.draw(canvas, container)
            canvas.dispose()
            img
        }
    }

    private fun diff(
        a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage
    ): Int {
        var n = 0
        for (y in 0 until a.height step 2) {
            for (x in 0 until a.width step 2) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++
            }
        }
        return n
    }

    @Test
    fun `collapsing the view actually changes the panel`() {
        // THE REGRESSION. With the v64 latch this diff was zero: the panel
        // ignored the collapse and kept drawing full-size behind the host's
        // split-view chrome.
        LayoutOverride.clear()
        val frames = renderAt(listOf(full, collapsed))
        val d = diff(frames[0], frames[1])
        assertTrue(
            d > 500,
            "collapsing the view changed only $d sampled pixels. The renderer " +
                "must follow the stable area: a smaller one is always a real " +
                "layout change, never a transient to be ignored."
        )
    }

    @Test
    fun `expanding back restores the full-width panel exactly`() {
        // Round trip: collapse then expand must return to the frame we
        // started from, or the panel drifts with every toggle.
        LayoutOverride.clear()
        val frames = renderAt(listOf(full, collapsed, full))
        assertEquals(
            0, diff(frames[0], frames[2]),
            "expanding back must restore the original layout exactly"
        )
    }

    @Test
    fun `repeated toggling is stable`() {
        // The driver will do this more than once. Every full-width frame
        // must be identical and every collapsed frame must be identical.
        LayoutOverride.clear()
        val frames = renderAt(listOf(full, collapsed, full, collapsed, full))
        assertEquals(0, diff(frames[0], frames[2]), "full-width frames differ")
        assertEquals(0, diff(frames[0], frames[4]), "full-width frames differ")
        assertEquals(0, diff(frames[1], frames[3]), "collapsed frames differ")
    }

    @Test
    fun `the two surfaces sit on opposite sides of the breakpoint`() {
        assertEquals(
            PanelLayout.Mode.WIDE,
            PanelLayout.choose(full.width(), full.height()).mode
        )
        assertEquals(
            PanelLayout.Mode.NARROW,
            PanelLayout.choose(collapsed.width(), collapsed.height()).mode,
            "the collapsed surface is aspect " +
                "${"%.2f".format(collapsed.width().toDouble() / collapsed.height())}, " +
                "below the 1.5 threshold -- so collapsing IS a genuine mode change"
        )
    }

    @Test
    fun `the action strip grows the visible rect and leaves stable alone`() {
        // Documents the distinction the v64 fix got backwards, so the next
        // person reading this does not repeat it.
        val stripStable = Rect(24, 88, 776, 388)
        val stripVisible = Rect(24, 24, 776, 388)

        assertEquals(
            full.height(), stripStable.height(),
            "the action strip must not be expected to alter the stable rect"
        )
        assertTrue(
            stripVisible.height() > stripStable.height(),
            "the strip GROWS the visible area; it shrinks nothing"
        )
    }
}
