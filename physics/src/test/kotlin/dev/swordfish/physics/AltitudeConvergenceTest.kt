package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GPS trim must actually converge, at the fix rates real drives produce.
 *
 * ## What went wrong
 *
 * Reported from the car on 2026-08-24: altitude read roughly 100 m low for a
 * whole drive. Down on the valley floor (~20 m) the panel showed
 * -12 to -15 m; atop the ridge (~120 m) it showed
 * about 20 m. The RANGE was right -- the logs show 176 m of variation across
 * a drive, correctly spanning river level to clifftop -- so this was a fixed
 * bias, not noise.
 *
 * The barometer is referenced to the standard atmosphere (1013.25 hPa). On a
 * real day the true sea-level pressure differs, and each hPa is ~8.3 m, so a
 * 90 m error is an ordinary ~11 hPa departure. GPS altitude exists to trim
 * exactly that out.
 *
 * It could not, because the gain was scaled by the RENDER interval:
 *
 *     alpha = gpsCorrectionRate * dtSec   // dtSec ~0.05 s at 20 fps
 *
 * That is 0.05% of the error per fix instead of the intended ~1% per second.
 * Measured against the real logs:
 *
 * | GPS altitude fixes | bias corrected | 100 m error becomes |
 * |---|---|---|
 * | 4472 (daytime drive) | 89% | 10.7 m |
 * | 47 (evening drive) | 2.3% | 97.7 m |
 * | 9 (last drive) | 0.45% | 99.6 m |
 *
 * The gain is now scaled by time since the last fix, and the FIRST fix seeds
 * the bias outright rather than easing toward it from a known-bad reference.
 */
class AltitudeConvergenceTest {

    /** Down on the valley floor. */
    private val valleyFloorM = 20.0

    /** Atop the ridge. */
    private val ridgeTopM = 120.0

    /**
     * A barometer referenced to the standard atmosphere on a day that is not
     * standard: every reading is this far below the truth.
     */
    private val pressureBiasM = -95.0

    @Test
    fun `the first fix lands the panel on the truth, not 100 m below it`() {
        // The reported symptom, on the valley floor. Before the fix the panel read
        // about -12 m here for the whole drive.
        val est = GradeEstimator()
        val baro = valleyFloorM + pressureBiasM   // what the barometer says

        est.update(baro, null, 0.0, 0.05)
        est.update(baro, valleyFloorM, 10.0, 0.05)   // first GPS altitude

        assertTrue(
            abs(est.altitudeM!! - valleyFloorM) < 5.0,
            "after the first GPS fix the panel should read about " +
                "${valleyFloorM}m on the valley floor, not ${"%.1f".format(est.altitudeM)}m"
        )
    }

    @Test
    fun `nine fixes over ten minutes are enough`() {
        // The exact case that failed: the 2026-08-24 evening drive logged
        // gps_alt_fixes = 9. Under the old gain that corrected 0.45% of the
        // bias; the panel stayed ~100 m low the whole way.
        val est = GradeEstimator(seedBiasFromFirstFix = false)
        val baro = ridgeTopM + pressureBiasM

        est.update(baro, null, 0.0, 0.05)
        // Ten minutes of driving, a GPS altitude roughly every 70 s.
        repeat(9) {
            repeat(1400) { est.update(baro, null, 10.0, 0.05) }  // 70 s, no fix
            est.update(baro, ridgeTopM, 10.0, 0.05)             // one fix
        }

        val err = abs(est.altitudeM!! - ridgeTopM)
        assertTrue(
            err < 30.0,
            "nine fixes over ten minutes left ${"%.1f".format(err)}m of error. " +
                "The gain must be scaled by time since the last fix, not by " +
                "the frame interval."
        )
    }

    @Test
    fun `the ridge read as a clifftop, not a riverbank`() {
        val est = GradeEstimator()
        val baro = ridgeTopM + pressureBiasM
        est.update(baro, null, 0.0, 0.05)
        est.update(baro, ridgeTopM, 10.0, 0.05)

        assertTrue(
            est.altitudeM!! > 90.0,
            "atop the ridge the panel read ${"%.1f".format(est.altitudeM)}m; " +
                "it showed ~20m before this fix"
        )
    }

    @Test
    fun `a drive from riverbank to clifftop keeps the whole climb`() {
        // The relative measurement was never broken and must stay that way:
        // the fix corrects the OFFSET without flattening the terrain.
        val est = GradeEstimator()
        est.update(0.0 + pressureBiasM, null, 0.0, 0.05)
        est.update(0.0 + pressureBiasM, 0.0, 10.0, 0.05)   // seed at river level
        val atRiver = est.altitudeM!!

        // Climb 120 m over a mile, no further GPS altitude.
        for (m in 1..120) {
            est.update(m.toDouble() + pressureBiasM, null, 13.0, 0.05)
        }
        val atTop = est.altitudeM!!

        assertTrue(
            abs((atTop - atRiver) - 120.0) < 5.0,
            "the climb measured ${"%.1f".format(atTop - atRiver)}m, expected ~120m"
        )
    }

    @Test
    fun `no GPS at all still gives relative altitude`() {
        // Location may never be granted, and the barometer alone is still
        // excellent at change. Absolute accuracy degrades; nothing breaks.
        val est = GradeEstimator()
        est.update(100.0, null, 0.0, 0.05)
        repeat(200) { est.update(160.0, null, 10.0, 0.05) }
        assertTrue(
            abs(est.altitudeM!! - 160.0) < 2.0,
            "without GPS the barometer must still track: ${est.altitudeM}"
        )
    }

    @Test
    fun `convergence does not depend on frame rate`() {
        // THE ROOT CAUSE, pinned directly. The same drive at 20 fps and at
        // 60 fps must reach the same place: the correction is a function of
        // TIME, not of how often the panel happened to repaint.
        fun run(dt: Double): Double {
            val est = GradeEstimator(seedBiasFromFirstFix = false)
            val baro = ridgeTopM + pressureBiasM
            est.update(baro, null, 0.0, dt)
            val stepsPerFix = (5.0 / dt).toInt()   // a fix every 5 s
            repeat(20) {
                repeat(stepsPerFix) { est.update(baro, null, 10.0, dt) }
                est.update(baro, ridgeTopM, 10.0, dt)
            }
            return est.altitudeM!!
        }

        val at20fps = run(0.05)
        val at60fps = run(1.0 / 60.0)
        assertTrue(
            abs(at20fps - at60fps) < 2.0,
            "frame rate changed the result: ${"%.1f".format(at20fps)}m at 20fps " +
                "against ${"%.1f".format(at60fps)}m at 60fps"
        )
    }

    @Test
    fun `reset clears the seed so the next drive re-seeds`() {
        val est = GradeEstimator()
        est.update(valleyFloorM + pressureBiasM, null, 0.0, 0.05)
        est.update(valleyFloorM + pressureBiasM, valleyFloorM, 10.0, 0.05)
        est.reset()

        // A new drive, elsewhere, on a different day.
        est.update(ridgeTopM - 40.0, null, 0.0, 0.05)
        est.update(ridgeTopM - 40.0, ridgeTopM, 10.0, 0.05)
        assertTrue(
            abs(est.altitudeM!! - ridgeTopM) < 5.0,
            "after reset the first fix must seed again, not carry the old bias"
        )
    }
}
