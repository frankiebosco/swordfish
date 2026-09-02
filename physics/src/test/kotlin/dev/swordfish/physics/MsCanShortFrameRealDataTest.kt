package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Short-frame detection against the real 2026-08-28 calibration capture.
 *
 * ## The capture that looked perfect
 *
 * This file is the first run of the filtered `WHEEL_CALIBRATION` mode. Every
 * health signal was clean -- `unparsed=0`, `overflow=0`, no re-arms, steady
 * ~450-frame slices, exactly the three expected IDs -- and the calibration
 * still could not be fitted.
 *
 * The reason is invisible in all of those counters: **218 of 480 `215` frames
 * arrived truncated to 3 bytes**, and the truncated run covers almost exactly
 * the turn (16:15:00-16:15:23) while the approach and exit straights are a
 * clean 8. Three bytes is one wheel and half of the second, so the two wheels
 * a side comparison needs were absent for the whole circle.
 *
 * The surviving samples were the straights, where there is correctly no side
 * difference -- which is why agreement came out 45%, reading as noise rather
 * than as missing data.
 *
 * These tests assert the detector sees on real data what a hand analysis had
 * to dig for. Skips cleanly when the logs are absent.
 */
class MsCanShortFrameRealDataTest {

    private fun capture(): List<String>? {
        val roots = listOf("logs/2026-08-28/mscan", "../logs/2026-08-28/mscan")
        for (r in roots) {
            val f = File("$r/mscan-1787948090860.ndjson")
            if (f.isFile) return f.readLines()
        }
        return null
    }

    /** Pull the `data` array length out of one NDJSON frame record. */
    private fun frameLengths(lines: List<String>, id: String): List<Int> =
        lines.filter { it.contains("\"kind\":\"frame\"") && it.contains("\"id\":\"$id\"") }
            .mapNotNull { line ->
                val open = line.indexOf("\"data\":[")
                if (open < 0) return@mapNotNull null
                val close = line.indexOf(']', open)
                if (close < 0) return@mapNotNull null
                val body = line.substring(open + 8, close)
                if (body.isBlank()) 0 else body.count { it == ',' } + 1
            }

    @Test
    fun `the capture reports itself healthy on every old signal`() {
        val lines = capture() ?: return
        val stop = lines.last { it.contains("\"msg\":\"stopped\"") }
        assertTrue(stop.contains("\"unparsed\":0"), "no unparsed lines")
        assertTrue(stop.contains("\"overflow\":0"), "no adapter overflow")
        // This is the point: every counter that existed before said "fine".
    }

    @Test
    fun `yet a third of the wheel frames are truncated`() {
        val lines = capture() ?: return
        val lens = frameLengths(lines, "215")
        assertTrue(lens.isNotEmpty(), "expected 215 frames in this capture")

        val short = lens.count { it < 8 }
        assertTrue(
            short > 0,
            "this capture is known to contain truncated 215 frames"
        )
        // Recorded so a future parser change that silently drops or pads
        // them fails here instead of on the next calibration drive.
        assertEquals(480, lens.size, "215 frame count for this capture")
        assertEquals(218, short, "known truncated count for this capture")
    }

    @Test
    fun `the cross-check ID is NOT truncated in the same capture`() {
        val lines = capture() ?: return
        val lens = frameLengths(lines, "202")
        assertTrue(lens.isNotEmpty())
        val shortFrac = lens.count { it < 8 }.toDouble() / lens.size
        assertTrue(
            shortFrac < 0.05,
            "202 stayed intact while 215 did not -- so this is specific to " +
                "215, not general congestion (was ${"%.1f".format(shortFrac * 100)}%)"
        )
    }

    @Test
    fun `the detector flags exactly the truncated frames`() {
        val lines = capture() ?: return
        val lens = frameLengths(lines, "215")
        val flagged = lens.count { len ->
            MsCanProbe.isShortFrame(
                MsCanProbe.Frame("215", List(len) { 0 }, 0L)
            )
        }
        assertEquals(
            lens.count { it < 8 },
            flagged,
            "isShortFrame must flag every truncated frame and no others"
        )
    }
}
