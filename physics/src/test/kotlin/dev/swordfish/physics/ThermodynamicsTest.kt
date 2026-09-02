package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the mechanical picture (force per unit fuel weight-flow) and
 * the chemical picture (thermal efficiency times heating value) describe the
 * same vehicle.
 *
 * This is the answer to "a car is not a rocket — are we accounting for the
 * chemical energy conversion properly?" If these tests pass, the Isp figure is
 * not a borrowed rocket number but a thermodynamically grounded one.
 */
class ThermodynamicsTest {

    private val car = Vehicle.ND2_CLUB

    /** The reference operating point: 65 mph, 34 mpg, flat road. */
    private val cruiseSpeed = Units.mphToMps(65.0)
    private val cruiseFlow = Units.gallonsToKg(Units.metersToMiles(cruiseSpeed) / 34.0)
    private val cruiseLoad = DeltaVModel.roadLoadNewtons(
        car, car.totalMassKg(Units.gallonsToKg(11.9)), cruiseSpeed, 0.0
    )

    // --- The identity that matters ---

    @Test
    fun `force-based and energy-based Isp agree exactly`() {
        // The central claim: computing Isp from road load and fuel flow gives
        // the same answer as computing it from thermal efficiency and the
        // fuel's heating value. Two independent routes, one number.
        val mechanical = DeltaVModel.effectiveIsp(cruiseLoad, cruiseFlow, cruiseSpeed)

        val eta = Thermodynamics.thermalEfficiency(cruiseLoad, cruiseSpeed, cruiseFlow)
        val chemical = Thermodynamics.ispFromEnergy(eta, cruiseSpeed)

        assertEquals(mechanical, chemical, mechanical * 1e-9)
    }

    @Test
    fun `implied thermal efficiency is realistic for a gasoline engine`() {
        // ~21% tank-to-wheel at light-load cruise. If this drifts outside
        // 15-35%, either the road-load model or the fuel-flow path is wrong,
        // because no naturally aspirated petrol engine sits outside that band.
        val eta = Thermodynamics.thermalEfficiency(cruiseLoad, cruiseSpeed, cruiseFlow)
        assertTrue(eta in 0.15..0.35, "tank-to-wheel efficiency = ${eta * 100}%")
    }

    @Test
    fun `most of the fuel's energy is wasted, as it must be`() {
        val useful = Thermodynamics.usefulPowerWatts(cruiseLoad, cruiseSpeed)
        val chem = Thermodynamics.chemicalPowerWatts(cruiseFlow)
        val wasted = Thermodynamics.wastedPowerWatts(cruiseLoad, cruiseSpeed, cruiseFlow)

        assertEquals(chem - useful, wasted, 1.0)
        assertTrue(wasted > useful * 2, "an ICE should throw away most of it")
        // ~13 kW to the road out of ~65 kW burned.
        assertTrue(useful / 1000.0 in 8.0..20.0, "useful = ${useful / 1000} kW")
    }

    // --- The speed dependence ---

    @Test
    fun `Isp falls with speed even at constant efficiency`() {
        // Isp = eta*LHV/(v*g0), so doubling speed halves Isp at fixed
        // efficiency. This is why hypermilers slow down, and it means the
        // instrument rewards reducing speed, not just feathering the throttle.
        val eta = 0.25
        val at25 = Thermodynamics.ispAtConstantEfficiency(eta, Units.mphToMps(25.0))
        val at50 = Thermodynamics.ispAtConstantEfficiency(eta, Units.mphToMps(50.0))
        assertEquals(2.0, at25 / at50, 0.01)
    }

    @Test
    fun `the speed-Isp table in the docs is accurate`() {
        val eta = 0.25
        fun ispAt(mph: Double) =
            Thermodynamics.ispAtConstantEfficiency(eta, Units.mphToMps(mph))

        assertEquals(99_000.0, ispAt(25.0), 1_000.0)
        assertEquals(55_000.0, ispAt(45.0), 1_000.0)
        assertEquals(38_000.0, ispAt(65.0), 1_000.0)
        assertEquals(29_000.0, ispAt(85.0), 1_000.0)
    }

    @Test
    fun `real-world speed penalty is steeper than the constant-efficiency one`() {
        // Because road load also grows with v^2, going faster costs more than
        // the 1/v relationship alone suggests.
        val mass = car.totalMassKg(Units.gallonsToKg(11.9))
        fun realIsp(mph: Double, mpg: Double): Double {
            val v = Units.mphToMps(mph)
            val load = DeltaVModel.roadLoadNewtons(car, mass, v, 0.0)
            val flow = Units.gallonsToKg(Units.metersToMiles(v) / mpg)
            return DeltaVModel.effectiveIsp(load, flow, v)
        }
        // A real car also loses economy at speed: ~38 mpg at 45, ~30 at 80.
        val slow = realIsp(45.0, 38.0)
        val fast = realIsp(80.0, 30.0)
        assertTrue(slow > fast, "slower must win: $slow vs $fast")
    }

