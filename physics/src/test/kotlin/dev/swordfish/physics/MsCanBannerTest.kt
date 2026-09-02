package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The capture state, condensed for the panel headline.
 *
 * The phone shows a full sentence; the head unit gets one short line read at
 * a glance at speed. Both come from the SAME verdict string, so they can
 * never disagree about what is happening -- that is the property these pin.
 */
class MsCanBannerTest {

    private fun health(capture: MsCanCapture, moving: Boolean) =
        capture.health(System.currentTimeMillis(), moving)

    private fun bytes(vararg v: Int) = v.toList()

    @Test
    fun `a working capture shows the paired count`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = System.currentTimeMillis())
        repeat(1234) { c.onFrame("0x085", bytes(1), atMs = System.currentTimeMillis()) }

        val h = health(c, moving = true)
        assertEquals("MS-CAN 1234", MsCanBanner.label(h, c.size))
        assertTrue(!MsCanBanner.isFault(h), "working is not a fault")
        assertEquals("", MsCanBanner.hint(h), "a healthy state needs no remedy")
    }

    /**
     * The state that cost the 2026-08-26 drive, at a glance.
     *
     * This is the one the driver must be able to act on without stopping to
     * read, so it gets an explicit remedy and the fault colour.
     */
    @Test
    fun `moving with no reference is a fault and says nothing is saved`() {
        val c = MsCanCapture()
        repeat(735) { c.onFrame("0x085", bytes(1), atMs = System.currentTimeMillis()) }

        val h = health(c, moving = true)
        assertEquals("MS-CAN NO REF", MsCanBanner.label(h, c.size))
        assertTrue(MsCanBanner.isFault(h), "nothing being saved IS a fault")
        assertTrue(
            MsCanBanner.hint(h).contains("stop"),
            "must tell the driver to stop: '${MsCanBanner.hint(h)}'"
        )
    }

    /**
     * Parked must NOT be a fault.
     *
     * It is the normal state at the start of every capture and in every
     * traffic queue. Colouring it amber would make the amber meaningless
     * inside one drive -- the same reason LinkState does not flag HANDSHAKE.
     */
    @Test
    fun `parked is not a fault`() {
        val c = MsCanCapture()
        repeat(200) { c.onFrame("0x085", bytes(1), atMs = System.currentTimeMillis()) }

        val h = health(c, moving = false)
        assertEquals("MS-CAN PARKED", MsCanBanner.label(h, c.size))
        assertTrue(
            !MsCanBanner.isFault(h),
            "a parked car is not a fault; flagging it would train the " +
                "warning away"
        )
    }

    @Test
    fun `a stationary pause after pairing still reads as parked`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(1), atMs = 1000) }

        val h = c.health(nowMs = 31_000, moving = false)
        assertEquals("MS-CAN PARKED", MsCanBanner.label(h, c.size))
        assertTrue(!MsCanBanner.isFault(h))
    }

    @Test
    fun `a gps dropout while moving is distinct from the wiring fault`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(1), atMs = 1000) }

        val h = c.health(nowMs = 31_000, moving = true)
        assertEquals("MS-CAN STALE", MsCanBanner.label(h, c.size))
        assertTrue(MsCanBanner.isFault(h))
        assertTrue(
            MsCanBanner.label(h, c.size) != "MS-CAN NO REF",
            "a GPS dropout and a wiring fault need different labels: the " +
                "remedies differ"
        )
    }

    @Test
    fun `no frames at all points at the adapter`() {
        val c = MsCanCapture()
        val h = health(c, moving = true)
        assertEquals("MS-CAN WAIT", MsCanBanner.label(h, c.size))
        assertTrue(MsCanBanner.isFault(h))
        assertTrue(MsCanBanner.hint(h).contains("adapter"))
    }

    /** Every label must fit a glanceable headline. */
    @Test
    fun `labels stay short enough for the headline`() {
        val samples = listOf(
            "WORKING — 99999 paired of 100000 frames (99%), 24 IDs, reference fresh.",
            "BROKEN — 735 frames seen and the car is moving...",
            "REFERENCE STALE — last bearing 12s ago while moving...",
            "PAUSED — stationary, so the bearing has gone stale...",
            "WAITING TO MOVE — 200 frames seen...",
            "WAITING — no CAN frames yet..."
        )
        for (h in samples) {
            val label = MsCanBanner.label(h, 99999)
            assertTrue(
                label.length <= 14,
                "'$label' is ${label.length} chars; the headline is read at " +
                    "speed and must stay short"
            )
        }
    }

    /** An unrecognised verdict must not render as a confident lie. */
    @Test
    fun `an unknown verdict is marked unknown rather than guessed`() {
        assertEquals("MS-CAN ?", MsCanBanner.label("SOMETHING NEW", 0))
    }

    /**
     * Every state MsCanCapture can produce must have a real label.
     *
     * If health() gains a state and the banner is not updated, this fails
     * rather than silently showing "?" on the panel for a whole drive.
     */
    @Test
    fun `every health state the capture produces has a label`() {
        val states = mutableListOf<String>()

        states += MsCanCapture().health(1000, moving = true)          // WAITING
        states += MsCanCapture().apply {
            repeat(100) { onFrame("0x085", bytes(1), atMs = 1000) }
        }.health(2000, moving = false)                                // WAITING TO MOVE
        states += MsCanCapture().apply {
            repeat(100) { onFrame("0x085", bytes(1), atMs = 1000) }
        }.health(2000, moving = true)                                 // BROKEN
        states += MsCanCapture().apply {
            onReference(0.3, atMs = 1000)
            repeat(50) { onFrame("0x085", bytes(1), atMs = 1000) }
        }.health(1100, moving = true)                                 // WORKING
        states += MsCanCapture().apply {
            onReference(0.3, atMs = 1000)
            repeat(50) { onFrame("0x085", bytes(1), atMs = 1000) }
        }.health(31_000, moving = false)                              // PAUSED
        states += MsCanCapture().apply {
            onReference(0.3, atMs = 1000)
            repeat(50) { onFrame("0x085", bytes(1), atMs = 1000) }
        }.health(31_000, moving = true)                               // STALE

        for (h in states) {
            assertTrue(
                MsCanBanner.label(h, 1) != "MS-CAN ?",
                "no banner label for health state: '$h'"
            )
        }
    }
}
