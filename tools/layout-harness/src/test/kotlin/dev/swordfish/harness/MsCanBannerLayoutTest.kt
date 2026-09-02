package dev.swordfish.harness

import dev.swordfish.car.MsCanBannerText
import dev.swordfish.physics.LinkState
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MS-CAN banner must change the WORDS and nothing else.
 *
 * ## Why this is pixel-diffed rather than eyeballed
 *
 * The tuned layout is untouchable: a whole evening was lost to layout churn
 * before the tuner existed, and the standing rule is that any renderer change
 * is proved by capturing harness renders, making the change, re-rendering and
 * diffing pixel-for-pixel.
 *
 * Reusing the status headline for capture state is exactly the kind of change
 * that looks textual and is not. The headline reserves vertical space in three
 * separate places and draws into it in a fourth; if the banner appeared while
 * those three still thought nothing was showing, the text would land in
 * unreserved space and print over the navball/scope pair.
 *
 * So these tests pin the two halves of the contract:
 *
 *  1. with no capture running, output is **byte-identical** to before
 *  2. with one running, the ONLY pixels that differ are inside the headline
 *     band -- the instruments below it are untouched
 */
class MsCanBannerLayoutTest {

    private fun cases() = defaultCases()

    private fun bannerOf(label: String, hint: String = "", fault: Boolean = false) =
        MsCanBannerText(label = label, hint = hint, isFault = fault)

    /** Every pixel that differs between two renders of the same geometry. */
    private fun diffRows(a: BufferedImage, b: BufferedImage): Set<Int> {
        assertEquals(a.width, b.width, "width must not change")
        assertEquals(a.height, b.height, "height must not change")
        val rows = mutableSetOf<Int>()
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    rows += y
                    break
                }
            }
        }
        return rows
    }

    /**
     * The last row the status strip can legitimately touch.
     *
     * Derived from the renderer's own constants rather than guessed. The
     * strip is 10% of the PANEL height (PanelLayout.statusStripFraction) and
     * begins at the STABLE AREA's top edge -- not the surface top, which on
     * the real ND2 head unit is 88px higher.
     *
     * An earlier version used `height / 4` from the surface top and reported
     * rows 101-110 as displaced instruments when they were in fact the
     * banner's own second line. The wrong origin, not a real regression.
     *
     * A few px of margin covers descenders on the hint line.
     */
    private fun stripBottom(case: Case): Int {
        val stable = case.geometry.stable
        val panelH = stable.bottom - stable.top
        return stable.top + (panelH * 0.10f).toInt() + 4
    }

    /**
     * The no-capture path must be untouched.
     *
     * This is the case that runs on every ordinary drive, so it is the one
     * that must be provably identical -- not merely "looks the same".
     */
    @Test
    fun `with no capture running every case renders identically`() {
        for (case in cases()) {
            val withoutField = render(case)

            // Explicitly null, which is what GaugeScreen passes when the
            // bridge reports no capture.
            val explicitNull = render(
                case.copy(state = case.state.copy(msCanBanner = null))
            )

            val rows = diffRows(withoutField, explicitNull)
            assertTrue(
                rows.isEmpty(),
                "${case.label}: a null banner changed ${rows.size} rows; the " +
                    "no-capture path must be byte-identical"
            )
        }
    }

    /**
     * With a capture running, only the headline band may change.
     *
     * The instruments are the thing that must not move. If a row below the
     * headline differs, the banner has displaced something.
     */
    @Test
    fun `a running capture changes only the headline band`() {
        for (case in cases()) {
            // Compare like with like: the link is LOST during a capture,
            // because the capture owns the socket. So the baseline is a
            // LOST panel, and the only difference under test is the words.
            val lost = case.copy(
                state = case.state.copy(
                    isLive = false,
                    linkState = LinkState.LOST,
                    msCanBanner = null
                )
            )
            val capturing = case.copy(
                state = lost.state.copy(
                    msCanBanner = bannerOf("MS-CAN 1234")
                )
            )

            val a = render(lost)
            val b = render(capturing)
            val rows = diffRows(a, b)

            if (rows.isEmpty()) continue

            // The headline sits in the top band. Nothing below the top
            // quarter of the surface may differ.
            val limit = stripBottom(case)
            val below = rows.filter { it > limit }
            assertTrue(
                below.isEmpty(),
                "${case.label}: rows ${below.take(8)} below the headline band " +
                    "(y>$limit) changed. The banner must not displace any " +
                    "instrument."
            )
        }
    }

    /**
     * A capture banner must reserve the SAME space a link announcement does.
     *
     * This is the specific failure the shared `announcesStatus` rule prevents:
     * if the draw gate said "show it" while the three space-reservation sites
     * said "nothing to announce", the instruments would shift up under the
     * text.
     */
    @Test
    fun `a capture banner reserves the same space as a link announcement`() {
        for (case in cases()) {
            // LOST announces; the banner replaces its text. If both reserve
            // the same band, every instrument row is identical and only the
            // glyphs differ -- which the previous test already bounds.
            val announcing = case.copy(
                state = case.state.copy(
                    isLive = false, linkState = LinkState.LOST, msCanBanner = null
                )
            )

            // LIVE does NOT announce, so without the banner nothing shows.
            // With one, the strip must appear -- and the instruments must
            // move exactly as they do for a link announcement.
            val liveNoBanner = case.copy(
                state = case.state.copy(
                    isLive = true, linkState = LinkState.LIVE, msCanBanner = null
                )
            )
            val liveWithBanner = case.copy(
                state = liveNoBanner.state.copy(
                    msCanBanner = bannerOf("MS-CAN 1234")
                )
            )

            val a = render(announcing)
            val c = render(liveWithBanner)

            // Both announce something, so both reserve the strip. Instrument
            // rows must therefore agree below the headline band.
            val limit = stripBottom(case)
            val below = diffRows(a, c).filter { it > limit }
            assertTrue(
                below.isEmpty(),
                "${case.label}: a capture banner reserved different space " +
                    "from a link announcement; rows ${below.take(8)} differ " +
                    "below y=$limit"
            )

            // And the banner must genuinely be drawing, not silently
            // skipped -- EXCEPT in MINIMAL, which suppresses the status
            // strip entirely (statusStripFraction = 0). That surface is too
            // small to spend height on chrome, and the link state is hidden
            // there for the same reason, so a capture banner is correctly
            // hidden too. Discovered by this test rather than assumed.
            if (!case.label.startsWith("minimal")) {
                val appeared = diffRows(render(liveNoBanner), c)
                assertTrue(
                    appeared.isNotEmpty(),
                    "${case.label}: the banner drew nothing over a LIVE panel"
                )
            }
        }
    }

    /**
     * The fault colour must not change geometry either.
     *
     * An amber banner and a normal one are the same glyphs in a different
     * colour; if the fault path also drew a hint line where the normal path
     * did not, it would occupy extra rows.
     */
    @Test
    fun `the fault styling does not move anything`() {
        for (case in cases()) {
            val base = case.state.copy(
                isLive = false, linkState = LinkState.LOST
            )
            val normal = case.copy(
                state = base.copy(msCanBanner = bannerOf("MS-CAN 1234"))
            )
            val fault = case.copy(
                state = base.copy(
                    msCanBanner = bannerOf("MS-CAN NO REF", "nothing saved — stop", true)
                )
            )

            val limit = stripBottom(case)
            val below = diffRows(render(normal), render(fault)).filter { it > limit }
            assertTrue(
                below.isEmpty(),
                "${case.label}: fault styling changed rows ${below.take(8)} " +
                    "below the headline band"
            )
        }
    }
}
