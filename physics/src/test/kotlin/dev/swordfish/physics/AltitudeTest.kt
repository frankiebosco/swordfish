package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Altitude as a displayed readout.
 *
 * The distinction these tests exist to protect is **relative versus
 * absolute**. The barometer is excellent at change and poor at absolute
 * value; GPS is the reverse. Confusing the two would produce a readout that
 * looks precise and is systematically wrong.
 */
class AltitudeTest {

    /** Standard sea-level pressure. */
    private val seaLevel = 1013.25

    @Test
    fun `sea level pressure reads as sea level`() {
        val est = GradeEstimator()
        assertEquals(0.0, est.pressureToAltitudeM(seaLevel), 1.0)
    }

    @Test
    fun `lower pressure reads as higher altitude`() {
        val est = GradeEstimator()
        val low = est.pressureToAltitudeM(900.0)
        val high = est.pressureToAltitudeM(1000.0)
        assertTrue(low > high, "900 hPa should be higher than 1000 hPa")
    }

    @Test
    fun `a known pressure gives a plausible altitude`() {
        // 900 hPa is roughly 1000 m in the standard atmosphere.
        val est = GradeEstimator()
        val alt = est.pressureToAltitudeM(900.0)
        assertTrue(alt in 900.0..1100.0, "900 hPa gave ${alt}m, expected ~1000m")
    }

    @Test
    fun `the sea-level reference dominates ABSOLUTE accuracy`() {
        // This is the headline caveat and the reason GPS is wired in at all:
        // each hPa of reference error is ~8.3 m, and real weather spans
        // roughly 980-1040 hPa. Using the standard atmosphere on a
        // low-pressure day is a couple of hundred metres out with a
        // perfectly functioning sensor.
        val est = GradeEstimator()
        val measured = 1000.0
        val standard = est.pressureToAltitudeM(measured, seaLevelHpa = 1013.25)
        val actual = est.pressureToAltitudeM(measured, seaLevelHpa = 990.0)
        val error = abs(standard - actual)
        assertTrue(
            error > 150.0,
            "expected a large absolute error from a wrong reference, got ${error}m"
        )
    }

    @Test
    fun `RELATIVE altitude is unaffected by the reference`() {
        // The saving grace: the reference cancels out of a difference. A
        // climb is measured correctly even when the absolute value is not.
        val est = GradeEstimator()
        val climbStandard =
            est.pressureToAltitudeM(990.0) - est.pressureToAltitudeM(1000.0)
        val climbWrongRef =
            est.pressureToAltitudeM(990.0, 990.0) - est.pressureToAltitudeM(1000.0, 990.0)
        assertEquals(climbStandard, climbWrongRef, 1.0)
    }

    // --- Fusion ---

    @Test
    fun `altitude is null until the first sample arrives`() {
        // The panel must dash it rather than show a confident zero.
        assertNull(GradeEstimator().altitudeM)
    }

    @Test
    fun `the first sample establishes an altitude`() {
        val est = GradeEstimator()
        est.update(barometricAltM = 100.0, gpsAltM = null, horizontalDistanceM = 0.0, dtSec = 0.1)
        assertNotNull(est.altitudeM)
        assertEquals(100.0, est.altitudeM!!, 1.0)
    }

    @Test
    fun `GPS pulls the barometer toward the true absolute value`() {
        val est = GradeEstimator()
        // Barometer says 100 m; GPS insists on 200 m.
        est.update(100.0, null, 0.0, 0.1)
        repeat(600) { est.update(100.0, 200.0, 10.0, 0.1) }
        assertTrue(
            est.altitudeM!! > 105.0,
            "GPS never corrected the barometer: ${est.altitudeM}"
        )
    }

    @Test
    fun `GPS correction is slow enough not to inject its own noise`() {
        // GPS altitude is +/-10-20 m and jumps around. Correcting quickly
        // would import that jitter into a reading whose whole value is
        // smoothness.
        //
        // Tested on a LATER fix, not the first. The first fix SEEDS the bias
        // outright and is supposed to move the estimate a long way -- before
        // it arrives the barometer is referenced to the standard atmosphere
        // and carries the whole sea-level pressure error. Rejecting the
        // first fix would mean clinging to a known-bad reference; rejecting
        // later ones is what keeps the reading smooth.
        val est = GradeEstimator()
        est.update(100.0, null, 0.0, 0.1)
        est.update(100.0, 100.0, 10.0, 0.1)   // first fix: seeds, agrees
        val settled = est.altitudeM!!

        // Now one noisy fix 50 m out, a second later.
        est.update(100.0, 150.0, 10.0, 1.0)
        assertTrue(
            abs(est.altitudeM!! - settled) < 1.0,
            "a later GPS fix moved altitude by ${est.altitudeM!! - settled}m"
        )
    }

    @Test
    fun `altitude still works with no GPS at all`() {
        // Location may never be granted. Relative altitude and grade must
        // both survive that.
        val est = GradeEstimator()
        est.update(100.0, null, 0.0, 0.1)
        repeat(100) { est.update(120.0, null, 10.0, 0.1) }
        assertEquals(120.0, est.altitudeM!!, 1.0)
    }

    @Test
    fun `a climb registers as rising altitude`() {
        val est = GradeEstimator()
        est.update(0.0, null, 0.0, 0.1)
        for (m in 1..50) est.update(m.toDouble(), null, 10.0, 0.1)
        assertTrue(est.altitudeM!! > 45.0, "climb not tracked: ${est.altitudeM}")
    }

    @Test
    fun `altitude rounds to whole metres for display`() {
        // The panel draws no decimal points, and a tenth of a metre is well
        // inside the absolute error of any barometric altitude anyway.
        val est = GradeEstimator()
        est.update(123.456, null, 0.0, 0.1)
        assertEquals(123L, Math.round(est.altitudeM!!))
    }

    @Test
    fun `reset clears the altitude so a new drive starts clean`() {
        val est = GradeEstimator()
        est.update(100.0, null, 0.0, 0.1)
        est.reset()
        assertNull(est.altitudeM)
    }

    @Test
    fun `altitude tracks with zero distance travelled`() {
        // The DHU bug: ImuSource returned early when road speed was unknown,
        // so altitude stayed null with no OBD link and the ALT row silently
        // never appeared. Only GRADE needs the run; altitude does not.
        val est = GradeEstimator()
        est.update(barometricAltM = 50.0, gpsAltM = null, horizontalDistanceM = 0.0, dtSec = 0.2)
        repeat(50) {
            est.update(barometricAltM = 50.0, gpsAltM = null, horizontalDistanceM = 0.0, dtSec = 0.2)
        }
        assertNotNull(est.altitudeM, "altitude should track while parked")
        assertEquals(50.0, est.altitudeM!!, 1.0)
    }

    @Test
    fun `a parked car reports no grade but still reports altitude`() {
        // Zero run must not manufacture a grade -- dividing by a near-zero
        // denominator is how you get a 4000% grade at a stoplight.
        val est = GradeEstimator()
        repeat(50) { est.update(50.0, null, 0.0, 0.2) }
        assertEquals(0.0, est.gradeFraction, 1e-9)
        assertNotNull(est.altitudeM)
    }

}
