package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Vehicle attitude and cornering forces, from the phone's IMU.
 *
 * ## Why this exists
 *
 * An earlier version of `INSTRUMENT_PANEL.md` said the navball's roll axis had
 * to stay level because "we have no lateral sensor and faking it from GPS
 * heading change would be a lie". That was wrong: the phone *is* the lateral
 * sensor. The moto g carries an ICM-4x607 accelerometer and gyroscope at up to
 * 400 Hz, plus fused `ROTATION_VECTOR` and `GRAVITY` virtual sensors at 200 Hz.
 *
 * That gives genuine roll, pitch and cornering G — everything a navball needs,
 * and the lateral-acceleration signal that makes a traction readout possible
 * without touching the car's own DSC module.
 *
 * ## The mounting problem, and why it is solvable
 *
 * The phone is not bolted to the chassis in a known orientation; it sits in a
 * cradle at whatever angle the mount allows. Raw sensor axes are therefore
 * meaningless until calibrated.
 *
 * [MountCalibration] solves this: while the car is stationary and level, the
 * gravity vector reveals which way is down in *phone* axes. Then a brief
 * straight-line acceleration reveals which way is forward. From those two the
 * third axis follows, and every later reading can be rotated into vehicle
 * coordinates.
 *
 * All of this is pure maths on vectors, so it lives in `:physics` and is fully
 * testable without an Android runtime.
 */
object Attitude {

    /** Standard gravity, m/s^2 — the reference for expressing accelerations in G. */
    const val G = Units.G0

    /**
     * A 3-vector in whatever frame the caller is working in.
     *
     * Vehicle frame convention, once calibrated:
     * - **x** — lateral, positive to the driver's right
     * - **y** — vertical, positive up
     * - **z** — longitudinal, positive forward
     */
    data class Vec3(val x: Double, val y: Double, val z: Double) {
        val magnitude: Double get() = sqrt(x * x + y * y + z * z)

        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

        fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

        fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        )

        /** Unit vector, or null when the magnitude is too small to normalise. */
        fun normalized(): Vec3? {
            val m = magnitude
            if (m < 1e-9) return null
            return Vec3(x / m, y / m, z / m)
        }

