package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ObdPidTest {

    // --- Frame parsing: the messy real-world cases ---

    @Test
    fun `parses a clean spaced response`() {
        val data = ObdPid.extractDataBytes("41 0C 1A F8", ObdPid.ENGINE_RPM)
        assertEquals(listOf(0x1A, 0xF8), data)
    }

    @Test
    fun `parses an unspaced response`() {
        // Some adapters omit the spaces entirely.
        val data = ObdPid.extractDataBytes("410C1AF8", ObdPid.ENGINE_RPM)
        assertEquals(listOf(0x1A, 0xF8), data)
    }

    @Test
    fun `strips the ELM327 prompt and line endings`() {
        val data = ObdPid.extractDataBytes("41 0C 1A F8\r\r>", ObdPid.ENGINE_RPM)
        assertEquals(listOf(0x1A, 0xF8), data)
    }

    @Test
    fun `strips a SEARCHING notice preceding the payload`() {
        // The adapter emits this on the first query after connecting.
        val data = ObdPid.extractDataBytes("SEARCHING...\r41 0C 1A F8", ObdPid.ENGINE_RPM)
        assertEquals(listOf(0x1A, 0xF8), data)
    }

    @Test
    fun `finds the payload past a multi-ECU header prefix`() {
        // With headers on, the ECU address precedes the response bytes.
        val data = ObdPid.extractDataBytes("7E8 03 41 0C 1A F8", ObdPid.ENGINE_RPM)
        assertEquals(listOf(0x1A, 0xF8), data)
    }

    @Test
    fun `returns null for NO DATA`() {
        assertNull(ObdPid.extractDataBytes("NO DATA", ObdPid.ENGINE_FUEL_RATE))
    }

    @Test
    fun `returns null when the echoed pid does not match`() {
        // Asked for RPM, got a speed frame -- a real hazard when responses
        // arrive out of order on a busy bus.
        assertNull(ObdPid.extractDataBytes("41 0D 45", ObdPid.ENGINE_RPM))
    }

    @Test
    fun `returns null on an empty or garbage frame`() {
        assertNull(ObdPid.extractDataBytes("", ObdPid.ENGINE_RPM))
        assertNull(ObdPid.extractDataBytes("?", ObdPid.ENGINE_RPM))
        assertNull(ObdPid.extractDataBytes("STOPPED", ObdPid.ENGINE_RPM))
    }

    // --- Decoders, checked against the OBD-II spec formulas ---

    @Test
    fun `decodes rpm as quarter counts`() {
        // 0x1AF8 = 6904; /4 = 1726 rpm
        assertEquals(1726.0, ObdPid.decodeRpm(listOf(0x1A, 0xF8))!!, 0.01)
    }

    @Test
    fun `decodes idle rpm`() {
        // 0x0BB8 = 3000; /4 = 750 rpm
        assertEquals(750.0, ObdPid.decodeRpm(listOf(0x0B, 0xB8))!!, 0.01)
    }

    @Test
    fun `decodes speed from km per hour to meters per second`() {
        // 0x64 = 100 km/h = 27.78 m/s
        assertEquals(27.78, ObdPid.decodeSpeedMps(listOf(0x64))!!, 0.01)
    }

    @Test
    fun `decodes maf in grams per second`() {
        // 0x0BB8 = 3000; /100 = 30 g/s
        assertEquals(30.0, ObdPid.decodeMafGramsPerSec(listOf(0x0B, 0xB8))!!, 0.01)
    }

    @Test
    fun `decodes engine fuel rate to kilograms per second`() {
        // 0x00C8 = 200; *0.05 = 10 L/h
        val kgs = ObdPid.decodeFuelRateKgPerSec(listOf(0x00, 0xC8))!!
        val lph = kgs * 3600.0 / Units.GASOLINE_KG_PER_L
        assertEquals(10.0, lph, 0.01)
    }

    @Test
    fun `decodes fuel level as a fraction`() {
        assertEquals(1.0, ObdPid.decodeFuelLevelFraction(listOf(0xFF))!!, 0.01)
        assertEquals(0.5, ObdPid.decodeFuelLevelFraction(listOf(0x80))!!, 0.01)
        assertEquals(0.0, ObdPid.decodeFuelLevelFraction(listOf(0x00))!!, 0.01)
    }

    @Test
    fun `decodes barometric pressure to hectopascals`() {
        // 101 kPa = 1010 hPa, roughly sea level.
        assertEquals(1010.0, ObdPid.decodeBarometricHpa(listOf(101))!!, 0.01)
    }

    @Test
    fun `decodes throttle position as a percentage`() {
        assertEquals(100.0, ObdPid.decodeThrottlePercent(listOf(0xFF))!!, 0.5)
        assertEquals(0.0, ObdPid.decodeThrottlePercent(listOf(0x00))!!, 0.5)
    }

    @Test
    fun `decodes intake air temperature with its minus forty offset`() {
        assertEquals(0.0, ObdPid.decodeIntakeAirTempC(listOf(40))!!, 0.01)
        assertEquals(20.0, ObdPid.decodeIntakeAirTempC(listOf(60))!!, 0.01)
        assertEquals(-40.0, ObdPid.decodeIntakeAirTempC(listOf(0))!!, 0.01)
    }

    @Test
    fun `decoders reject short payloads rather than reading past the end`() {
        assertNull(ObdPid.decodeRpm(listOf(0x1A)))
        assertNull(ObdPid.decodeMafGramsPerSec(listOf(0x0B)))
        assertNull(ObdPid.decodeFuelRateKgPerSec(listOf(0x00)))
        assertNull(ObdPid.decodeSpeedMps(emptyList()))
    }

    // --- Capability detection ---

    @Test
    fun `decodes a supported-pid bitmask`() {
        // MSB of the first byte is PID 0x01.
        val supported = ObdPid.decodeSupportedPids(listOf(0x80, 0x00, 0x00, 0x00), 0x00)
        assertEquals(setOf(0x01), supported)
    }

    @Test
    fun `decodes a realistic supported-pid mask covering rpm speed and maf`() {
        // 0xBE1FA813 is the mask many cars return to 0100.
        val supported = ObdPid.decodeSupportedPids(
            listOf(0xBE, 0x1F, 0xA8, 0x13), 0x00
        )
        assertTrue(supported.contains(0x0C), "RPM should be supported")
        assertTrue(supported.contains(0x0D), "speed should be supported")
        assertTrue(supported.contains(0x10), "MAF should be supported")
    }

    @Test
    fun `bitmask offsets apply for the second and third pid banks`() {
        // First bit of the 0x20 bank is PID 0x21.
        assertEquals(setOf(0x21), ObdPid.decodeSupportedPids(listOf(0x80, 0, 0, 0), 0x20))
        // And of the 0x40 bank, PID 0x41.
        assertEquals(setOf(0x41), ObdPid.decodeSupportedPids(listOf(0x80, 0, 0, 0), 0x40))
    }

    @Test
    fun `capabilities prefer engine fuel rate when present`() {
        val caps = VehicleCapabilities.fromSupportedPids(setOf(0x0C, 0x0D, 0x10, 0x5E, 0x2F))
        assertTrue(caps.hasEngineFuelRate)
        assertTrue(caps.canComputeFuelFlow)
        assertFalse(caps.needsManualFuelReset)
        assertTrue(caps.describe().contains("015E"))
    }

    @Test
    fun `capabilities fall back to maf when fuel rate is absent`() {
        val caps = VehicleCapabilities.fromSupportedPids(setOf(0x0C, 0x0D, 0x10))
        assertFalse(caps.hasEngineFuelRate)
        assertTrue(caps.hasMaf)
        assertTrue(caps.canComputeFuelFlow)
        // No tank level either, so the user must confirm fill-ups.
        assertTrue(caps.needsManualFuelReset)
        assertTrue(caps.describe().contains("MAF-derived"))
    }

    @Test
    fun `capabilities report when no fuel flow source exists at all`() {
        val caps = VehicleCapabilities.fromSupportedPids(setOf(0x0C, 0x0D))
        assertFalse(caps.canComputeFuelFlow)
        assertTrue(caps.describe().contains("NONE"))
    }

    // --- MAF fallback path ---

    @Test
    fun `maf converts to a plausible fuel flow at highway cruise`() {
        // ~20 g/s of air at a steady 60 mph is about 1.33 g/s of fuel at
        // stoichiometric, which is roughly 35 mpg -- right for an ND2.
        // If this drifts, the MAF fallback path has broken.
        val fuelKgS = Units.mafToFuelKgPerSec(20.0)
        val mpg = DeltaVModel.instantaneousMpg(Units.mphToMps(60.0), fuelKgS)
        assertTrue(mpg in 25.0..45.0, "MAF-derived mpg = $mpg")
    }

    @Test
    fun `maf derived economy degrades as airflow rises`() {
        // More air means more fuel means worse economy -- the monotonicity
        // the fallback path depends on.
        val speed = Units.mphToMps(60.0)
        val cruise = DeltaVModel.instantaneousMpg(speed, Units.mafToFuelKgPerSec(20.0))
        val heavy = DeltaVModel.instantaneousMpg(speed, Units.mafToFuelKgPerSec(40.0))
        assertTrue(heavy < cruise)
    }
}
