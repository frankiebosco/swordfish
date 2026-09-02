package dev.swordfish.physics

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identifying MS-CAN signals by correlating bytes against a known reference.
 *
 * The bus is reachable (227 frames, 18 IDs, 2026-08-20) but nothing in it is
 * decoded. These tests plant a signal in a synthetic bus so the search can be
 * checked against a known answer -- on the real car there is no answer key,
 * which is exactly why the search has to be trustworthy first.
 */
class MsCanIdentifyTest {

    /**
     * A synthetic capture: one ID carries the reference, the rest carry
     * plausible noise and constants.
     */
    private fun syntheticBus(
        n: Int = 120,
        signalId: String = "0x085",
        offset: Int = 2,
        scale: Double = 100.0,
        bigEndian: Boolean = true,
        signed: Boolean = true
    ): Pair<List<MsCanIdentify.Observation>, List<Double>> {
        val obs = ArrayList<MsCanIdentify.Observation>()
        val refs = ArrayList<Double>()
        for (i in 0 until n) {
            // A steady circle then a straight: yaw large and constant, then zero.
            val ref = if (i < n / 2) 0.35 else 0.0
            refs += ref

            // The ID carrying the signal.
            val raw = (ref * scale).toInt()
            val bytes = MutableList(8) { (i * 7 + it * 13) % 256 }
            val hi = (raw shr 8) and 0xFF
            val lo = raw and 0xFF
            if (bigEndian) { bytes[offset] = hi; bytes[offset + 1] = lo }
            else { bytes[offset] = lo; bytes[offset + 1] = hi }
            obs += MsCanIdentify.Observation(signalId, bytes, ref)

            // A constant ID -- must never be reported as a match.
            obs += MsCanIdentify.Observation("0x201", List(8) { 0x55 }, ref)

            // A noisy ID uncorrelated with the reference.
            obs += MsCanIdentify.Observation(
                "0x4B0", List(8) { ((i * 31 + it * 17) % 256) }, ref
            )
        }
        return obs to refs
    }

    @Test
    fun `a planted signal is found, with the right ID and offset`() {
        val (obs, _) = syntheticBus()
        val best = MsCanIdentify.identify(obs).first()
        assertEquals("0x085", best.canId)
        assertEquals(2, best.offset)
        assertTrue(
            best.strength > 0.99,
            "a planted exact signal should correlate near 1, got ${best.correlation}"
        )
    }

    @Test
    fun `the endianness is identified`() {
        val (be, _) = syntheticBus(bigEndian = true)
        assertTrue(MsCanIdentify.identify(be).first().bigEndian)

        val (le, _) = syntheticBus(bigEndian = false, offset = 3)
        val bestLe = MsCanIdentify.identify(le).first()
        assertEquals(3, bestLe.offset)
    }

    @Test
    fun `the scale factor is recovered`() {
        // reference = scale * raw, so a raw scaled by 100 should recover
        // 1/100 as the fitted scale.
        val (obs, _) = syntheticBus(scale = 100.0)
        val best = MsCanIdentify.identify(obs).first()
        assertEquals(
            0.01, best.scale, 0.002,
            "expected to recover 1/100; a wrong scale means a wrong unit on the panel"
        )
    }

    @Test
    fun `a constant byte is never reported as a match`() {
        // 0x201 is all 0x55 throughout. Its variance is zero, and dividing
        // by that would give a NaN that sorts to the top.
        val (obs, _) = syntheticBus()
        val results = MsCanIdentify.identify(obs)
        assertTrue(
            results.none { it.canId == "0x201" },
            "a constant ID cannot correlate with anything"
        )
    }

    @Test
    fun `an ID seen too rarely is not scored`() {
        // A correlation over five points is an accident. The real capture
        // will contain IDs that appear a handful of times.
        val obs = (0 until 5).map {
            MsCanIdentify.Observation("0x999", List(8) { b -> it * b }, it.toDouble())
        }
        assertTrue(MsCanIdentify.identify(obs, minSamples = 30).isEmpty())
    }

    @Test
    fun `an inverted signal is still found`() {
        // A sensor mounted the other way round produces r = -1. That is a
        // match with a sign flip, not a miss.
        val obs = (0 until 100).map { i ->
            val ref = sin(i * 2 * PI / 50)
            val raw = ((-ref) * 1000).toInt() and 0xFFFF
            val bytes = MutableList(8) { 0 }
            bytes[0] = (raw shr 8) and 0xFF
            bytes[1] = raw and 0xFF
            MsCanIdentify.Observation("0x0A1", bytes, ref)
        }
        val best = MsCanIdentify.identify(obs).first()
        assertTrue(best.correlation < -0.9, "expected a strong negative correlation")
        assertTrue(best.strength > 0.9, "strength must ignore the sign")
    }

    // --- decoding ---

