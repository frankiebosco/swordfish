package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `<DATA ERROR` frames must be RECOVERED, not discarded.
 *
 * ## What this cost
 *
 * Every MS-CAN capture ever taken reported roughly a 34% parse rate, and the
 * two-thirds counted as "unparsed" were never looked at because the text was
 * thrown away. Logging them verbatim on 2026-08-27 showed what they were:
 *
 * ```
 * 245 80 17 E8 01 80 00 00 00 <DATA ERROR
 * 340 60 00 00 00 00 00 00 00 <DATA ERROR
 * ```
 *
 * **100% of 128,799 "unparsed" lines** in the ridge-road capture were complete,
 * well-formed frames with an adapter annotation appended. `<DATA ERROR` means
 * the CAN checksum did not verify -- a real caveat, but the id and payload are
 * intact and are exactly what byte-correlation needs. Discarding them threw
 * away two thirds of every capture.
 *
 * ## The ordering trap
 *
 * `ElmProtocol.classify` reports `<DATA ERROR` as ERROR, so the old code
 * rejected these lines before anything could inspect them. The strip MUST
 * happen before the classify check, and classify must not be applied to a
 * line already known to be a data-error frame.
 */
class MsCanDataErrorRecoveryTest {

    @Test
    fun `a DATA ERROR frame is parsed, not discarded`() {
        val f = MsCanProbe.parseFrame("245 80 17 E8 01 80 00 00 00 <DATA ERROR", 42L)
        assertNotNull(f, "a complete frame must survive the DATA ERROR marker")
        assertEquals("245", f.id)
        assertEquals(listOf(0x80, 0x17, 0xE8, 0x01, 0x80, 0x00, 0x00, 0x00), f.bytes)
        assertEquals(42L, f.atMillis)
    }

    @Test
    fun `a short DATA ERROR frame keeps its real length`() {
        val f = MsCanProbe.parseFrame("511 03 02 00 <DATA ERROR")
        assertNotNull(f)
        assertEquals("511", f.id)
        assertEquals(listOf(0x03, 0x02, 0x00), f.bytes)
    }

    @Test
    fun `a clean frame is unaffected`() {
        val f = MsCanProbe.parseFrame("215 27 10 27 10 27 10 27 10")
        assertNotNull(f)
        assertEquals("215", f.id)
        assertEquals(8, f.bytes.size)
        assertEquals(0x27, f.bytes[0])
    }

    /**
     * The adapter's real error strings must STILL be rejected. Recovering
     * data-error frames must not turn genuine status text into fake frames.
     */
    @Test
    fun `genuine adapter status strings are still rejected`() {
        assertNull(MsCanProbe.parseFrame("BUFFER FULL"))
        assertNull(MsCanProbe.parseFrame("STOPPED"))
        assertNull(MsCanProbe.parseFrame("SEARCHING..."))
        assertNull(MsCanProbe.parseFrame("?"))
        assertNull(MsCanProbe.parseFrame("NO DATA"))
        assertNull(MsCanProbe.parseFrame(""))
    }

    /** A marker with nothing before it is not a frame. */
    @Test
    fun `a bare DATA ERROR marker yields nothing`() {
        assertNull(MsCanProbe.parseFrame("<DATA ERROR"))
    }

    /** The wheel-speed frame, the one signal decoded so far, round-trips. */
    @Test
    fun `a wheel speed frame decodes to four sixteen bit values`() {
        val f = MsCanProbe.parseFrame("215 2A 91 2A CF 2A 97 2A CF <DATA ERROR")
        assertNotNull(f)
        val b = f.bytes
        val wheels = listOf(
            (b[0] shl 8) or b[1],
            (b[2] shl 8) or b[3],
            (b[4] shl 8) or b[5],
            (b[6] shl 8) or b[7]
        )
        assertEquals(listOf(10897, 10959, 10903, 10959), wheels)
    }
}
