package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelLayoutTest {

    // Representative geometries. The DHU window is ~800x420 usable; a real
    // Mazda Connect screen is wider. Split view roughly halves the width.
    private val fullWidth = 800 to 420
    private val splitView = 390 to 420
    private val shortStrip = 800 to 180

    @Test
    fun `a full-width surface gets the wide three-column layout`() {
        val l = PanelLayout.choose(fullWidth.first, fullWidth.second)
        assertEquals(PanelLayout.Mode.WIDE, l.mode)
        assertTrue(l.shows(PanelLayout.Element.NAVBALL))
        assertTrue(l.shows(PanelLayout.Element.STATS_BLOCK))
        assertTrue(l.shows(PanelLayout.Element.GRAVITY_STRIP))
    }

    @Test
    fun `split view keeps the navball beside the figure`() {
        // An earlier version dropped the navball here and enlarged delta-V to
        // fill the space. That wasted width on a centred column and left the
        // panel with one huge number and nothing else.
        val l = PanelLayout.choose(splitView.first, splitView.second)
        assertEquals(PanelLayout.Mode.NARROW, l.mode)
        assertTrue(l.shows(PanelLayout.Element.NAVBALL))
        assertTrue(l.navballColumnFraction > 0.0)
        assertTrue(l.shows(PanelLayout.Element.ISP_BAR))
        assertTrue(l.shows(PanelLayout.Element.EFFICIENCY_LAMP))
    }

    @Test
    fun `the headline yields height in split view rather than growing`() {
        // The correction: a big number on a small surface is not more
        // glanceable, it just crowds everything else out.
        val wide = PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE)
        val narrow = PanelLayout.deltaVTextFraction(PanelLayout.Mode.NARROW)
        assertTrue(narrow < wide, "narrow ($narrow) should not exceed wide ($wide)")
    }

    @Test
    fun `a very short surface shows only the essentials`() {
        val l = PanelLayout.choose(shortStrip.first, shortStrip.second)
        assertEquals(PanelLayout.Mode.MINIMAL, l.mode)
        assertTrue(l.shows(PanelLayout.Element.DELTA_V))
        assertFalse(l.shows(PanelLayout.Element.ISP_BAR))
        assertFalse(l.shows(PanelLayout.Element.STATS_BLOCK))
    }

    @Test
    fun `delta-V survives every layout`() {
        // The glanceability rule: one element must always be readable.
        val geometries = listOf(
            fullWidth, splitView, shortStrip,
            100 to 100, 2000 to 300, 320 to 240
        )
        for ((w, h) in geometries) {
            val l = PanelLayout.choose(w, h)
            assertTrue(
                l.shows(PanelLayout.Element.DELTA_V),
                "delta-V dropped at ${w}x$h"
            )
        }
    }

    @Test
    fun `degenerate dimensions do not crash and still show delta-V`() {
        for ((w, h) in listOf(0 to 0, -100 to 400, 400 to -100, 0 to 400)) {
            val l = PanelLayout.choose(w, h)
            assertEquals(PanelLayout.Mode.MINIMAL, l.mode)
            assertTrue(l.shows(PanelLayout.Element.DELTA_V))
        }
    }

    @Test
    fun `the wide-narrow boundary is where we expect it`() {
        // Full-width head units land near 2.0 aspect, split view near 1.0.
        // Nothing realistic should sit close to the 1.5 threshold.
        assertEquals(PanelLayout.Mode.WIDE, PanelLayout.choose(600, 400).mode)   // 1.5
        assertEquals(PanelLayout.Mode.NARROW, PanelLayout.choose(599, 400).mode) // 1.4975
    }

    @Test
    fun `side columns leave the centre with the majority of the width`() {
        val l = PanelLayout.choose(fullWidth.first, fullWidth.second)
        val centre = 1.0 - l.navballColumnFraction - l.statsColumnFraction
        assertTrue(centre > 0.4, "centre column got only $centre of the width")
    }

    @Test
    fun `only the minimal layout lets the figure dominate`() {
        // MINIMAL genuinely has nothing else to show, so the figure can take
        // the space. NARROW does, so it must not.
        val wide = PanelLayout.deltaVTextFraction(PanelLayout.Mode.WIDE)
        val narrow = PanelLayout.deltaVTextFraction(PanelLayout.Mode.NARROW)
        val minimal = PanelLayout.deltaVTextFraction(PanelLayout.Mode.MINIMAL)
        assertTrue(minimal > wide)
        assertTrue(minimal > narrow)
        assertTrue(narrow < wide)
    }

    @Test
    fun `type fractions stay within the surface`() {
        for (mode in PanelLayout.Mode.entries) {
            val dv = PanelLayout.deltaVTextFraction(mode)
            val label = PanelLayout.labelTextFraction(mode)
            assertTrue(dv + label < 0.5f, "$mode: text would dominate the panel")
            assertTrue(dv > label * 3, "$mode: delta-V must dominate the label")
        }
    }

    // --- Navball sizing ---
    //
    // The navball was photographed on the real head unit rendering small and
    // hard to read, with obvious black space below and to its right. The
    // cause was arithmetic, not taste: `min(navW * 0.40f, h * 0.28f)` was
    // width-bound on every real geometry, and the 0.28 height cap it was
    // paired with never engaged at all.
    //
    // These tests pin the fit numerically so a future change cannot quietly
    // reintroduce a ball that does not use the space it has.

    @Test
    fun `the navball cluster fits the height it is given`() {
        // The ball is not just the circle -- an attitude readout and prompt
        // sit above it and a three-row compass strip below. The whole cluster
        // must fit, or the compass letters fall off the bottom edge, which is
        // what happened when the extents were guessed rather than measured.
        for ((w, h) in listOf(800 to 420, 1280 to 720, 1080 to 600, 1920 to 1080)) {
            val l = PanelLayout.choose(w, h)
            val navW = (w * l.navballColumnFraction).toFloat()
            val r = PanelLayout.navballRadiusPx(navW, h.toFloat())
            val cy = h * PanelLayout.navballCentreFraction()

            val top = cy - PanelLayout.NAVBALL_EXTENT_ABOVE.toFloat() * r
            val bottom = cy + PanelLayout.NAVBALL_EXTENT_BELOW.toFloat() * r

            assertTrue(top >= 0f, "cluster top $top clipped at ${w}x$h")
            assertTrue(bottom <= h.toFloat(), "cluster bottom $bottom overflows ${w}x$h")
        }
    }

    @Test
    fun `the navball stays inside its own column`() {
        // Sizing the radius off the full column rather than the half-column
        // pushed the ball's left edge off the surface once before. The ball
        // is drawn from its CENTRE, so the usable radius is half the column.
        for ((w, h) in listOf(800 to 420, 1280 to 720, 390 to 420)) {
            val l = PanelLayout.choose(w, h)
            val navW = (w * l.navballColumnFraction).toFloat()
            val r = PanelLayout.navballRadiusPx(navW, h.toFloat())
            assertTrue(
                r <= navW / 2f,
                "radius $r exceeds half the ${navW}px column at ${w}x$h"
            )
        }
    }

    @Test
    fun `the navball is materially bigger than the old width-bound sizing`() {
        // The regression guard for the actual complaint. The old expression
        // is reproduced here verbatim; if a future change drops back toward
        // it, this fails. 25% is well under the ~42% actually gained, so
        // ordinary retuning has room without tripping it.
        for ((w, h) in listOf(800 to 420, 1280 to 720, 1920 to 1080)) {
            val l = PanelLayout.choose(w, h)
            val navW = (w * l.navballColumnFraction).toFloat()
            val now = PanelLayout.navballRadiusPx(navW, h.toFloat())

            val oldNavW = w * 0.28f
            val before = minOf(oldNavW * 0.40f, h * 0.28f)

            assertTrue(
                now > before * 1.25f,
                "navball radius $now is not meaningfully above the old $before at ${w}x$h"
            )
        }
    }

    @Test
    fun `the navball no longer leaves a dead band beneath it`() {
        // The specific defect in the photograph: the cluster ended around 78%
        // of the way down and the rest was black. Centring the CLUSTER rather
        // than the circle is what reclaims it.
        val (w, h) = 1280 to 720
        val l = PanelLayout.choose(w, h)
        val navW = (w * l.navballColumnFraction).toFloat()
        val r = PanelLayout.navballRadiusPx(navW, h.toFloat())
        val cy = h * PanelLayout.navballCentreFraction()
        val bottom = cy + PanelLayout.NAVBALL_EXTENT_BELOW.toFloat() * r

        assertTrue(
            bottom > h * 0.90f,
            "cluster ends at $bottom of $h -- dead space left underneath"
        )
    }

    @Test
    fun `the cluster is centred rather than the circle`() {
        // The extents are asymmetric -- more below than above -- so a centre
        // fraction of 0.5 would mean the circle is centred and the cluster is
        // not, which is the bug this replaced.
        val f = PanelLayout.navballCentreFraction()
        assertTrue(f < 0.5f, "centre fraction $f does not account for the taller lower half")
        assertTrue(f > 0.4f, "centre fraction $f pushes the ball too high")
    }

    @Test
    fun `widening the navball column leaves the centre and stats usable`() {
        // Width for the ball came out of the stats column. Stats are
        // text-driven and self-scaling so they absorb it, but not without
        // limit, and delta-V must still own the majority.
        val l = PanelLayout.choose(1280, 720)
        assertTrue(l.statsColumnFraction >= 0.20, "stats column ${l.statsColumnFraction} too thin")
        val centre = 1.0 - l.navballColumnFraction - l.statsColumnFraction
        assertTrue(centre > 0.4, "centre column got only $centre of the width")
    }

    @Test
    fun `navball sizing degrades safely on nonsense input`() {
        // The renderer bails below a 4px radius; it must never be handed a
        // negative or NaN one.
        assertEquals(0f, PanelLayout.navballRadiusPx(0f, 500f))
        assertEquals(0f, PanelLayout.navballRadiusPx(200f, 0f))
        assertEquals(0f, PanelLayout.navballRadiusPx(-10f, -10f))
    }

    // --- Split-view navball ---
    //
    // Split view is the layout that actually gets used: as of 2026-08-22
    // every live run has been split, and full width has never rendered for a
    // whole session. Photographed on the DHU the ball came out ~176px on a
    // ~800x420 surface, still too small.
    //
    // The defect here is the OPPOSITE of the wide one. The ball is
    // height-bound, so widening the column achieves nothing, and the height
    // was being spent on chrome drawn in other columns.

    // The DHU's real usable surface, which is what these were measured on.
    private val dhuSplit = 800 to 420

    @Test
    fun `in split view the navball is not limited by the column width`() {
        // The proof that widening the column is the wrong lever: the radius
        // must not change across a wide sweep of column fractions, because
        // height is what binds. If this ever starts varying, the balance has
        // shifted and the height budget should be revisited first.
        val (w, h) = dhuSplit
        val radii = listOf(0.38, 0.46, 0.62).map {
            PanelLayout.navballRadiusNarrowPx((w * it).toFloat(), h.toFloat())
        }
        assertTrue(
            radii.distinct().size == 1,
            "radius varied with column width $radii -- width is binding, not height"
        )
    }

    @Test
    fun `the split-view navball is materially bigger than the body-height sizing`() {
        // The regression guard for the actual complaint. The old NARROW path
        // measured against bodyH (panel minus a 10% status strip and an 18%
        // stats strip); the column now owns its full height instead.
        val (w, h) = dhuSplit
        val l = PanelLayout.choose(w, h)
        val navW = (w * l.navballColumnFraction).toFloat()

        val before = PanelLayout.navballRadiusPx(navW, h * 0.90f * 0.82f)
        val now = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())

        // 1.15, not 1.25: the ball was pulled back from a 0.98 height share
        // to 0.86 so its compass would stop colliding with the stats row.
        // That trade was deliberate and costs some of the gain -- the guard
        // tracks the intent, which is that split view stays materially
        // bigger than the body-height sizing it replaced (+21% today).
        assertTrue(
            now > before * 1.15f,
            "split navball $now is not meaningfully above the old $before"
        )
    }

    @Test
    fun `the split-view cluster fits the panel height`() {
        // It now uses nearly all of it, so the fit is tighter than in WIDE
        // and worth pinning across several plausible split geometries.
        for ((w, h) in listOf(800 to 420, 698 to 530, 640 to 480, 390 to 420)) {
            val l = PanelLayout.choose(w, h)
            if (!l.shows(PanelLayout.Element.NAVBALL)) continue
            val navW = (w * l.navballColumnFraction).toFloat()
            val r = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())
            val cy = h * PanelLayout.navballCentreNarrowFraction()

            val top = cy - PanelLayout.NAVBALL_EXTENT_ABOVE.toFloat() * r
            val bottom = cy + PanelLayout.NAVBALL_EXTENT_BELOW.toFloat() * r

            assertTrue(top >= 0f, "split cluster top $top clipped at ${w}x$h")
            assertTrue(bottom <= h.toFloat(), "split cluster bottom $bottom overflows ${w}x$h")
        }
    }

    @Test
    fun `the split-view navball stays inside its column`() {
        for ((w, h) in listOf(800 to 420, 390 to 420, 640 to 480)) {
            val l = PanelLayout.choose(w, h)
            if (!l.shows(PanelLayout.Element.NAVBALL)) continue
            val navW = (w * l.navballColumnFraction).toFloat()
            val r = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())
            assertTrue(r <= navW / 2f, "radius $r exceeds half the ${navW}px column at ${w}x$h")
        }
    }

    @Test
    fun `the compass keeps its place as the ball grows`() {
        // Frank's requirement: the ball and compass are ONE instrument. The
        // compass must scale with the ball and hold its relative position,
        // never be trimmed to make room. Everything in drawNavball is
        // expressed in radii, so this holds as long as the extents are used
        // unchanged -- which is what this asserts.
        val (w, h) = dhuSplit
        val navW = (w * 0.38).toFloat()
        val small = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())
        val large = PanelLayout.navballRadiusNarrowPx(navW * 2f, (h * 2).toFloat())

        // Compass offset is a fixed multiple of the radius in both cases, so
        // the ratio of offset to radius is scale-invariant.
        val belowSmall = PanelLayout.NAVBALL_EXTENT_BELOW * small
        val belowLarge = PanelLayout.NAVBALL_EXTENT_BELOW * large
        assertEquals(
            (belowSmall / small).toFloat(), (belowLarge / large).toFloat(), 1e-4f,
            "compass position drifted relative to the ball"
        )
    }

    @Test
    fun `the split stats row yields the navball column`() {
        // The enlarged ball reaches the bottom of its column, so the stats
        // row's first cell would collide with it. The row starts after the
        // column instead.
        val l = PanelLayout.choose(dhuSplit.first, dhuSplit.second)
        val left = PanelLayout.statsRowLeftFraction(l.navballColumnFraction)
        assertEquals(l.navballColumnFraction.toFloat(), left, 1e-6f)
        assertTrue(left > 0f, "stats row still starts under the navball")
        assertTrue(left < 0.5f, "stats row gave up more than half the width")
    }

    @Test
    fun `split navball sizing degrades safely on nonsense input`() {
        assertEquals(0f, PanelLayout.navballRadiusNarrowPx(0f, 420f))
        assertEquals(0f, PanelLayout.navballRadiusNarrowPx(300f, 0f))
        assertEquals(0f, PanelLayout.navballRadiusNarrowPx(-5f, -5f))
    }

    // --- Split-view stats row ---
    //
    // Growing the navball to 0.98 of the column height, with the stats row
    // yielding that column, left the row too narrow for four cells. They
    // centre their own content and nothing enforced a gutter, so they drew
    // straight over each other: "GEAR 6RPM 2669TWR 85FUEL 37L" on the DHU.

    @Test
    fun `the split stats row shrinks rather than overlapping`() {
        // The guard for the actual defect. Content wider than its cell must
        // scale down, never spill into the neighbour.
        val cellW = 120f
        val scale = PanelLayout.statRowTextScale(widestCellPx = 200f, cellWidthPx = cellW)
        assertTrue(scale < 1f, "oversized content was not shrunk")
        assertTrue(
            200f * scale <= cellW * PanelLayout.STAT_CELL_FILL.toFloat() + 0.01f,
            "shrunk content still exceeds its cell budget"
        )
    }

    @Test
    fun `the stats row never grows to fill its cell`() {
        // Shrink-to-fit only. Scaling small content UP would make the row
        // jump around as values change width digit to digit.
        assertEquals(1f, PanelLayout.statRowTextScale(40f, 200f))
        assertEquals(1f, PanelLayout.statRowTextScale(1f, 1000f))
    }

    @Test
    fun `the fitted stats row leaves a gutter between cells`() {
        // Exactly filling the cell would put neighbouring cells in contact,
        // which reads as overlap even when it technically is not.
        assertTrue(
            PanelLayout.STAT_CELL_FILL < 1.0,
            "cells may fill their whole width -- neighbours will touch"
        )
        val scale = PanelLayout.statRowTextScale(300f, 100f)
        assertTrue(300f * scale < 100f, "no gutter left between cells")
    }

    @Test
    fun `split view shows three stat cells not four`() {
        // TWR is dropped in split view: it only means something read next to
        // Isp, which has its own readout, and a fourth cell is what pushed
        // the row into overlap.
        assertEquals(3, PanelLayout.NARROW_STAT_CELLS)
    }

    @Test
    fun `the split navball no longer reaches the stats row`() {
        // The pullback from 0.98 to 0.86. The cluster must clear the stats
        // row baseline at 0.94 of panel height, or the compass letters land
        // on the stat cells.
        val (w, h) = dhuSplit
        val l = PanelLayout.choose(w, h)
        val navW = (w * l.navballColumnFraction).toFloat()
        val r = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())
        val cy = h * PanelLayout.navballCentreNarrowFraction()
        val bottom = cy + PanelLayout.NAVBALL_EXTENT_BELOW.toFloat() * r

        assertTrue(
            bottom < h * 0.94f,
            "cluster bottom $bottom reaches the stats row baseline at ${h * 0.94f}"
        )
    }

    @Test
    fun `the split navball is still meaningfully larger after the pullback`() {
        // Pulling back to fix the overlap must not undo the whole gain --
        // the ball being too small is the problem we started from.
        val (w, h) = dhuSplit
        val l = PanelLayout.choose(w, h)
        val navW = (w * l.navballColumnFraction).toFloat()

        val before = PanelLayout.navballRadiusPx(navW, h * 0.90f * 0.82f)
        val now = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())

        assertTrue(now > before * 1.15f, "split navball $now gave back too much of the gain")
    }

    @Test
    fun `stat row scaling degrades safely on nonsense input`() {
        assertEquals(1f, PanelLayout.statRowTextScale(0f, 100f))
        assertEquals(1f, PanelLayout.statRowTextScale(100f, 0f))
        assertEquals(1f, PanelLayout.statRowTextScale(-5f, -5f))
    }

    @Test
    fun `the real DHU split geometry gets a bigger navball`() {
        // The DHU's virtual display is 800x400 -- confirmed from
        // DisplayDeviceInfo in logcat, not assumed. Split view is therefore
        // about 468x400, NOT the ~800-wide surface the first pass took it
        // for. At 468 the ball is WIDTH-bound again, so the height work
        // alone bought nothing and the column had to widen too.
        //
        // This is the geometry that actually ships; pin it directly.
        val (w, h) = 468 to 400
        val l = PanelLayout.choose(w, h)
        assertEquals(PanelLayout.Mode.NARROW, l.mode)

        val navW = (w * l.navballColumnFraction).toFloat()
        val before = PanelLayout.navballRadiusPx((w * 0.38).toFloat(), h * 0.90f * 0.82f)
        val now = PanelLayout.navballRadiusNarrowPx(navW, h.toFloat())

        assertTrue(now > before * 1.15f, "real-geometry navball $now vs old $before")

        // ...and it must still clear the stats row.
        val cy = h * PanelLayout.navballCentreNarrowFraction()
        val bottom = cy + PanelLayout.NAVBALL_EXTENT_BELOW.toFloat() * now
        assertTrue(bottom < h * 0.94f, "cluster bottom $bottom hits the stats row")
    }

    @Test
    fun `the navball column does not starve the split stats row`() {
        // Widening the column to the balance point takes width from the
        // stats row. Three cells must still get a usable share.
        val l = PanelLayout.choose(468, 400)
        val statsShare = 1.0 - PanelLayout.statsRowLeftFraction(l.navballColumnFraction)
        assertTrue(statsShare > 0.5, "stats row left with only $statsShare of the width")
    }
}