    // --- Degenerate inputs ---

    @Test
    fun `efficiency and energy Isp are zero rather than infinite when stopped`() {
        assertEquals(0.0, Thermodynamics.ispFromEnergy(0.25, 0.0), 1e-9)
        assertEquals(0.0, Thermodynamics.thermalEfficiency(400.0, 0.0, 0.001), 1e-9)
    }

    @Test
    fun `no fuel flow yields zero efficiency, not a divide by zero`() {
        val eta = Thermodynamics.thermalEfficiency(400.0, 25.0, 0.0)
        assertEquals(0.0, eta, 1e-9)
        assertTrue(eta.isFinite())
    }

    @Test
    fun `negative road load does not produce negative efficiency`() {
        // Steep descent: gravity more than covers drag.
        assertEquals(0.0, Thermodynamics.thermalEfficiency(-200.0, 25.0, 0.001), 1e-9)
    }

    @Test
    fun `wasted power is never negative`() {
        // Guard against a rounding or sign error implying an engine that
        // produces more work than the fuel contains.
        val w = Thermodynamics.wastedPowerWatts(1_000_000.0, 100.0, 1e-9)
        assertTrue(w >= 0.0)
    }

    // --- Using efficiency to validate the road-load model ---

    @Test
    fun `the reference operating point passes the plausibility check`() {
        // ~21% at 65 mph cruise. Landing inside the plausible band on the
        // first attempt is weak positive evidence that Cd, A and Crr are
        // about right for this car.
        assertTrue(
            Thermodynamics.roadLoadPlausibility(cruiseLoad, cruiseSpeed, cruiseFlow),
            "implied eta = ${Thermodynamics.thermalEfficiency(cruiseLoad, cruiseSpeed, cruiseFlow)}"
        )
    }

    @Test
    fun `a badly wrong road load model would be caught`() {
        // The check earns its keep only if it actually rejects bad inputs.
        // Halving or doubling road load must fall outside the plausible band.
        assertTrue(
            !Thermodynamics.roadLoadPlausibility(cruiseLoad * 0.5, cruiseSpeed, cruiseFlow),
            "half the road load should imply an implausibly low efficiency"
        )
        assertTrue(
            !Thermodynamics.roadLoadPlausibility(cruiseLoad * 2.0, cruiseSpeed, cruiseFlow),
            "double the road load should imply an implausibly high efficiency"
        )
    }

    @Test
    fun `efficiency scales linearly with the road-load estimate`() {
        // Documents the sensitivity: eta inherits all of the road-load
        // model's error, one for one.
        val base = Thermodynamics.thermalEfficiency(cruiseLoad, cruiseSpeed, cruiseFlow)
        val thirtyPctHigh = Thermodynamics.thermalEfficiency(
            cruiseLoad * 1.3, cruiseSpeed, cruiseFlow
        )
        assertEquals(1.3, thirtyPctHigh / base, 1e-9)
    }

    @Test
    fun `implied road load inverts the efficiency calculation`() {
        val eta = Thermodynamics.thermalEfficiency(cruiseLoad, cruiseSpeed, cruiseFlow)
        val backToF = Thermodynamics.impliedRoadLoadNewtons(eta, cruiseSpeed, cruiseFlow)
        assertEquals(cruiseLoad, backToF, cruiseLoad * 1e-9)
    }

    @Test
    fun `the plausible efficiency band brackets a believable road load`() {
        // Feeding the ends of the band gives the range of road loads
        // consistent with the observed burn. The drag model's 460 N should
        // sit inside it.
        val low = Thermodynamics.impliedRoadLoadNewtons(0.15, cruiseSpeed, cruiseFlow)
        val high = Thermodynamics.impliedRoadLoadNewtons(0.35, cruiseSpeed, cruiseFlow)
        assertTrue(cruiseLoad in low..high, "$cruiseLoad N not in $low..$high N")
    }

    // --- Cross-check against the whole model ---

    @Test
    fun `the full model's Isp is thermodynamically consistent at every speed`() {
        // Sweep the usable range and confirm the two derivations never diverge.
        val mass = car.totalMassKg(Units.gallonsToKg(11.9))
        for (mph in listOf(25.0, 35.0, 45.0, 55.0, 65.0, 75.0)) {
            val v = Units.mphToMps(mph)
            val load = DeltaVModel.roadLoadNewtons(car, mass, v, 0.0)
            val flow = Units.gallonsToKg(Units.metersToMiles(v) / 34.0)

            val mechanical = DeltaVModel.effectiveIsp(load, flow, v)
            val eta = Thermodynamics.thermalEfficiency(load, v, flow)
            val chemical = Thermodynamics.ispFromEnergy(eta, v)

            val relErr = abs(mechanical - chemical) / mechanical
            assertTrue(relErr < 1e-9, "at $mph mph: $mechanical vs $chemical")
        }
    }
}
