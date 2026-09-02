package dev.swordfish.physics

import kotlin.math.abs

/**
 * Works out how the phone is oriented in the car, without asking the user to
 * mount it any particular way.
 *
 * ## The problem
 *
 * The phone does not know which way the car is pointing. A fixed assumption —
 * "it is upright in a windscreen cradle" — is wrong the moment the phone is
 * lying flat on the passenger seat, which is how it usually travels. Gravity
 * then reads on a completely different axis and every derived figure is
 * garbage: an earlier build assumed a cradle, read the flat phone's pitch as a
 * steep descent, and drove delta-V to zero.
 *
 * There is a further wrinkle that a simple "tare" cannot fix. Levelling the
 * phone tells you **down**, and that is genuinely free. But it tells you
 * nothing about **forward**: a phone lying flat can be rotated to any compass
 * heading on the seat without gravity changing at all. Down and forward are
 * separate unknowns and need separate observations.
 *
 * ## The two stages
 *
 * **Stage 1 — down (immediate, passive).** Average the accelerometer while the
 * vehicle is stationary. That vector is gravity, which fixes the vertical axis.
 * Enough on its own for a G-force magnitude readout, and it removes the worst
 * failure mode straight away.
 *
 * **Stage 2 — forward (needs one straight-line pull).** While accelerating in a
 * straight line, the horizontal component of acceleration points forward. One
 * ordinary pull away from a junction is plenty. Until this lands, lateral and
 * longitudinal cannot be told apart, and the navball's roll axis is unknown.
 *
 * Both stages are automatic — no menu, no "hold your phone like this" screen —
 * and [state] tells the UI which readouts are trustworthy yet.
 */
