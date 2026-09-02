package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The instrument boxes must sit ON the circles they claim to control.
 *
 * `Boxes.kt` reproduces the pair-placement arithmetic from `drawWide` in
 * order to know where to put a box. That duplication is the risk this file
 * exists to contain: if the renderer's own placement changes and the box
 * maths does not, the box drifts off its instrument and the tool silently
 * lies about what you are dragging.
 *
 * So rather than compare formula to formula, these find the drawn circle in
 * the RENDERED PIXELS and check the box lands on it.
 */
class BoxOverlayTest {

    private fun navBox() = buildBoxes().first { it.label == "NAVBALL" }
    private fun scopeBox() = buildBoxes().first { it.label == "SCOPE" }

    /**
     * Scan a rendered panel for the horizontal extent of lit pixels in a
     * band of rows around cy, within a column window. Returns null if the
     * band is empty.
     */
    private fun litSpanX(
        img: java.awt.image.BufferedImage, cy: Int, x0: Int, x1: Int
    ): Pair<Int, Int>? {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (dy in -2..2) {
            val y = cy + dy
            if (y < 0 || y >= img.height) continue
            for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(img.width)) {
                val c = img.getRGB(x, y)
                val g = (c shr 8) and 0xFF
                val r = (c shr 16) and 0xFF
                val b = c and 0xFF
                // The phosphor palette: anything clearly brighter than the
                // near-black background counts as instrument ink.
                if (g > 90 && (g > r + 20 || g > b + 20)) {
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
        }
        return if (lo <= hi) lo to hi else null
    }

    @Test
    fun `the navball box lands on the drawn navball`() {
        LayoutOverride.clear()
        val geom = Geometry.ND2
        val img = render(Case("t", geom, demoState()))

        val box = navBox()
        val circle = box.circle(geom, false)
        assertTrue(circle != null, "the navball box must exist at WIDE")
        val (cx, cy, r) = circle!!

        // Measure ABOVE the centre row on purpose.
        //
        // At exactly cy the navball's horizon line runs the full width of the
        // ball AND the centre column's delta-V segments sit on the same rows,
        // so a naive span reaches x=339 and reports a centre of 191. Sampling
        // a few rows higher catches the ball's rim alone. This is the kind of
        // thing that makes pixel assertions fragile, so keep the window tight
        // and bounded to the navball's own column.
        val span = litSpanX(img, (cy - 4f).toInt(), 0, (geom.width * 0.36f).toInt())
        assertTrue(span != null, "found no navball pixels at y=${cy.toInt()}")
        val (lo, hi) = span!!
        val drawnCx = (lo + hi) / 2f
        val drawnR = (hi - lo) / 2f

        assertTrue(
            kotlin.math.abs(drawnCx - cx) <= 14f,
            "box centre x=${"%.1f".format(cx)} but the navball is drawn at " +
                "${"%.1f".format(drawnCx)} -- the box maths in Boxes.kt has " +
                "drifted from drawWide."
        )
        assertTrue(
            kotlin.math.abs(drawnR - r) <= 14f,
            "box radius ${"%.1f".format(r)} but the navball is drawn at " +
                "${"%.1f".format(drawnR)}"
        )
    }

    @Test
    fun `the scope box only exists in radar mode`() {
        LayoutOverride.clear()
        assertEquals(
            null, scopeBox().circle(Geometry.ND2, false),
            "there is no scope to box when the centre column shows instruments"
        )
        assertTrue(
            scopeBox().circle(Geometry.ND2, true) != null,
            "the scope box must appear in radar mode"
        )
    }

    @Test
    fun `resizing the navball changes only the navball`() {
        LayoutOverride.clear()
        val geom = Geometry.ND2
        val case = Case("t", geom, demoState())
        val before = render(case)

        val box = navBox()
        box.scale = 0.6f
        box.push(false)
        val after = renderTuned(case)
        LayoutOverride.clear()
        box.reset()

        // The stats column on the right must be untouched: the whole point
        // of a per-instrument knob is that it does not move its neighbours.
        var rightDiff = 0
        for (y in 0 until before.height step 2) {
            for (x in (geom.width * 0.75f).toInt() until before.width step 2) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) rightDiff++
            }
        }
        assertEquals(
            0, rightDiff,
            "scaling the navball changed $rightDiff pixels in the stats column; " +
                "the per-instrument scale must not disturb its neighbours"
        )

