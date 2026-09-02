package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Grip usage, estimated from the phone's IMU rather than the car's DSC module.
 *
 * ## Why not just read the car's traction sensors?
 *
 * **PARTLY SUPERSEDED 2026-08-27.** The wheel-speed sensors ARE reachable, on
 * MS-CAN rather than generic OBD-II: arbitration ID `215` carries all four
 * wheels at ~10 Hz. See [WheelSpeeds]. The paragraph below remains true of
 * generic Mode 01 — the 34-PID survey in `VEHICLE_SURVEY.md` really does not
 * list them — but "unreachable" was only ever true of that one channel.
 *
 * A lateral accelerometer and a yaw sensor have still NOT been found. Every
 * byte and byte-pair of all 20 observed MS-CAN IDs was correlated against
 * GPS-derived yaw rate and nothing exceeded r = 0.45. Either they are on a bus
 * we do not monitor, or this car's DSC derives yaw from wheel speeds as well.
 * That null result used a 1 Hz reference against a 10 Hz signal, so it is
 * suggestive rather than conclusive.
 *
 * ## This class is still the right thing, and should not be deleted
 *
 * [WheelSpeeds] gives a vehicle-fixed cornering RATE, which is genuinely
 * better than the phone IMU for what it measures: immune to the phone sliding,
 * and alive in tunnels and under tree cover where GPS heading is not. But it
 * has no absolute heading, it is blind below walking pace, and integrating it
 * accumulates drift.
 *
 * So the two are complements, not competitors — the same split already used
 * for altitude, where surveyed elevation is the authority and the barometer
 * interpolates between queries. Keep this estimate; feed it the car's own
 * wheel data when the calibration constants land.
 *
 * ## The friction circle
 *
 * A tyre has a finite grip budget shared between cornering and
 * accelerating/braking. Plotting lateral against longitudinal acceleration
 * traces a rough circle whose radius is the peak available grip. Using 0.6 g
 * laterally leaves far less for braking than 0.2 g does.
 *
 * `combinedG = hypot(lateral, longitudinal)` is how much of that budget is in
 * use, and it is the honest headline number: it says "you are at 70% of what
 * these tyres will do" without needing to know anything about slip angle.
 *
 * ## The disagreement check
 *
 * There is a second, sharper signal available. In a steady turn a vehicle that
 * is gripping satisfies `a_lat = v * yaw_rate`. When the tyres let go, measured
 * lateral acceleration falls below what speed and yaw rate predict — the car is
 * rotating faster than its cornering force can account for.
 *
 * That is a crude cousin of what DSC itself does, built from the phone gyro and
 * PID 010D alone. It will not match the car's own detection for precision, but
 * it does respond to the same physical event.
 */
object Traction {

    /**
     * Typical peak grip for street tyres on dry asphalt, in g.
     *
     * 205/45R17 summer-biased tyres on a light car will manage around 0.9-1.0 g
     * in the dry, rather less in the wet, and far less on anything loose. This
     * is the denominator for [gripUsage], and it is deliberately a tunable
     * assumption rather than a measurement.
     */
    const val ASSUMED_PEAK_GRIP_G = 0.95

    /** Below this speed, yaw-rate comparison is noise. */
    const val MIN_SPEED_FOR_YAW_CHECK_MPS = 5.0

    /** Below this yaw rate the car is going straight; the check does not apply. */
    const val MIN_YAW_RATE_RAD_S = 0.05

    /**
     * How much of the available grip is currently in use, 0..1+.
     *
     * Above 1.0 means the tyres are being asked for more than the assumed peak,
     * which either means they are sliding or that [ASSUMED_PEAK_GRIP_G] is set
     * too low for the actual surface and rubber.
     */
    fun gripUsage(
        lateralG: Double,
        longitudinalG: Double,
        peakGripG: Double = ASSUMED_PEAK_GRIP_G
    ): Double {
        if (peakGripG <= 0.0) return 0.0
        return hypot(lateralG, longitudinalG) / peakGripG
    }

    /**
     * Grip usage bands, for a panel indicator.
     *
     * Deliberately coarse: the underlying peak-grip figure is an assumption,
     * so presenting a precise percentage would overstate what we know.
     */
    enum class Band { CRUISING, WORKING, PRESSING, AT_THE_LIMIT }

    fun band(usage: Double): Band = when {
        usage < 0.25 -> Band.CRUISING
        usage < 0.55 -> Band.WORKING
        usage < 0.85 -> Band.PRESSING
        else -> Band.AT_THE_LIMIT
    }

    /**
     * Compares measured lateral acceleration against what speed and yaw rate
     * imply, as a slip indicator.
     *
     * @return a ratio: 1.0 means measured matches predicted (gripping);
     *   below 1.0 means the car is rotating more than its cornering force
     *   explains, which is what a slide looks like. Null when the vehicle is
     *   too slow or too straight for the comparison to mean anything.
     */
    fun yawAgreement(
        measuredLateralG: Double,
        speedMps: Double,
        yawRateRadPerSec: Double
    ): Double? {
        if (speedMps < MIN_SPEED_FOR_YAW_CHECK_MPS) return null
        if (abs(yawRateRadPerSec) < MIN_YAW_RATE_RAD_S) return null

        val expected = Attitude.expectedLateralG(speedMps, yawRateRadPerSec)
        if (abs(expected) < 1e-6) return null

        return abs(measuredLateralG) / abs(expected)
    }

    /**
     * True when the yaw-rate comparison suggests the tyres have let go.
     *
     * The threshold is loose on purpose. This is an *indicator*, not a safety
     * system: the car's own DSC is authoritative and will intervene long
     * before a phone-derived estimate is worth acting on. Anything shown from
     * this should be presented as an observation after the fact, never as a
     * warning to react to.
     */
    fun suggestsSlip(
        measuredLateralG: Double,
        speedMps: Double,
        yawRateRadPerSec: Double,
        threshold: Double = 0.75
    ): Boolean {
        val agreement = yawAgreement(measuredLateralG, speedMps, yawRateRadPerSec)
            ?: return false
        return agreement < threshold
    }

    /**
     * Energy being dissipated through cornering, in watts.
     *
     * Cornering costs fuel: tyre slip angle produces drag that the engine must
     * overcome, which is why a twisty road returns worse economy than a
     * motorway at the same average speed. This is a rough estimate — real
     * cornering drag depends on slip angle and construction — but it is the
     * right shape, rising with the square of lateral force.
     *
     * Worth having because it lets the panel attribute a chunk of Isp loss to
     * *cornering* rather than leaving it unexplained. It is not currently fed
     * into the road-load model; doing so would need a measured coefficient.
     */
    fun corneringDragWatts(
        lateralG: Double,
        massKg: Double,
        speedMps: Double,
        dragCoefficient: Double = 0.015
    ): Double {
        val latAccel = lateralG * Units.G0
        val lateralForce = massKg * latAccel
        // Induced drag rises with the square of lateral force.
        val drag = dragCoefficient * lateralForce * lateralForce / (massKg * Units.G0)
        return (drag * speedMps).coerceAtLeast(0.0)
    }
}
