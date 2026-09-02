package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [WheelCalibration], the analysis that turns a calibration drive into answers.
 *
 * The acceptance thresholds are pinned here BEFORE the calibration drive
 * happens, deliberately: deciding after the fact what counts as a good fit is
 * how a marginal number talks its way into being treated as settled.
 */
class WheelCalibrationTest {

    /** A wheel frame at a given speed, all four wheels equal. */
    private fun frameLine(t: Long, counts: Int): String {
        val v = WheelSpeeds.STATIONARY_OFFSET + counts
        val hi = (v shr 8) and 0xFF
        val lo = v and 0xFF
        val bytes = listOf(hi, lo, hi, lo, hi, lo, hi, lo).joinToString(",")
        return """{"t":$t,"kind":"frame","id":"215","data":[$bytes]}"""
    }

    /** A wheel frame in a turn: one side faster by [spread] counts. */
    private fun turnLine(t: Long, counts: Int, spread: Int, pos02Faster: Boolean): String {
        val a = WheelSpeeds.STATIONARY_OFFSET + counts + if (pos02Faster) spread else 0
        val b = WheelSpeeds.STATIONARY_OFFSET + counts + if (pos02Faster) 0 else spread
        fun hl(v: Int) = listOf((v shr 8) and 0xFF, v and 0xFF)
        val bytes = (hl(a) + hl(b) + hl(a) + hl(b)).joinToString(",")
        return """{"t":$t,"kind":"frame","id":"215","data":[$bytes]}"""
    }

    /**
     * A straight-line drive at a constant 20 m/s, sampled once a second.
     * 0.00018 degrees of latitude per second is very close to 20 m.
     */
    private fun steadyDrive(n: Int, startMs: Long = 0): List<String> =
        (0 until n).map { i ->
            val lat = 40.9 + i * 0.00018
            """{"t":${startMs + i * 1000L},"kind":"sample","lat":$lat,"lon":-77.96}"""
        }

    @Test
    fun `gps speeds come from unique positions, not held fixes`() {
        // The middle two rows repeat the same position -- the fix being held.
        val drive = listOf(
            """{"t":1000,"kind":"sample","lat":40.90000,"lon":-77.96}""",
            """{"t":2000,"kind":"sample","lat":40.90000,"lon":-77.96}""",
            """{"t":3000,"kind":"sample","lat":40.90018,"lon":-77.96}"""
        )
        val speeds = WheelCalibration.gpsSpeeds(drive)
        // One interval only: 40.90000 -> 40.90018 across 2 s.
        assertEquals(1, speeds.size)
        assertTrue(speeds[0].mps in 8.0..12.0, "got ${speeds[0].mps}")
    }

    /**
     * The bug that produced 65 m/s readings: differencing positions across a
     * long gap. Such intervals must be dropped, not scaled.
     */
    @Test
    fun `a gps interval longer than the limit is rejected`() {
        val drive = listOf(
            """{"t":0,"kind":"sample","lat":40.90,"lon":-77.96}""",
            """{"t":10000,"kind":"sample","lat":40.92,"lon":-77.96}"""
        )
        assertTrue(WheelCalibration.gpsSpeeds(drive).isEmpty())
    }

    @Test
    fun `an impossible speed is rejected even within a valid interval`() {
        // ~2 km in one second.
        val drive = listOf(
            """{"t":0,"kind":"sample","lat":40.90,"lon":-77.96}""",
            """{"t":1000,"kind":"sample","lat":40.92,"lon":-77.96}"""
        )
        assertTrue(WheelCalibration.gpsSpeeds(drive).isEmpty())
    }