        // ...and the navball itself must actually have changed.
        var leftDiff = 0
        for (y in 0 until before.height step 2) {
            for (x in 0 until (geom.width * 0.35f).toInt() step 2) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) leftDiff++
            }
        }
        assertTrue(
            leftDiff > 100,
            "scaling the navball to 0.6 changed only $leftDiff pixels; the " +
                "NAVBALL_SCALE knob is not reaching the renderer"
        )
    }

    @Test
    fun `every element has a box in the mode it belongs to`() {
        LayoutOverride.clear()
        val boxes = buildBoxes().associateBy { it.label }

        // Instruments mode.
        for (name in listOf("NAVBALL", "ISP", "DELTA-V", "SPEED", "STATS")) {
            assertTrue(
                boxes.getValue(name).bounds(Geometry.ND2, false) != null,
                "$name must have a box in instruments mode"
            )
        }
        // Radar mode.
        for (name in listOf("NAVBALL", "SCOPE", "READOUTS", "STATS")) {
            assertTrue(
                boxes.getValue(name).bounds(Geometry.ND2, true) != null,
                "$name must have a box in radar mode"
            )
        }
        // The centre-column text blocks are replaced by the scope.
        for (name in listOf("ISP", "DELTA-V", "SPEED")) {
            assertEquals(
                null, boxes.getValue(name).bounds(Geometry.ND2, true),
                "$name is not drawn in radar mode, so it must not offer a box"
            )
        }
    }

    @Test
    fun `there is no compass box`() {
        // Confirmed as wanted 2026-08-24: the compass is expressed in
        // navball radii inside drawNavball and scales with the ball as one
        // instrument. Giving it its own box would let the two separate,
        // which is not what a compass ring under a ball should ever do.
        assertTrue(
            buildBoxes().none { it.label.contains("COMPASS", ignoreCase = true) },
            "the compass must stay welded to the navball"
        )
    }

    @Test
    fun `each element knob moves only its own element`() {
        val geom = Geometry.ND2
        val case = Case("t", geom, demoState())

        // STATS lives on the right; moving it must not disturb the navball
        // on the left. This is the property that makes per-element knobs
        // worth having at all.
        LayoutOverride.clear()
        val before = render(case)
        LayoutOverride.values["STATS_DY"] = 0.05f
        val after = renderTuned(case)
        LayoutOverride.clear()

        var leftDiff = 0
        for (y in 0 until before.height step 2) {
            for (x in 0 until (geom.width * 0.30f).toInt() step 2) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) leftDiff++
            }
        }
        assertEquals(
            0, leftDiff,
            "moving the stats column changed $leftDiff pixels over the navball"
        )
    }

    @Test
    fun `the tuned values stay within sane bounds`() {
        // These were dialled in by hand in the layout tuner on 2026-08-24,
        // so they are NOT expected to be 1.0/0.0 any more. What is still
        // worth pinning is that nobody fat-fingers one into absurdity: a
        // scale of 0 makes an element vanish, and an offset past half the
        // panel puts it off-screen with no error anywhere.
        val scales = mapOf(
            "NAVBALL_SCALE" to PanelLayout.NAVBALL_SCALE,
            "SCOPE_SCALE" to PanelLayout.SCOPE_SCALE,
            "STATS_SCALE" to PanelLayout.STATS_SCALE,
            "DELTAV_SCALE" to PanelLayout.DELTAV_SCALE,
            "ISP_SCALE" to PanelLayout.ISP_SCALE,
            "SPEED_SCALE" to PanelLayout.SPEED_SCALE,
            "READOUTS_SCALE" to PanelLayout.READOUTS_SCALE
        )
        for ((name, v) in scales) {
            assertTrue(
                v in 0.2f..2.5f,
                "$name = $v is outside the sane range 0.2..2.5"
            )
        }

        val offsets = mapOf(
            "NAVBALL_DX" to PanelLayout.NAVBALL_DX,
            "NAVBALL_DY" to PanelLayout.NAVBALL_DY,
            "SCOPE_DX" to PanelLayout.SCOPE_DX,
            "SCOPE_DY" to PanelLayout.SCOPE_DY,
            "STATS_DX" to PanelLayout.STATS_DX,
            "STATS_DY" to PanelLayout.STATS_DY,
            "DELTAV_DX" to PanelLayout.DELTAV_DX,
            "DELTAV_DY" to PanelLayout.DELTAV_DY,
            "ISP_DX" to PanelLayout.ISP_DX,
            "ISP_DY" to PanelLayout.ISP_DY,
            "SPEED_DX" to PanelLayout.SPEED_DX,
            "SPEED_DY" to PanelLayout.SPEED_DY,
            "READOUTS_DX" to PanelLayout.READOUTS_DX,
            "READOUTS_DY" to PanelLayout.READOUTS_DY
        )
        for ((name, v) in offsets) {
            assertTrue(
                kotlin.math.abs(v) <= 0.35f,
                "$name = $v moves the element more than a third of the panel"
            )
        }
    }

    @Test
    fun `every tuned element still lands inside the panel`() {
        // A scale-up plus an offset can walk an element off the surface,
        // and nothing in the render path would complain -- it would simply
        // be clipped away, which is how the radar START line was lost.
        LayoutOverride.clear()
        for (radar in listOf(false, true)) {
            for (box in buildBoxes()) {
                val b = box.bounds(Geometry.ND2, radar) ?: continue
                assertTrue(
                    b.cx - b.halfW >= -8f && b.cx + b.halfW <= Geometry.ND2.width + 8f,
                    "${box.label} (radar=$radar) runs off the panel horizontally: " +
                        "${"%.1f".format(b.cx - b.halfW)}..${"%.1f".format(b.cx + b.halfW)}"
                )
                assertTrue(
                    b.cy - b.halfH >= -8f && b.cy + b.halfH <= Geometry.ND2.height + 8f,
                    "${box.label} (radar=$radar) runs off the panel vertically: " +
                        "${"%.1f".format(b.cy - b.halfH)}..${"%.1f".format(b.cy + b.halfH)}"
                )
            }
        }
    }
}