    @Test
    fun `signed decoding handles negatives`() {
        // 0xFF38 is -200 as a signed 16-bit value: a left turn, not a
        // 65,336-unit right one.
        val data = listOf(0x00, 0xFF, 0x38, 0x00)
        assertEquals(
            -200,
            MsCanIdentify.decodePair(data, 1, bigEndian = true, signed = true)
        )
        assertEquals(
            0xFF38,
            MsCanIdentify.decodePair(data, 1, bigEndian = true, signed = false)
        )
    }

    @Test
    fun `endianness is applied correctly`() {
        val data = listOf(0x12, 0x34)
        assertEquals(0x1234, MsCanIdentify.decodePair(data, 0, true, false))
        assertEquals(0x3412, MsCanIdentify.decodePair(data, 0, false, false))
    }

    @Test
    fun `decoding past the end of the payload does not throw`() {
        assertEquals(0, MsCanIdentify.decodePair(listOf(0x12), 0, true, false))
        assertEquals(0, MsCanIdentify.decodePair(emptyList(), 3, true, false))
    }

    // --- reference signals ---

    @Test
    fun `yaw rate from bearings wraps the short way`() {
        // 359 -> 1 is a 2 degree right turn, not a 358 degree left one.
        val r = MsCanIdentify.yawRateFromBearings(359.0, 1.0, 1.0)!!
        assertEquals(Math.toRadians(2.0), r, 1e-9)

        val l = MsCanIdentify.yawRateFromBearings(1.0, 359.0, 1.0)!!
        assertEquals(Math.toRadians(-2.0), l, 1e-9)
    }

    @Test
    fun `yaw rate is null without two bearings or a usable interval`() {
        assertNull(MsCanIdentify.yawRateFromBearings(null, 10.0, 1.0))
        assertNull(MsCanIdentify.yawRateFromBearings(10.0, null, 1.0))
        assertNull(MsCanIdentify.yawRateFromBearings(10.0, 20.0, 0.0))
        assertNull(
            MsCanIdentify.yawRateFromBearings(10.0, 20.0, 5.0),
            "a five second gap is not one manoeuvre"
        )
    }

    @Test
    fun `yaw rate from wheel speeds matches the geometry`() {
        // A car turning right has its LEFT wheels travelling further.
        // 20 m/s left, 19 m/s right over a 1.495 m track.
        val yaw = MsCanIdentify.yawRateFromWheels(leftMps = 20.0, rightMps = 19.0)
        assertEquals(-1.0 / 1.495, yaw, 1e-9)
        assertTrue(yaw < 0.0, "outside-left means a right turn, negative yaw")
    }

    @Test
    fun `straight running produces no yaw from wheels`() {
        assertEquals(0.0, MsCanIdentify.yawRateFromWheels(20.0, 20.0), 1e-12)
    }

    @Test
    fun `a zero track width cannot divide by zero`() {
        assertEquals(
            0.0,
            MsCanIdentify.yawRateFromWheels(20.0, 19.0, trackWidthM = 0.0)
        )
    }

    // --- verdict ---

    @Test
    fun `the verdict is honest about what a correlation proves`() {
        val (obs, _) = syntheticBus()
        val v = MsCanIdentify.verdict(MsCanIdentify.identify(obs))
        assertTrue(v.startsWith("STRONG"), v)
        assertTrue(
            v.contains("confirm", ignoreCase = true),
            "even a strong match must ask for a second manoeuvre: $v"
        )
    }

    // --- traction ---

    @Test
    fun `slip ratio is zero when the driven wheels match road speed`() {
        assertEquals(0.0, MsCanIdentify.slipRatio(20.0, 20.0), 1e-9)
    }

    @Test
    fun `a spinning wheel shows positive slip`() {
        // A rear wheel turning 10% faster than the car is moving.
        assertEquals(0.1, MsCanIdentify.slipRatio(22.0, 20.0), 1e-9)
    }

    @Test
    fun `a locking wheel shows negative slip`() {
        // Under braking a wheel can turn slower than the road speed.
        assertTrue(MsCanIdentify.slipRatio(18.0, 20.0) < 0.0)
    }

    @Test
    fun `slip is not computed from rest`() {
        // Dividing by a near-zero reference is how a stationary car reports
        // a 4000% slip.
        assertEquals(0.0, MsCanIdentify.slipRatio(5.0, 0.0), 1e-9)
        assertEquals(0.0, MsCanIdentify.slipRatio(5.0, 0.5), 1e-9)
    }

    @Test
    fun `slip is documented as derived, not directly searchable`() {
        // The honest limit: correlation needs a reference the phone already
        // measures, and the phone has no independent view of wheel slip.
        // Wheel SPEEDS are findable against OBD road speed, and slip falls
        // out of those arithmetically.
        val slip = MsCanIdentify.Target.SLIP
        assertTrue(
            slip.reference.contains("DERIVED"),
            "slip must be documented as derived: ${slip.reference}"
        )
        assertTrue(
            MsCanIdentify.Target.WHEEL_SPEED.reference.contains("road speed"),
            "wheel speed is the searchable route to traction"
        )
    }

    @Test
    fun `no candidates is reported rather than crashing`() {
        assertTrue(MsCanIdentify.verdict(emptyList()).startsWith("NO CANDIDATES"))
    }
}
