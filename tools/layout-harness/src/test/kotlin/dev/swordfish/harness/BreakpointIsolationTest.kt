package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WIDE and NARROW must never share a tuned value.
 *
 * Reported from the head unit on 2026-08-24: a `SCOPE_SCALE` of 1.59,
 * dialled in against the 800x400 full panel, was also being applied to the
 * COLLAPSED split-screen surface -- which has a fraction of the width and
 * none of the slack. The elements were visibly wrong in split view while
 * looking correct at full width.
 *
 * The cause was that `drawCentre` serves BOTH breakpoints, so one set of
 * knobs reached both. Every knob now has a `_N` twin and the call sites go
 * through `PanelLayout.tunedFor(mode, ...)`.
 *
 * These tests pin the isolation in both directions, because a leak either
 * way is the same class of bug.
 */
class BreakpointIsolationTest {

    private val wideKnobs = listOf(
        "NAVBALL_SCALE", "NAVBALL_DX", "NAVBALL_DY",
        "SCOPE_SCALE", "SCOPE_DX", "SCOPE_DY",
        "STATS_SCALE", "STATS_DX", "STATS_DY",
        "DELTAV_SCALE", "DELTAV_DX", "DELTAV_DY",
        "ISP_SCALE", "ISP_DX", "ISP_DY",
        "SPEED_SCALE", "SPEED_DX", "SPEED_DY",
        "READOUTS_SCALE", "READOUTS_DX", "READOUTS_DY"
    )

    /** Count differing pixels between two renders. */
    private fun diff(
        a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage
    ): Int {
        var n = 0
        for (y in 0 until minOf(a.height, b.height) step 2) {
            for (x in 0 until minOf(a.width, b.width) step 2) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++
            }
        }
        return n
    }

    @Test
    fun `WIDE knobs do not affect the collapsed layout`() {
        for (radar in listOf(false, true)) {
            val case = Case(
                "narrow", Geometry.NARROW, demoState(),
                centre = if (radar) {
                    dev.swordfish.physics.RadarLayout.CentreContent.RADAR
                } else {
                    dev.swordfish.physics.RadarLayout.CentreContent.INSTRUMENTS
                }
            )
            LayoutOverride.clear()
            val before = render(case)

            // Slam every WIDE knob to something extreme. None of it should
            // reach a NARROW surface.
            for (k in wideKnobs) {
                LayoutOverride.values[k] = if (k.endsWith("_SCALE")) 2.2f else 0.15f
            }
            val after = renderTuned(case)
            LayoutOverride.clear()

            assertEquals(
                0, diff(before, after),
                "WIDE knobs changed the COLLAPSED layout (radar=$radar). " +
                    "drawCentre serves both breakpoints, so every knob it " +
                    "reads must go through tunedFor(layout.mode, ...)."
            )
        }
    }

    @Test
    fun `NARROW knobs do not affect the full-width layout`() {
        for (radar in listOf(false, true)) {
            val case = Case(
                "wide", Geometry.ND2, demoState(),
                centre = if (radar) {
                    dev.swordfish.physics.RadarLayout.CentreContent.RADAR
                } else {
                    dev.swordfish.physics.RadarLayout.CentreContent.INSTRUMENTS
                }
            )
            LayoutOverride.clear()
            val before = render(case)

            for (k in wideKnobs) {
                LayoutOverride.values[k + "_N"] =
                    if (k.endsWith("_SCALE")) 2.2f else 0.15f
            }
            val after = renderTuned(case)
            LayoutOverride.clear()

            assertEquals(
                0, diff(before, after),
                "NARROW knobs changed the FULL-WIDTH layout (radar=$radar)"
            )
        }
    }

    @Test
    fun `the collapsed layout ships untouched`() {
        // The reported symptom was the collapsed view being DISTURBED by
        // wide tuning. Its own knobs are all still at "no change", so it
        // renders exactly as it did before any of this existed.
        val defaults = mapOf(
            "NAVBALL_SCALE_N" to PanelLayout.NAVBALL_SCALE_N,
            "SCOPE_SCALE_N" to PanelLayout.SCOPE_SCALE_N,
            "STATS_SCALE_N" to PanelLayout.STATS_SCALE_N,
            "DELTAV_SCALE_N" to PanelLayout.DELTAV_SCALE_N,
            "ISP_SCALE_N" to PanelLayout.ISP_SCALE_N,
            "SPEED_SCALE_N" to PanelLayout.SPEED_SCALE_N,
            "READOUTS_SCALE_N" to PanelLayout.READOUTS_SCALE_N
        )
        for ((name, v) in defaults) {
            assertEquals(
                1.0f, v,
                "$name should still be 1.0 -- the collapsed layout was " +
                    "reverted to its pre-tuning appearance and has not been " +
                    "dialled in yet"
            )
        }
        val offsets = mapOf(
            "NAVBALL_DX_N" to PanelLayout.NAVBALL_DX_N,
            "NAVBALL_DY_N" to PanelLayout.NAVBALL_DY_N,
            "SCOPE_DX_N" to PanelLayout.SCOPE_DX_N,
            "SCOPE_DY_N" to PanelLayout.SCOPE_DY_N,
            "STATS_DX_N" to PanelLayout.STATS_DX_N,
            "STATS_DY_N" to PanelLayout.STATS_DY_N
        )
        for ((name, v) in offsets) {
            assertEquals(0.0f, v, "$name should still be 0.0")
        }
    }

    @Test
    fun `tunedFor picks the right side`() {
        LayoutOverride.clear()
        LayoutOverride.values["X"] = 5f
        LayoutOverride.values["X_N"] = 9f
        PanelLayout.tuningHook = { n, f -> LayoutOverride.of(n, f) }
        try {
            assertEquals(5f, PanelLayout.tunedFor(PanelLayout.Mode.WIDE, "X", 1f, 2f))
            assertEquals(9f, PanelLayout.tunedFor(PanelLayout.Mode.NARROW, "X", 1f, 2f))
            // MINIMAL deliberately shares NARROW's values.
            assertEquals(9f, PanelLayout.tunedFor(PanelLayout.Mode.MINIMAL, "X", 1f, 2f))
        } finally {
            PanelLayout.tuningHook = null
            LayoutOverride.clear()
        }
        // With no hook, the constants come through unchanged.
        assertTrue(PanelLayout.tunedFor(PanelLayout.Mode.WIDE, "X", 1f, 2f) == 1f)
        assertTrue(PanelLayout.tunedFor(PanelLayout.Mode.NARROW, "X", 1f, 2f) == 2f)
    }
}
