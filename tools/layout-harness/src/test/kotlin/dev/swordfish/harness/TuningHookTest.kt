package dev.swordfish.harness

import dev.swordfish.physics.PanelLayout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The tuner is only useful if dragging actually changes the picture.
 *
 * These pin the two halves of that: the hook must reach the renderer, and
 * it must be OFF by default so the app and the snapshot tool are never
 * affected by a tuner session in the same JVM.
 */
class TuningHookTest {

    @Test
    fun `the hook is null by default so the app is unaffected`() {
        assertEquals(null, PanelLayout.tuningHook)
        assertEquals(
            PanelLayout.DV_TOP_WIDE,
            PanelLayout.tuned("DV_TOP_WIDE", PanelLayout.DV_TOP_WIDE)
        )
    }

    @Test
    fun `dragging a knob changes the rendered pixels`() {
        val case = Case("t", Geometry.ND2, demoState())
        val before = render(case)

        LayoutOverride.clear()
        LayoutOverride.values["DV_TOP_WIDE"] = 0.20f
        val after = renderTuned(case)
        LayoutOverride.clear()

        var diff = 0
        for (y in 0 until before.height step 3) {
            for (x in 0 until before.width step 3) {
                if (before.getRGB(x, y) != after.getRGB(x, y)) diff++
            }
        }
        assertTrue(
            diff > 200,
            "moving DV_TOP_WIDE from ${PanelLayout.DV_TOP_WIDE} to 0.20 changed " +
                "only $diff sampled pixels -- the tuning hook is not reaching " +
                "the renderer, so the tuner would show a frozen picture."
        )
    }

    @Test
    fun `the hook is cleared after a tuned render`() {
        LayoutOverride.values["DV_TOP_WIDE"] = 0.20f
        renderTuned(Case("t", Geometry.ND2, demoState()))
        LayoutOverride.clear()
        assertEquals(
            null, PanelLayout.tuningHook,
            "renderTuned must not leave the hook installed: a later snapshot " +
                "run in the same JVM would silently use tuner values."
        )
    }
}
