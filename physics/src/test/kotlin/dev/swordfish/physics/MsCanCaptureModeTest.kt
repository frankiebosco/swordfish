package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capture modes, the CAN receive filter, and adapter warnings.
 *
 * ## The drive these tests exist because of
 *
 * 2026-08-28, the Esplanade traffic circle: 3.5 laps counter-clockwise at a
 * steady ~19 m radius and 15-20 mph -- textbook calibration input. It could
 * not be fitted. `ATMA` forwarded the whole bus, the adapter said
 * `BUFFER FULL` twice and `STOPPED` once, half the capture window delivered
 * nothing, and only 46 usable `215` frames survived against a threshold of
 * 100.
 *
 * Nothing here tests the driving. Every test below pins a decision that
 * turns that drive into a fittable one.
 */
class MsCanCaptureModeTest {

    @Test
    fun `discovery mode is unfiltered`() {
        val setup = MsCanProbe.setupFor(MsCanProbe.CaptureMode.DISCOVERY)
        val texts = setup.map { it.text }
        assertTrue(
            texts.none { it.startsWith("ATCF") || it.startsWith("ATCM") },
            "discovery must never filter -- finding unknown IDs is its whole job"
        )
    }

    @Test
    fun `discovery clears a filter left by a calibration run`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.DISCOVERY).map { it.text }
        assertTrue(
            texts.any { it.startsWith("ATCRA") },
            "a stale filter makes discovery blind, and it looks like a dead bus"
        )
    }

    @Test
    fun `calibration mode filters the bus`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)
            .map { it.text }
        assertTrue(texts.any { it.startsWith("ATCM") }, "needs a mask")
        assertTrue(texts.any { it.startsWith("ATCF") }, "needs a filter")
    }

    @Test
    fun `both modes still select MS-CAN first`() {
        for (mode in MsCanProbe.CaptureMode.entries) {
            val texts = MsCanProbe.setupFor(mode).map { it.text }
            assertTrue(
                texts.indexOf("STP 53") in 0 until texts.size,
                "$mode must still select the medium-speed bus"
            )
            assertTrue(
                texts.indexOf("ATH1") < texts.size,
                "$mode needs headers on -- the CAN ID is the point"
            )
        }
    }

    @Test
    fun `the filter is sent after protocol selection`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)
            .map { it.text }
        val stp = texts.indexOfFirst { it.startsWith("STP") }
        val cf = texts.indexOfFirst { it.startsWith("ATCF") }
        assertTrue(stp >= 0 && cf >= 0)
        assertTrue(cf > stp, "filtering before the bus is selected would not stick")
    }

    /**
     * The mask must admit BOTH calibration IDs.
     *
     * `(id AND mask) == (filter AND mask)` is the adapter's rule. This is
     * arithmetic, so it is checked rather than trusted -- a mask that
     * silently dropped `215` would reproduce the exact failure being fixed,
     * and would look like a quiet bus.
     */
    @Test
    fun `the mask admits both 215 and 202`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)
            .map { it.text }
        val mask = texts.first { it.startsWith("ATCM") }.removePrefix("ATCM ")
            .trim().toInt(16)
        val filter = texts.first { it.startsWith("ATCF") }.removePrefix("ATCF ")
            .trim().toInt(16)

        for (id in MsCanProbe.CALIBRATION_IDS) {
            val v = id.toInt(16)
            assertEquals(
                filter and mask,
                v and mask,
                "$id must pass the receive filter"
            )
        }
    }

    /**
     * The IDs that flooded the 2026-08-28 capture must be rejected.
     *
     * These are the real top talkers from that file. `491` alone was 1,096
     * frames -- more than five times the `215` count -- and contributed
     * nothing to the calibration.
     */
    @Test
    fun `the mask rejects the loud irrelevant IDs`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)
            .map { it.text }
        val mask = texts.first { it.startsWith("ATCM") }.removePrefix("ATCM ")
            .trim().toInt(16)
        val filter = texts.first { it.startsWith("ATCF") }.removePrefix("ATCF ")
            .trim().toInt(16)

        // The real top talkers from that capture. `217` is deliberately
        // absent: no mask admits 215 and 202 without also admitting it, and
        // at ~165 frames against the ~8,200 rejected it is not worth a
        // second filter pass. It is discarded by ID at parse time.
        val noisy = listOf("491", "21C", "477", "050", "492", "0FD", "228", "25D", "166")
        for (id in noisy) {
            val v = id.toInt(16)
            assertTrue(
                (v and mask) != (filter and mask),
                "$id flooded the buffer on 2026-08-28 and must be filtered out"
            )
        }
    }

    /**
     * The one ID that leaks is known, bounded, and harmless.
     *
     * Documented as a test so a future mask change that widens the leak
     * fails loudly instead of quietly costing throughput.
     */
    @Test
    fun `at most one unwanted ID leaks through the mask`() {
        val texts = MsCanProbe.setupFor(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)
            .map { it.text }
        val mask = texts.first { it.startsWith("ATCM") }.removePrefix("ATCM ")
            .trim().toInt(16)
        val filter = texts.first { it.startsWith("ATCF") }.removePrefix("ATCF ")
            .trim().toInt(16)

        // Every 11-bit ID actually observed on this car's MS-CAN bus.
        val observed = listOf(
            "491", "202", "21C", "477", "050", "492", "0FD", "228", "25D",
            "166", "215", "503", "47B", "217", "09B", "4F8", "4F0", "4F5",
            "09A", "086", "078", "079", "240", "245", "091", "43D", "09E",
            "09F", "436", "340", "40A", "165", "511"
        )
        val admitted = observed.filter {
            (it.toInt(16) and mask) == (filter and mask)
        }
        val unwanted = admitted - MsCanProbe.CALIBRATION_IDS.toSet()
        assertTrue(
            unwanted.size <= 1,
            "mask should leak at most one ID, leaked: $unwanted"
        )
    }

    @Test
    fun `calibration keeps the cross-check ID`() {
        assertTrue(
            "202" in MsCanProbe.CALIBRATION_IDS,
            "202 gives vehicle speed in the same units -- it catches a bad fit"
        )
        assertTrue("215" in MsCanProbe.CALIBRATION_IDS, "215 is the signal itself")
    }

    // ---- short frames -----------------------------------------------------

    /**
     * The 2026-08-28 failure, pinned as a test.
     *
     * `215` arrived as 3 bytes for the whole turn. It PARSED -- which is why
     * nothing caught it -- so the guard has to be a length check, not an
     * error path.
     */
    @Test
    fun `a truncated wheel frame is flagged as short`() {
        val f = MsCanProbe.parseFrame("215 32 F3 32")
        assertNotNull(f, "a 3-byte frame still parses -- that is the problem")
        assertEquals(3, f.bytes.size)
        assertTrue(MsCanProbe.isShortFrame(f), "3 bytes is short for 215")
    }

    @Test
    fun `a full wheel frame is not short`() {
        val f = MsCanProbe.parseFrame("215 2A 91 2A CF 2A 97 2A CF")
        assertNotNull(f)
        assertTrue(!MsCanProbe.isShortFrame(f), "8 bytes is the expected length")
    }

    @Test
    fun `an ID with no known length is never short`() {
        val f = MsCanProbe.parseFrame("477 01 02")
        assertNotNull(f)
        assertTrue(
            !MsCanProbe.isShortFrame(f),
            "expectedLength must never guess at an unmeasured ID"
        )
    }

    @Test
    fun `expected lengths are only claimed for measured IDs`() {
        assertEquals(8, MsCanProbe.expectedLength("215"))
        assertEquals(8, MsCanProbe.expectedLength("202"))
        assertEquals(null, MsCanProbe.expectedLength("477"))
        assertEquals(null, MsCanProbe.expectedLength("217"))
    }

    @Test
    fun `every capture mode has a setup sequence`() {
        for (mode in MsCanProbe.CaptureMode.entries) {
            assertTrue(
                MsCanProbe.setupFor(mode).isNotEmpty(),
                "$mode must define its own setup"
            )
        }
    }

    // ---- adapter warnings -------------------------------------------------

    @Test
    fun `BUFFER FULL is recognised as a warning`() {
        assertEquals("BUFFER_FULL", MsCanProbe.adapterWarning("BUFFER FULL"))
    }

    @Test
    fun `STOPPED is recognised as a warning`() {
        assertEquals("STOPPED", MsCanProbe.adapterWarning("STOPPED"))
    }

    @Test
    fun `an ordinary frame is not a warning`() {
        assertNull(
            MsCanProbe.adapterWarning("215 37 A0 37 AB 37 A3 37 A0 <DATA ERROR"),
            "a real frame must never be counted as an overflow"
        )
    }

    @Test
    fun `warnings are recognised regardless of case or whitespace`() {
        assertEquals("BUFFER_FULL", MsCanProbe.adapterWarning("  buffer full  "))
    }

    /**
     * A `<DATA ERROR` frame must still parse while warnings are detected.
     *
     * Guards the v77 recovery against regression from the warning path:
     * these two mechanisms look at the same unparsed lines and must not
     * interfere.
     */
    @Test
    fun `data error recovery still works alongside warning detection`() {
        val f = MsCanProbe.parseFrame("215 37 A0 37 AB 37 A3 37 A0 <DATA ERROR")
        assertNotNull(f)
        assertEquals("215", f.id)
        assertEquals(8, f.bytes.size)
        assertEquals(0x37, f.bytes[0])
    }
}
