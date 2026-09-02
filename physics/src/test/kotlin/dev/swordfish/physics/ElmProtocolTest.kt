package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElmProtocolTest {

    // --- Reply classification ---
    //
    // The NO_DATA / ERROR distinction is the one that matters. Conflating
    // them produces an app that retries an unsupported PID forever.

    @Test
    fun `a hex payload is data`() {
        assertEquals(ElmProtocol.ReplyKind.DATA, ElmProtocol.classify("410C1AF8"))
    }

    @Test
    fun `a spaced hex payload is data`() {
        assertEquals(ElmProtocol.ReplyKind.DATA, ElmProtocol.classify("41 0C 1A F8"))
    }

    @Test
    fun `NO DATA is a capability fact not an error`() {
        assertEquals(ElmProtocol.ReplyKind.NO_DATA, ElmProtocol.classify("NO DATA"))
    }

    @Test
    fun `the ATZ banner is recognised`() {
        assertEquals(ElmProtocol.ReplyKind.BANNER, ElmProtocol.classify("ELM327 v1.5"))
    }

    @Test
    fun `an AT acknowledgement is OK`() {
        assertEquals(ElmProtocol.ReplyKind.OK, ElmProtocol.classify("OK"))
    }

    @Test
    fun `SEARCHING is not an error`() {
        // Emitted while the adapter negotiates a protocol on first contact.
        // Treating it as a failure would abort every cold connect.
        assertEquals(ElmProtocol.ReplyKind.SEARCHING, ElmProtocol.classify("SEARCHING..."))
    }

    @Test
    fun `bus faults are errors`() {
        for (fault in listOf("CAN ERROR", "BUS INIT: ...ERROR", "UNABLE TO CONNECT", "?")) {
            assertEquals(
                ElmProtocol.ReplyKind.ERROR,
                ElmProtocol.classify(fault),
                "expected $fault to classify as an error"
            )
        }
    }

    @Test
    fun `the trailing prompt does not affect classification`() {
        assertEquals(ElmProtocol.ReplyKind.DATA, ElmProtocol.classify("410C1AF8\r\r>"))
        assertEquals(ElmProtocol.ReplyKind.NO_DATA, ElmProtocol.classify("NO DATA\r>"))
    }

    @Test
    fun `an empty reply is unknown rather than an error`() {
        assertEquals(ElmProtocol.ReplyKind.UNKNOWN, ElmProtocol.classify(""))
        assertEquals(ElmProtocol.ReplyKind.UNKNOWN, ElmProtocol.classify("   \r\n>"))
    }

    // --- No-contact detection ---
    //
    // "Turn the ignition on" and "this PID is unsupported" need different
    // things from the user, so the app must tell them apart.

    @Test
    fun `ignition-off replies are flagged as no vehicle contact`() {
        assertTrue(ElmProtocol.indicatesNoVehicleContact("UNABLE TO CONNECT"))
        assertTrue(ElmProtocol.indicatesNoVehicleContact("BUS INIT: ERROR"))
    }

    @Test
    fun `NO DATA is not a loss of vehicle contact`() {
        // The car is talking; it just has nothing for this PID.
        assertFalse(ElmProtocol.indicatesNoVehicleContact("NO DATA"))
    }

    // --- Framing ---

    @Test
    fun `a buffer is complete only once the prompt arrives`() {
        assertFalse(ElmProtocol.isComplete("41 0C 1A"))
        assertTrue(ElmProtocol.isComplete("41 0C 1A F8\r>"))
    }

    @Test
    fun `lines split on CR and drop the prompt`() {
        val lines = ElmProtocol.splitLines("SEARCHING...\r41 0C 1A F8\r\r>")
        assertEquals(listOf("SEARCHING...", "41 0C 1A F8"), lines)
    }

    @Test
    fun `a multi-frame reply yields one line per frame`() {
        val lines = ElmProtocol.splitLines("41 00 BE 3E B8 11\r41 20 90 07 E0 11\r>")
        assertEquals(2, lines.size)
    }

    // --- Handshake ---

    @Test
    fun `the handshake resets first and selects a protocol last`() {
        // Order is load-bearing: ATZ wipes any prior configuration, so
        // sending it after ATSP0 would discard the protocol selection.
        assertEquals("ATZ", ElmProtocol.HANDSHAKE.first().text)
        assertEquals("ATSP0", ElmProtocol.HANDSHAKE.last().text)
    }

    @Test
    fun `the handshake disables echo before anything else is read`() {
        // With echo on, every reply is preceded by the command, which
        // doubles the bytes on a transport that is already the binding
        // constraint. It must come immediately after the reset.
        val idx = ElmProtocol.HANDSHAKE.indexOfFirst { it.text == "ATE0" }
        assertEquals(1, idx)
    }

    @Test
    fun `ATZ is given a longer timeout than the rest`() {
        // It genuinely reboots the adapter. A 1 s timeout fails the
        // handshake on a healthy dongle.
        val atz = ElmProtocol.HANDSHAKE.first { it.text == "ATZ" }
        assertTrue(atz.timeoutMs >= 4000, "ATZ needs seconds, not milliseconds")
        for (cmd in ElmProtocol.HANDSHAKE.filter { it.text != "ATZ" }) {
            assertTrue(cmd.timeoutMs < atz.timeoutMs)
        }
    }

    @Test
    fun `every command is CR terminated`() {
        for (cmd in ElmProtocol.HANDSHAKE) {
            assertTrue(cmd.wire.endsWith("\r"), "${cmd.text} must end with CR")
            assertFalse(cmd.wire.contains("\n"), "${cmd.text} must not send LF")
        }
    }

    @Test
    fun `every handshake command records why it is sent`() {
        // Same principle as PollSchedule.Entry.rationale: the reasoning
        // lives in the data so it survives a future tuning session.
        for (cmd in ElmProtocol.HANDSHAKE) {
            assertTrue(cmd.rationale.isNotBlank(), "${cmd.text} has no rationale")
        }
    }

    // --- BUSY: the failure that cost a session in the field ---

    @Test
    fun `STOPPED is busy rather than a fault`() {
        // Observed 2026-08-20: ATZ answered STOPPED because a previous
        // session left the adapter mid-command. Classifying it as an error
        // made the probe give up on a working dongle, and the apparent fix
        // was opening the OBDLink app and closing it -- which only reset
        // the adapter as a side effect.
        assertEquals(ElmProtocol.ReplyKind.BUSY, ElmProtocol.classify("STOPPED"))
        assertEquals(ElmProtocol.ReplyKind.BUSY, ElmProtocol.classify("STOPPED>"))
    }

    @Test
    fun `busy is retryable and errors are not`() {
        assertTrue(ElmProtocol.isRetryable(ElmProtocol.ReplyKind.BUSY))
        assertFalse(ElmProtocol.isRetryable(ElmProtocol.ReplyKind.ERROR))
        assertFalse(ElmProtocol.isRetryable(ElmProtocol.ReplyKind.NO_DATA))
        assertFalse(ElmProtocol.isRetryable(ElmProtocol.ReplyKind.DATA))
    }

    @Test
    fun `a busy adapter is not reported as loss of vehicle contact`() {
        // The remedy is a retry, not turning the key.
        assertFalse(ElmProtocol.indicatesNoVehicleContact("STOPPED"))
    }

    // --- Latency tuning variants ---

    @Test
    fun `the tuning race includes an unmodified baseline control`() {
        // Without a control the other variants have nothing to be compared
        // against, and "faster" becomes unfalsifiable.
        val baseline = ElmProtocol.TUNING_VARIANTS.first()
        assertTrue(baseline.setup.isEmpty(), "the first variant must be unmodified")
    }

    @Test
    fun `every tuning variant states what it is testing`() {
        for (v in ElmProtocol.TUNING_VARIANTS) {
            assertTrue(v.label.isNotBlank(), "variant has no label")
            assertTrue(v.hypothesis.isNotBlank(), "${v.label} has no hypothesis")
        }
    }

    @Test
    fun `tuning variants are distinct configurations`() {
        val setups = ElmProtocol.TUNING_VARIANTS.map { it.setup }
        assertEquals(setups.size, setups.distinct().size, "duplicate variant setups")
    }

    @Test
    fun `the fixed-protocol variant pins the ND2 protocol`() {
        // ATSP6 is ISO 15765-4 CAN 11-bit/500k, which the vehicle survey
        // confirmed. A different number would pin the wrong bus.
        val v = ElmProtocol.TUNING_VARIANTS.first { it.label == "fixed protocol" }
        assertTrue(v.setup.contains("ATSP6"), "expected ATSP6, got ${v.setup}")
    }

}
