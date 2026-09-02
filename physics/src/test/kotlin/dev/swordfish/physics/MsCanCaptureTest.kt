package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pairing frames with what the car was doing when they arrived.
 *
 * Without this the capture is unusable: frames with no reference cannot be
 * correlated, however many of them there are.
 */
class MsCanCaptureTest {

    private fun bytes(vararg v: Int) = v.toList()

    @Test
    fun `a frame with no reference yet is dropped`() {
        val c = MsCanCapture()
        assertTrue(!c.onFrame("0x085", bytes(1, 2, 3, 4), atMs = 1000))
        assertEquals(0, c.size)
        assertEquals(1, c.droppedNoReference)
    }

    @Test
    fun `a frame takes the most recent reference`() {
        val c = MsCanCapture()
        c.onReference(0.35, atMs = 1000)
        assertTrue(c.onFrame("0x085", bytes(1, 2), atMs = 1100))
        assertEquals(0.35, c.observations().first().reference)
    }

    @Test
    fun `a stale reference is not stamped onto a frame`() {
        // A bearing from ten seconds ago describes a corner already left.
        // Pairing it with current frames blurs the correlation toward zero,
        // which makes a REAL signal look absent -- the worst failure mode.
        val c = MsCanCapture()
        c.onReference(0.35, atMs = 1000)
        assertTrue(!c.onFrame("0x085", bytes(1, 2), atMs = 11_000))
        assertEquals(1, c.droppedNoReference)
    }

    @Test
    fun `many frames share one reference`() {
        // Frames arrive far faster than GPS bearing updates. Each takes the
        // most recent value, which is correct: the reference is a continuous
        // quantity being sampled, not an event.
        val c = MsCanCapture()
        c.onReference(0.20, atMs = 1000)
        repeat(50) { c.onFrame("0x085", bytes(it, it), atMs = 1000L + it) }
        assertEquals(50, c.size)
        assertTrue(c.observations().all { it.reference == 0.20 })
    }

    @Test
    fun `the buffer cannot grow without bound`() {
        val c = MsCanCapture(maxObservations = 10)
        c.onReference(0.1, atMs = 0)
        repeat(50) { c.onFrame("0x085", bytes(1, 2), atMs = 100) }
        assertEquals(10, c.size)
        assertEquals(40, c.droppedFull)
    }

    @Test
    fun `id counts show what can actually be scored`() {
        val c = MsCanCapture()
        c.onReference(0.1, atMs = 0)
        repeat(40) { c.onFrame("0x085", bytes(1, 2), atMs = 100) }
        repeat(5) { c.onFrame("0x201", bytes(3, 4), atMs = 100) }
        assertEquals(mapOf("0x085" to 40, "0x201" to 5), c.idCounts())
    }

    // --- readiness ---

    @Test
    fun `an empty capture reports no data`() {
        assertTrue(MsCanCapture().readiness().startsWith("NO DATA"))
    }

    @Test
    fun `too few samples per ID is reported`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 0)
        repeat(10) { c.onFrame("0x085", bytes(1, 2), atMs = 100) }
        assertTrue(c.readiness().startsWith("TOO FEW"), c.readiness())
    }

    @Test
    fun `a one-sided drive is reported as unusable`() {
        // THE TRAP. A route that only turns left exercises half a signed
        // sensor's range. A byte can correlate beautifully across that half
        // and still be wrong about the other, and the sign convention would
        // be unresolvable.
        val c = MsCanCapture()
        repeat(40) {
            c.onReference(0.3, atMs = it * 100L)          // left turns only
            c.onFrame("0x085", bytes(it, it), atMs = it * 100L)
        }
        val r = c.readiness()
        assertTrue(r.startsWith("ONE-SIDED"), r)
        assertTrue(r.contains("BOTH ways"), "the message must say what to do: $r")
    }

    @Test
    fun `a drive turning both ways is ready`() {
        val c = MsCanCapture()
        var t = 0L
        repeat(40) {
            c.onReference(0.3, atMs = t); c.onFrame("0x085", bytes(it, it), atMs = t)
            t += 100
        }
        repeat(40) {
            c.onReference(-0.3, atMs = t); c.onFrame("0x085", bytes(it, it), atMs = t)
            t += 100
        }
        val r = c.readiness()
        assertTrue(r.startsWith("READY"), r)
    }

    @Test
    fun `clear resets everything including the reference`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 0)
        c.onFrame("0x085", bytes(1, 2), atMs = 10)
        c.clear()
        assertEquals(0, c.size)
        // The reference must go too, or the first frame after a clear would
        // be stamped with a value from the previous session.
        assertTrue(!c.onFrame("0x085", bytes(1, 2), atMs = 20))
    }

    @Test
    fun `an end-to-end capture identifies a planted signal`() {
        // The whole pipeline: reference in, frames in, correlation out.
        val c = MsCanCapture()
        var t = 0L
        for (i in 0 until 200) {
            // A route that turns both ways, as a real drive does.
            val yaw = when {
                i < 60 -> 0.3
                i < 120 -> -0.3
                else -> 0.0
            }
            c.onReference(yaw, atMs = t)
            val raw = ((yaw * 1000).toInt()) and 0xFFFF
            c.onFrame("0x085", listOf(0, 0, (raw shr 8) and 0xFF, raw and 0xFF, 0, 0), atMs = t)
            c.onFrame("0x201", listOf(0x55, 0x55, 0x55, 0x55, 0x55, 0x55), atMs = t)
            t += 100
        }
        assertTrue(c.readiness().startsWith("READY"), c.readiness())

        val results = MsCanIdentify.identify(c.observations())
        val best = results.first()
        assertEquals("0x085", best.canId, "the carrying ID must be found")
        assertTrue(best.strength > 0.99, "planted signal should be found: $best")

        // The offset is NOT asserted exactly, and that is a real property of
        // the search rather than a weak test.
        //
        // The planted value spans 0x0000..0x012C, so its high byte barely
        // moves. Reading the pair starting one byte LATER -- low byte plus
        // the constant beside it -- tracks the signal just as well. Both are
        // honest matches at this magnitude.
        //
        // On the real bus this means neighbouring offsets will appear near
        // the top together. Distinguishing them needs a manoeuvre that drives
        // the signal through a wider range, which is what a longer drive with
        // sharper corners provides.
        assertTrue(
            best.offset in 2..3,
            "expected the signal near offset 2, got ${best.offset}"
        )
    }
}
