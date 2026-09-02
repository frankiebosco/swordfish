package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConditionsTest {

    private val car = Vehicle.ND2_CLUB
    private val mass = car.totalMassKg(Units.gallonsToKg(11.9))
    private val cruise = Units.mphToMps(65.0)

    // --- Air density, which is genuinely measured on this car ---

    @Test
    fun `air density matches the standard atmosphere at reference conditions`() {
        val rho = Conditions.airDensity(15.0, 101.325)
        assertEquals(1.225, rho, 0.005)
    }

    @Test
    fun `cold air is denser and costs more drag`() {
        val cold = Conditions.airDensity(-10.0, 101.0)
        val hot = Conditions.airDensity(40.0, 101.0)
        assertTrue(cold > hot)
        // ~19% denser at -10C than at 40C.
        assertTrue((cold / hot - 1.0) in 0.15..0.25, "ratio = ${cold / hot}")
    }

    @Test
    fun `the observed survey conditions give a plausible density`() {
        // From VEHICLE_SURVEY: AAT 42C, BARO 101 kPa.
        val rho = Conditions.airDensity(42.0, 101.0)
        assertTrue(rho in 1.05..1.20, "rho = $rho")
        assertTrue(rho < 1.225, "a hot day must be thinner than standard")
    }

    // --- Temperature effect on rolling resistance ---

    @Test
    fun `cold tyres roll worse than warm ones`() {
        assertTrue(Conditions.crrTemperatureFactor(-10.0) > 1.0)
        assertTrue(Conditions.crrTemperatureFactor(40.0) < 1.0)
        assertEquals(1.0, Conditions.crrTemperatureFactor(20.0), 1e-9)
    }

    @Test
    fun `the temperature factor is clamped against absurd inputs`() {
        assertTrue(Conditions.crrTemperatureFactor(-100.0) <= 1.4)
        assertTrue(Conditions.crrTemperatureFactor(200.0) >= 0.7)
    }

    // --- Tyre pressure: the biggest thing a driver controls ---

    @Test
    fun `under-inflation raises rolling resistance`() {
        assertTrue(Conditions.crrPressureFactor(26.0) > 1.0)
        assertTrue(Conditions.crrPressureFactor(38.0) < 1.0)
        assertEquals(1.0, Conditions.crrPressureFactor(32.0), 1e-9)
    }

    @Test
    fun `six psi low costs a few percent of road load`() {
        val correct = Conditions.correctionFor(
            car, cruise, mass, tyrePsi = 32.0
        )
        val flat = Conditions.correctionFor(
            car, cruise, mass, tyrePsi = 26.0
        )
        val penalty = flat.roadLoadMultiplier / correct.roadLoadMultiplier - 1.0
        assertTrue(penalty in 0.02..0.06, "26 psi penalty = ${penalty * 100}%")
    }

    @Test
    fun `a flat tyre does not produce an infinite correction`() {
        val f = Conditions.crrPressureFactor(0.0)
        assertTrue(f.isFinite() && f <= 1.6)
    }

    // --- Wet roads ---

    @Test
    fun `water on the road costs real efficiency`() {
        val dry = Conditions.correctionFor(car, cruise, mass, surface = Conditions.SurfaceState.DRY)
        val wet = Conditions.correctionFor(car, cruise, mass, surface = Conditions.SurfaceState.WET)
        val standing = Conditions.correctionFor(
            car, cruise, mass, surface = Conditions.SurfaceState.STANDING_WATER
        )
        assertTrue(wet.roadLoadMultiplier > dry.roadLoadMultiplier)
        assertTrue(standing.roadLoadMultiplier > wet.roadLoadMultiplier)
        // Wet should cost roughly 8-14% at cruise.
        val wetPenalty = wet.roadLoadMultiplier / dry.roadLoadMultiplier - 1.0
        assertTrue(wetPenalty in 0.05..0.15, "wet penalty = ${wetPenalty * 100}%")
    }

    // --- The headline: how much can conditions swing the numbers? ---

    @Test
    fun `worst and best conditions differ by tens of percent`() {
        val worst = Conditions.correctionFor(
            car, cruise, mass,
            ambientC = -10.0, pressureKpa = 103.0,
            tyrePsi = 26.0, surface = Conditions.SurfaceState.STANDING_WATER
        )
        val best = Conditions.correctionFor(
            car, cruise, mass,
            ambientC = 35.0, pressureKpa = 99.0,
            tyrePsi = 38.0, surface = Conditions.SurfaceState.DRY
        )
        val spread = worst.roadLoadMultiplier / best.roadLoadMultiplier - 1.0
        assertTrue(spread > 0.25, "expected a large spread, got ${spread * 100}%")
    }

    @Test
    fun `conditions feed through to Isp and the mission budget`() {
        // The point of the whole file: on a cold wet day the driver is
        // penalised by the weather, and the panel must be able to say so.
        val flow = Units.gallonsToKg(Units.metersToMiles(cruise) / 34.0)

        fun ispUnder(ambientC: Double, surface: Conditions.SurfaceState): Double {
            val c = Conditions.correctionFor(
                car, cruise, mass, ambientC = ambientC, surface = surface
            )
            val load = DeltaVModel.roadLoadNewtons(
                car, mass, cruise, 0.0, airDensity = c.airDensity
            )
            // Approximate: scale by the rolling-resistance change too.
            return DeltaVModel.effectiveIsp(load * c.roadLoadMultiplier, flow, cruise)
        }

        val mild = ispUnder(20.0, Conditions.SurfaceState.DRY)
        val nasty = ispUnder(-5.0, Conditions.SurfaceState.WET)
        assertTrue(nasty > mild, "worse conditions raise road load, hence computed Isp")
        // The point is that they differ meaningfully.
        assertTrue(nasty / mild > 1.1, "conditions should move Isp by >10%")
    }

    // --- Description strings ---

    @Test
    fun `describe frames the result as environmental, not a judgement`() {
        val costly = Conditions.Correction(1.2, 0.016, 1.15)
        assertTrue(Conditions.describe(costly).contains("costing"))

        val helpful = Conditions.Correction(1.1, 0.010, 0.92)
        assertTrue(Conditions.describe(helpful).contains("helping"))

        val neutral = Conditions.Correction(1.2, 0.012, 1.0)
        assertTrue(Conditions.describe(neutral).contains("neutral"))
    }

    @Test
    fun `reference conditions produce a neutral correction`() {
        val c = Conditions.correctionFor(
            car, cruise, mass,
            ambientC = 15.0, pressureKpa = 101.325,
            tyrePsi = 32.0, surface = Conditions.SurfaceState.DRY
        )
        assertEquals(1.0, c.roadLoadMultiplier, 0.03)
    }
}
