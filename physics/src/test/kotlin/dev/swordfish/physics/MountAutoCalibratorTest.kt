package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "control from here" problem: the phone does not know which way the car
 * is pointing, and a fixed mounting assumption is wrong the moment the phone
 * is lying flat on the passenger seat instead of clipped to the windscreen.
 */
class MountAutoCalibratorTest {

    private val G = Attitude.G

    /**
     * Phone flat on its back, screen up. Measured from the actual device:
     * `0.25, 0.01, 9.81` — gravity almost entirely on +Z.
     */
    private val flatOnBack = Attitude.Vec3(0.25, 0.01, 9.81)

    /** Phone upright in a windscreen cradle: gravity on -Y. */
    private val uprightCradle = Attitude.Vec3(0.0, -G, 0.0)

    /**
     * Feed a steady orientation until the calibrator settles.
     *
     * 100 samples, not 60. A move must now PERSIST for
     * `moveConfirmSamples` before it is believed (added 2026-08-25, after
     * the navball re-levelled 22 times in one drive), so re-settling into a
     * NEW orientation costs that confirmation window plus the usual
     * `gravitySamples`. 60 was enough when the reset fired on the first
     * out-of-tolerance sample; it is not now, and that is the fix working
     * rather than a regression.
     */
    private fun settle(c: MountAutoCalibrator, accel: Attitude.Vec3, n: Int = 100) {
        repeat(n) { c.onAccelSample(accel, speedMps = 0.0) }
    }

    // --- Stage 1: levelling ---

    @Test
    fun `nothing is known before any samples arrive`() {
        val c = MountAutoCalibrator()
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
        assertNull(c.calibration)
    }

