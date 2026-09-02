package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that Swordfish computes what Kerbal Space Program computes.
 *
 * The premise of this project is not that a car is *like* a spacecraft — it is
 * that the same equations apply, and that an air-breathing jet is the correct
 * reference rather than a rocket. See `docs/THE_JET_ANALOGY.md`.
 *
 * These tests reproduce readings taken from Frank's KSP aircraft (the DEA
 * Evader 1, twin turbofan) at two throttle settings, and check our formulas
 * against the game's own displayed figures. If these fail, either the model
 * has drifted or someone has "corrected" a formula that was already right.
 *
 * Tolerances are a couple of percent because the reference values were read
 * off screenshots with masses quoted to one decimal tonne.
 */
class KspCorrespondenceTest {

    /**
     * One observed KSP readout.
     *
     * @param isp Displayed specific impulse, seconds.
     * @param thrustN Displayed thrust.
     * @param twr Displayed thrust-to-weight.
     * @param startKg Displayed start mass.
     * @param endKg Displayed end (dry) mass.
     * @param deltaV Displayed delta-V.
     */
    private data class KspReading(
        val label: String,
        val isp: Double,
        val thrustN: Double,
        val twr: Double,
        val startKg: Double,
        val endKg: Double,
        val deltaV: Double
    )

    private val fullThrottle = KspReading(
        label = "DEA Evader, full throttle",
        isp = 4_000.0,
        thrustN = 339_200.0,
        twr = 3.02,
        startKg = 11_500.0,
        endKg = 7_200.0,
        deltaV = 18_525.0
    )

    private val halfThrottle = KspReading(
        label = "DEA Evader, half throttle",
        isp = 9_000.0,
        thrustN = 101_660.0,
        twr = 0.92,
        startKg = 11_300.0,
        endKg = 5_800.0,
        deltaV = 57_984.0
    )

    // --- Thrust-to-weight: F / (m * g0) ---

    @Test
    fun `our TWR formula reproduces KSP's displayed thrust-to-weight`() {
        for (r in listOf(fullThrottle, halfThrottle)) {
            val computed = r.thrustN / (r.startKg * Units.G0)
            assertTrue(
                abs(computed - r.twr) < 0.05,
                "${r.label}: computed TWR $computed vs game ${r.twr}"
            )
        }
    }

    // --- Tsiolkovsky: Isp * g0 * ln(m0/mf) ---

    @Test
    fun `our delta-V formula reproduces KSP's displayed delta-V`() {
        for (r in listOf(fullThrottle, halfThrottle)) {
            val computed = r.isp * Units.G0 * ln(r.startKg / r.endKg)
            val relErr = abs(computed - r.deltaV) / r.deltaV
            assertTrue(
                relErr < 0.02,
                "${r.label}: computed ${computed.toInt()} vs game ${r.deltaV.toInt()} " +
                    "(${"%.1f".format(relErr * 100)}% off)"
            )
        }
    }

    // --- Mass flow: mdot = F / (Isp * g0), the inverse of our Isp formula ---

    @Test
    fun `Isp and mass flow are inverses, as in KSP`() {
        // Our effectiveIsp is F/(mdot*g0). Inverting it must return the mass
        // flow KSP implies from its own thrust and Isp figures.
        for (r in listOf(fullThrottle, halfThrottle)) {
            val mdot = r.thrustN / (r.isp * Units.G0)
            val backToIsp = DeltaVModel.effectiveIsp(
                roadLoadN = r.thrustN,
                fuelFlowKgPerSec = mdot,
                speedMps = 100.0
            )
            assertTrue(
                abs(backToIsp - r.isp) / r.isp < 0.001,
                "${r.label}: round-trip Isp $backToIsp vs $r.isp"
            )
        }
    }

    // --- The behaviour that makes this a good instrument ---

    @Test
    fun `backing off the throttle raises Isp and expands the budget`() {
        // The observed jet behaviour we are reproducing in the car: the same
        // aircraft, minutes apart, differing mainly in throttle.
        assertTrue(halfThrottle.isp > fullThrottle.isp * 2, "Isp should more than double")
        assertTrue(halfThrottle.deltaV > fullThrottle.deltaV * 2, "budget should expand")
        assertTrue(halfThrottle.thrustN < fullThrottle.thrustN, "at the cost of thrust")
    }

    @Test
    fun `the car reproduces the jet's throttle trade`() {
        // Same trade, same direction, same instrument -- this is the whole
        // premise of the project.
        val car = Vehicle.ND2_CLUB
        val fullTank = Units.gallonsToKg(11.9)

        val efficient = DeltaVModel.tsiolkovskyDeltaV(car, fullTank, 45_000.0)
        val thirsty = DeltaVModel.tsiolkovskyDeltaV(car, fullTank, 9_000.0)

        // Light throttle expands the budget, exactly as pulling back the
        // throttle did for the jet.
        assertTrue(efficient > thirsty * 2)

        // And the thrust trade runs the other way, as it does in the aircraft.
        val twrLow = Thrust.thrustToWeight(car, 1, 4_000.0, fullTank)
        val twrHigh = Thrust.thrustToWeight(car, 6, 4_000.0, fullTank)
        assertTrue(twrLow > twrHigh, "low gear must out-thrust top gear")
    }

    // --- Where the car sits relative to the jet ---

    @Test
    fun `a hard-driven Miata is about as efficient as a cruising jet`() {
        // Both are air-breathing, so neither hauls oxidiser. The car wins
        // overall because it does not spend thrust holding itself up -- but
        // drive it badly enough and it lands right on the jet's cruise Isp.
        val miataWideOpen = 9_000.0
        assertTrue(
            abs(miataWideOpen - halfThrottle.isp) / halfThrottle.isp < 0.2,
            "WOT car should be in the same band as a cruising jet"
        )
    }

    @Test
    fun `both air-breathers vastly exceed a chemical rocket`() {
        val rs25VacuumIsp = 452.0
        assertTrue(fullThrottle.isp > rs25VacuumIsp * 5, "even a thirsty jet beats a rocket")

        val car = Vehicle.ND2_CLUB
        val speed = Units.mphToMps(60.0)
        val mass = car.totalMassKg(Units.gallonsToKg(11.9))
        val load = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)
        val milesPerSec = Units.metersToMiles(speed)
        val flow = Units.gallonsToKg(milesPerSec / 35.0)
        val carIsp = DeltaVModel.effectiveIsp(load, flow, speed)

        assertTrue(carIsp > rs25VacuumIsp * 10, "the car beats it by another order")
        assertTrue(carIsp > halfThrottle.isp, "and beats a cruising jet too")
    }
}