    @Test
    fun `scale is recovered from a steady cruise`() {
        // Car reports 1000 counts while GPS says ~20 m/s -> 50 counts per m/s.
        val drive = steadyDrive(60)
        val capture = (0 until 300).map { frameLine(it * 200L, 1000) }

        val fit = WheelCalibration.fitScale(capture, drive)
        assertNotNull(fit)
        assertEquals(50.0, fit.countsPerMps, 3.0)
        assertTrue(fit.samples > 200, "samples=${fit.samples}")
        assertTrue(fit.residualStdevMps < 1.5, "stdev=${fit.residualStdevMps}")
        assertTrue(fit.isTrustworthy)
        // And the fit round-trips.
        assertEquals(20.0, fit.mps(1000.0), 1.5)
    }

    /**
     * A fit built on a handful of points must NOT present itself as usable,
     * however tidy its arithmetic looks.
     */
    @Test
    fun `a thin fit reports itself untrustworthy`() {
        val drive = steadyDrive(6)
        val capture = (0 until 12).map { frameLine(it * 400L, 1000) }
        val fit = WheelCalibration.fitScale(capture, drive)
        assertNotNull(fit)
        assertFalse(fit.isTrustworthy, "samples=${fit.samples}")
    }

    @Test
    fun `no gps reference yields no fit rather than a fabricated one`() {
        val capture = (0 until 300).map { frameLine(it * 200L, 1000) }
        assertEquals(null, WheelCalibration.fitScale(capture, emptyList()))
    }

    /**
     * The headline calibration: a counter-clockwise lap is a sustained LEFT
     * turn, so the RIGHT wheels are outside and faster.
     */
    @Test
    fun `a left turn identifies the faster pair as the right wheels`() {
        // Positions 0,2 run faster throughout -> they are the OUTSIDE, so in a
        // left turn they are the RIGHT wheels, making 1,3 the left.
        val capture = (0 until 200).map { turnLine(it * 100L, 800, 60, pos02Faster = true) }
        val fit = WheelCalibration.fitSide(capture, turnedLeft = true)

        assertEquals(WheelSpeeds.SideConvention.POS_1_3_IS_LEFT, fit.convention)
        assertEquals(1.0, fit.confidence, 1e-9)
        assertTrue(fit.isTrustworthy)
    }

    /** The mirror case must give the mirror answer. */
    @Test
    fun `a right turn inverts the conclusion`() {
        val capture = (0 until 200).map { turnLine(it * 100L, 800, 60, pos02Faster = true) }
        val fit = WheelCalibration.fitSide(capture, turnedLeft = false)
        assertEquals(WheelSpeeds.SideConvention.POS_0_2_IS_LEFT, fit.convention)
        assertTrue(fit.isTrustworthy)
    }

    /**
     * Straight-line running carries no side information. Counting it would
     * dilute a real answer toward a coin flip.
     */
    @Test
    fun `straight running contributes nothing and leaves the side unknown`() {
        val capture = (0 until 200).map { frameLine(it * 100L, 800) }
        val fit = WheelCalibration.fitSide(capture, turnedLeft = true)
        assertEquals(WheelSpeeds.SideConvention.UNKNOWN, fit.convention)
        assertEquals(0, fit.turningSamples)
        assertFalse(fit.isTrustworthy)
    }

    /**
     * A lot loop that wanders both ways is not a calibration drive, and must
     * not be accepted as one.
     */
    @Test
    fun `a mixed-direction drive is not trustworthy`() {
        val capture = (0 until 200).map {
            turnLine(it * 100L, 800, 60, pos02Faster = it % 2 == 0)
        }
        val fit = WheelCalibration.fitSide(capture, turnedLeft = true)
        assertEquals(0.5, fit.confidence, 0.05)
        assertFalse(fit.isTrustworthy)
    }

    @Test
    fun `the report names the verdict in words`() {
        val drive = steadyDrive(60)
        val capture = (0 until 300).map { frameLine(it * 200L, 1000) }
        val text = WheelCalibration.report(capture, drive, turnedLeft = null)
        assertTrue(text.contains("countsPerMps"), text)
        assertTrue(text.contains("VERDICT"), text)
        assertTrue(text.contains("skipped"), text)
    }
}
