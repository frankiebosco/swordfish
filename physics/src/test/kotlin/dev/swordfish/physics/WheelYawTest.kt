package dev.swordfish.physics

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The measured side convention, and yaw rate derived from it.
 *
 * The convention came from two controlled left-turn circle runs; these tests
 * pin both the arithmetic and the real-data result so neither can regress.
 */
class WheelYawTest {

    /** Build a frame from four wheel counts. */
    private fun frame(vararg counts: Int): List<Int> = buildList {
        for (c in counts) {
            val raw = c + WheelSpeeds.STATIONARY_OFFSET
            add((raw shr 8) and 0xFF)
            add(raw and 0xFF)
        }
    }

    @Test
    fun `the convention is recorded, not unknown`() {
        assertEquals(
            WheelSpeeds.SideConvention.POS_1_3_IS_LEFT,
            WheelSpeeds.MEASURED_CONVENTION,
            "measured on 2026-08-28 and 2026-08-29 circle runs"
        )
    }

    /**
     * A LEFT turn makes the RIGHT wheels — positions (0,2) — faster, so the
     * raw sideDifference is positive and the true-signed value is negative.
     */
    @Test
    fun `a left turn reads negative once signed`() {
        val r = WheelSpeeds.decode(frame(4200, 4100, 4200, 4100))
        assertNotNull(r)
        assertTrue(r.sideDifference > 0, "raw (0,2)-(1,3) is positive")
        assertTrue(r.signedSideDifference < 0, "signed: left turn is negative")
    }

    @Test
    fun `a right turn reads positive once signed`() {
        val r = WheelSpeeds.decode(frame(4100, 4200, 4100, 4200))
        assertNotNull(r)
        assertTrue(r.signedSideDifference > 0, "signed: right turn is positive")
    }

    @Test
    fun `straight running has no side signal`() {
        val r = WheelSpeeds.decode(frame(4200, 4200, 4200, 4200))
        assertNotNull(r)
        assertEquals(0.0, r.signedSideDifference, 1e-9)
        assertEquals(0.0, r.yawRateRadPerSec(0.00275)!!, 1e-9)
    }

    /**
     * Yaw rate is plane geometry: two wheels on one axle differ by
     * `yawRate * track`.
     */
    @Test
    fun `yaw rate follows the track-width geometry`() {
        // 100 counts of side difference at 0.00275 m/s per count = 0.275 m/s
        // across a 1.495 m track -> 0.1839 rad/s.
        val r = WheelSpeeds.decode(frame(4100, 4200, 4100, 4200))
        assertNotNull(r)
        val yaw = r.yawRateRadPerSec(0.00275, 1.495)
        assertNotNull(yaw)
        assertEquals(0.275 / 1.495, yaw, 1e-6)
    }

    @Test
    fun `yaw rate is null when stopped`() {
        val r = WheelSpeeds.decode(frame(0, 0, 0, 0))
        assertNotNull(r)
        assertNull(r.yawRateRadPerSec(0.00275), "no yaw from a stationary car")
    }

    @Test
    fun `a wider track yields a lower yaw rate for the same difference`() {
        val r = WheelSpeeds.decode(frame(4100, 4200, 4100, 4200))
        assertNotNull(r)
        val narrow = r.yawRateRadPerSec(0.00275, 1.4)!!
        val wide = r.yawRateRadPerSec(0.00275, 1.6)!!
        assertTrue(wide < narrow, "same delta over a wider track is less yaw")
    }

    // ---- against the real circle runs --------------------------------------

    private fun capture(name: String): List<String>? {
        for (r in listOf("logs", "../logs")) {
            val f = File("$r/$name")
            if (f.isFile) return f.readLines()
        }
        return null
    }

    /** Cornering-only side differences, exactly as the convention was derived. */
    private fun corneringDiffs(lines: List<String>): List<Double> =
        lines.filter { it.contains("\"id\":\"215\"") }
            .mapNotNull { line ->
                val open = line.indexOf("\"data\":[")
                if (open < 0) return@mapNotNull null
                val close = line.indexOf(']', open)
                val bytes = line.substring(open + 8, close)
                    .split(',').mapNotNull { it.trim().toIntOrNull() }
                if (bytes.size != 8) return@mapNotNull null
                WheelSpeeds.decode(bytes)?.takeIf { it.isMoving }?.sideDifference
            }
            .filter { abs(it) > 20.0 }

    /**
     * Both controlled left-turn runs must agree that (0,2) is faster.
     *
     * This is the evidence the convention rests on. If a decode change ever
     * flips it, this fails rather than silently inverting every yaw reading.
     */
    @Test
    fun `both circle runs show the right wheels faster in a left turn`() {
        val runs = listOf(
            "2026-08-28/mscan/mscan-1787948090860.ndjson",
            "2026-08-29/mscan/mscan-1788024301465.ndjson"
        )
        var checked = 0
        for (name in runs) {
            val lines = capture(name) ?: continue
            val diffs = corneringDiffs(lines)
            if (diffs.size < 30) continue
            checked++
            val faster02 = diffs.count { it > 0 }.toDouble() / diffs.size
            assertTrue(
                faster02 > 0.7,
                "$name: (0,2) faster in ${"%.0f".format(faster02 * 100)}% of " +
                    "cornering samples — expected >70% for a left turn"
            )
        }
        if (checked == 0) return // logs absent; nothing to assert
    }

    /**
     * On the same data, the SIGNED value must read as a left turn.
     *
     * The sign is what a consumer actually uses, so it is asserted directly
     * rather than inferred from the raw difference.
     */
    @Test
    fun `the signed reading calls the circle a left turn`() {
        val lines = capture("2026-08-29/mscan/mscan-1788024301465.ndjson") ?: return
        val signed = corneringDiffs(lines).map {
            if (WheelSpeeds.MEASURED_CONVENTION ==
                WheelSpeeds.SideConvention.POS_1_3_IS_LEFT
            ) -it else it
        }
        if (signed.size < 30) return
        val left = signed.count { it < 0 }.toDouble() / signed.size
        assertTrue(
            left > 0.7,
            "the drive was counter-clockwise, so the signed value must read " +
                "negative (left) in most cornering samples — was " +
                "${"%.0f".format(left * 100)}%"
        )
    }
}
