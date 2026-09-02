package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests pinned to real values observed from the 2023 MX-5 ND2 Club on
 * 2026-08-19 via an Ancel AD310 (protocol: CAN OBD-II, Cal ID
 * PG5XEC000PEP6010).
 *
 * These are regression guards against the *car*, not just the code. If one
 * fails after a refactor, the model has drifted away from the only ground
 * truth available. See docs/VEHICLE_SURVEY.md for the full survey.
 *
 * Observed at warm idle, stationary:
 *   RPM 784, VSS 0 km/h, MAF 2.31 g/s, MAP 29 kPa, BARO 100-101 kPa,
 *   IAT 45 C, AAT 42 C, ECT 49 C, TP 12.5%, LOAD_PCT 23.5%,
 *   LOAD_ABS 17.3%, SHRTFT1 +6.1%, LONGFT1 +5.5%, EQ_RAT 1.028-1.029,
 *   FLI 83.1%, FRP 10070 kPa, VPWR 13.649 V
 */
class Nd2ObservedTest {

    private val car = Vehicle.ND2_CLUB
    private val caps = VehicleCapabilities.ND2_2023_OBSERVED

    // --- Capability profile ---

    @Test
    fun `nd2 does not support engine fuel rate`() {
        // The single most consequential finding of the survey: the MAF path
        // is not a fallback on this car, it is the ONLY path.
        assertFalse(caps.hasEngineFuelRate)
        assertTrue(caps.canComputeFuelFlow, "MAF must carry the load instead")
        assertTrue(caps.describe().contains("MAF-derived"))
    }

    @Test
    fun `nd2 supports everything needed to correct the maf path`() {
        assertTrue(caps.hasMaf)
        assertTrue(caps.hasEquivalenceRatio, "EQ_RAT observed at 1.028")
        assertTrue(caps.hasFuelTrims, "SHRTFT1/LONGFT1 observed")
        assertTrue(caps.canCorrectMafMixture)
        assertTrue(caps.describe().contains("mixture-corrected"))
    }

    @Test
    fun `nd2 reports tank level so no manual fill-up flow is needed`() {
        // FLI observed at 83.1%, with one decimal place.
        assertTrue(caps.hasFuelLevel)
        assertFalse(caps.needsManualFuelReset)
    }

    @Test
    fun `nd2 reports its own barometer and both air temps`() {
        // BARO 101 kPa means we can skip phone-barometer drift entirely for
        // the air-density correction. AAT gives true outside air, which is
        // what drag should use -- IAT reads 45 C from underhood heat soak.
        assertTrue(caps.hasBarometricPressure)
        assertTrue(caps.hasIntakeAirTemp)
        assertTrue(caps.hasAmbientAirTemp)
    }

    // --- The observed idle sample, decoded end to end ---

    @Test
    fun `observed idle maf implies a plausible fuel burn`() {
        // 2.31 g/s of air at stoichiometric is ~0.157 g/s of fuel, which is
        // about 0.20 gal/h. A warm 2.0L four idles at roughly 0.2-0.4 gal/h,
        // so the MAF path lands correctly at the efficient end of that band.
        val fuelKgS = Units.mafToFuelKgPerSec(2.31)
        val galPerHour = Units.kgToGallons(fuelKgS) * 3600.0
        assertTrue(galPerHour in 0.15..0.45, "idle burn = $galPerHour gal/h")
    }

    @Test
    fun `observed trims and lambda raise the idle burn estimate`() {
        // The ECU was adding 11.6% via trims while commanding slightly lean
        // (lambda 1.028). Net effect must still be an increase over naive.
        val naive = Units.mafToFuelKgPerSec(2.31)
        val corrected = Units.mafToFuelKgPerSecCorrected(
            mafGramsPerSec = 2.31,
            lambda = 1.028,
            shortTrimPercent = 6.1,
            longTrimPercent = 5.5
        )
        assertTrue(corrected > naive, "trims should add fuel")
        // Lean lambda removes ~2.7%, trims add 11.6%: net ~+8.6%.
        assertEquals(1.086, corrected / naive, 0.01)
    }

    @Test
    fun `enrichment under load raises fuel flow well above the naive figure`() {
        // The failure mode the correction exists to fix. At WOT the ECU
        // commands lambda ~0.80; an uncorrected model would under-report
        // consumption by a fifth, flattering exactly the driving the game
        // is supposed to penalise.
        val naive = Units.mafToFuelKgPerSec(80.0)
        val enriched = Units.mafToFuelKgPerSecCorrected(80.0, lambda = 0.80)
        assertEquals(1.25, enriched / naive, 0.01)
    }

    @Test
    fun `corrected maf falls back to naive when no mixture data is given`() {
        assertEquals(
            Units.mafToFuelKgPerSec(20.0),
            Units.mafToFuelKgPerSecCorrected(20.0),
            1e-12
        )
    }

