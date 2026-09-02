package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryAssemblerTest {

    private val tank = 11.9

    /** A cursor primed with a plausible highway-cruise frame. */
    private fun cruising(now: Long = 1000L): PollCursor {
        val c = PollCursor()
        // 2661 rpm = 10644 quarter-rpm = 0x2994
        c.record(ObdPid.ENGINE_RPM, listOf(0x29, 0x94), now)
        // 105 km/h
        c.record(ObdPid.VEHICLE_SPEED, listOf(105), now)
        // MAF 12.00 g/s = 1200 = 0x04B0
        c.record(ObdPid.MAF_RATE, listOf(0x04, 0xB0), now)
        // lambda 1.0 = 32768 = 0x8000
        c.record(ObdPid.COMMANDED_EQUIV_RATIO, listOf(0x80, 0x00), now)
        c.record(ObdPid.SHORT_FUEL_TRIM_1, listOf(128), now)
        c.record(ObdPid.LONG_FUEL_TRIM_1, listOf(128), now)
        // tank 75%
        c.record(ObdPid.FUEL_LEVEL, listOf(191), now)
        return c
    }

    @Test
    fun `a complete frame assembles into telemetry`() {
        val r = TelemetryAssembler.assemble(cruising(), 1000L, tank)
        val t = r.telemetry
        assertNotNull(t)
        assertEquals(2661.0, t!!.rpm, 1.0)
        assertEquals(29.17, t.speedMps, 0.1)
        assertTrue(t.fuelFlowKgPerSec!! > 0.0)
    }

    @Test
    fun `missing speed or rpm yields no telemetry at all`() {
        // Without them there is no road load, no gear, and no Isp. Returning
        // a partial sample would render zeros as though they were measured.
        val c = PollCursor()
        c.record(ObdPid.MAF_RATE, listOf(0x04, 0xB0), 1000L)
        val r = TelemetryAssembler.assemble(c, 1000L, tank)
        assertNull(r.telemetry)
        assertTrue(r.missing.contains(ObdPid.VEHICLE_SPEED))
        assertTrue(r.missing.contains(ObdPid.ENGINE_RPM))
    }

    @Test
    fun `stale readings are treated as missing`() {
        // The whole point of the staleness clock: a frozen value must not
        // reach the model.
        val c = cruising(0L)
        val r = TelemetryAssembler.assemble(c, 60_000L, tank)
        assertNull(r.telemetry)
    }

    @Test
    fun `mixture correction is applied when lambda and trims are fresh`() {
        val r = TelemetryAssembler.assemble(cruising(), 1000L, tank)
        assertTrue(r.usedMixtureCorrection)
    }

    @Test
    fun `enrichment raises fuel flow above the stoichiometric estimate`() {
        // The correction that matters. A naive MAF/14.7 under-reports by up
        // to 25% under WOT enrichment, flattering exactly the driving the
        // game exists to penalise.
        val now = 1000L
        val lean = cruising(now)
        val rich = cruising(now).apply {
            // lambda 0.8 = 26214 = 0x6666
            record(ObdPid.COMMANDED_EQUIV_RATIO, listOf(0x66, 0x66), now)
        }

        val leanFlow = TelemetryAssembler.assemble(lean, now, tank)
            .telemetry!!.fuelFlowKgPerSec!!
        val richFlow = TelemetryAssembler.assemble(rich, now, tank)
            .telemetry!!.fuelFlowKgPerSec!!

        assertTrue(
            richFlow > leanFlow * 1.2,
            "enrichment should raise flow markedly: $richFlow vs $leanFlow"
        )
    }

    @Test
    fun `absent mixture data degrades rather than failing`() {
        // A slightly optimistic Isp beats no Isp, and the slow tier
        // refreshes within a second.
        val c = PollCursor()
        val now = 1000L
        c.record(ObdPid.ENGINE_RPM, listOf(0x29, 0x94), now)
        c.record(ObdPid.VEHICLE_SPEED, listOf(105), now)
        c.record(ObdPid.MAF_RATE, listOf(0x04, 0xB0), now)

        val r = TelemetryAssembler.assemble(c, now, tank)
        assertNotNull(r.telemetry)
        assertTrue(r.telemetry!!.fuelFlowKgPerSec!! > 0.0)
        assertFalse(r.usedMixtureCorrection)
    }

    @Test
    fun `tank percentage converts to a fuel mass`() {
        val t = TelemetryAssembler.assemble(cruising(), 1000L, tank).telemetry!!
        val gallons = Units.kgToGallons(t.fuelRemainingKg)
        assertTrue(gallons in 8.5..9.2, "75% of 11.9 gal expected, got $gallons")
    }

    @Test
    fun `idle assembles with near-zero flow and zero speed`() {
        // The neutral case, end to end: it must produce a valid telemetry
        // sample rather than being rejected as incomplete.
        val c = PollCursor()
        val now = 1000L
        // 780 rpm = 3120 quarter-rpm = 0x0C30
        c.record(ObdPid.ENGINE_RPM, listOf(0x0C, 0x30), now)
        c.record(ObdPid.VEHICLE_SPEED, listOf(0), now)
        // MAF 2.31 g/s = 231 = 0x00E7 -- the documented ND2 idle figure
        c.record(ObdPid.MAF_RATE, listOf(0x00, 0xE7), now)
        c.record(ObdPid.FUEL_LEVEL, listOf(191), now)

        val t = TelemetryAssembler.assemble(c, now, tank).telemetry
        assertNotNull(t)
        assertEquals(0.0, t!!.speedMps)
        assertEquals(780.0, t.rpm, 1.0)

        // And it should reproduce the calibration point.
        val galPerHour = Units.kgToGallons(t.fuelFlowKgPerSec!!) * 3600.0
        assertTrue(
            galPerHour in 0.17..0.23,
            "expected ~0.20 gal/h at idle, got $galPerHour"
        )
    }

    // --- Air density ---

    @Test
    fun `air density falls back to sea level without baro or temp`() {
        val c = PollCursor()
        assertEquals(
            DeltaVModel.RHO_SEA_LEVEL,
            TelemetryAssembler.airDensity(c, 1000L)
        )
    }

    @Test
    fun `air density is computed from baro and ambient temp`() {
        val c = PollCursor()
        val now = 1000L
        c.record(ObdPid.BAROMETRIC_PRESSURE, listOf(101), now)  // 1010 hPa
        c.record(ObdPid.AMBIENT_AIR_TEMP, listOf(55), now)      // 15 C
        val rho = TelemetryAssembler.airDensity(c, now)
        assertTrue(rho in 1.18..1.26, "expected ~1.22 kg/m3, got $rho")
    }

    @Test
    fun `hot air is less dense than cold air`() {
        val now = 1000L
        fun at(tempByte: Int): Double {
            val c = PollCursor()
            c.record(ObdPid.BAROMETRIC_PRESSURE, listOf(101), now)
            c.record(ObdPid.AMBIENT_AIR_TEMP, listOf(tempByte), now)
            return TelemetryAssembler.airDensity(c, now)
        }
        // 0 C vs 40 C
        assertTrue(at(40) > at(80))
    }

    @Test
    fun `air density uses ambient not intake air temperature`() {
        // Intake air is heat-soaked by the engine bay -- 45 C observed
        // against 42 C true ambient, widening after a hard run. Feeding IAT
        // into a density calculation would systematically under-report drag.
        val c = PollCursor()
        val now = 1000L
        c.record(ObdPid.BAROMETRIC_PRESSURE, listOf(101), now)
        c.record(ObdPid.INTAKE_AIR_TEMP, listOf(85), now)  // 45 C, ignored

        // AAT absent, so it must fall back rather than reach for IAT.
        assertEquals(
            DeltaVModel.RHO_SEA_LEVEL,
            TelemetryAssembler.airDensity(c, now)
        )
    }

    @Test
    fun `a recorded row can be reconstructed from assembled telemetry`() {
        // The drive recorder writes what assemble() produces. If the
        // assembler yields null the row carries only link state, which is
        // still worth recording -- that is how a failed connect is
        // diagnosed after the fact.
        val c = PollCursor()
        val r = TelemetryAssembler.assemble(c, 1000L, tank)
        assertNull(r.telemetry)
        assertTrue(r.missing.isNotEmpty(), "a null sample must say what was missing")
    }

    @Test
    fun `a stale tank reading is reported as unknown, not as half a tank`() {
        // The assembler used to substitute 0.5 for a missing fuel level, so
        // a stale PID told the model the car had ~25.8 kg aboard. On the
        // 2026-08-22 evening drive that fired 24 times and threw the
        // delta-V budget from ~4,000 to 7,273 m/s -- an upward spike on a
        // gauge whose whole promise is that it only drains.
        //
        // tankLevelFraction must be NULL so the caller can hold the last
        // known value instead of accepting a fabricated one.
        // Speed and rpm ARE present -- otherwise the assembler returns
        // early and the test would pass without exercising the tank path.
        val now = 1_000L
        val cursor = PollCursor()
        cursor.record(ObdPid.ENGINE_RPM, listOf(0x29, 0x94), now)
        cursor.record(ObdPid.VEHICLE_SPEED, listOf(105), now)
        // Deliberately never feed FUEL_LEVEL.
        val r = TelemetryAssembler.assemble(cursor, now, tankCapacityGallons = 11.9)
        assertNotNull(r.telemetry, "test setup: assembler returned no telemetry")

        assertNull(
            r.tankLevelFraction,
            "a missing tank reading must be null, not a guess"
        )
    }

    @Test
    fun `a fresh tank reading is passed through`() {
        // The other half: when the PID IS fresh, the caller gets the real
        // fraction so it can seed and correct its tracker.
        // cruising() already primes speed and rpm, which the assembler
        // requires before it reads anything else.
        val now = 1_000L
        val cursor = cruising(now)

        val r = TelemetryAssembler.assemble(cursor, now, tankCapacityGallons = 11.9)
        assertNotNull(r.tankLevelFraction, "a fresh tank reading was dropped")
    }
}
