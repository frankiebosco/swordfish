package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThrustTest {

    private val car = Vehicle.ND2_CLUB
    private val fullTankKg = Units.gallonsToKg(11.9)

    // --- Torque curve shape ---

    @Test
    fun `torque peaks at the rated peak torque rpm`() {
        assertEquals(1.0, Thrust.torqueFraction(Thrust.ND2_PEAK_TORQUE_RPM), 1e-9)
    }

    @Test
    fun `torque falls off either side of the peak`() {
        val peak = Thrust.torqueFraction(4000.0)
        assertTrue(Thrust.torqueFraction(1500.0) < peak, "should be down low")
        assertTrue(Thrust.torqueFraction(7000.0) < peak, "should be down high")
    }

    @Test
    fun `torque curve stays within its floor and ceiling`() {
        for (rpm in 500..7500 step 100) {
            val f = Thrust.torqueFraction(rpm.toDouble())
            assertTrue(f in 0.15..1.0, "rpm $rpm gave $f")
        }
    }

    @Test
    fun `torque is zero at zero rpm`() {
        assertEquals(0.0, Thrust.torqueFraction(0.0), 1e-9)
    }

    @Test
    fun `the engine still makes useful torque across the usable band`() {
        // A shape sanity check: nothing in the normal driving range should
        // collapse to the floor.
        for (rpm in 2000..6000 step 500) {
            assertTrue(Thrust.torqueFraction(rpm.toDouble()) > 0.5,
                "rpm $rpm is implausibly weak")
        }
    }

    // --- Engine torque ---

    @Test
    fun `peak engine torque matches the published figure`() {
        val nm = Thrust.engineTorqueNm(Thrust.ND2_PEAK_TORQUE_RPM)
        // 151 lb-ft = 204.7 Nm
        assertEquals(204.7, nm, 0.5)
    }

    @Test
    fun `partial throttle reduces torque proportionally`() {
        val full = Thrust.engineTorqueNm(4000.0)
        val half = Thrust.engineTorqueNm(4000.0, throttleFraction = 0.5)
        assertEquals(full / 2.0, half, 1e-9)
    }

    @Test
    fun `null throttle means available torque, not zero`() {
        // TWR answers "what could this do", so the default is wide open.
        assertTrue(Thrust.engineTorqueNm(4000.0, null) > 0.0)
    }

    // --- Tractive force ---

    @Test
    fun `tractive force falls with every upshift`() {
        // The core behaviour the readout depends on: taller gear, less thrust.
        val forces = (1..6).map {
            Thrust.tractiveForceNewtons(car, it, 4000.0)
        }
        for (i in 0 until forces.size - 1) {
            assertTrue(forces[i] > forces[i + 1],
                "gear ${i + 1} (${forces[i]} N) should out-pull gear ${i + 2}")
        }
    }

    @Test
    fun `first gear tractive force is in a plausible range`() {
        // ~8500 N at peak torque in first for an ND2. If gearing or the
        // torque figure drifts, this catches it.
        val f = Thrust.tractiveForceNewtons(car, 1, 4000.0)
        assertTrue(f in 7000.0..10000.0, "first gear force = $f N")
    }

    @Test
    fun `no thrust with the clutch in or in neutral`() {
        assertEquals(0.0, Thrust.tractiveForceNewtons(car, null, 4000.0), 1e-9)
    }

    @Test
    fun `out of range gears produce no thrust rather than crashing`() {
        assertEquals(0.0, Thrust.tractiveForceNewtons(car, 0, 4000.0), 1e-9)
        assertEquals(0.0, Thrust.tractiveForceNewtons(car, 7, 4000.0), 1e-9)
        assertEquals(0.0, Thrust.tractiveForceNewtons(car, -1, 4000.0), 1e-9)
    }

    // --- TWR: the headline joke, told accurately ---

    @Test
    fun `twr is always below one because a car cannot hover`() {
        for (gear in 1..6) {
            val twr = Thrust.thrustToWeight(car, gear, 4000.0, fullTankKg)
            assertTrue(twr < 1.0, "gear $gear gave TWR $twr -- cars do not fly")
        }
    }

    @Test
    fun `first gear twr is around three quarters`() {
        val twr = Thrust.thrustToWeight(car, 1, 4000.0, fullTankKg)
        assertEquals(0.77, twr, 0.05)
    }

    @Test
    fun `sixth gear twr is around one seventh`() {
        val twr = Thrust.thrustToWeight(car, 6, 4000.0, fullTankKg)
        assertEquals(0.15, twr, 0.03)
    }

    @Test
    fun `a saturn v out-thrusts the miata even in first gear`() {
        // Pins the comparison the panel draws on.
        val twr = Thrust.thrustToWeight(car, 1, 4000.0, fullTankKg)
        assertTrue(twr < Thrust.SATURN_V_LIFTOFF_TWR)
        assertTrue(twr < Thrust.FALCON_9_LIFTOFF_TWR)
    }

    @Test
    fun `twr falls as the car gets heavier`() {
        val light = Thrust.thrustToWeight(car, 2, 4000.0, fullTankKg)
        val heavy = Thrust.thrustToWeight(
            car.copy(payload = Payload.twoUp(cargoLb = 100.0)),
            2, 4000.0, fullTankKg
        )
        assertTrue(heavy < light)
    }

    @Test
    fun `twr rises slightly as fuel burns off`() {
        val full = Thrust.thrustToWeight(car, 2, 4000.0, fullTankKg)
        val empty = Thrust.thrustToWeight(car, 2, 4000.0, 0.0)
        assertTrue(empty > full, "a lighter ship accelerates harder")
    }

    // --- The trade the panel exists to show ---

    @Test
    fun `twr and isp move in opposite directions across the gears`() {
        // The whole reason both readouts sit side by side: a tall gear buys
        // efficiency at the cost of acceleration. Low gear = high TWR, low
        // Isp. Tall gear = low TWR, high Isp.
        val speed = Units.mphToMps(45.0)
        val mass = car.totalMassKg(fullTankKg)
        val load = DeltaVModel.roadLoadNewtons(car, mass, speed, 0.0)

        // Same road load and speed; the low gear spins faster and burns more.
        val rpmLow = 4500.0
        val rpmHigh = 1800.0
        val flowLow = 0.0016
        val flowHigh = 0.0007

        val twrLow = Thrust.thrustToWeight(car, 3, rpmLow, fullTankKg)
        val twrHigh = Thrust.thrustToWeight(car, 6, rpmHigh, fullTankKg)
        val ispLow = DeltaVModel.effectiveIsp(load, flowLow, speed)
        val ispHigh = DeltaVModel.effectiveIsp(load, flowHigh, speed)

        assertTrue(twrLow > twrHigh, "low gear should have more thrust")
        assertTrue(ispHigh > ispLow, "tall gear should be more efficient")
    }

    @Test
    fun `zero mass does not produce a divide by zero`() {
        val weightless = car.copy(dryMassKg = 0.0, payload = Payload(Occupant.Exact(0.0)))
        val twr = Thrust.thrustToWeight(weightless, 1, 4000.0, 0.0)
        assertEquals(0.0, twr, 1e-9)
    }
}