        companion object {
            val ZERO = Vec3(0.0, 0.0, 0.0)
        }
    }

    /**
     * Vehicle attitude and the accelerations acting on it.
     *
     * @param rollRadians Positive when the car leans to the driver's right,
     *   i.e. in a left-hand turn.
     *
     *   **Known limitation:** an accelerometer cannot distinguish body roll
     *   from lateral acceleration — both tip the gravity vector the same way.
     *   This figure is therefore chassis lean *plus* road camber *plus* the
     *   cornering force acting on the sensor, undifferentiated. Separating
     *   them would require integrating gyroscope roll rate and fusing it
     *   against the accelerometer, which drifts and needs tuning.
     *
     *   For a navball this is acceptable — the ball tips when the car is
     *   working laterally, which is the right *feel* — but it is not a
     *   measurement of suspension travel.
     *
     *   **This stays the source (decided 2026-08-29).** MS-CAN access WAS
     *   achieved and the car's own wheel speeds do yield a true yaw rate
     *   ([decomposeRoll] consumes it) — but wheel speeds are on MS-CAN while
     *   speed, rpm and fuel are on HS-CAN, and one adapter reads one bus at a
     *   time. Using the car's sensors live would cost the entire fuel model,
     *   so this is no longer "a fallback pending better data". The realistic
     *   improvement is **mounting the phone**, which removes the phone's own
     *   motion from the reading even though it cannot separate lean from
     *   cornering. See `docs/MSCAN_SIGNALS.md`.
     * @param pitchRadians Positive nose-up. Combines road grade with
     *   squat/dive under acceleration and braking.
     * @param lateralG Cornering acceleration, positive rightward.
     * @param longitudinalG Positive accelerating, negative braking.
     */
    data class Reading(
        val rollRadians: Double,
        val pitchRadians: Double,
        val lateralG: Double,
        val longitudinalG: Double
    ) {
        /** Total horizontal acceleration — the "friction circle" magnitude. */
        val combinedG: Double get() = hypot(lateralG, longitudinalG)

        /** Where on the friction circle we are, in radians. */
        val gForceHeading: Double get() = atan2(lateralG, longitudinalG)
    }

    /**
     * Extract attitude and G forces from a calibrated accelerometer reading.
     *
     * @param accel Total acceleration in **vehicle** axes, gravity included —
     *   i.e. Android's `TYPE_ACCELEROMETER` after mount rotation.
     */
    fun fromVehicleFrameAccel(accel: Vec3): Reading {
        // NOTE the negated Y. An accelerometer at rest reads the reaction to
        // gravity, so a LEVEL vehicle gives accel.y = -G, not +G. Using
        // atan2(x, y) directly would make atan2(0, -9.81) = 180 degrees and a
        // level car would report fully inverted -- which is exactly what
        // happened the first time a real flat-on-its-back reading was fed in.
        val roll = atan2(accel.x, -accel.y)
        val pitch = atan2(accel.z, -accel.y)
        return Reading(
            rollRadians = roll,
            pitchRadians = pitch,
            lateralG = accel.x / G,
            longitudinalG = accel.z / G
        )
    }

    /**
     * Roll separated into body lean and cornering force, using a TRUE yaw
     * rate from the car's own wheel-speed sensors.
     *
     * ## The problem this solves
     *
     * An accelerometer cannot tell body lean from lateral acceleration:
     * both tip the gravity vector the same way, so [Reading.rollRadians] is
     * chassis lean *plus* road camber *plus* cornering force *plus* whatever
     * the phone is doing on the seat -- undifferentiated. That is stated as a
     * known limitation on [Reading] and it is why the navball tips for the
     * right *feel* but is not a measurement.
     *
     * Wheel speeds break the tie. Two wheels on one axle differ by
     * `yawRate * track`, so `WheelSpeeds.Reading.yawRateRadPerSec` yields a
     * yaw rate measured by sensors bolted to the chassis -- no gravity vector
     * involved, nothing to slide. From it, `a_lat = v * omega` gives the
     * lateral acceleration the corner MUST be producing. Subtract that from
     * the accelerometer's lateral channel and what remains is the part
     * gravity is responsible for: **actual lean**.
     *
     * ## What this does and does not fix
     *
     * It removes the CORNERING component. It cannot separate chassis roll
     * from road camber -- both are genuine gravity-vector tilts and no
     * amount of yaw data distinguishes them. A phone sliding mid-corner
     * still corrupts the reading; this makes that corruption visible
     * (as [phoneDisagreement]) rather than silently folding it into roll.
     *
     * @param accel total acceleration in VEHICLE axes, gravity included --
     *   the same input [fromVehicleFrameAccel] takes.
     * @param speedMps road speed.
     * @param yawRateRadPerSec TRUE yaw rate, positive turning right, from
     *   `WheelSpeeds.Reading.yawRateRadPerSec`. Passing the phone gyro here
     *   defeats the purpose -- the point is an independent reference.
     */
    fun decomposeRoll(
        accel: Vec3,
        speedMps: Double,
        yawRateRadPerSec: Double
    ): RollDecomposition {
        val measured = fromVehicleFrameAccel(accel)

        // Lateral acceleration the corner must be producing, in m/s^2.
        // Positive yaw is a right turn, which throws the occupants LEFT, so
        // the reaction the accelerometer sees along +x is positive for a
        // right turn -- the same sign convention as lateralG.
        val corneringAccel = speedMps * yawRateRadPerSec

        // Remove it from the lateral channel and re-derive the tilt. What is
        // left in x is gravity's contribution, so this atan2 is body lean.
        val leanAccel = Vec3(accel.x - corneringAccel, accel.y, accel.z)
        val lean = atan2(leanAccel.x, -leanAccel.y)

        return RollDecomposition(
            measuredRollRadians = measured.rollRadians,
            leanRollRadians = lean,
            corneringG = corneringAccel / G,
            measuredLateralG = measured.lateralG
        )
    }

    /**
     * Roll split into what the corner explains and what it does not.
     *
     * @param measuredRollRadians the raw accelerometer tilt -- what the
     *   navball shows today.
     * @param leanRollRadians tilt remaining once the corner's lateral
     *   acceleration is removed. This is body lean plus road camber.
     * @param corneringG lateral G the wheels say the corner is producing.
     * @param measuredLateralG lateral G the accelerometer reports.
     */
    data class RollDecomposition(
        val measuredRollRadians: Double,
        val leanRollRadians: Double,
        val corneringG: Double,
        val measuredLateralG: Double
    ) {
        /** Roll attributable to cornering rather than to lean. */
        val corneringRollRadians: Double
            get() = measuredRollRadians - leanRollRadians

        /**
         * Gap between what the wheels predict and what the phone reports.
         *
         * In steady cornering on flat ground these should agree. A large
         * persistent gap means one of them is wrong, and the wheels are the
         * one bolted to the car -- so this is the signal that the PHONE has
         * moved, which previously had no independent check at all.
         */
        val phoneDisagreement: Double
            get() = measuredLateralG - corneringG

        /**
         * True when the phone and the wheels disagree beyond plausible
         * cornering.
         *
         * Threshold is deliberately loose: real disagreement of 0.15 G is
         * far outside sensor noise and road camber, and a false alarm here
         * would discredit a reading that is probably fine.
         */
        val phoneLikelyMoved: Boolean
            get() = abs(phoneDisagreement) > PHONE_DISAGREEMENT_G
    }

    /** See [RollDecomposition.phoneLikelyMoved]. */
    const val PHONE_DISAGREEMENT_G = 0.15

    /**
     * Lateral acceleration expected from speed and yaw rate.
     *
     * `a_lat = v * omega` for a vehicle in a steady turn. Comparing this with
     * the *measured* lateral acceleration is the basis of the traction
     * estimate — see [TractionEstimate].
     *
     * @param speedMps From PID 010D.
     * @param yawRateRadPerSec From the phone gyroscope's vertical axis.
     */
    fun expectedLateralG(speedMps: Double, yawRateRadPerSec: Double): Double =
        speedMps * yawRateRadPerSec / G

    /**
     * Turn radius implied by speed and yaw rate, in metres.
     *
     * Returns null when going straight or nearly stopped, where radius is
     * unbounded and the figure would be meaningless.
     */
    fun turnRadiusM(speedMps: Double, yawRateRadPerSec: Double): Double? {
        if (abs(yawRateRadPerSec) < 1e-3 || speedMps < 0.5) return null
        return speedMps / abs(yawRateRadPerSec)
    }
}

