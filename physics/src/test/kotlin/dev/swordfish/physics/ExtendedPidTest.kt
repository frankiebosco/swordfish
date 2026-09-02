package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PIDs the Ancel AD310 survey never asked about.
 *
 * Context, because it is the whole reason this file exists: the survey
 * stopped querying at `0160`, concluded the ND2 supported 34 PIDs, and
 * recorded `015E` engine fuel rate as absent. the project then built the fuel
 * model on that absence, marked "do not re-litigate".
 *
 * The MX+ diagnostic report of 2026-08-20 enumerated 51 PIDs including
 * `015E`. The survey was not wrong about what it saw — it was wrong about
 * what it had looked at.
 */
class ExtendedPidTest {

    // --- Range walking ---

    @Test
    fun `the sweep covers all eight ranges not four`() {
        // Stopping at 0160 is exactly what missed sixteen PIDs.
        assertEquals(8, ObdPid.SUPPORT_QUERIES.size)
        assertTrue(ObdPid.SUPPORT_QUERIES.contains("0180"))
        assertTrue(ObdPid.SUPPORT_QUERIES.contains("01A0"))
    }

    @Test
    fun `the sweep follows the continuation bit into the next range`() {
        // Bit 0x20 of the 0100 mask means "0120 is worth asking about".
        val discovered = setOf(0x20)
        assertEquals("0120", ObdPid.nextSupportQuery(discovered, 0x00))
    }

    @Test
    fun `the sweep stops when the car declines to continue`() {
        // No continuation bit means no further ranges exist. Asking anyway
        // costs a NO DATA per range and tells us nothing.
        assertNull(ObdPid.nextSupportQuery(emptySet(), 0x00))
    }

    @Test
    fun `the sweep chains through the ranges the ND2 actually reports`() {
        // The MX+ found PIDs up to 0xA6, which requires the chain to reach
        // the 0x A0 range — four hops past where the Ancel stopped.
        val discovered = setOf(0x20, 0x40, 0x60, 0x80, 0xA0)
        assertEquals("0120", ObdPid.nextSupportQuery(discovered, 0x00))
        assertEquals("0140", ObdPid.nextSupportQuery(discovered, 0x20))
        assertEquals("0160", ObdPid.nextSupportQuery(discovered, 0x40))
        assertEquals("0180", ObdPid.nextSupportQuery(discovered, 0x60))
        assertEquals("01A0", ObdPid.nextSupportQuery(discovered, 0x80))
    }

    @Test
    fun `the sweep never walks past the last legal range`() {
        // 0x E0 is the final range; there is no 0x100.
        assertNull(ObdPid.nextSupportQuery(setOf(0xE0 + 0x20), 0xE0))
    }

    // --- Fuel rate: the headline PID ---

    @Test
    fun `engine fuel rate decodes to a plausible idle burn`() {
        // 0.20 gal/h is the ND2's measured idle burn — the project's one
        // real-world calibration point. In PID 015E's encoding
        // (0.05 L/h per count) that is 0.7571 L/h, or 15 counts.
        val kgPerSec = ObdPid.decodeEngineFuelRateKgPerSec(listOf(0x00, 0x0F))
        assertTrue(kgPerSec != null)
        val galPerHour = Units.kgToGallons(kgPerSec!!) * 3600.0
        assertTrue(
            galPerHour in 0.15..0.25,
            "expected ~0.20 gal/h at idle, got $galPerHour"
        )
    }

    @Test
    fun `a zero fuel rate decodes to zero rather than null`() {
        // This is what the accessory-mode report returned. It must decode
        // cleanly as 0.0 — "enumerated but reading zero" is a real state
        // and has to be distinguishable from "unparseable".
        assertEquals(0.0, ObdPid.decodeEngineFuelRateKgPerSec(listOf(0x00, 0x00)))
    }

    @Test
    fun `a truncated fuel rate frame is rejected`() {
        assertNull(ObdPid.decodeEngineFuelRateKgPerSec(listOf(0x00)))
    }

    // --- The second fuel path ---

    @Test
    fun `019D yields separate engine and vehicle fuel rates`() {
        // Four bytes at 0.02 g/s per count: engine A-B, vehicle C-D.
        val r = ObdPid.decodeEngineVehicleFuelRate(listOf(0x00, 0x64, 0x00, 0x32))
        assertTrue(r != null)
        val (engine, vehicle) = r!!
        // 100 counts * 0.02 = 2.0 g/s = 0.002 kg/s
        assertEquals(0.002, engine, 1e-9)
        assertEquals(0.001, vehicle, 1e-9)
    }

    @Test
    fun `019D rejects a frame too short to hold both rates`() {
        assertNull(ObdPid.decodeEngineVehicleFuelRate(listOf(0x00, 0x64)))
    }

    // --- Torque, which would replace a modelled curve ---

    @Test
    fun `actual torque percent is offset by 125`() {
        // The encoding is signed via an offset: 125 means zero torque, which
        // is what a warm idle in neutral reports.
        assertEquals(0.0, ObdPid.decodeActualTorquePercent(listOf(125)))
        assertEquals(-125.0, ObdPid.decodeActualTorquePercent(listOf(0)))
        assertEquals(50.0, ObdPid.decodeActualTorquePercent(listOf(175)))
    }

