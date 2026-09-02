package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The navball must not re-level on every pothole.
 *
 * Reported from the 2026-08-25 drive: "navball levelling constantly
 * mid-drive, especially after stop/starting". The log agreed -- 869 of 2276
 * samples (38%) sat in UNCALIBRATED across 22 transitions, against 4
 * transitions on the previous drive.
 *
 * The trigger is `looksStationary && hasMoved()`. `looksStationary` becomes
 * true when road speed drops below 0.5 m/s, which is exactly a stop light --
 * and the accelerometer is not quiet there. A hard brake, a pothole or a
 * door closing all tilt the measured vector past the 20 degree tolerance for
 * an instant.
 *
 * A genuine slide PERSISTS. A transient does not.
 *
 * These assert "did not fall back to UNCALIBRATED" rather than "the state is
 * unchanged": the calibrator legitimately advances LEVELLED -> CALIBRATED as
 * roll samples accumulate, and that is progress, not a re-level.
 */
class MountStabilityTest {

    private val g = Attitude.G

    /** Phone lying flat: gravity straight down the Z axis. */
    private fun flat() = Attitude.Vec3(0.0, 0.0, g)

    /** A tilt of [deg] about the X axis. */
    private fun tilted(deg: Double): Attitude.Vec3 {
        val r = Math.toRadians(deg)
        return Attitude.Vec3(0.0, g * Math.sin(r), g * Math.cos(r))
    }

    /** Settle a calibrator into CALIBRATED-or-LEVELLED at rest. */
    private fun settled(): MountAutoCalibrator {
        val c = MountAutoCalibrator()
        repeat(80) { c.onAccelSample(flat(), speedMps = 0.0) }
        return c
    }

    @Test
    fun `a single jolt at a stop light does not re-level`() {
        val c = settled()
        val before = c.state
        assertTrue(
            before != MountAutoCalibrator.State.UNCALIBRATED,
            "precondition: the calibrator should have settled"
        )

        // One pothole: far out of tolerance, for a single sample.
        c.onAccelSample(tilted(45.0), speedMps = 0.0)
        c.onAccelSample(flat(), speedMps = 0.0)

        assertTrue(
            c.state != MountAutoCalibrator.State.UNCALIBRATED,
            "a single out-of-tolerance sample must not discard the calibration; " +
                "state went $before -> ${c.state}"
        )
    }

    @Test
    fun `a brief jostle does not re-level`() {
        // A hard brake is a few samples, not half a second of displacement.
        val c = settled()
        val before = c.state
        repeat(10) { c.onAccelSample(tilted(40.0), speedMps = 0.0) }
        c.onAccelSample(flat(), speedMps = 0.0)
        assertTrue(
            c.state != MountAutoCalibrator.State.UNCALIBRATED,
            "ten samples of jostle is a brake, not a phone sliding off the " +
                "seat; state went $before -> ${c.state}"
        )
    }

    @Test
    fun `a phone that genuinely slides DOES re-level`() {
        // The behaviour that must survive: a real move still resets. A
        // calibration describing an orientation the phone no longer has is
        // worse than none, which is why this detection exists.
        val c = settled()
        repeat(40) { c.onAccelSample(tilted(45.0), speedMps = 0.0) }
        assertTrue(
            c.state == MountAutoCalibrator.State.UNCALIBRATED ||
                c.state == MountAutoCalibrator.State.LEVELLED,
            "a sustained displacement must still trigger re-levelling, got ${c.state}"
        )
    }

    @Test
    fun `the run counter resets between separate jolts`() {
        // Two brief jolts must not add up to one confirmed move -- otherwise
        // a rough road accumulates its way to a reset.
        val c = settled()
        val before = c.state
        repeat(5) { time ->
            repeat(10) { c.onAccelSample(tilted(40.0), speedMps = 0.0) }
            repeat(10) { c.onAccelSample(flat(), speedMps = 0.0) }
        }
        assertTrue(
            c.state != MountAutoCalibrator.State.UNCALIBRATED,
            "five separate jolts must not accumulate into a confirmed move; " +
                "state went $before -> ${c.state}"
        )
    }

    @Test
    fun `driving does not re-level however hard the cornering`() {
        // Above 0.5 m/s `looksStationary` is false and the move check does
        // not run at all. Pinned because it is the reason the check is gated
        // on speed in the first place.
        val c = settled()
        val before = c.state
        repeat(200) { c.onAccelSample(tilted(35.0), speedMps = 20.0) }
        assertTrue(
            c.state != MountAutoCalibrator.State.UNCALIBRATED,
            "cornering at speed must never be read as the phone moving; " +
                "state went $before -> ${c.state}"
        )
    }
}
