package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WheelSpeeds], checked against frames actually transmitted by the car.
 *
 * Every byte sequence below is copied from the 2026-08-27 captures rather
 * than invented, so a decoder that passes here provably reads the real bus.
 */
class WheelSpeedsTest {

    /** Stationary in the Stop & Shop lot -- the sentinel, four times. */
    private val stationary = listOf(0x27, 0x10, 0x27, 0x10, 0x27, 0x10, 0x27, 0x10)

    /** Under way, from the ridge-road capture. */
    private val moving = listOf(0x2A, 0x91, 0x2A, 0xCF, 0x2A, 0x97, 0x2A, 0xCF)

    @Test
    fun `the stationary sentinel decodes to zero on every wheel`() {
        val r = WheelSpeeds.decode(stationary)
        assertNotNull(r)
        assertEquals(listOf(10000, 10000, 10000, 10000), r.raw)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), r.counts)
        assertEquals(0.0, r.aggregate)
        assertFalse(r.isMoving)
    }

    @Test
    fun `a moving frame decodes to four offset-corrected counts`() {
        val r = WheelSpeeds.decode(moving)
        assertNotNull(r)
        assertEquals(listOf(10897, 10959, 10903, 10959), r.raw)
        assertEquals(listOf(897.0, 959.0, 903.0, 959.0), r.counts)
        assertEquals(929.5, r.aggregate)
        assertTrue(r.isMoving)
    }

    /**
     * The sentinel is a FLOOR, not a zero point to subtract blindly. A wheel
     * reading below it is a corrupt frame however plausible the bytes look,
     * and admitting one would produce a negative speed.
     */
    @Test
    fun `a frame reading below the stationary offset is rejected`() {
        assertNull(WheelSpeeds.decode(listOf(0x00, 0x00, 0x27, 0x10, 0x27, 0x10, 0x27, 0x10)))
        assertNull(WheelSpeeds.decode(listOf(0x27, 0x10, 0x26, 0xFF, 0x27, 0x10, 0x27, 0x10)))
    }

    @Test
    fun `a short frame is rejected`() {
        assertNull(WheelSpeeds.decode(listOf(0x27, 0x10, 0x27, 0x10)))
        assertNull(WheelSpeeds.decode(emptyList()))
    }

    /**
     * The cornering signal. Positions 0 and 2 are one side, 1 and 3 the other,
     * so a turn shows as a difference between those means.
     */
    @Test
    fun `side difference reflects the confirmed alternating layout`() {
        // Sides differ by 100 counts; axles identical.
        val r = WheelSpeeds.decode(
            listOf(0x27, 0x74, 0x27, 0x10, 0x27, 0x74, 0x27, 0x10)
        )
        assertNotNull(r)
        assertEquals(100.0, r.sideDifference)
        assertEquals(0.0, r.axleDifference)
    }

    /** The slip signal pairs one wheel from each side -- the axle split. */
    @Test
    fun `axle difference is orthogonal to side difference`() {
        // Axles differ by 100 counts; sides identical.
        val r = WheelSpeeds.decode(
            listOf(0x27, 0x74, 0x27, 0x74, 0x27, 0x10, 0x27, 0x10)
        )
        assertNotNull(r)
        assertEquals(0.0, r.sideDifference)
        assertEquals(100.0, r.axleDifference)
    }

    /**
     * Curvature, not raw difference, is the yaw-like quantity.
     *
     * The same steering angle at twice the speed doubles the side difference,
     * so the raw difference conflates how hard you are turning with how fast
     * you are going. Dividing by speed separates them.
     */
    @Test
    fun `curvature normalises the side difference by speed`() {
        val slow = WheelSpeeds.decode(
            listOf(0x27, 0x74, 0x27, 0x10, 0x27, 0x74, 0x27, 0x10)
        )
        assertNotNull(slow)
        val c = slow.curvature
        assertNotNull(c)
        assertEquals(100.0 / 50.0, c, 1e-9)
    }

    /**
     * Dividing by a near-zero speed would manufacture enormous phantom yaw
     * out of sensor dither, which is exactly the sort of confident nonsense
     * a gauge must never show.
     */
    @Test
    fun `curvature is null when stopped rather than enormous`() {
        val r = WheelSpeeds.decode(stationary)
        assertNotNull(r)
        assertNull(r.curvature)

        // Crawling, below the moving threshold.
        val crawl = WheelSpeeds.decode(
            listOf(0x27, 0x12, 0x27, 0x11, 0x27, 0x12, 0x27, 0x11)
        )
        assertNotNull(crawl)
        assertFalse(crawl.isMoving)
        assertNull(crawl.curvature)
    }

    /** Differences below the sensor's resolution are not turns. */
    @Test
    fun `a difference under the resolution floor does not count as turning`() {
        val r = WheelSpeeds.decode(moving)
        assertNotNull(r)
        // moving frame: sides are 900.0 vs 959.0 -> a real turn
        assertTrue(r.isTurning)

        val straight = WheelSpeeds.decode(
            listOf(0x27, 0x74, 0x27, 0x74, 0x27, 0x74, 0x27, 0x74)
        )
        assertNotNull(straight)
        assertFalse(straight.isTurning)
    }

    /**
     * The side convention is UNKNOWN until a known-direction circle is driven,
     * and the enum must say so rather than defaulting to a guess -- a wrong
     * default would silently invert a navball.
     */
    @Test
    fun `the side convention starts unknown`() {
        assertEquals(
            WheelSpeeds.SideConvention.UNKNOWN,
            WheelSpeeds.SideConvention.valueOf("UNKNOWN")
        )
        assertEquals(3, WheelSpeeds.SideConvention.values().size)
    }
}
