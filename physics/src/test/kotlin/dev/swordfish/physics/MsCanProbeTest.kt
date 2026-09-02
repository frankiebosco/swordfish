package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing and summarising a raw MS-CAN capture.
 *
 * The parser has to be right *before* the drive, because a capture is a
 * one-shot: an hour of frames dropped by a bad parser is an hour that has
 * to be driven again. Everything here is testable without a car, which is
 * the whole reason it lives in `:physics`.
 */
class MsCanProbeTest {

    // --- Frame parsing ---

    @Test
    fun `an 11-bit frame with headers on is parsed`() {
        val f = MsCanProbe.parseFrame("4B0 12 34 56 78")
        assertNotNull(f)
        assertEquals("4B0", f!!.id)
        assertEquals(listOf(0x12, 0x34, 0x56, 0x78), f.bytes)
    }

    @Test
    fun `a 29-bit extended frame is parsed`() {
        val f = MsCanProbe.parseFrame("18DAF110 01 02")
        assertNotNull(f)
        assertEquals("18DAF110", f!!.id)
        assertEquals(listOf(0x01, 0x02), f.bytes)
    }

    @Test
    fun `the trailing prompt does not break a frame`() {
        val f = MsCanProbe.parseFrame("4B0 12 34\r>")
        assertNotNull(f)
        assertEquals(listOf(0x12, 0x34), f!!.bytes)
    }

    @Test
    fun `adapter chatter is not mistaken for a frame`() {
        // These arrive mixed into ATMA output. Decoding "STOPPED" as a
        // frame would inject garbage IDs into the summary.
        for (noise in listOf("STOPPED", "SEARCHING...", "CAN ERROR", "?", "")) {
            assertNull(
                MsCanProbe.parseFrame(noise),
                "\"$noise\" should not parse as a frame"
            )
        }
    }

    @Test
    fun `a bare ID with no payload is rejected`() {
        assertNull(MsCanProbe.parseFrame("4B0"))
    }

    @Test
    fun `a malformed ID length is rejected`() {
        // Valid CAN IDs are 3 hex chars (11-bit) or 8 (29-bit).
        assertNull(MsCanProbe.parseFrame("4B 12 34"))
        assertNull(MsCanProbe.parseFrame("4B0FF 12 34"))
    }

    @Test
    fun `a truncated payload byte is rejected rather than guessed`() {
        // A line caught mid-write. Guessing at it would silently corrupt
        // the capture.
        assertNull(MsCanProbe.parseFrame("4B0 12 3"))
    }

    @Test
    fun `capture time is preserved for offline correlation`() {
        // The whole identification method is correlating CAN bytes against
        // synchronised phone IMU data, which needs timestamps.
        val f = MsCanProbe.parseFrame("4B0 12 34", atMillis = 1234567L)
        assertEquals(1234567L, f!!.atMillis)
    }

    // --- Summarising ---

    @Test
    fun `a static frame is reported as static`() {
        // Configuration and status frames never change. They are not
        // candidates for a sensor and should not consume attention.
        val frames = List(50) { MsCanProbe.Frame("200", listOf(0x01, 0x02), it.toLong()) }
        val sum = MsCanProbe.summarise(frames).single()
        assertFalse(sum.isInteresting)
        assertTrue(sum.activeBytes.isEmpty())
    }

    @Test
    fun `a frame with a moving byte is flagged as interesting`() {
        // This is how a yaw or wheel-speed sensor reveals itself without
        // any published mapping: its bytes move while the car does.
        val frames = List(50) {
            MsCanProbe.Frame("4B0", listOf(0x01, it), it.toLong())
        }
        val sum = MsCanProbe.summarise(frames).single()
        assertTrue(sum.isInteresting)
        assertEquals(listOf(1), sum.activeBytes)
    }

    @Test
    fun `multiple moving bytes are all reported`() {
        // A 16-bit sensor value spans two bytes; reporting only one would
        // hide half the signal.
        val frames = List(50) {
            MsCanProbe.Frame("4B0", listOf(it, it * 2, 0xFF), it.toLong())
        }
        val sum = MsCanProbe.summarise(frames).single()
        assertEquals(listOf(0, 1), sum.activeBytes)
    }

    @Test
    fun `IDs are ranked by how often they appear`() {
        // A high-rate ID is more likely to carry a live measurement than
        // one that appears twice a minute.
        val frames = List(10) { MsCanProbe.Frame("100", listOf(it), it.toLong()) } +
            List(50) { MsCanProbe.Frame("200", listOf(it), it.toLong()) }
        val summaries = MsCanProbe.summarise(frames)
        assertEquals("200", summaries.first().id)
    }

    @Test
    fun `a ragged payload length does not lose bytes`() {
        // Some frames are shorter than others on the same ID. The summary
        // must size itself to the longest.
        val frames = listOf(
            MsCanProbe.Frame("4B0", listOf(0x01, 0x02), 0L),
            MsCanProbe.Frame("4B0", listOf(0x01, 0x02, 0x03), 1L)
        )
        val sum = MsCanProbe.summarise(frames).single()
        assertEquals(3, sum.payloadLength)
    }

    // --- Verdict ---

    @Test
    fun `no frames is a clean no`() {
        // The probe exists to answer yes or no. Zero frames is a definite
        // answer, not an inconclusive one.
        val v = MsCanProbe.verdict(emptyList())
        assertTrue(v.contains("NO FRAMES"), v)
    }

    @Test
    fun `frames that never change do not count as success`() {
        // Reaching a bus that carries only static frames is not the same
        // as reaching the sensors, and must not be reported as if it were.
        val frames = List(50) { MsCanProbe.Frame("200", listOf(0x01), it.toLong()) }
        val v = MsCanProbe.verdict(frames)
        assertFalse(v.contains("REACHABLE"), v)
    }

    @Test
    fun `live frames are reported as reachable`() {
        val frames = List(50) { MsCanProbe.Frame("4B0", listOf(it), it.toLong()) }
        val v = MsCanProbe.verdict(frames)
        assertTrue(v.contains("REACHABLE"), v)
    }

    // --- Command sequence ---

    @Test
    fun `headers are enabled because the CAN ID is the point`() {
        // ATH0 elsewhere in the app strips headers to save bytes. Here the
        // arbitration ID is the entire signal -- without it every frame is
        // anonymous and the capture is worthless.
        assertTrue(MsCanProbe.SELECT_MS_CAN.any { it.text == "ATH1" })
        assertFalse(MsCanProbe.SELECT_MS_CAN.any { it.text == "ATH0" })
    }

    @Test
    fun `the protocol is selected before headers are configured`() {
        val stp = MsCanProbe.SELECT_MS_CAN.indexOfFirst { it.text.startsWith("STP") }
        val ath = MsCanProbe.SELECT_MS_CAN.indexOfFirst { it.text == "ATH1" }
        assertTrue(stp in 0 until ath, "STP must precede ATH1")
    }

    @Test
    fun `every command records why it is sent`() {
        for (c in MsCanProbe.SELECT_MS_CAN) {
            assertTrue(c.rationale.isNotBlank(), "${c.text} has no rationale")
        }
    }
}