class MountAutoCalibrator(
    /** Samples to average for the gravity estimate. */
    private val gravitySamples: Int = 50,
    /**
     * Consecutive out-of-tolerance samples before a move is believed.
     *
     * At ~50 Hz, 25 is half a second. A phone that has genuinely slid stays
     * displaced; a pothole or a hard brake does not. Before this existed the
     * navball re-levelled 22 times in one drive and spent 38% of it
     * UNCALIBRATED.
     */
    private val moveConfirmSamples: Int = 25,
    /**
     * Minimum horizontal acceleration to treat as a usable forward cue, m/s².
     *
     * ~1.5 m/s² is a gentle pull away from a stop — brisk enough to dominate
     * sensor noise, mild enough that normal driving produces it constantly.
     */
    private val minForwardAccel: Double = 1.5,
    /**
     * How steady gravity must be to count the vehicle as stationary, m/s².
     *
     * Engine idle vibration is well under this; driving is well over.
     */
    private val stationaryTolerance: Double = 0.35
) {

    enum class State {
        /** Nothing known. No attitude readouts are valid. */
        UNCALIBRATED,

        /** Gravity known. Tilt and total G are valid; direction is not. */
        LEVELLED,

        /** Both axes known. Everything is valid. */
        CALIBRATED
    }

    var state: State = State.UNCALIBRATED
        private set

    /** The calibration, once at least gravity is known. */
    var calibration: MountCalibration? = null
        private set

    private var gravitySum = Attitude.Vec3.ZERO
    private var gravityCount = 0
    private var settledGravity: Attitude.Vec3? = null

    /** Consecutive out-of-tolerance samples while stopped. */
    private var movedRun: Int = 0

    /** Best forward estimate so far, refined as better pulls are seen. */
    private var forwardEstimate: Attitude.Vec3? = null
    private var forwardStrength = 0.0

    /**
     * Feed a raw accelerometer sample, in phone axes.
     *
     * @param accel Total acceleration including gravity.
     * @param speedMps Vehicle speed, if known. Used to tell stationary from
     *   moving; null falls back to judging by the accelerometer alone.
     */
    fun onAccelSample(accel: Attitude.Vec3, speedMps: Double? = null) {
        val magnitude = accel.magnitude
        val looksStationary = abs(magnitude - Attitude.G) < stationaryTolerance &&
            (speedMps == null || speedMps < 0.5)

        // A phone loose on the passenger seat MIGRATES. It slides on every
        // corner, and when it does, the stored gravity vector describes an
        // orientation the phone no longer has -- so pitch, roll and the
        // forward axis all quietly become wrong while still being reported
        // with full confidence.
        //
        // Detecting it needs a STATIONARY sample: while driving, cornering
        // and braking tilt the measured acceleration vector away from
        // gravity all the time, and treating that as movement would reset
        // the calibration constantly. When the car is stopped, though, the
        // only thing that can move the vector is the phone itself.
        // A MOVE MUST PERSIST TO COUNT.
        //
        // One out-of-tolerance sample is not a phone sliding off the seat --
        // it is a pothole, a hard brake, or a door closing. The car being
        // stopped does not make the accelerometer quiet.
        //
        // Measured on the 2026-08-25 drive: 869 of 2276 samples (38%) sat in
        // UNCALIBRATED across 22 transitions, against 4 transitions on the
        // drive before. The navball was re-levelling constantly, and the
        // report was "especially after stop/starting" -- exactly where
        // `looksStationary` becomes true and every jostle is then read as a
        // move.
        //
        // Requiring the displacement to hold for [moveConfirmSamples] costs a
        // fraction of a second on a genuine slide and rejects every transient.
        if (looksStationary && settledGravity != null && hasMoved(accel)) {
            movedRun++
            if (movedRun >= moveConfirmSamples) {
                movedRun = 0
                reset()
                // Fall through: this sample is a perfectly good first sample
                // of the NEW orientation, so re-levelling starts immediately
                // rather than waiting for the next one.
            }
        } else {
            movedRun = 0
        }

        if (looksStationary && settledGravity == null) {
            gravitySum += accel
            gravityCount++
            if (gravityCount >= gravitySamples) {
                val avg = gravitySum * (1.0 / gravityCount)
                settledGravity = avg
                state = State.LEVELLED
                rebuild()
            }
            return
        }

        // Moving: look for a forward cue.
        val g = settledGravity ?: return
        val up = (g * -1.0).normalized() ?: return

        // Strip gravity, then strip the vertical component of what is left.
        val linear = accel - g
        val horizontal = linear - up * linear.dot(up)
        val strength = horizontal.magnitude

        if (strength < minForwardAccel) return

        // Prefer the strongest pull seen -- braking and cornering also
        // produce horizontal acceleration, and a hard straight-line pull is
        // the cleanest single cue available without a heading reference.
        //
        // But the record DECAYS. Without that, the hardest pull of the
        // journey defines "forward" permanently: if the phone then slides,
        // no ordinary acceleration can ever beat the stale record, and the
        // navball stays confidently wrong for the rest of the drive.
        // Decaying lets a sustained series of gentler-but-current pulls
        // reclaim the axis within about a minute of driving.
        forwardStrength *= FORWARD_DECAY

        if (strength > forwardStrength) {
            forwardStrength = strength
            forwardEstimate = horizontal
            state = State.CALIBRATED
            rebuild()
        }
    }

    private fun rebuild() {
        val g = settledGravity ?: return
        val fwd = forwardEstimate
            // Before a forward cue arrives, pick an arbitrary horizontal axis
            // so tilt magnitude still works. Direction will be wrong until
            // stage 2 completes -- state reports LEVELLED to say so.
            ?: arbitraryHorizontal(g)
        calibration = MountCalibration(downInPhone = g, forwardInPhone = fwd)
    }

    /**
     * Any horizontal vector, for the levelled-but-not-orientated case.
     *
     * Cross gravity with whichever world axis it is least aligned to, which
     * guarantees a non-degenerate result whatever the phone's attitude.
     */
    private fun arbitraryHorizontal(gravity: Attitude.Vec3): Attitude.Vec3 {
        val g = gravity.normalized() ?: return Attitude.Vec3(0.0, 0.0, 1.0)
        val candidate = if (abs(g.x) < 0.9) {
            Attitude.Vec3(1.0, 0.0, 0.0)
        } else {
            Attitude.Vec3(0.0, 1.0, 0.0)
        }
        return g.cross(candidate).cross(g).normalized()
            ?: Attitude.Vec3(0.0, 0.0, 1.0)
    }

    /** Human-readable status for the settings screen. */
    fun describe(): String = when (state) {
        State.UNCALIBRATED -> "levelling — hold still"
        State.LEVELLED -> "levelled — drive straight to finish"
        State.CALIBRATED -> "calibrated"
    }

    /**
     * The calibrator's whole learned state, for saving across a restart.
     *
     * ## Why this exists
     *
     * Calibration lived only in memory, so any process death threw away
     * both stages and the driver had to earn them back at speed. That is
     * not hypothetical: the Android Auto host crashes on its own (see the
     * launcher notes), and on the 2026-08-21 drive the restart cost 118 s
     * to re-level and then lost it again 46 s later -- **72% of that
     * session had no navball at all**, because `drawNavball` correctly
     * refuses to draw a horizon it cannot trust.
     *
     * Nothing about the phone changes in the four seconds a host crash
     * takes, so the calibration that was valid before the crash is still
     * valid after it. Restoring it is honest, and [hasMoved] still throws
     * it away the moment the phone actually shifts.
     */
    data class Snapshot(
        val gravityX: Double,
        val gravityY: Double,
        val gravityZ: Double,
        val forwardX: Double,
        val forwardY: Double,
        val forwardZ: Double,
        val forwardStrength: Double,
        val hasForward: Boolean
    )

    /**
     * Capture the current calibration, or null if there is nothing to save.
     *
     * Null until gravity has settled -- a half-finished average is not
     * worth persisting and would only delay a clean re-level.
     */
    fun snapshot(): Snapshot? {
        val g = settledGravity ?: return null
        val f = forwardEstimate
        return Snapshot(
            gravityX = g.x, gravityY = g.y, gravityZ = g.z,
            forwardX = f?.x ?: 0.0,
            forwardY = f?.y ?: 0.0,
            forwardZ = f?.z ?: 0.0,
            forwardStrength = forwardStrength,
            hasForward = f != null
        )
    }

    /**
     * Restore a saved calibration, skipping the re-learning entirely.
     *
     * The restored state is exactly what was saved: LEVELLED when only
     * gravity was known, CALIBRATED when a forward cue had been found. The
     * forward strength is restored too, so a stale record still decays at
     * the usual rate rather than being unbeatable.
     *
     * **The restored calibration is not privileged.** [hasMoved] runs on
     * the next stationary sample as it always does, so a phone that shifted
     * while the app was dead is detected and the calibration discarded --
     * which is the case that makes restoring safe rather than reckless.
     */
    fun restore(s: Snapshot) {
        settledGravity = Attitude.Vec3(s.gravityX, s.gravityY, s.gravityZ)
        gravitySum = Attitude.Vec3.ZERO
        gravityCount = 0
        if (s.hasForward) {
            forwardEstimate = Attitude.Vec3(s.forwardX, s.forwardY, s.forwardZ)
            forwardStrength = s.forwardStrength
            state = State.CALIBRATED
        } else {
            forwardEstimate = null
            forwardStrength = 0.0
            state = State.LEVELLED
        }
        rebuild()
    }

    /**
     * Discard everything, e.g. when the phone is moved mid-journey.
     *
     * Worth exposing: a phone that slides off the seat is no longer calibrated
     * and silently wrong readings are worse than none.
     */
    fun reset() {
        rollBiasRadians = 0.0
        rollSamples = 0
        state = State.UNCALIBRATED
        calibration = null
        gravitySum = Attitude.Vec3.ZERO
        gravityCount = 0
        settledGravity = null
        forwardEstimate = null
        forwardStrength = 0.0
        movedRun = 0
    }

    /**
     * True when the phone appears to have been moved since calibration.
     *
     * Compares current gravity against the settled estimate. A large sustained
     * disagreement while stationary means the phone was picked up or slid, and
     * the calibration should be thrown away.
     */
    /**
     * Set the forward axis directly, from a known world-space direction.
     *
     * ## Why this beats waiting for a straight-line pull
     *
     * The acceleration route ([onAccelSample]) infers forward from a hard
     * pull away from a stop. It needs >1.5 m/s² to register, confuses
     * braking and cornering for acceleration, and once it latches it only
     * updates on a *stronger* pull — so a phone that slides afterwards
     * leaves the axis wrong with no way back. On the 2026-08-21 drive
     * `DRIVE TO ORIENT` was on screen for the entire journey and the
     * compass "kept getting thrown off and could not be recovered".
     *
     * GPS course over ground has none of those problems. A car moving
     * forward is travelling along its own forward axis, so the bearing IS
     * the answer — absolute, un-integrated, and available at any speed
     * above walking pace. Given the phone's rotation matrix, that world
     * direction can be rotated back into phone coordinates in one step.
     *
     * Gravity is still required first: forward must be perpendicular to
     * down, and only [onAccelSample] can establish which way down is.
     *
     * @param forwardInPhone the car's forward axis, already expressed in
     *   phone coordinates by the caller (which owns the rotation matrix).
     */
    fun setForwardFromWorld(forwardInPhone: Attitude.Vec3) {
        // Down must be known: a forward axis with no vertical reference
        // cannot be made perpendicular to anything.
        if (settledGravity == null) return
        if (forwardInPhone.normalized() == null) return

        forwardEstimate = forwardInPhone
        // Treat a GPS-derived axis as unbeatable by acceleration guesses.
        // It is a direct measurement; those are inferences.
        forwardStrength = Double.MAX_VALUE
        state = State.CALIBRATED
        rebuild()
    }

    /**
     * Zero the roll axis, adopting the current attitude as level.
     *
     * ## The fake roll, observed 2026-08-21
     *
     * Gravity fixes "up" and GPS fixes "forward", but the third axis —
     * rotation ABOUT forward — is still unknown, and that is roll. A phone
     * resting on its edge in the seat bight reads a large constant roll
     * even on a level road, because gravity alone cannot distinguish:
     *
     *   - a phone lying flat in a level car
     *   - a phone tilted 30 degrees in a car tilted 30 degrees the other way
     *
     * Both produce an identical gravity vector. No amount of filtering
     * fixes an ambiguity this fundamental.
     *
     * What resolves it is an assumption that is true almost all the time:
     * **averaged over a drive, a car is level.** Roads are banked a little
     * and corners lean a little, but it all cancels. So the mean roll over
     * a long window is the MOUNT's tilt, not the car's, and subtracting it
     * leaves real body roll.
     *
     * @param observedRollRadians the raw roll from [Attitude], including
     *   the mount's own tilt.
     */
    fun observeRoll(observedRollRadians: Double) {
        if (!observedRollRadians.isFinite()) return
        rollSamples++
        // Running mean, so a long drive costs no memory.
        rollBiasRadians += (observedRollRadians - rollBiasRadians) / rollSamples
    }

    /**
     * The mount's own tilt about the forward axis, radians.
     *
     * Subtract from a raw roll reading to get the car's actual body roll.
     * Zero until [ROLL_BIAS_MIN_SAMPLES] observations have accumulated —
     * before that the mean is dominated by whatever the car was doing
     * during the first few seconds.
     */
    val rollBias: Double
        get() = if (rollSamples >= ROLL_BIAS_MIN_SAMPLES) rollBiasRadians else 0.0

    private var rollBiasRadians = 0.0
    private var rollSamples = 0

    fun hasMoved(accel: Attitude.Vec3, toleranceDeg: Double = 20.0): Boolean {
        val g = settledGravity ?: return false
        val a = accel.normalized() ?: return false
        val b = g.normalized() ?: return false
        val cos = a.dot(b).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(cos)) > toleranceDeg
    }

    private companion object {
        /**
         * Roll observations before the bias estimate is trusted.
         *
         * 500 at ~50 Hz is ten seconds. Long enough that a single corner
         * cannot define "level", short enough to settle early in a drive.
         */
        const val ROLL_BIAS_MIN_SAMPLES = 500

        /**
         * Per-sample decay applied to the best-pull record.
         *
         * At ~50 Hz this halves the record in roughly 90 seconds of driving
         * — slow enough that one good pull keeps the axis stable through a
         * junction, fast enough that a stale record cannot outlive a phone
         * that has slid across the seat.
         */
        const val FORWARD_DECAY = 0.99985
    }


}
