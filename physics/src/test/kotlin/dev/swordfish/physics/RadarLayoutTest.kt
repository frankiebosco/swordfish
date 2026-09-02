package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadarLayoutTest {

    // Same representative geometries as PanelLayoutTest: the DHU window is
    // ~800x420 usable, split view roughly halves the width.
    private val fullWidth = 800 to 420
    private val splitView = 390 to 420
    private val shortStrip = 800 to 180

    // --- content mode ---

    @Test
    fun `mode toggles between instruments and radar`() {
        assertEquals(
            RadarLayout.CentreContent.RADAR,
            RadarLayout.CentreContent.INSTRUMENTS.next()
        )
        assertEquals(
            RadarLayout.CentreContent.INSTRUMENTS,
            RadarLayout.CentreContent.RADAR.next()
        )
    }

    @Test
    fun `an unknown persisted mode falls back to the instrument panel`() {
        // A preference written by a future build must never stop the panel
        // rendering -- same rule as DisplayTheme.fromName.
        assertEquals(
            RadarLayout.CentreContent.INSTRUMENTS,
            RadarLayout.CentreContent.fromName(null)
        )
        assertEquals(
            RadarLayout.CentreContent.INSTRUMENTS,
            RadarLayout.CentreContent.fromName("SOMETHING_ELSE")
        )
    }

    @Test
    fun `a persisted mode round-trips by name`() {
        for (c in RadarLayout.CentreContent.entries) {
            assertEquals(c, RadarLayout.CentreContent.fromName(c.name))
        }
    }

    // --- the asymmetric navball rule, which is the whole design ---

    @Test
    fun `full width radar KEEPS the navball beside the scope`() {
        // REVISED 2026-08-23. The first version dropped the navball here so
        // the scope could take its column. Rendered on the DHU that was
        // simply wrong: the panel looked half-empty, and an instrument the
        // driver had in split view vanished when they widened the app.
        //
        // Both layouts now read the same way left to right: navball, scope,
        // stats.
        val l = PanelLayout.choose(
            fullWidth.first, fullWidth.second, RadarLayout.CentreContent.RADAR
        )
        assertEquals(PanelLayout.Mode.WIDE, l.mode)
        assertTrue(l.shows(PanelLayout.Element.RADAR))
        assertTrue(
            l.shows(PanelLayout.Element.NAVBALL),
            "a mode switch must not cost the driver an instrument"
        )
        assertTrue(l.navballColumnFraction > 0.0)
    }

    @Test
    fun `the scope sits on the navball centre at full width too`() {
        // Swept over strip heights: the two must agree whether or not the
        // status banner is showing, since the banner's reclaim shifts both.
        for (strip in listOf(0f, 20f, 40f, 72f)) {
            for (h in listOf(300f, 364f, 648f)) {
                assertEquals(
                    PanelLayout.navballCentreWideFraction(strip, h),
                    RadarLayout.scopeCentreWideFraction(strip, h),
                    "circles disagree at strip=$strip h=$h"
                )
            }
        }
    }

    @Test
    fun `the flanking columns take back the status strip at full width`() {
        // `draw` insets the whole content rect by the strip, but the banner
        // is drawn top-CENTRE and renders no pixels over the navball or the
        // scope. Measured on the DHU at 778x404 with NO ADAPTER showing:
        // 47px of dead space above the cluster and 8px below.
        //
        // The same defect NAVBALL_NARROW_HEIGHT_SHARE fixed for split view.
        val h = 364f
        val strip = 40f

        val withStrip = PanelLayout.navballCentreWideFraction(strip, h)
        val without = PanelLayout.navballCentreWideFraction(0f, h)

        assertTrue(
            withStrip < without,
            "reclaiming the strip must move the centre UP, not down " +
                "($withStrip vs $without)"
        )
    }

    @Test
    fun `reclaiming the strip centres the cluster in the column`() {
        // The point of the reclaim: dead space above and below should match.
        // Before the fix it was 47px above against 8px below.
        val panelH = 404f
        val strip = 40f
        val contentH = panelH - strip
        val navW = 778f * 0.34f

        val r = PanelLayout.navballRadiusWidePx(navW, contentH, strip)
        val cy = strip + contentH * PanelLayout.navballCentreWideFraction(strip, contentH)

        val above = cy - r * PanelLayout.NAVBALL_EXTENT_ABOVE.toFloat()
        val below = panelH - (cy + r * PanelLayout.NAVBALL_EXTENT_BELOW.toFloat())

        assertTrue(above >= 0f, "cluster clipped at the top ($above)")
        assertTrue(below >= 0f, "cluster clipped at the bottom ($below)")
        assertTrue(
            kotlin.math.abs(above - below) < panelH * 0.05f,
            "cluster is not centred: ${above}px above vs ${below}px below"
        )
    }

    @Test
    fun `the full-width scope and readouts fit around the shared centre`() {
        // Swept with the status strip both ways. Reserving the split view's
        // row height while full width drew a taller one pushed the readouts
        // off the bottom on 453 of 506 geometries.
        for (w in 700..1800 step 50) {
            for (h in 360..760 step 40) {
                for (stripShown in listOf(true, false)) {
                    val areaH = h.toFloat()
                    val strip = if (stripShown) (areaH * 0.10f).toInt() else 0
                    val top = strip.toFloat()
                    val contentH = areaH - strip

                    val columnW = w * 0.44f
                    val r = RadarLayout.scopeRadiusWidePx(columnW, contentH, top)
                    val cy = top +
                        contentH * RadarLayout.scopeCentreWideFraction(top, contentH)
                    val clusterTop = cy - r * RadarLayout.SCOPE_EXTENT_ABOVE.toFloat()
                    val clusterBottom = cy + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()

                    val rowTop = top + RadarLayout.readoutTopWidePx(contentH, r, top)
                    val rowBottom = rowTop + contentH * 0.105f

                    val where = "${w}x$h strip=$stripShown"
                    assertTrue(r > 0f, "no scope at $where")
                    // The cluster may reach ABOVE the content rect now, into
                    // the strip band the banner does not use in this column.
                    assertTrue(clusterTop >= -0.5f, "scope clipped at $where")
                    assertTrue(rowTop > clusterBottom, "readouts hit the scope at $where")
                    assertTrue(
                        rowBottom <= top + contentH + 0.5f,
                        "readouts off the panel at $where"
                    )
                }
            }
        }
    }

    @Test
    fun `the instrument pair is packed, not centred in its columns`() {
        // On a head unit both circles are HEIGHT-bound -- at 778x406 the
        // navball's width cap is 124 against a height cap of 115, the
        // scope's 161 against 119 -- so column fractions do not change
        // their size. Centring each in a column it cannot fill puts every
        // spare pixel BETWEEN them: 118px of void down the middle of the
        // panel, photographed on the DHU.
        val available = 611f
        val leftR = 89f
        val rightR = 89f

        val (leftCx, rightCx) = PanelLayout.packInstrumentPair(available, leftR, rightR)

        val edgeGap = (rightCx - rightR * PanelLayout.SCOPE_HALF_EXTENT.toFloat()) -
            (leftCx + leftR * PanelLayout.NAVBALL_HALF_EXTENT.toFloat())

        assertTrue(edgeGap > 0f, "the clusters must not touch or overlap")

        // The upper bound is a PROPORTION, not a pixel count.
        //
        // This test used to assert `edgeGap < 118f`, from when the defect was
        // a void left by centring each circle in its own column. The gap has
        // since been widened deliberately -- twice -- because a numerically
        // generous gap still read as a seam beside a large circle. Pinning
        // the old pixel figure would now fail the layout the user actually
        // approved.
        //
        // What still matters is that the pair reads as a PAIR: the space
        // between them must not exceed the space they occupy.
        val clusterWidth = (leftR * PanelLayout.NAVBALL_HALF_EXTENT.toFloat() +
            rightR * PanelLayout.SCOPE_HALF_EXTENT.toFloat())
        assertTrue(
            edgeGap < clusterWidth,
            "the pair has drifted apart: ${edgeGap}px gap against " +
                "${clusterWidth}px of instrument"
        )
    }

    @Test
    fun `the packed pair is biased left, giving the stats column room`() {
        // NOT centred. The pair's right neighbour is the stats column --
        // text with a hard edge that must stay legible -- while its left
        // neighbour is the panel edge, which needs nothing. Equal margins
        // crowded the stats column and wasted the same space outside, seen
        // on the DHU as the scope brushing GEAR/RPM.
        // Radii the real geometry produces: r=89 on a 611px span. The old
        // 115/119 figures predate INSTRUMENT_GAP widening to 0.70 and now
        // OVERFLOW that span by 86px, leaving no slack to bias -- which is
        // what this test then reported as a failure.
        val available = 611f
        val leftR = 89f
        val rightR = 89f
        val (leftCx, rightCx) = PanelLayout.packInstrumentPair(available, leftR, rightR)

        val leftEdge = leftCx - leftR * PanelLayout.NAVBALL_HALF_EXTENT.toFloat()
        val rightEdge = available -
            (rightCx + rightR * PanelLayout.SCOPE_HALF_EXTENT.toFloat())

        assertTrue(leftEdge >= 0f, "the pair must not leave the surface")
        assertTrue(
            rightEdge > leftEdge,
            "the stats side should get the larger margin " +
                "(left=$leftEdge right=$rightEdge)"
        )
    }

    @Test
    fun `the left bias still leaves an outer margin`() {
        // Pinning hard to the edge would look like a rendering error rather
        // than a layout choice.
        assertTrue(PanelLayout.PAIR_LEFT_BIAS > 0.0)
        assertTrue(PanelLayout.PAIR_LEFT_BIAS < 0.5)

        val (leftCx, _) = PanelLayout.packInstrumentPair(611f, 89f, 89f)
        val leftEdge = leftCx - 89f * PanelLayout.NAVBALL_HALF_EXTENT.toFloat()
        assertTrue(leftEdge > 5f, "expected a visible left margin, got $leftEdge")
    }

    @Test
    fun `packing accounts for the navball's compass overhang`() {
        // THE BUG THIS PINS: drawNavball draws its compass strip at
        // radius * 2.3, so the cluster reaches past the rim. Packing by
        // radius made a computed 61px rim gap render as 47px of visible
        // space, and widening INSTRUMENT_GAP could not fix it because the
        // compass reclaimed a fixed slice of whatever was granted.
        assertTrue(
            PanelLayout.NAVBALL_HALF_EXTENT > 1.0,
            "the navball cluster is wider than its circle"
        )

        val available = 611f
        val r = 96f
        val (leftCx, rightCx) = PanelLayout.packInstrumentPair(available, r, r)

        val compassRight = leftCx + r * PanelLayout.NAVBALL_HALF_EXTENT.toFloat()
        val scopeLeft = rightCx - r * PanelLayout.SCOPE_HALF_EXTENT.toFloat()

        assertTrue(
            scopeLeft > compassRight,
            "the compass strip must not reach the scope " +
                "(compass to $compassRight, scope from $scopeLeft)"
        )
    }

    @Test
    fun `packing keeps both circles inside the shared space`() {
        for (available in listOf(400f, 607f, 900f, 1400f)) {
            for (leftR in listOf(60f, 115f, 180f)) {
                for (rightR in listOf(60f, 119f, 190f)) {
                    // Extents, not radii: the clusters are what must fit.
                    val lh = leftR * PanelLayout.NAVBALL_HALF_EXTENT.toFloat()
                    val rh = rightR * PanelLayout.SCOPE_HALF_EXTENT.toFloat()
                    if (lh * 2f + rh * 2f > available) continue

                    val (l, r) = PanelLayout.packInstrumentPair(available, leftR, rightR)
                    assertTrue(
                        l - lh >= -0.5f,
                        "left cluster off the edge at $available/$leftR/$rightR"
                    )
                    assertTrue(
                        r + rh <= available + 0.5f,
                        "right cluster past the edge at $available/$leftR/$rightR"
                    )
                    assertTrue(l + lh <= r - rh + 0.5f, "clusters overlap")
                }
            }
        }
    }

    @Test
    fun `split view radar keeps the navball beside the scope`() {
        // This is the arrangement the user asked for: two instruments side
        // by side. Split view is also the layout every real drive has used.
        val l = PanelLayout.choose(
            splitView.first, splitView.second, RadarLayout.CentreContent.RADAR
        )
        assertEquals(PanelLayout.Mode.NARROW, l.mode)
        assertTrue(l.shows(PanelLayout.Element.RADAR))
        assertTrue(l.shows(PanelLayout.Element.NAVBALL))
        assertTrue(l.navballColumnFraction > 0.0)
    }

    @Test
    fun `radar mode leaves the column fractions alone`() {
        // Radar changes what the CENTRE column carries, not how the panel is
        // divided. Both flanking columns keep their instrument-mode widths,
        // so drawWide's existing left/width arithmetic needs no special case.
        val instruments = PanelLayout.choose(
            800, 420, RadarLayout.CentreContent.INSTRUMENTS
        )
        val radar = PanelLayout.choose(800, 420, RadarLayout.CentreContent.RADAR)

        assertEquals(instruments.navballColumnFraction, radar.navballColumnFraction)
        assertEquals(instruments.statsColumnFraction, radar.statsColumnFraction)
    }

    @Test
    fun `radar mode never disturbs the stats column`() {
        for ((w, h) in listOf(fullWidth, splitView)) {
            val instruments =
                PanelLayout.choose(w, h, RadarLayout.CentreContent.INSTRUMENTS)
            val radar = PanelLayout.choose(w, h, RadarLayout.CentreContent.RADAR)
            assertEquals(
                instruments.statsColumnFraction, radar.statsColumnFraction,
                "stats column should be untouched at ${w}x$h"
            )
            assertEquals(
                instruments.shows(PanelLayout.Element.STATS_BLOCK),
                radar.shows(PanelLayout.Element.STATS_BLOCK)
            )
        }
    }

    @Test
    fun `delta-V survives radar mode as a demoted line`() {
        // Demoted, never dropped: it is still the thing the app is for.
        for ((w, h) in listOf(fullWidth, splitView)) {
            val l = PanelLayout.choose(w, h, RadarLayout.CentreContent.RADAR)
            assertTrue(
                l.shows(PanelLayout.Element.DELTA_V),
                "delta-V must survive radar mode at ${w}x$h"
            )
        }
    }

    @Test
    fun `a surface too short for extras refuses radar`() {
        // MINIMAL is the "one number, readable in half a second" layout.
        // A scope cannot help there and delta-V is the last thing standing.
        val l = PanelLayout.choose(
            shortStrip.first, shortStrip.second, RadarLayout.CentreContent.RADAR
        )
        assertEquals(PanelLayout.Mode.MINIMAL, l.mode)
        assertFalse(l.shows(PanelLayout.Element.RADAR))
        assertTrue(l.shows(PanelLayout.Element.DELTA_V))
    }

    @Test
    fun `instrument mode is byte-for-byte the old layout`() {
        // The single-argument choose is what every existing caller and test
        // uses. Adding radar must not have moved it by a hair.
        for ((w, h) in listOf(fullWidth, splitView, shortStrip)) {
            assertEquals(
                PanelLayout.choose(w, h),
                PanelLayout.choose(w, h, RadarLayout.CentreContent.INSTRUMENTS),
                "instrument mode should equal the original layout at ${w}x$h"
            )
        }
    }

    // --- scope sizing ---

    @Test
    fun `scope radius is bound by whichever axis runs out first`() {
        // Tall and narrow: width binds.
        val widthBound = RadarLayout.scopeRadiusPx(200f, 800f)
        assertEquals(
            (200f / 2f) * RadarLayout.SCOPE_MARGIN.toFloat(), widthBound, 0.01f
        )

        // Wide and short: height binds. Divided by the cluster's REAL
        // extent, not by 2 -- the range label sits above the rim and the
        // status line below it, and sizing as though the circle owned the
        // band put the rim at y=0 on the DHU.
        val heightBound = RadarLayout.scopeRadiusPx(800f, 200f)
        assertEquals(
            (200f * RadarLayout.SCOPE_HEIGHT_SHARE.toFloat() *
                RadarLayout.SCOPE_VERTICAL_MARGIN.toFloat()) /
                RadarLayout.SCOPE_EXTENT_TOTAL.toFloat(),
            heightBound, 0.01f
        )
    }

    @Test
    fun `a degenerate column produces no scope rather than a negative one`() {
        assertEquals(0f, RadarLayout.scopeRadiusPx(0f, 400f))
        assertEquals(0f, RadarLayout.scopeRadiusPx(400f, 0f))
        assertEquals(0f, RadarLayout.scopeRadiusPx(-10f, -10f))
    }

    @Test
    fun `the scope leaves room for the demoted readout lines`() {
        // The scope must not grow into the band that carries delta-V and
        // Isp beneath it -- that band is why SCOPE_HEIGHT_SHARE is not 1.0.
        val h = 400f
        val r = RadarLayout.scopeRadiusPx(2000f, h)
        assertTrue(r * 2f < h, "scope diameter ($r*2) should not fill the column ($h)")
        assertTrue(RadarLayout.SCOPE_HEIGHT_SHARE < 1.0)
    }

    @Test
    fun `the scope centre sits inside its own band`() {
        val f = RadarLayout.scopeCentreFraction()
        assertTrue(f > 0f && f < RadarLayout.SCOPE_HEIGHT_SHARE.toFloat())
    }

    // --- alignment with the navball, which is the point of split view ---

    @Test
    fun `the split-view scope sits on the navball's centre`() {
        // Photographed on the DHU: the scope rode 79px high, its rim nearly
        // touching the panel top while the ball sat comfortably below. Two
        // circular instruments at different heights read as a mistake -- the
        // eye catches the step before it reads either one.
        assertEquals(
            PanelLayout.navballCentreNarrowFraction(),
            RadarLayout.scopeCentreNarrowFraction(),
            "the scope must adopt the navball's centre, not compute its own"
        )
    }

    @Test
    fun `the scope cluster stays on the panel in split view`() {
        // Swept rather than spot-checked: a fixed readout fraction cleared
        // the rim at 468px and buried itself in it at 390px, and a 500x400
        // surface pushed the readouts 3px off the bottom. None of that is
        // visible from one screenshot.
        for (w in 320..700 step 20) {
            for (h in 360..560 step 20) {
                val navW = w * 0.46f
                val columnW = w - navW
                val panelH = h.toFloat()

                val r = RadarLayout.scopeRadiusNarrowPx(columnW, panelH)
                val cy = panelH * RadarLayout.scopeCentreNarrowFraction()
                val top = cy - r * RadarLayout.SCOPE_EXTENT_ABOVE.toFloat()
                val bottom = cy + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()

                assertTrue(r > 0f, "no scope at ${w}x$h")
                assertTrue(top >= 0f, "scope clipped at the top at ${w}x$h (top=$top)")
                assertTrue(bottom <= panelH, "scope past the bottom at ${w}x$h")
            }
        }
    }

    @Test
    fun `the readout row clears the scope and stays on the panel`() {
        for (w in 320..700 step 20) {
            for (h in 360..560 step 20) {
                val navW = w * 0.46f
                val columnW = w - navW
                val panelH = h.toFloat()
                // The renderer draws the row against the body height left
                // after the stats strip, which is where its segment height
                // comes from.
                val bodyH = panelH * 0.82f

                val r = RadarLayout.scopeRadiusNarrowPx(columnW, panelH)
                val cy = panelH * RadarLayout.scopeCentreNarrowFraction()
                val scopeBottom = cy + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()

                val rowTop = RadarLayout.readoutTopNarrowPx(panelH, r)
                val rowBottom = rowTop + bodyH * 0.090f

                assertTrue(
                    rowTop > scopeBottom,
                    "readouts collide with the scope at ${w}x$h " +
                        "(row $rowTop vs scope $scopeBottom)"
                )
                assertTrue(
                    rowBottom <= panelH,
                    "readouts fall off the panel at ${w}x$h ($rowBottom > $panelH)"
                )
            }
        }
    }

    @Test
    fun `radar mode stacks stats, scope and readouts without overlap`() {
        // Photographed on the DHU: delta-V and Isp printed straight through
        // GEAR / RPM / FUEL, 12px apart. The stats row is hardcoded at
        // h * 0.94 in instrument mode and the radar readouts want the same
        // band, so radar mode moves the stats row to the TOP.
        //
        // Swept with the status strip both shown and hidden, because the
        // strip shifts every band by 10% of the panel when the link is not
        // live -- which is the DHU's permanent state.
        for (w in 320..700 step 20) {
            for (h in 360..560 step 20) {
                for (stripShown in listOf(true, false)) {
                    val areaH = h.toFloat()
                    val strip = if (stripShown) (areaH * 0.10f).toInt() else 0
                    val top = strip.toFloat()
                    val bodyH = areaH - strip

                    val navW = w * 0.46f
                    val columnW = w - navW

                    val r = RadarLayout.scopeRadiusNarrowPx(columnW, bodyH)
                    val cy = top + bodyH * RadarLayout.scopeCentreNarrowFraction()
                    val clusterTop = cy - r * RadarLayout.SCOPE_EXTENT_ABOVE.toFloat()
                    val clusterBottom = cy + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()

                    val statsY =
                        top + bodyH * RadarLayout.STATS_ROW_TOP_FRACTION_RADAR.toFloat()
                    val rowTop = top + RadarLayout.readoutTopNarrowPx(bodyH, r)
                    val rowBottom = rowTop + bodyH * 0.090f

                    val where = "${w}x$h strip=$stripShown"
                    assertTrue(
                        statsY < clusterTop,
                        "stats row collides with the scope at $where"
                    )
                    assertTrue(
                        rowTop > clusterBottom,
                        "readouts collide with the scope at $where"
                    )
                    assertTrue(
                        rowBottom <= areaH,
                        "readouts fall off the panel at $where"
                    )
                }
            }
        }
    }

    // --- the readout row shrinks to fit ---

    @Test
    fun `a readout row that already fits is not scaled`() {
        // The row shrinks, never grows. A short row at full size is correct.
        assertEquals(1f, RadarLayout.readoutRowScale(100f, 400f))
    }

    @Test
    fun `an overflowing readout row is scaled into its width`() {
        // Measured on the DHU at 468x402: three readouts need 336px of a
        // 253px column, which is what printed them through each other.
        val scale = RadarLayout.readoutRowScale(336f, 253f)
        assertTrue(scale < 1f, "an overflowing row must shrink")
        assertTrue(
            336f * scale <= 253f,
            "the scaled row must fit: ${336f * scale} > 253"
        )
    }

    @Test
    fun `the scaled row leaves a gutter rather than merely touching`() {
        // Cells that exactly fill the row read as one run of digits. The
        // stat row hit this first -- hence PanelLayout.STAT_CELL_FILL.
        val width = 253f
        val scaled = 336f * RadarLayout.readoutRowScale(336f, width)
        assertTrue(scaled < width, "the row should not fill its width edge to edge")
        assertTrue(RadarLayout.READOUT_ROW_FILL < 1.0)
    }

    @Test
    fun `content-sized cells beat equal thirds`() {
        // The reason the row packs proportionally: `29 m/s` needs 77px while
        // `31564 s` needs 133, so equal thirds starves the wide cells to pay
        // for a narrow one. Same row, same space, more readable.
        val width = 253f
        val cells = listOf(126f, 133f, 77f)

        val proportional = RadarLayout.readoutRowScale(cells.sum(), width)
        val equalThirds = RadarLayout.readoutTextScale(cells.max(), width / 3f)

        assertTrue(
            proportional > equalThirds,
            "proportional ($proportional) should beat equal thirds ($equalThirds)"
        )
    }

    @Test
    fun `a degenerate row is not scaled to nothing`() {
        assertEquals(1f, RadarLayout.readoutRowScale(0f, 400f))
        assertEquals(1f, RadarLayout.readoutRowScale(300f, 0f))
        assertEquals(1f, RadarLayout.readoutRowScale(-5f, 400f))
    }

    @Test
    fun `the readout row fits at every split geometry`() {
        // Swept with the WIDEST values the readouts can carry -- five-digit
        // Isp and five-digit delta-V -- because a row that fits today's
        // sample frame and not a full tank is a bug waiting for a drive.
        val aspect = 0.58f
        val tracking = 0.30f
        fun segWidth(digits: Int, h: Float): Float {
            val cell = h * aspect
            return cell * digits + cell * tracking * (digits - 1)
        }

        for (w in 320..700 step 20) {
            for (h in 360..560 step 20) {
                val bodyH = h - (h * 0.10f)
                val rowWidth = w * 0.54f
                val segH = bodyH * 0.090f
                val unitSize = bodyH * 0.040f
                fun unitWidth(text: String, scale: Float) =
                    0.6f * unitSize * scale * text.length

                val worst = listOf("99999" to "m/s", "99999" to "s", "999" to "m/s")
                val natural = worst.sumOf { (digits, unit) ->
                    (segWidth(digits.length, segH) + segH * 0.22f +
                        unitWidth(unit, 1f)).toDouble()
                }.toFloat()

                val scale = RadarLayout.readoutRowScale(natural, rowWidth)
                val fitted = worst.sumOf { (digits, unit) ->
                    (segWidth(digits.length, segH * scale) + segH * scale * 0.22f +
                        unitWidth(unit, scale)).toDouble()
                }.toFloat()

                assertTrue(
                    fitted <= rowWidth + 0.5f,
                    "readouts overflow at ${w}x$h ($fitted > $rowWidth)"
                )
            }
        }
    }

    @Test
    fun `the stats row sits above the scope only in radar mode`() {
        // Instrument mode keeps GEAR/RPM/FUEL along the bottom at h * 0.94.
        // Radar mode is the exception, and it must stay the exception.
        assertTrue(
            RadarLayout.STATS_ROW_TOP_FRACTION_RADAR < 0.5,
            "radar mode puts the stats row in the TOP half"
        )
        assertTrue(
            RadarLayout.STATS_ROW_TOP_FRACTION_RADAR <
                RadarLayout.SCOPE_TOP_FLOOR_RADAR,
            "the stats row must sit above where the scope's band begins"
        )
    }

    @Test
    fun `the full-width scope is not flush against the panel edge`() {
        // At full width the scope is WIDTH-bound, so without a vertical
        // margin the cluster sits at y=0 with its range label touching the
        // edge. Measured on the DHU at 800x420.
        val h = 420f
        val columnW = 800f * 0.78f
        val r = RadarLayout.scopeRadiusPx(columnW, h)
        val cy = h * RadarLayout.scopeCentreFraction()
        val top = cy - r * RadarLayout.SCOPE_EXTENT_ABOVE.toFloat()

        assertTrue(top > 0f, "the scope should not touch the panel top (top=$top)")
    }

    @Test
    fun `the full-width readout row clears the scope`() {
        for (w in 600..1600 step 100) {
            for (h in 360..760 step 40) {
                val columnW = w * 0.78f
                val panelH = h.toFloat()
                val r = RadarLayout.scopeRadiusPx(columnW, panelH)
                val cy = panelH * RadarLayout.scopeCentreFraction()
                val scopeBottom = cy + r * RadarLayout.SCOPE_EXTENT_BELOW.toFloat()

                val rowTop = panelH * RadarLayout.READOUT_TOP_FRACTION_WIDE.toFloat()
                val rowBottom = rowTop + panelH * 0.105f

                assertTrue(
                    rowTop > scopeBottom,
                    "readouts collide with the scope at ${w}x$h"
                )
                assertTrue(rowBottom <= panelH, "readouts off the panel at ${w}x$h")
            }
        }
    }

    @Test
    fun `the scope is sized from its cluster, not from the circle alone`() {
        // Dividing the band by 2 rather than by the real extent is what put
        // the rim at y=0 with the range label clipped.
        assertTrue(RadarLayout.SCOPE_EXTENT_TOTAL > 2.0)
        assertTrue(
            RadarLayout.SCOPE_EXTENT_BELOW > RadarLayout.SCOPE_EXTENT_ABOVE,
            "the status line beneath the rim makes the cluster bottom-heavy"
        )
    }

    // --- range rings ---

    @Test
    fun `the outermost ring is the scope edge`() {
        val r = 120f
        val outer = RadarLayout.ringRadiusPx(RadarLayout.RING_COUNT - 1, r)
        assertEquals(r, outer, 0.01f)
    }

    @Test
    fun `rings increase outward and stay inside the scope`() {
        val r = 120f
        var previous = 0f
        for (i in 0 until RadarLayout.RING_COUNT) {
            val ring = RadarLayout.ringRadiusPx(i, r)
            assertTrue(ring > previous, "ring $i ($ring) should exceed ring ${i - 1}")
            assertTrue(ring <= r, "ring $i ($ring) should stay inside the scope")
            previous = ring
        }
    }

    @Test
    fun `an out-of-bounds ring index draws nothing`() {
        assertEquals(0f, RadarLayout.ringRadiusPx(-1, 120f))
        assertEquals(0f, RadarLayout.ringRadiusPx(RadarLayout.RING_COUNT, 120f))
    }

    @Test
    fun `a ring's label matches its radius`() {
        // The invariant that makes the rings readable: a ring at half the
        // scope radius must be labelled half the range.
        val scope = 120f
        val range = 30
        for (i in 0 until RadarLayout.RING_COUNT) {
            val radiusFraction = RadarLayout.ringRadiusPx(i, scope) / scope
            val rangeFraction = RadarLayout.ringRangeMiles(i, range) / range
            // 1e-6, not 1e-9: the radius is a Float and carries about seven
            // significant digits, so a third of the scope cannot agree with
            // a Double third to nine places. The invariant under test is
            // that the two are the SAME fraction, not that Float is exact.
            assertEquals(
                radiusFraction.toDouble(), rangeFraction, 1e-6,
                "ring $i geometry and label disagree"
            )
        }
    }

    @Test
    fun `the outermost ring is labelled the full range`() {
        assertEquals(
            40.0, RadarLayout.ringRangeMiles(RadarLayout.RING_COUNT - 1, 40), 1e-9
        )
    }

    // --- range selection ---

    @Test
    fun `range steps upward through the offered ranges`() {
        assertEquals(20, RadarLayout.nextRange(10))
        assertEquals(40, RadarLayout.nextRange(20))
        assertEquals(80, RadarLayout.nextRange(40))
    }

    @Test
    fun `range wraps rather than sticking at the end`() {
        // A head-unit control that stops responding at the end of its travel
        // reads as broken when you cannot look at it.
        assertEquals(
            RadarLayout.RANGES_MILES.first(),
            RadarLayout.nextRange(RadarLayout.RANGES_MILES.last())
        )
    }

    @Test
    fun `an unknown range resets to the default`() {
        assertEquals(RadarLayout.DEFAULT_RANGE_MILES, RadarLayout.nextRange(37))
    }

    @Test
    fun `the default range is one of the offered ranges`() {
        assertTrue(RadarLayout.DEFAULT_RANGE_MILES in RadarLayout.RANGES_MILES)
    }

    // --- heading-up projection ---

    @Test
    fun `a target dead ahead draws straight up`() {
        // Screen y is DOWN, so "up" is negative. Getting this backwards puts
        // every target on the wrong side of the car.
        val (x, y) = assertNotNull(
            RadarLayout.toScopeXY(
                bearingDeg = 0.0, distanceMiles = 10.0,
                headingDeg = 0.0, rangeMiles = 20, scopeRadiusPx = 100f
            )
        )
        assertEquals(0f, x, 0.01f)
        assertTrue(y < 0f, "dead ahead should draw up the screen, got y=$y")
        assertEquals(50f, abs(y), 0.01f)
    }

    @Test
    fun `the scope is heading-up, not north-up`() {
        // The same northern target must swing to the LEFT of the car once
        // the car turns to face east. This is the property that makes the
        // scope readable beside a heading-up navball.
        val (x, y) = assertNotNull(
            RadarLayout.toScopeXY(
                bearingDeg = 0.0, distanceMiles = 10.0,
                headingDeg = 90.0, rangeMiles = 20, scopeRadiusPx = 100f
            )
        )
        assertTrue(x < 0f, "a northern target should sit left when heading east, got x=$x")
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `a target to starboard draws right`() {
        val (x, y) = assertNotNull(
            RadarLayout.toScopeXY(
                bearingDeg = 90.0, distanceMiles = 20.0,
                headingDeg = 0.0, rangeMiles = 20, scopeRadiusPx = 100f
            )
        )
        assertTrue(x > 0f, "a target to starboard should draw right, got x=$x")
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `distance scales linearly to the scope edge`() {
        val edge = assertNotNull(
            RadarLayout.toScopeXY(0.0, 20.0, 0.0, 20, 100f)
        )
        assertEquals(100f, abs(edge.second), 0.01f)

        val half = assertNotNull(
            RadarLayout.toScopeXY(0.0, 10.0, 0.0, 20, 100f)
        )
        assertEquals(50f, abs(half.second), 0.01f)
    }

    @Test
    fun `a target beyond range is not drawn at all`() {
        // Returning null rather than a clipped point means the renderer
        // never has to decide what "at the edge but further" looks like.
        assertNull(RadarLayout.toScopeXY(0.0, 21.0, 0.0, 20, 100f))
    }

    @Test
    fun `a target at the car draws at the centre`() {
        val (x, y) = assertNotNull(RadarLayout.toScopeXY(0.0, 0.0, 0.0, 20, 100f))
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `a degenerate scope projects nothing`() {
        assertNull(RadarLayout.toScopeXY(0.0, 5.0, 0.0, 0, 100f))
        assertNull(RadarLayout.toScopeXY(0.0, 5.0, 0.0, 20, 0f))
        assertNull(RadarLayout.toScopeXY(0.0, -1.0, 0.0, 20, 100f))
    }

    @Test
    fun `bearing wraps without a discontinuity`() {
        // 359 degrees and -1 degree are the same direction. A projection
        // that special-cases the wrap would put a seam in the scope.
        val a = assertNotNull(RadarLayout.toScopeXY(359.0, 10.0, 0.0, 20, 100f))
        val b = assertNotNull(RadarLayout.toScopeXY(-1.0, 10.0, 0.0, 20, 100f))
        assertEquals(a.first, b.first, 0.01f)
        assertEquals(a.second, b.second, 0.01f)
    }
}
