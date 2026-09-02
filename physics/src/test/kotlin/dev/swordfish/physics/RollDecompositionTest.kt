package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Separating body lean from cornering force using wheel-derived yaw.
 *
 * The accelerometer alone cannot do this — both tip the gravity vector
 * identically. These tests build accelerometer readings for known physical
 * situations and check the decomposition attributes each one correctly.
 */
class RollDecompositionTest {

    private val g = Attitude.G

    /** A level car, stationary. */
    private fun level() = Attitude.Vec3(0.0, -g, 0.0)

    @Test
    fun `a level stationary car has no lean and no cornering`() {
        val d = Attitude.decomposeRoll(level(), speedMps = 0.0, yawRateRadPerSec = 0.0)
        assertEquals(0.0, d.leanRollRadians, 1e-9)
        assertEquals(0.0, d.corneringG, 1e-9)
        assertEquals(0.0, d.corneringRollRadians, 1e-9)
    }

    /**
     * The case the whole feature exists for.
     *
     * A car cornering on FLAT ground with no body roll still reads a tilted
     * accelerometer, because the lateral force tips the gravity vector. The
     * naive roll is non-zero; the decomposed lean must be ~zero.
     */
    @Test
    fun `flat cornering is attributed to the corner, not to lean`() {
        val speed = 20.0
        val yaw = 0.25 // rad/s, a right turn
        val lat = speed * yaw // 5.0 m/s^2 of real cornering

        // Accelerometer in a flat corner: lateral reaction plus gravity.
        val accel = Attitude.Vec3(lat, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speed, yaw)

        assertTrue(
            abs(d.measuredRollRadians) > 0.4,
            "the raw reading should look strongly rolled"
        )
        assertEquals(
            0.0, d.leanRollRadians, 1e-6,
            "with the corner removed there is no lean left"
        )
        assertTrue(
            abs(d.corneringRollRadians) > 0.4,
            "all of the apparent roll belongs to the corner"
        )
    }

    /**
     * The complement: leaning while going straight is all lean.
     *
     * A car on a cambered road, not turning, must attribute its tilt to lean
     * because there is no yaw to explain it.
     */
    @Test
    fun `a camber with no yaw is attributed entirely to lean`() {
        val accel = Attitude.Vec3(2.0, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speedMps = 20.0, yawRateRadPerSec = 0.0)
        assertEquals(
            d.measuredRollRadians, d.leanRollRadians, 1e-9,
            "no yaw means nothing to subtract"
        )
        assertEquals(0.0, d.corneringRollRadians, 1e-9)
    }

    @Test
    fun `real body lean survives the subtraction`() {
        val speed = 20.0
        val yaw = 0.25
        val lat = speed * yaw
        // The corner's lateral force PLUS an extra 2 m/s^2 of genuine lean.
        val accel = Attitude.Vec3(lat + 2.0, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speed, yaw)
        assertTrue(d.leanRollRadians > 0.0, "the extra tilt must remain")
        assertEquals(
            kotlin.math.atan2(2.0, g), d.leanRollRadians, 1e-6,
            "and it must equal exactly the un-explained part"
        )
    }

    @Test
    fun `cornering G carries the sign of the yaw rate`() {
        val right = Attitude.decomposeRoll(level(), 20.0, 0.25)
        val left = Attitude.decomposeRoll(level(), 20.0, -0.25)
        assertTrue(right.corneringG > 0, "positive yaw is a right turn")
        assertTrue(left.corneringG < 0, "negative yaw is a left turn")
    }

    @Test
    fun `stationary means no cornering however the wheels read`() {
        val d = Attitude.decomposeRoll(level(), speedMps = 0.0, yawRateRadPerSec = 0.5)
        assertEquals(0.0, d.corneringG, 1e-9, "v * omega is zero when v is zero")
    }

    // ---- phone-movement detection ------------------------------------------

    @Test
    fun `agreement between phone and wheels is not flagged`() {
        val speed = 20.0
        val yaw = 0.25
        val accel = Attitude.Vec3(speed * yaw, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speed, yaw)
        assertTrue(abs(d.phoneDisagreement) < 1e-6)
        assertTrue(!d.phoneLikelyMoved)
    }

    /**
     * A phone that has slid reports lateral G the corner cannot explain.
     */
    @Test
    fun `a large unexplained lateral reading flags the phone`() {
        val speed = 20.0
        val yaw = 0.25
        // Phone reports far more lateral G than v*omega can account for.
        val accel = Attitude.Vec3(speed * yaw + 0.4 * g, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speed, yaw)
        assertTrue(
            d.phoneLikelyMoved,
            "0.4 G unexplained is well past the ${Attitude.PHONE_DISAGREEMENT_G} G threshold"
        )
    }

    @Test
    fun `small disagreement stays below the threshold`() {
        val speed = 20.0
        val yaw = 0.25
        val accel = Attitude.Vec3(speed * yaw + 0.05 * g, -g, 0.0)
        val d = Attitude.decomposeRoll(accel, speed, yaw)
        assertTrue(
            !d.phoneLikelyMoved,
            "0.05 G is ordinary camber/noise and must not raise a false alarm"
        )
    }

    /**
     * End to end: a wheel frame drives the decomposition.
     *
     * Uses the measured side convention, so a sign regression in either
     * WheelSpeeds or Attitude surfaces here.
     */
    @Test
    fun `a wheel frame feeds the decomposition end to end`() {
        // Right turn: left wheels (positions 1,3 by MEASURED_CONVENTION)
        // are outside and faster.
        val counts = intArrayOf(4100, 4200, 4100, 4200)
        val bytes = buildList {
            for (c in counts) {
                val raw = c + WheelSpeeds.STATIONARY_OFFSET
                add((raw shr 8) and 0xFF)
                add(raw and 0xFF)
            }
        }
        val r = WheelSpeeds.decode(bytes)!!
        val k = 0.00275
        val yaw = r.yawRateRadPerSec(k)!!
        assertTrue(yaw > 0, "left wheels faster = right turn = positive yaw")

        val speed = r.speedMps(k)
        val d = Attitude.decomposeRoll(Attitude.Vec3(speed * yaw, -g, 0.0), speed, yaw)
        assertEquals(
            0.0, d.leanRollRadians, 1e-6,
            "a pure flat corner leaves no lean"
        )
    }
}
