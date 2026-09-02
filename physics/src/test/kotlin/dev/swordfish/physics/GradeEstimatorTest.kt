package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradeEstimatorTest {

    @Test
    fun `standard sea level pressure maps to zero altitude`() {
        val g = GradeEstimator()
        assertEquals(0.0, g.pressureToAltitudeM(1013.25), 0.5)
    }

    @Test
    fun `lower pressure maps to higher altitude`() {
        val g = GradeEstimator()
        // ~900 hPa is roughly 1000 m.
        val alt = g.pressureToAltitudeM(898.75)
        assertTrue(alt in 900.0..1100.0, "got $alt m")
    }

    @Test
    fun `air density falls with altitude`() {
        val g = GradeEstimator()
        val seaLevel = g.airDensity(0.0)
        val mile = g.airDensity(1609.0)
        assertEquals(1.225, seaLevel, 0.01)
        assertTrue(mile < seaLevel)
        // Denver is about 15% thinner than sea level.
        assertTrue((seaLevel - mile) / seaLevel in 0.10..0.20)
    }

    @Test
    fun `altitude is unset until the first sample`() {
        assertNull(GradeEstimator().altitudeM)
    }

    @Test
    fun `grade is zero on level ground`() {
        val g = GradeEstimator()
        repeat(100) { g.update(100.0, 100.0, 25.0, 1.0) }
        assertEquals(0.0, g.gradeFraction, 0.001)
    }

    @Test
    fun `sustained climb produces a positive grade`() {
        val g = GradeEstimator()
        // 6% grade: 1.5 m of rise per 25 m of run, each second.
        var alt = 100.0
        repeat(200) {
            alt += 1.5
            g.update(alt, alt, 25.0, 1.0)
        }
        assertTrue(g.gradeFraction > 0.04, "grade = ${g.gradeFraction}")
        assertEquals(0.06, g.gradeFraction, 0.015)
        assertTrue(g.gradeRadians > 0.0)
    }

    @Test
    fun `sustained descent produces a negative grade`() {
        val g = GradeEstimator()
        var alt = 500.0
        repeat(200) {
            alt -= 1.5
            g.update(alt, alt, 25.0, 1.0)
        }
        assertTrue(g.gradeFraction < -0.04, "grade = ${g.gradeFraction}")
        assertTrue(g.gradeRadians < 0.0)
    }

    @Test
    fun `grade is clamped to physically plausible road inclines`() {
        val g = GradeEstimator()
        // A sensor glitch reporting a 100 m jump over 25 m of travel.
        var alt = 100.0
        repeat(200) {
            alt += 100.0
            g.update(alt, alt, 25.0, 1.0)
        }
        assertTrue(abs(g.gradeFraction) <= 0.30 + 1e-9,
            "grade should clamp at 30%, got ${g.gradeFraction}")
    }

    @Test
    fun `stationary samples do not produce a divide-by-zero grade`() {
        // Sitting at a light: run is zero, so no grade update should occur.
        val g = GradeEstimator()
        g.update(100.0, 100.0, 25.0, 1.0)
        val before = g.gradeFraction
        repeat(60) { g.update(101.0, 101.0, 0.0, 1.0) }
        assertEquals(before, g.gradeFraction, 1e-9)
        assertTrue(g.gradeFraction.isFinite())
    }

    @Test
    fun `noisy gps alone still yields a stable grade estimate`() {
        // GPS-only path, +/-8 m of noise on a true 4% climb. The point is
        // that the smoothing keeps the output usable without a barometer.
        val g = GradeEstimator()
        val rng = java.util.Random(42)
        var trueAlt = 100.0
        repeat(400) {
            trueAlt += 1.0
            val noisy = trueAlt + (rng.nextDouble() - 0.5) * 16.0
            g.update(null, noisy, 25.0, 1.0)
        }
        assertTrue(g.gradeFraction > 0.0, "should detect the climb")
        assertTrue(abs(g.gradeFraction) <= 0.30)
    }

    @Test
    fun `barometer drives grade while gps trims long-term drift`() {
        // Barometer reads 50 m low the whole time; GPS is correct. The fused
        // altitude should migrate toward GPS without the grade going haywire.
        val g = GradeEstimator()
        var trueAlt = 200.0
        repeat(600) {
            trueAlt += 0.5
            g.update(trueAlt - 50.0, trueAlt, 25.0, 1.0)
        }
        val fused = g.altitudeM
        assertNotNull(fused)
        assertTrue(fused > trueAlt - 50.0, "GPS should pull the estimate up")
        // Grade stays sane throughout: 0.5 m per 25 m = 2%.
        assertEquals(0.02, g.gradeFraction, 0.02)
    }

    @Test
    fun `reset clears all state`() {
        val g = GradeEstimator()
        repeat(100) { g.update(100.0 + it, 100.0 + it, 25.0, 1.0) }
        g.reset()
        assertNull(g.altitudeM)
        assertEquals(0.0, g.gradeFraction, 1e-9)
    }

    @Test
    fun `zero timestep is ignored`() {
        val g = GradeEstimator()
        g.update(100.0, 100.0, 25.0, 0.0)
        assertNull(g.altitudeM)
    }

    // --- Absolute altitude and the sea-level reference ---
    //
    // Observed 2026-08-23: a suburban driveway (true elevation
    // ~20-30 m) read +47.6 m at the start of a drive and -20.8 m on
    // returning to the SAME SPOT 63 minutes later. A 68 m round-trip drift
    // means the fusion never converged, which is what a GPS altitude that
    // never arrives looks like.

    @Test
    fun `barometric altitude is only as good as its sea-level reference`() {
        // The standard atmosphere is an assumption, not a measurement. Real
        // weather spans roughly 980-1040 hPa, and each hPa is ~8.3 m, so a
        // perfectly working barometer can be badly wrong in absolute terms.
        // This is why GPS has to trim it.
        val pressure = 1000.0
        val e = GradeEstimator()

        val standard = e.pressureToAltitudeM(pressure)
        val actual = e.pressureToAltitudeM(pressure, seaLevelHpa = 1030.0)

        // ~8.3 m per hPa is the documented sensitivity; 16.75 hPa of
        // reference error therefore costs about 138 m. Pinning the rate
        // rather than a bare threshold means this test says something true
        // about the physics instead of just "it is big".
        val error = kotlin.math.abs(standard - actual)
        val perHpa = error / (1030.0 - 1013.25)
        assertEquals(8.3, perHpa, 0.5, "sea-level sensitivity drifted: $perHpa m/hPa")
        assertTrue(error > 100.0, "a 17 hPa reference error should cost >100 m, got $error")
    }

    @Test
    fun `relative altitude is unaffected by the reference`() {
        // The counterpart, and the reason grade works regardless: the
        // reference cancels out of a DIFFERENCE. Climb is trustworthy even
        // when the absolute number is not.
        val lo = 1000.0
        val hi = 990.0
        val e = GradeEstimator()

        val climbStd = e.pressureToAltitudeM(hi) - e.pressureToAltitudeM(lo)
        val climbOff = e.pressureToAltitudeM(hi, seaLevelHpa = 1030.0) -
            e.pressureToAltitudeM(lo, seaLevelHpa = 1030.0)

        assertEquals(climbStd, climbOff, 2.0)
    }

    @Test
    fun `gps altitude pulls a badly referenced barometer back to truth`() {
        // The convergence the 2026-08-23 drive did NOT show. With GPS
        // arriving, a 68 m initial disagreement must be substantially gone
        // well inside an hour of driving -- if this fails, the fusion is
        // broken; if it passes and the field still drifts, GPS is not
        // arriving and the fault is in the location wiring.
        val est = GradeEstimator()

        val baro = 93.0   // badly referenced
        val gps = 25.0    // truth, surveyed

        // 10 minutes at 5 Hz, the barometer's real cadence.
        repeat(10 * 60 * 5) {
            est.update(
                barometricAltM = baro,
                gpsAltM = gps,
                horizontalDistanceM = 0.0,
                dtSec = 0.2
            )
        }

        val settled = est.altitudeM!!
        assertTrue(
            kotlin.math.abs(settled - gps) < 5.0,
            "after 10 min of GPS the altitude should be near $gps, was $settled"
        )
    }

    @Test
    fun `altitude still works with no gps at all`() {
        // Location is optional and must stay optional -- the barometer
        // carries relative altitude on its own. It just cannot fix the
        // absolute reference, which is exactly the observed defect.
        val est = GradeEstimator()
        est.update(barometricAltM = 100.0, gpsAltM = null, horizontalDistanceM = 0.0, dtSec = 0.2)
        assertEquals(100.0, est.altitudeM!!, 1e-9)

        est.update(barometricAltM = 110.0, gpsAltM = null, horizontalDistanceM = 0.0, dtSec = 0.2)
        assertEquals(110.0, est.altitudeM!!, 1e-9)
    }
}
