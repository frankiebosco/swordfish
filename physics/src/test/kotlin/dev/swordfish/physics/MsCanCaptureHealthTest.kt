package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live "is this working RIGHT NOW" verdict.
 *
 * ## Why this is separate from readiness
 *
 * `readiness()` judges the FINISHED dataset. At the start of a drive it says
 * `NO DATA` whether the capture is perfectly healthy or completely broken,
 * which makes it useless as a live indicator -- and being unable to tell
 * those apart from the driver's seat is what wasted the 2026-08-26 drive.
 *
 * `health()` answers the question that matters while driving, and the whole
 * point is that its four states are DISTINGUISHABLE:
 *
 *  - waiting for frames (adapter/bus problem)
 *  - parked, so no bearing yet (normal, not a fault)
 *  - moving with no reference ever (BROKEN -- stop now)
 *  - working
 */
class MsCanCaptureHealthTest {

    private fun bytes(vararg v: Int) = v.toList()

    @Test
    fun `no frames yet says so rather than blaming the reference`() {
        val c = MsCanCapture()
        val h = c.health(nowMs = 1000, moving = true)
        assertTrue(h.startsWith("WAITING"), h)
        assertTrue(h.contains("no CAN frames"), h)
    }

    /**
     * Parked in the Stop & Shop lot: frames arriving, no bearing possible.
     *
     * This MUST NOT read as a fault -- it is the normal state at the start of
     * every capture, and crying wolf here would train the warning away.
     */
    @Test
    fun `stationary with frames but no reference is normal, not broken`() {
        val c = MsCanCapture()
        repeat(200) { c.onFrame("0x085", bytes(1, 2), atMs = 1000L + it) }

        val h = c.health(nowMs = 2000, moving = false)
        assertTrue(h.startsWith("WAITING TO MOVE"), h)
        assertTrue(!h.contains("BROKEN"), "parked must not read as broken: $h")
    }

    /**
     * The exact 2026-08-26 failure: moving, frames arriving, no reference.
     *
     * This is the state that must be unmissable.
     */
    @Test
    fun `moving with frames and no reference ever is BROKEN`() {
        val c = MsCanCapture()
        repeat(735) { c.onFrame("0x085", bytes(1, 2), atMs = 1000L + it) }

        val h = c.health(nowMs = 2000, moving = true)
        assertTrue(h.startsWith("BROKEN"), h)
        assertTrue(h.contains("735"), "should report the frame count: $h")
        assertTrue(
            h.contains("Stop"),
            "must tell the driver to stop rather than drive the route: $h"
        )
    }

    @Test
    fun `a healthy capture reports WORKING with a pairing rate`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(100) { c.onFrame("0x085", bytes(1, 2), atMs = 1000L + it) }

        val h = c.health(nowMs = 1100, moving = true)
        assertTrue(h.startsWith("WORKING"), h)
        assertTrue(h.contains("100%"), "all frames paired here: $h")
        assertTrue(h.contains("1 IDs"), h)
    }

    /**
     * Stopped at a light mid-capture: the bearing goes stale and frames stop
     * pairing. Correct behaviour, and must read as PAUSED not as failure.
     */
    @Test
    fun `stationary after pairing reports PAUSED and keeps the count`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(1, 2), atMs = 1000L + it) }

        // 30 s later, still parked.
        val h = c.health(nowMs = 31_000, moving = false)
        assertTrue(h.startsWith("PAUSED"), h)
        assertTrue(h.contains("50 paired"), "must not lose the tally: $h")
    }

    /**
     * Moving but the bearing has gone stale -- GPS dropped, not a wiring
     * fault. Distinct from BROKEN, because the fix is different.
     */
    @Test
    fun `moving with a stale reference reports STALE not BROKEN`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(1, 2), atMs = 1000L + it) }

        val h = c.health(nowMs = 31_000, moving = true)
        assertTrue(h.startsWith("REFERENCE STALE"), h)
        assertTrue(!h.contains("BROKEN"), "a GPS dropout is not the wiring bug: $h")
    }

    /** Every state must be distinguishable from every other. */
    @Test
    fun `the health states are all distinct`() {
        val empty = MsCanCapture()

        val parked = MsCanCapture().apply {
            repeat(100) { onFrame("0x085", bytes(1), atMs = 1000L + it) }
        }
        val broken = MsCanCapture().apply {
            repeat(100) { onFrame("0x085", bytes(1), atMs = 1000L + it) }
        }
        val working = MsCanCapture().apply {
            onReference(0.3, atMs = 1000)
            repeat(100) { onFrame("0x085", bytes(1), atMs = 1000L + it) }
        }

        val verdicts = listOf(
            empty.health(1000, moving = false),
            parked.health(2000, moving = false),
            broken.health(2000, moving = true),
            working.health(1100, moving = true)
        )

        assertEquals(
            verdicts.size,
            verdicts.map { it.substringBefore(" —") }.toSet().size,
            "each state needs its own headline, was: $verdicts"
        )
    }

    @Test
    fun `reference age is null before any reference arrives`() {
        val c = MsCanCapture()
        assertNull(c.referenceAgeMs(nowMs = 5000))
        assertTrue(!c.hasEverHadReference)
        assertTrue(!c.referenceIsFresh(nowMs = 5000))
    }

    @Test
    fun `frames offered counts kept and dropped alike`() {
        val c = MsCanCapture()
        repeat(10) { c.onFrame("0x085", bytes(1), atMs = 1000L + it) }
        c.onReference(0.3, atMs = 1100)
        repeat(5) { c.onFrame("0x085", bytes(1), atMs = 1100L + it) }

        assertEquals(15, c.framesOffered)
        assertEquals(5, c.size)
        assertEquals(10, c.droppedNoReference)
    }

    @Test
    fun `clear resets the health state to empty`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(1), atMs = 1000L + it) }
        c.clear()

        assertTrue(!c.hasEverHadReference)
        assertEquals(0, c.framesOffered)
        assertTrue(c.health(2000, moving = true).startsWith("WAITING"))
    }
}
