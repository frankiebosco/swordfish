package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reserved strip that keeps transient status text off the instrument.
 *
 * Two collisions on the real head unit motivated this. The link banner
 * (HANDSHAKE / NO ADAPTER) was drawn at a fixed offset from the panel top
 * and landed on the `Isp` label; DRIVE TO ORIENT was positioned off the
 * navball radius and computed to a baseline of **1.2px** — clipped by the
 * top edge and overlapping the attitude readout beneath it.
 *
 * Both came from the same mistake: positioning text by ad-hoc multipliers
 * off whatever it happened to sit near, with no reserved space. These tests
 * pin the arithmetic so a future tweak cannot quietly reintroduce an
 * overlap that is only visible on hardware.
 */
class StatusStripTest {

    /** The real head unit, split view. */
    private val panelH = 480

    @Test
    fun `the status baseline clears the top edge`() {
        // The DRIVE TO ORIENT bug: a baseline of 1.2px put the ascenders
        // off the surface entirely.
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            val baseline = panelH * PanelLayout.statusBaselineFraction(mode)
            val textSize = panelH * PanelLayout.statusTextFraction(mode)
            val ascenderTop = baseline - textSize * 0.75f
            assertTrue(
                ascenderTop > 0f,
                "$mode: text starts at ${ascenderTop}px, above the top edge"
            )
        }
    }

    @Test
    fun `status text fits inside its reserved strip`() {
        // If the text overflows the strip, insetting the content by the
        // strip height does not actually prevent a collision.
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            val strip = panelH * PanelLayout.statusStripFraction(mode)
            val baseline = panelH * PanelLayout.statusBaselineFraction(mode)
            val textSize = panelH * PanelLayout.statusTextFraction(mode)
            val descenderBottom = baseline + textSize * 0.25f
            assertTrue(
                descenderBottom <= strip,
                "$mode: text reaches ${descenderBottom}px, strip is only ${strip}px"
            )
        }
    }

    @Test
    fun `the fault hint also fits inside the strip`() {
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            val strip = panelH * PanelLayout.statusStripFraction(mode)
            val hintBaseline = panelH * PanelLayout.statusHintBaselineFraction(mode)
            val hintSize = panelH * PanelLayout.statusTextFraction(mode) * 0.62f
            assertTrue(
                hintBaseline + hintSize * 0.25f <= strip,
                "$mode: hint overflows the strip"
            )
        }
    }

    @Test
    fun `the hint sits below the label without overlapping it`() {
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            val label = panelH * PanelLayout.statusBaselineFraction(mode)
            val hint = panelH * PanelLayout.statusHintBaselineFraction(mode)
            val labelSize = panelH * PanelLayout.statusTextFraction(mode)
            assertTrue(
                hint - label >= labelSize * 0.5f,
                "$mode: hint at $hint crowds the label at $label"
            )
        }
    }

    @Test
    fun `the strip is a modest share of the panel`() {
        // It is chrome. Spending a quarter of a 480px surface announcing a
        // transient state would be worse than the collision it fixes.
        for (mode in listOf(PanelLayout.Mode.WIDE, PanelLayout.Mode.NARROW)) {
            val f = PanelLayout.statusStripFraction(mode)
            assertTrue(f in 0.05f..0.12f, "$mode strip fraction $f is out of range")
        }
    }

    @Test
    fun `MINIMAL suppresses the strip entirely`() {
        // Too small to spend any height on chrome; the numbers win.
        assertEquals(0.0f, PanelLayout.statusStripFraction(PanelLayout.Mode.MINIMAL))
        assertFalse(PanelLayout.showsStatusStrip(PanelLayout.Mode.MINIMAL, 480))
    }

    @Test
    fun `a very short panel drops the strip rather than shrinking the numbers`() {
        assertFalse(PanelLayout.showsStatusStrip(PanelLayout.Mode.WIDE, 150))
        assertTrue(PanelLayout.showsStatusStrip(PanelLayout.Mode.WIDE, 480))
    }

    @Test
    fun `insetting by the strip leaves the instrument the rest`() {
        // The content rect must be exactly what is left, with nothing lost
        // to rounding.
        val strip = (panelH * PanelLayout.statusStripFraction(PanelLayout.Mode.WIDE)).toInt()
        val content = panelH - strip
        assertTrue(content > panelH * 0.85, "strip ate ${panelH - content}px of $panelH")
    }

    @Test
    fun `status text is smaller than the headline it must not compete with`() {
        // The panel.s one hard rule: delta-V must be readable in half a
        // second. A status line rivalling it in size would break that.
        val status = PanelLayout.statusTextFraction(PanelLayout.Mode.WIDE)
        val headline = PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE)
        assertTrue(
            status < headline * 0.35f,
            "status text $status is too close to the headline $headline"
        )
    }
}