    @Test
    fun `reference torque matches the observed ND2 figure`() {
        // The MX+ read 184.39 lb-ft, which is 250 Nm.
        val nm = ObdPid.decodeReferenceTorqueNm(listOf(0x00, 0xFA))
        assertEquals(250.0, nm)
        val lbFt = nm!! * 0.7375621
        assertTrue(lbFt in 183.0..186.0, "expected ~184 lb-ft, got $lbFt")
    }

    // --- Gear, which would replace inference ---

    @Test
    fun `gear ratio matches the observed neutral reading`() {
        // The MX+ reported 5.09 in neutral. Bytes C-D at 0.001 resolution;
        // 5090 = 0x13E2.
        val ratio = ObdPid.decodeGearRatio(listOf(0x03, 0x00, 0x13, 0xE2))
        assertEquals(5.09, ratio!!, 0.001)
    }

    @Test
    fun `gear ratio ignores the leading support and gear bytes`() {
        // Bytes A and B are a support mask and gear number. Changing them
        // must not move the ratio — if it does, the byte offset is wrong.
        val a = ObdPid.decodeGearRatio(listOf(0x03, 0x00, 0x13, 0xE2))
        val b = ObdPid.decodeGearRatio(listOf(0xFF, 0x06, 0x13, 0xE2))
        assertEquals(a, b)
    }

    @Test
    fun `gear ratio rejects a short frame`() {
        assertNull(ObdPid.decodeGearRatio(listOf(0x03, 0x00, 0x13)))
    }

    // --- Odometer ---

    @Test
    fun `odometer matches the observed mileage`() {
        // The MX+ read 30277.18 miles = 48726.4 km = 487264 counts at 0.1 km.
        val km = ObdPid.decodeOdometerKm(listOf(0x00, 0x07, 0x6F, 0xA0))
        assertTrue(km != null)
        val miles = Units.metersToMiles(km!! * 1000.0)
        assertTrue(miles in 30200.0..30350.0, "expected ~30277 mi, got $miles")
    }

    @Test
    fun `odometer handles a value beyond signed 24-bit range`() {
        // Four bytes at 0.1 km reaches 429,496 km. A naive Int shift would
        // overflow to negative near the top of the range.
        val km = ObdPid.decodeOdometerKm(listOf(0xFF, 0xFF, 0xFF, 0xFF))
        assertTrue(km != null && km > 0.0, "odometer overflowed to $km")
    }

    // --- The claim under test ---

    @Test
    fun `the recorded baseline still omits the PIDs the MX plus found`() {
        // Guards the correction itself: ND2_2023_OBSERVED is the ANCEL
        // survey and is knowingly incomplete above 0x4C. When it is updated
        // from engine-running MX+ data, this test should be updated with it
        // — deliberately, not by accident.
        val baseline = VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        for (pid in listOf(0x5E, 0x9D, 0x62, 0x63, 0xA4, 0xA6)) {
            assertTrue(
                !baseline.contains(pid),
                "01%02X is now in the baseline — was it verified with the engine RUNNING?"
                    .format(pid)
            )
        }
    }

    @Test
    fun `enumeration in accessory mode is not evidence of a live PID`() {
        // Encodes the reasoning error the accessory-mode report invited: a
        // PID that answers 00 00 is present and dead, which reads identically
        // to present and idle-at-zero. Only a non-zero reading with the
        // engine turning distinguishes them.
        val deadFrame = listOf(0x00, 0x00)
        assertEquals(0.0, ObdPid.decodeEngineFuelRateKgPerSec(deadFrame))
        // Decoding succeeds — which is exactly why success must not be read
        // as confirmation.
        assertTrue(ObdPid.decodeEngineFuelRateKgPerSec(deadFrame) != null)
    }

    // --- Timing advance: the octane question, made measurable ---

    @Test
    fun `timing advance decodes with the correct offset`() {
        // A/2 - 64. Byte 128 is zero advance (TDC), which is what the ND2
        // reported at idle.
        assertEquals(0.0, ObdPid.decodeTimingAdvanceDegrees(listOf(128))!!, 0.01)
        // Byte 178 = 25 degrees BTDC, a normal cruise figure.
        assertEquals(25.0, ObdPid.decodeTimingAdvanceDegrees(listOf(178))!!, 0.01)
    }

    @Test
    fun `retarded timing decodes as negative`() {
        // The knock sensor CAN pull timing past TDC under load. If this
        // clamped at zero the very signal we want would be invisible.
        val retarded = ObdPid.decodeTimingAdvanceDegrees(listOf(100))!!
        assertTrue(retarded < 0.0, "expected negative advance, got $retarded")
    }

    @Test
    fun `timing advance rejects an empty frame`() {
        assertNull(ObdPid.decodeTimingAdvanceDegrees(emptyList()))
    }

    @Test
    fun `less advance means less work from the same fuel`() {
        // The mechanism behind "premium gives better mileage" on a 13:1
        // engine: octane does not change the fuel's energy, it changes how
        // much of it the engine extracts. Pinned as a relationship rather
        // than a magic number -- thermal efficiency is what carries it.
        val speed = Units.mphToMps(60.0)
        val flow = Units.gallonsToKg(Units.metersToMiles(speed) / 34.0)
        val load = 400.0

        val efficient = Thermodynamics.thermalEfficiency(load, speed, flow)
        // Same road load, more fuel burned for it -- what retarded timing
        // costs you.
        val degraded = Thermodynamics.thermalEfficiency(load, speed, flow * 1.04)

        assertTrue(
            degraded < efficient,
            "burning more fuel for the same work must lower efficiency"
        )
    }

}
