package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkStateTest {

    @Test
    fun `a working link does not announce itself`() {
        // Moving numbers are the announcement. A permanent "LIVE" badge
        // spends panel space saying nothing.
        assertFalse(LinkState.LIVE.shouldAnnounce)
    }

    @Test
    fun `every state except LIVE is announced`() {
        for (s in LinkState.entries.filter { it != LinkState.LIVE }) {
            assertTrue(s.shouldAnnounce, "$s should be surfaced to the driver")
        }
    }

    @Test
    fun `transient connect steps are not styled as faults`() {
        // Flashing a warning colour through a normal 200 ms handshake
        // trains the driver to ignore the indicator entirely.
        assertFalse(LinkState.HANDSHAKE.isFault)
        assertFalse(LinkState.CAPABILITIES.isFault)
    }

    @Test
    fun `things that need the driver to act are faults`() {
        assertTrue(LinkState.NO_ADAPTER.isFault)
        assertTrue(LinkState.NO_VEHICLE.isFault)
        assertTrue(LinkState.LOST.isFault)
    }

    @Test
    fun `demo mode is announced but is not a fault`() {
        // Nothing is broken — there is simply no car attached. Saying so
        // matters, because sample data on a real drive would otherwise be
        // indistinguishable from telemetry.
        assertTrue(LinkState.DEMO.shouldAnnounce)
        assertFalse(LinkState.DEMO.isFault)
    }

    @Test
    fun `a dropped link is distinguishable from one that never started`() {
        // "Check the pairing" and "the link died mid-drive" are different
        // findings, and the second is the one worth keeping.
        assertTrue(LinkState.LOST != LinkState.NO_ADAPTER)
        assertTrue(LinkState.LOST.isFault && LinkState.NO_ADAPTER.isFault)
    }

    @Test
    fun `ignition trouble reads as a question rather than a fault code`() {
        // The remedy is a key turn. A generic "NO DATA" sends the driver
        // hunting for a fault that is not there.
        assertTrue(LinkState.NO_VEHICLE.label.contains("IGNITION"))
    }

    @Test
    fun `every state carries a label and a remedy hint`() {
        for (s in LinkState.entries) {
            assertTrue(s.label.isNotBlank(), "$s has no label")
            assertTrue(s.hint.isNotBlank(), "$s has no hint")
            // The label is drawn at panel scale on a head unit; long
            // strings would either overflow or shrink below legibility.
            assertTrue(s.label.length <= 12, "$s label is too long to draw: ${s.label}")
        }
    }
}