    @Test
    fun `holding still levels the phone`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
        assertNotNull(c.calibration)
    }

    @Test
    fun `levelling works from any resting orientation`() {
        // The whole point: no assumption about how the phone is placed.
        for (orientation in listOf(
            flatOnBack,
            uprightCradle,
            Attitude.Vec3(G, 0.0, 0.0),           // on its side
            Attitude.Vec3(0.0, 0.0, -G),          // face down
            Attitude.Vec3(5.6, 5.6, 5.6)          // wedged at an angle
        )) {
            val c = MountAutoCalibrator()
            settle(c, orientation)
            assertEquals(
                MountAutoCalibrator.State.LEVELLED, c.state,
                "failed to level from $orientation"
            )
        }
    }

    @Test
    fun `a levelled phone reports gravity as straight down in vehicle frame`() {
        // The failure this prevents: an earlier build assumed a cradle, read
        // the flat phone's pitch as a steep descent, and zeroed delta-V.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        val cal = c.calibration!!
        val vehicleGravity = cal.toVehicleFrame(flatOnBack)

        // Down is -Y in vehicle frame, and there should be no significant
        // pitch or roll component.
        assertEquals(-G, vehicleGravity.y, 0.15)
        assertTrue(abs(vehicleGravity.x) < 0.3, "spurious roll: ${vehicleGravity.x}")
        assertTrue(abs(vehicleGravity.z) < 0.3, "spurious pitch: ${vehicleGravity.z}")
    }

    @Test
    fun `a flat phone reads as level, not as a steep descent`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        val reading = Attitude.fromVehicleFrameAccel(
            c.calibration!!.toVehicleFrame(flatOnBack)
        )
        assertEquals(0.0, Math.toDegrees(reading.pitchRadians), 3.0)
        assertEquals(0.0, Math.toDegrees(reading.rollRadians), 3.0)
    }

    @Test
    fun `driving does not corrupt the gravity estimate`() {
        // Only stationary samples should feed the average.
        val c = MountAutoCalibrator()
        repeat(200) {
            c.onAccelSample(Attitude.Vec3(3.0, 2.0, 9.0), speedMps = 20.0)
        }
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
    }

    // --- Stage 2: finding forward ---

    @Test
    fun `forward is unknowable from gravity alone`() {
        // A phone lying flat can point any direction on the seat without
        // gravity changing. Levelling is genuinely not enough.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
        assertFalse(c.state == MountAutoCalibrator.State.CALIBRATED)
    }

    @Test
    fun `one straight-line pull completes the calibration`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        // Accelerating: gravity plus a horizontal push. For a phone flat on
        // its back with the top edge toward the windscreen, forward is +Y.
        repeat(20) {
            c.onAccelSample(Attitude.Vec3(0.25, 3.0, 9.81), speedMps = 10.0)
        }

        assertEquals(MountAutoCalibrator.State.CALIBRATED, c.state)
    }

    @Test
    fun `forward ends up horizontal even if the cue was not`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        repeat(20) {
            // Pull with a vertical component, as when accelerating uphill.
            c.onAccelSample(Attitude.Vec3(0.25, 3.0, 11.0), speedMps = 10.0)
        }
        val cal = c.calibration!!
        assertEquals(0.0, cal.forward.dot(cal.up), 1e-6)
        assertTrue(cal.isValid())
    }

    @Test
    fun `gentle wobble does not count as a forward cue`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        repeat(50) {
            // Below the 1.5 m/s^2 threshold -- road noise, not a pull.
            c.onAccelSample(Attitude.Vec3(0.5, 0.4, 9.81), speedMps = 15.0)
        }
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
    }

    @Test
    fun `a stronger pull refines the forward estimate`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        repeat(10) { c.onAccelSample(Attitude.Vec3(0.25, 2.0, 9.81), speedMps = 8.0) }
        val weakForward = c.calibration!!.forward

        repeat(10) { c.onAccelSample(Attitude.Vec3(0.25, 6.0, 9.81), speedMps = 15.0) }
        val strongForward = c.calibration!!.forward

        // Both point the same way here; the point is that the stronger sample
        // is the one retained, and the axes stay valid.
        assertTrue(c.calibration!!.isValid())
        assertTrue(weakForward.magnitude > 0.9)
        assertTrue(strongForward.magnitude > 0.9)
    }

    @Test
    fun `a fully calibrated mount resolves all three axes orthogonally`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        repeat(20) { c.onAccelSample(Attitude.Vec3(0.25, 4.0, 9.81), speedMps = 12.0) }

        val cal = c.calibration!!
        assertTrue(cal.isValid(), "axes should be orthogonal after calibration")
    }

    // --- Movement detection ---

    @Test
    fun `picking the phone up is detected`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        assertFalse(c.hasMoved(flatOnBack))
        // Stood on edge -- a 90 degree change.
        assertTrue(c.hasMoved(Attitude.Vec3(0.0, -G, 0.0)))
    }

    @Test
    fun `small disturbances do not trip the moved check`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        // A few degrees of shift from a bump.
        assertFalse(c.hasMoved(Attitude.Vec3(1.0, 0.5, 9.7)))
    }

    @Test
    fun `movement detection is inert before calibration`() {
        val c = MountAutoCalibrator()
        assertFalse(c.hasMoved(flatOnBack))
    }

    // --- Reset and status ---

    @Test
    fun `reset clears everything`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        repeat(20) { c.onAccelSample(Attitude.Vec3(0.25, 4.0, 9.81), speedMps = 12.0) }
        assertEquals(MountAutoCalibrator.State.CALIBRATED, c.state)

        c.reset()
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
        assertNull(c.calibration)
    }

    @Test
    fun `each state has a distinct human-readable description`() {
        val c = MountAutoCalibrator()
        val uncal = c.describe()
        settle(c, flatOnBack)
        val levelled = c.describe()
        repeat(20) { c.onAccelSample(Attitude.Vec3(0.25, 4.0, 9.81), speedMps = 12.0) }
        val calibrated = c.describe()

        assertTrue(setOf(uncal, levelled, calibrated).size == 3)
        assertTrue(calibrated.contains("calibrated", ignoreCase = true))
    }

    // --- Phone migration: the failure that motivated all of this ---
    //
    // Frank keeps the phone loose on the passenger seat, where it slides on
    // every corner. Before these fixes the stored gravity vector simply went
    // stale: pitch, roll and the forward axis all became wrong while still
    // being reported with full confidence.

    @Test
    fun `a phone that slides while stopped is detected and re-levelled`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)

        // Slid into the seat bight: gravity is now largely on -Y instead
        // of +Z. Feed it while stationary, which is when detection runs.
        settle(c, uprightCradle)

        // It must have re-levelled to the NEW orientation, not kept the old
        // one and not been stuck uncalibrated.
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
        val cal = c.calibration
        assertNotNull(cal)
        // Down should now point along the new gravity, not the old.
        // "up" is the public view of the gravity axis. After sliding into
        // the seat bight, up should point along +Y, not the old +Z.
        assertTrue(cal!!.up.y > 0.9, "still using the pre-slide gravity: ${cal.up}")
    }

    @Test
    fun `a stationary phone that has not moved keeps its calibration`() {
        // The detector must not fire on noise, or the navball would spend
        // every red light re-levelling.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        val before = c.calibration!!.up

        // Idle vibration: small perturbations around the same vector.
        repeat(200) { i ->
            val jitter = if (i % 2 == 0) 0.05 else -0.05
            c.onAccelSample(
                Attitude.Vec3(0.25 + jitter, 0.01 + jitter, 9.81 - jitter),
                speedMps = 0.0
            )
        }

        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
        val after = c.calibration!!.up
        assertEquals(before.z, after.z, 0.1)
    }

    @Test
    fun `cornering while moving does not trigger a false re-calibration`() {
        // Detection is gated on being STATIONARY precisely because
        // cornering and braking tilt the measured vector away from gravity
        // constantly. If it fired while driving, the calibration would be
        // destroyed on every roundabout.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        // Hard cornering at speed: a large lateral component, well beyond
        // the 20-degree tolerance.
        repeat(200) {
            c.onAccelSample(Attitude.Vec3(5.0, 0.01, 9.81), speedMps = 20.0)
        }

        assertTrue(
            c.state != MountAutoCalibrator.State.UNCALIBRATED,
            "cornering destroyed the calibration"
        )
    }

    // --- Forward-axis staleness ---

    @Test
    fun `the strongest-pull record decays so a stale axis cannot persist`() {
        // Without decay, the hardest pull of the journey defines "forward"
        // permanently -- and after the phone slides, no ordinary
        // acceleration can ever beat it. The navball then stays confidently
        // wrong for the rest of the drive.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        // One hard launch, establishing forward along +X.
        repeat(5) {
            c.onAccelSample(Attitude.Vec3(0.25 + 6.0, 0.01, 9.81), speedMps = 10.0)
        }
        assertEquals(MountAutoCalibrator.State.CALIBRATED, c.state)
        val original = c.calibration!!.forward
        assertTrue(original.x > 0.9, "expected forward on +X, got $original")

        // Now a long stretch of ordinary driving with forward along +Y --
        // as if the phone had been rotated 90 degrees on the seat. Each
        // pull is gentler than the original launch, so without decay none
        // of them could ever take over.
        repeat(20000) {
            c.onAccelSample(Attitude.Vec3(0.25, 0.01 + 2.5, 9.81), speedMps = 15.0)
        }

        val updated = c.calibration!!.forward
        assertTrue(
            updated.y > 0.9,
            "forward axis never recovered from the stale record: $updated"
        )
    }

    @Test
    fun `a single good pull is not immediately overwritten by noise`() {
        // Decay must be slow enough that the axis stays stable through a
        // junction. A record that evaporated in seconds would be as bad as
        // one that never expired.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)

        repeat(5) {
            c.onAccelSample(Attitude.Vec3(0.25 + 6.0, 0.01, 9.81), speedMps = 10.0)
        }
        val original = c.calibration!!.forward

        // Ten seconds of driving with only weak, sub-threshold jitter.
        repeat(500) {
            c.onAccelSample(Attitude.Vec3(0.25 + 0.4, 0.01, 9.81), speedMps = 15.0)
        }

        val after = c.calibration!!.forward
        assertEquals(original.x, after.x, 0.05)
    }

    // --- Speed gating ---

    @Test
    fun `a smooth cruise is not mistaken for being stopped`() {
        // Without a speed input the calibrator judges stationary from
        // gravity steadiness alone -- and a smooth motorway cruise looks
        // exactly like a parked car to an accelerometer. Live OBD speed
        // makes the distinction exact.
        val c = MountAutoCalibrator()

        // Steady 1g, but moving at 30 m/s: must NOT be taken as stationary,
        // so no gravity average should be accumulated.
        repeat(200) { c.onAccelSample(flatOnBack, speedMps = 30.0) }
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
    }

    @Test
    fun `reset returns the calibrator to a known-nothing state`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        repeat(5) {
            c.onAccelSample(Attitude.Vec3(0.25 + 6.0, 0.01, 9.81), speedMps = 10.0)
        }
        assertEquals(MountAutoCalibrator.State.CALIBRATED, c.state)

        c.reset()
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
        assertNull(c.calibration)
    }


    // --- GPS-derived forward axis (2026-08-21) ---
    //
    // The acceleration route left DRIVE TO ORIENT on screen for an entire
    // drive and the compass "kept getting thrown off and could not be
    // recovered". These pin the replacement.

    @Test
    fun `a world-derived forward axis calibrates immediately`() {
        // No hard pull required -- one GPS fix is enough.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)

        c.setForwardFromWorld(Attitude.Vec3(1.0, 0.0, 0.0))
        assertEquals(MountAutoCalibrator.State.CALIBRATED, c.state)
    }

    @Test
    fun `a world-derived axis is not overridden by a later hard pull`() {
        // A GPS bearing is a MEASUREMENT; an acceleration guess is an
        // INFERENCE. The measurement must win, or a hard launch would undo
        // a correct axis.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        c.setForwardFromWorld(Attitude.Vec3(1.0, 0.0, 0.0))
        val fromGps = c.calibration!!.forward

        // A violent pull along a completely different axis.
        repeat(20) {
            c.onAccelSample(Attitude.Vec3(0.25, 0.01 + 9.0, 9.81), speedMps = 15.0)
        }

        val after = c.calibration!!.forward
        assertEquals(fromGps.x, after.x, 0.01)
        assertEquals(fromGps.y, after.y, 0.01)
    }

    @Test
    fun `forward cannot be set before gravity is known`() {
        // Forward must end up perpendicular to down, and without a gravity
        // estimate there is no "down" to be perpendicular to.
        val c = MountAutoCalibrator()
        c.setForwardFromWorld(Attitude.Vec3(1.0, 0.0, 0.0))
        assertEquals(MountAutoCalibrator.State.UNCALIBRATED, c.state)
        assertNull(c.calibration)
    }

    @Test
    fun `a degenerate forward vector is rejected`() {
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        c.setForwardFromWorld(Attitude.Vec3(0.0, 0.0, 0.0))
        assertEquals(MountAutoCalibrator.State.LEVELLED, c.state)
    }

    @Test
    fun `a re-solved axis replaces the previous one`() {
        // The whole point: the phone slides, the next GPS fix fixes it.
        // The old code could only be beaten by a STRONGER pull, which is
        // why a bad axis was unrecoverable.
        val c = MountAutoCalibrator()
        settle(c, flatOnBack)
        c.setForwardFromWorld(Attitude.Vec3(1.0, 0.0, 0.0))
        val first = c.calibration!!.forward

        c.setForwardFromWorld(Attitude.Vec3(0.0, 1.0, 0.0))
        val second = c.calibration!!.forward

        assertTrue(
            kotlin.math.abs(first.x - second.x) > 0.5,
            "the axis never moved: $first then $second"
        )
    }


    // --- Roll bias: the fake roll from the 2026-08-21 drive ---

    @Test
    fun `roll bias is ignored until enough samples accumulate`() {
        // Before the mean settles it is dominated by whatever the car was
        // doing in the first seconds, which is not a mount tilt.
        val c = MountAutoCalibrator()
        repeat(10) { c.observeRoll(0.5) }
        assertEquals(0.0, c.rollBias, 1e-9)
    }

    @Test
    fun `a constant tilt is learned as mount bias`() {
        // The phone rests at a fixed angle in the seat. Averaged over a
        // drive a car IS level, so that constant offset is the mount's.
        val c = MountAutoCalibrator()
        repeat(600) { c.observeRoll(0.35) }
        assertEquals(0.35, c.rollBias, 0.01)
    }

    @Test
    fun `real cornering averages out and does not become bias`() {
        // Left and right corners cancel. What remains is the mount tilt --
        // here deliberately zero.
        val c = MountAutoCalibrator()
        repeat(600) { i -> c.observeRoll(if (i % 2 == 0) 0.20 else -0.20) }
        assertEquals(0.0, c.rollBias, 0.02)
    }

    @Test
    fun `bias is cleared on reset`() {
        val c = MountAutoCalibrator()
        repeat(600) { c.observeRoll(0.4) }
        assertTrue(c.rollBias > 0.1)
        c.reset()
        assertEquals(0.0, c.rollBias, 1e-9)
    }

    // --- Surviving a restart ---
    //
    // The Android Auto host crashes on its own, and calibration lived only
    // in memory. On the 2026-08-21 drive that cost 118 s to re-level after a
    // crash, then it was lost again 46 s later: 72% of the session had NO
    // navball, because drawNavball correctly refuses to draw a horizon it
    // cannot trust. Nothing about the phone changes in the four seconds a
    // crash takes, so the calibration is still valid on the other side.

    @Test
    fun `nothing is saved before gravity has settled`() {
        // A half-finished average is not worth persisting -- restoring it
        // would only delay a clean re-level.
        val c = MountAutoCalibrator()
        assertNull(c.snapshot(), "snapshotted an uncalibrated calibrator")
    }

    @Test
    fun `a levelled calibration survives a restart`() {
        val before = MountAutoCalibrator()
        repeat(60) { before.onAccelSample(Attitude.Vec3(0.0, 0.0, -Attitude.G), 0.0) }
        assertEquals(MountAutoCalibrator.State.LEVELLED, before.state)

        val saved = before.snapshot()
        assertNotNull(saved)

        val after = MountAutoCalibrator()
        after.restore(saved)

        assertEquals(MountAutoCalibrator.State.LEVELLED, after.state)
        assertNotNull(after.calibration, "restored calibrator has no calibration")
    }

    @Test
    fun `a full calibration survives a restart with both axes intact`() {
        val before = MountAutoCalibrator()
        repeat(60) { before.onAccelSample(Attitude.Vec3(0.0, 0.0, -Attitude.G), 0.0) }
        // A forward pull along +x while moving.
        before.onAccelSample(Attitude.Vec3(3.0, 0.0, -Attitude.G), 10.0)
        assertEquals(MountAutoCalibrator.State.CALIBRATED, before.state)

        val expected = before.calibration!!
        val after = MountAutoCalibrator().apply { restore(before.snapshot()!!) }

        assertEquals(MountAutoCalibrator.State.CALIBRATED, after.state)
        val got = after.calibration!!
        // The derived AXES must come back, not just the state label -- a
        // restored calibration pointing somewhere else is worse than none,
        // because it is confidently wrong. up/forward/right are the public
        // surface and the thing every reading is computed from.
        assertEquals(expected.up.x, got.up.x, 1e-9)
        assertEquals(expected.up.y, got.up.y, 1e-9)
        assertEquals(expected.up.z, got.up.z, 1e-9)
        assertEquals(expected.forward.x, got.forward.x, 1e-9)
        assertEquals(expected.forward.y, got.forward.y, 1e-9)
        assertEquals(expected.forward.z, got.forward.z, 1e-9)
        assertEquals(expected.right.x, got.right.x, 1e-9)
        assertEquals(expected.right.y, got.right.y, 1e-9)
        assertEquals(expected.right.z, got.right.z, 1e-9)
    }

    @Test
    fun `a restored calibration is still thrown away if the phone moved`() {
        // This is what makes restoring safe rather than reckless. If the
        // phone shifted while the app was dead, the next stationary sample
        // must notice and discard, exactly as it would without a restart.
        val before = MountAutoCalibrator()
        repeat(60) { before.onAccelSample(Attitude.Vec3(0.0, 0.0, -Attitude.G), 0.0) }
        val after = MountAutoCalibrator().apply { restore(before.snapshot()!!) }
        assertEquals(MountAutoCalibrator.State.LEVELLED, after.state)

        // Phone now lying on a different face: gravity along -x instead.
        //
        // Fed as a SUSTAINED displacement, not one sample. Since 2026-08-25
        // a move must persist for `moveConfirmSamples` before it is believed
        // -- a single out-of-tolerance reading is a pothole or a hard brake,
        // and treating it as a slide re-levelled the navball 22 times in one
        // drive. A phone that genuinely moved stays moved, so this still
        // discards the restored calibration.
        repeat(30) { after.onAccelSample(Attitude.Vec3(-Attitude.G, 0.0, 0.0), 0.0) }
        assertEquals(
            MountAutoCalibrator.State.UNCALIBRATED, after.state,
            "a moved phone kept its restored calibration"
        )
    }

    @Test
    fun `a restored forward record still decays`() {
        // The strength is restored too, so a stale forward axis can be
        // beaten by current pulls at the usual rate. Restoring it as
        // unbeatable would recreate the bug FORWARD_DECAY exists to fix.
        val before = MountAutoCalibrator()
        repeat(60) { before.onAccelSample(Attitude.Vec3(0.0, 0.0, -Attitude.G), 0.0) }
        before.onAccelSample(Attitude.Vec3(3.0, 0.0, -Attitude.G), 10.0)

        val after = MountAutoCalibrator().apply { restore(before.snapshot()!!) }

        // A sustained series of gentler pulls in the OPPOSITE direction
        // should eventually reclaim the axis, as it would without a restart.
        repeat(20000) { after.onAccelSample(Attitude.Vec3(-2.0, 0.0, -Attitude.G), 10.0) }
        assertTrue(
            after.calibration!!.forward.x < 0.0,
            "restored forward record never decayed; axis stuck pointing +x"
        )
    }
}