/**
 * Establishes how the phone is oriented relative to the car.
 *
 * Two observations are needed, both easy to collect in normal use:
 *
 * 1. **Down** — average the gravity vector while stationary and level.
 * 2. **Forward** — average linear acceleration during a brief straight-line
 *    pull. Any accelerator application on a straight road will do.
 *
 * With those, the vehicle frame is fully determined and every subsequent
 * sensor reading can be rotated into it.
 */
class MountCalibration(
    /** Gravity direction in phone axes, from a stationary sample. */
    private val downInPhone: Attitude.Vec3,
    /** Forward direction in phone axes, from a straight-line acceleration. */
    forwardInPhone: Attitude.Vec3
) {
    /** Unit vector pointing up, in phone axes. */
    val up: Attitude.Vec3 = (downInPhone * -1.0).normalized()
        ?: Attitude.Vec3(0.0, 1.0, 0.0)

    /**
     * Unit vector pointing forward, in phone axes, with any vertical component
     * removed — a car's forward axis is horizontal by definition, and the
     * acceleration sample will have picked up some road grade.
     */
    val forward: Attitude.Vec3 = run {
        val f = forwardInPhone
        val vertical = up * f.dot(up)
        (f - vertical).normalized() ?: Attitude.Vec3(0.0, 0.0, 1.0)
    }

    /** Unit vector pointing to the driver's right, in phone axes. */
    val right: Attitude.Vec3 = forward.cross(up).normalized()
        ?: Attitude.Vec3(1.0, 0.0, 0.0)

    /** Rotate a phone-frame vector into vehicle axes. */
    fun toVehicleFrame(v: Attitude.Vec3): Attitude.Vec3 = Attitude.Vec3(
        x = v.dot(right),
        y = v.dot(up),
        z = v.dot(forward)
    )

    /**
     * True when the calibration axes are usably orthogonal.
     *
     * A bad calibration — phone moved mid-sample, or "forward" collected while
     * turning — produces axes that are not perpendicular, and every derived
     * reading would be quietly wrong. Better to detect it and ask for a redo.
     */
    fun isValid(): Boolean {
        val fu = abs(forward.dot(up))
        val fr = abs(forward.dot(right))
        val ru = abs(right.dot(up))
        return fu < 0.05 && fr < 0.05 && ru < 0.05
    }

    companion object {
        /**
         * Calibration for a phone mounted perfectly upright in a windscreen
         * cradle, screen facing the driver. Android's convention there is
         * +Y up the screen, +Z out of the screen toward the driver, so
         * forward is -Z.
         *
         * A sane default until the user runs a real calibration.
         */
        val UPRIGHT_CRADLE = MountCalibration(
            downInPhone = Attitude.Vec3(0.0, -Attitude.G, 0.0),
            forwardInPhone = Attitude.Vec3(0.0, 0.0, -1.0)
        )
    }
}
