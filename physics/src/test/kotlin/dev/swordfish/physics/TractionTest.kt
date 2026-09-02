package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TractionTest {

    // --- Friction circle ---

    @Test
    fun `grip usage combines lateral and longitudinal demand`() {
        // 0.3 lateral with 0.4 longitudinal is a 0.5 g resultant, which is
        // ~53% of an assumed 0.95 g peak.
        val usage = Traction.gripUsage(0.3, 0.4)
        assertEquals(0.5 / Traction.ASSUMED_PEAK_GRIP_G, usage, 0.001)
    }

    @Test
    fun `cornering and braking together use more grip than either alone`() {
        // The core insight of the friction circle, and the thing a driver
        // feels: you cannot spend the whole budget twice.
        val corneringOnly = Traction.gripUsage(0.6, 0.0)
        val brakingOnly = Traction.gripUsage(0.0, 0.6)
        val both = Traction.gripUsage(0.6, 0.6)
        assertTrue(both > corneringOnly)
        assertTrue(both > brakingOnly)
        assertEquals(corneringOnly, brakingOnly, 1e-9)
    }

    @Test
    fun `cruising gently uses almost no grip`() {
        assertEquals(Traction.Band.CRUISING, Traction.band(Traction.gripUsage(0.05, 0.05)))
    }

    @Test
    fun `bands escalate with demand`() {
        assertEquals(Traction.Band.CRUISING, Traction.band(0.1))
        assertEquals(Traction.Band.WORKING, Traction.band(0.4))
        assertEquals(Traction.Band.PRESSING, Traction.band(0.7))
        assertEquals(Traction.Band.AT_THE_LIMIT, Traction.band(0.95))
        assertEquals(Traction.Band.AT_THE_LIMIT, Traction.band(1.5))
    }

    @Test
    fun `zero peak grip does not divide by zero`() {
        assertEquals(0.0, Traction.gripUsage(0.5, 0.5, peakGripG = 0.0), 1e-9)
    }

    // --- Yaw agreement: the DSC-adjacent check ---

    @Test
    fun `a gripping car agrees with its own yaw rate`() {
        // 20 m/s at 0.2 rad/s implies ~0.41 g. If the accelerometer measures
        // that, the tyres are doing what the steering asked.
        val expected = Attitude.expectedLateralG(20.0, 0.2)
        val agreement = Traction.yawAgreement(expected, 20.0, 0.2)
        assertNotNull(agreement)
        assertEquals(1.0, agreement, 0.001)
    }

    @Test
    fun `a sliding car rotates faster than its grip explains`() {
        // Measured lateral G well below what speed and yaw rate predict is
        // what a slide looks like from the outside.
        val expected = Attitude.expectedLateralG(20.0, 0.4)
        val measured = expected * 0.5
        val agreement = Traction.yawAgreement(measured, 20.0, 0.4)
        assertNotNull(agreement)
        assertEquals(0.5, agreement, 0.001)
        assertTrue(Traction.suggestsSlip(measured, 20.0, 0.4))
    }

    @Test
    fun `normal cornering does not trigger the slip indicator`() {
        val expected = Attitude.expectedLateralG(20.0, 0.2)
        assertTrue(!Traction.suggestsSlip(expected, 20.0, 0.2))
        // Even with some measurement noise.
        assertTrue(!Traction.suggestsSlip(expected * 0.9, 20.0, 0.2))
    }

    @Test
    fun `the check abstains when going straight`() {
        assertNull(Traction.yawAgreement(0.0, 20.0, 0.0))
        assertTrue(!Traction.suggestsSlip(0.0, 20.0, 0.0))
    }

    @Test
    fun `the check abstains at parking speeds`() {
        // Yaw rate is large and lateral G tiny when manoeuvring in a car
        // park; comparing them would produce nonsense.
        assertNull(Traction.yawAgreement(0.05, 2.0, 0.5))
        assertTrue(!Traction.suggestsSlip(0.05, 2.0, 0.5))
    }

    @Test
    fun `slip detection is symmetric for left and right turns`() {
        val expectedRight = Attitude.expectedLateralG(20.0, 0.4)
        val expectedLeft = Attitude.expectedLateralG(20.0, -0.4)
        assertTrue(Traction.suggestsSlip(expectedRight * 0.5, 20.0, 0.4))
        assertTrue(Traction.suggestsSlip(expectedLeft * 0.5, 20.0, -0.4))
    }

    // --- Cornering drag ---

    @Test
    fun `cornering costs energy and hard cornering costs much more`() {
        val mass = 1200.0
        val gentle = Traction.corneringDragWatts(0.2, mass, 20.0)
        val hard = Traction.corneringDragWatts(0.6, mass, 20.0)
        assertTrue(gentle > 0.0)
        // Induced drag rises with the square of lateral force, so tripling
        // the G should cost roughly nine times the power.
        assertEquals(9.0, hard / gentle, 0.5)
    }

    @Test
    fun `straight-line driving has no cornering drag`() {
        assertEquals(0.0, Traction.corneringDragWatts(0.0, 1200.0, 25.0), 1e-9)
    }

    @Test
    fun `cornering drag is never negative regardless of turn direction`() {
        val left = Traction.corneringDragWatts(-0.5, 1200.0, 20.0)
        val right = Traction.corneringDragWatts(0.5, 1200.0, 20.0)
        assertTrue(left >= 0.0)
        assertTrue(right >= 0.0)
        assertEquals(left, right, 1e-9)
    }

    @Test
    fun `cornering drag is a plausible fraction of road load`() {
        // A firm 0.5 g corner at 20 m/s should cost a meaningful but not
        // absurd amount next to a ~460 N road load at cruise.
        val car = Vehicle.ND2_CLUB
        val mass = car.totalMassKg(Units.gallonsToKg(11.9))
        val watts = Traction.corneringDragWatts(0.5, mass, 20.0)
        val kw = watts / 1000.0
        assertTrue(kw in 0.5..15.0, "cornering drag = $kw kW")
    }
}