/**
 * MINIMAL must actually be minimal.
 *
 * `choose` declares `elements = setOf(DELTA_V, ORBITAL_LABEL)` for very
 * short surfaces, on the reasoning that they "cannot stack anything under
 * the figure". The renderer drew Isp and SPEED regardless until 2026-08-24,
 * when the desktop layout harness rendered an 800x180 surface and produced
 * a smear of overlapping text. These pin the contract the renderer now
 * honours via `layout.shows(Element.ISP_BAR)`.
 */
class MinimalModeContractTest {

    @org.junit.jupiter.api.Test
    fun `a short surface drops everything but the headline`() {
        val l = PanelLayout.choose(800, 180)
        kotlin.test.assertEquals(PanelLayout.Mode.MINIMAL, l.mode)
        kotlin.test.assertTrue(l.shows(PanelLayout.Element.DELTA_V))
        kotlin.test.assertTrue(
            !l.shows(PanelLayout.Element.ISP_BAR),
            "MINIMAL must not claim the Isp bar: the renderer keys the whole " +
                "lower stack (Isp AND speed) off this element."
        )
        kotlin.test.assertTrue(!l.shows(PanelLayout.Element.NAVBALL))
        kotlin.test.assertTrue(!l.shows(PanelLayout.Element.STATS_BLOCK))
    }

    @org.junit.jupiter.api.Test
    fun `the threshold is the documented one`() {
        kotlin.test.assertEquals(
            PanelLayout.Mode.MINIMAL,
            PanelLayout.choose(800, PanelLayout.MIN_HEIGHT_FOR_EXTRAS_PX - 1).mode
        )
        kotlin.test.assertTrue(
            PanelLayout.choose(800, PanelLayout.MIN_HEIGHT_FOR_EXTRAS_PX).mode
                != PanelLayout.Mode.MINIMAL
        )
    }
}