    @Test
    fun `idle produces zero isp because no useful work is being done`() {
        // Stationary at 784 rpm burning 0.2 gal/h: fuel flows, the car goes
        // nowhere. Isp must be zero, not a huge or infinite number.
        val t = Telemetry(
            speedMps = 0.0,
            rpm = 784.0,
            fuelFlowKgPerSec = Units.mafToFuelKgPerSec(2.31),
            fuelRemainingKg = Units.gallonsToKg(11.9 * 0.831)
        )
        val r = DeltaVModel.compute(car, t)
        assertEquals(0.0, r.effectiveIsp, 1e-9)
        assertFalse(r.inDeceleratingFuelCutoff, "engine is running, not coasting")

        // But the BUDGET must not vanish. Observed on the 2026-08-21 drive:
        // delta-V read 0 at every light, which is wrong -- a KSP jet on the
        // runway shows its full delta-V because the figure describes the
        // FUEL IN THE TANK, not what the engine is doing this second.
        assertTrue(
            r.deltaVRemaining > 1000.0,
            "idling must not empty the delta-V budget, got ${r.deltaVRemaining}"
        )
    }

    @Test
    fun `observed fuel level maps to a sensible tank quantity`() {
        // FLI 83.1% of 11.9 gal = 9.89 gal aboard, ~61 lb of propellant.
        val fuelKg = Units.gallonsToKg(11.9 * 0.831)
        assertEquals(9.89, Units.kgToGallons(fuelKg), 0.05)
        assertEquals(61.0, Units.kgToLb(fuelKg), 1.0)
    }

    @Test
    fun `observed barometer corresponds to near sea level`() {
        // BARO 101 kPa = 1010 hPa. this area is roughly 30-60 m up,
        // so this is the expected reading and confirms the decode is right.
        val g = GradeEstimator()
        val alt = g.pressureToAltitudeM(1010.0)
        assertTrue(alt in -50.0..150.0, "implied altitude = $alt m")
    }

    // --- Decoder round-trips against the observed values ---

    @Test
    fun `decoders reproduce the observed idle readings`() {
        // RPM 784 -> raw 3136 = 0x0C40
        assertEquals(784.0, ObdPid.decodeRpm(listOf(0x0C, 0x40))!!, 0.01)
        // MAF 2.31 g/s -> raw 231 = 0x00E7
        assertEquals(2.31, ObdPid.decodeMafGramsPerSec(listOf(0x00, 0xE7))!!, 0.01)
        // MAP 29 kPa -> raw 29
        assertEquals(29.0, ObdPid.decodeMapKpa(listOf(29))!!, 0.01)
        // IAT 45 C -> raw 85
        assertEquals(45.0, ObdPid.decodeIntakeAirTempC(listOf(85))!!, 0.01)
        // AAT 42 C -> raw 82
        assertEquals(42.0, ObdPid.decodeAmbientAirTempC(listOf(82))!!, 0.01)
        // BARO 101 kPa -> raw 101, reported as hPa
        assertEquals(1010.0, ObdPid.decodeBarometricHpa(listOf(101))!!, 0.01)
    }

    @Test
    fun `fuel trim decoder handles the observed positive trims`() {
        // +6.1% -> A = (6.1 + 100) * 1.28 = 135.8 -> 136 rounds to +6.25%
        val trim = ObdPid.decodeFuelTrimPercent(listOf(136))!!
        assertEquals(6.25, trim, 0.1)
        // Zero trim is A = 128.
        assertEquals(0.0, ObdPid.decodeFuelTrimPercent(listOf(128))!!, 0.01)
        // Negative trims must decode below zero.
        assertTrue(ObdPid.decodeFuelTrimPercent(listOf(100))!! < 0.0)
    }

    @Test
    fun `equivalence ratio decoder reproduces the observed lambda`() {
        // lambda 1.028 -> raw = 1.028 * 65536 / 2 = 33685 = 0x8395
        val lambda = ObdPid.decodeEquivalenceRatio(listOf(0x83, 0x95))!!
        assertEquals(1.028, lambda, 0.001)
        // Stoichiometric is raw 32768 = 0x8000.
        assertEquals(1.0, ObdPid.decodeEquivalenceRatio(listOf(0x80, 0x00))!!, 0.001)
    }

    @Test
    fun `fuel level decoder reproduces the observed 83 percent`() {
        // 83.1% -> A = 0.831 * 255 = 211.9 -> 212
        val frac = ObdPid.decodeFuelLevelFraction(listOf(212))!!
        assertEquals(0.831, frac, 0.005)
    }

    // --- Air density from vehicle sensors ---

    @Test
    fun `vehicle baro and ambient temp give a usable air density`() {
        // Using AAT 42 C rather than IAT 45 C: intake air is heat-soaked by
        // the engine bay and is not what the car is pushing through.
        val g = GradeEstimator()
        val alt = g.pressureToAltitudeM(1010.0)
        val rho = g.airDensity(alt, temperatureC = 42.0)
        // Hot day near sea level: thinner than the 1.225 standard.
        assertTrue(rho in 1.05..1.20, "air density = $rho kg/m3")
        assertTrue(rho < 1.225, "42 C air must be thinner than 15 C standard")
    }

    @Test
    fun `hot ambient air measurably reduces drag`() {
        // A small but real effect, and a nice touch of realism: the same
        // speed costs less on a hot day.
        val g = GradeEstimator()
        val rhoHot = g.airDensity(0.0, temperatureC = 42.0)
        val rhoStd = g.airDensity(0.0, temperatureC = 15.0)
        val speed = Units.mphToMps(60.0)
        val dragHot = DeltaVModel.aeroDragNewtons(car, speed, rhoHot)
        val dragStd = DeltaVModel.aeroDragNewtons(car, speed, rhoStd)
        assertTrue(dragHot < dragStd)
        assertTrue((dragStd - dragHot) / dragStd in 0.05..0.12)
    }
}
