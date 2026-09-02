package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-analysing a saved capture offline.
 *
 * The raw capture is the durable asset: a better reference, a different
 * hypothesis, or a wider search can all be tried against it without driving
 * again. These pin that the two files join correctly on wall-clock time,
 * because a mis-join produces a confident-looking empty result.
 */
class MsCanReplayTest {

    private fun frameRow(t: Long, id: String, data: List<Int>) =
        """{"t":$t,"kind":"frame","id":"$id","data":[${data.joinToString(",")}]}"""

    private fun sampleRow(t: Long, heading: Double, speed: Double = 20.0) =
        """{"t":$t,"kind":"sample","state":"CRUISE","speed_mps":$speed,""" +
            """"heading_deg":$heading}"""

    @Test
    fun `frame rows parse back`() {
        val lines = listOf(
            """{"t":1000,"kind":"mscan","msg":"started"}""",
            frameRow(1100, "0x085", listOf(1, 2, 3, 4)),
            frameRow(1200, "0x201", listOf(0x55, 0x55))
        )
        val frames = MsCanReplay.parseFrames(lines)
        assertEquals(2, frames.size)
        assertEquals("0x085", frames[0].canId)
        assertEquals(listOf(1, 2, 3, 4), frames[0].data)
    }

    @Test
    fun `non-frame rows are ignored`() {
        val lines = listOf(
            """{"t":1,"kind":"mscan","msg":"started"}""",
            """{"t":2,"kind":"mscan","msg":"stopped","frames":0}""",
            "garbage",
            ""
        )
        assertTrue(MsCanReplay.parseFrames(lines).isEmpty())
    }

    @Test
    fun `a truncated final row does not lose the capture`() {
        val lines = listOf(
            frameRow(1000, "0x085", listOf(1, 2)),
            """{"t":1100,"kind":"frame","id":"0x0"""    // cut off mid-write
        )
        assertEquals(1, MsCanReplay.parseFrames(lines).size)
    }

    @Test
    fun `yaw is derived from the drive log heading`() {
        // A 10 degree turn each second.
        val drive = (0..5).map { sampleRow(1000L + it * 1000, it * 10.0) }
        val yaw = MsCanReplay.yawSeries(drive)
        assertEquals(5, yaw.size)
        assertEquals(Math.toRadians(10.0), yaw.first().second, 1e-9)
    }

    @Test
    fun `a stationary car contributes no yaw`() {
        // Its bearing is noise, and correlating noise against real bytes is
        // how a genuine signal gets buried.
        val drive = (0..5).map { sampleRow(1000L + it * 1000, it * 30.0, speed = 0.0) }
        assertTrue(MsCanReplay.yawSeries(drive).isEmpty())
    }

    @Test
    fun `frames take the nearest reference in time`() {
        val frames = listOf(
            MsCanReplay.Frame(1000, "0x085", listOf(1, 2)),
            MsCanReplay.Frame(1900, "0x085", listOf(3, 4))
        )
        val ref = listOf(1000L to 0.1, 2000L to 0.9)
        val joined = MsCanReplay.join(frames, ref)
        assertEquals(2, joined.size)
        assertEquals(0.1, joined[0].reference)
        // 1900 is 100 ms from 2000 and 900 ms from 1000: the later one wins.
        assertEquals(0.9, joined[1].reference)
    }

    @Test
    fun `a frame with no nearby reference is dropped`() {
        // Pairing a frame with a reference from ten seconds away blurs the
        // correlation toward zero, which makes a REAL signal look absent.
        val frames = listOf(MsCanReplay.Frame(50_000, "0x085", listOf(1, 2)))
        val ref = listOf(1000L to 0.5)
        assertTrue(MsCanReplay.join(frames, ref).isEmpty())
    }

    @Test
    fun `no overlap between the two files is reported, not silently empty`() {
        // The trap: a capture and a drive log from different sessions produce
        // an empty result that looks like "no signal found" rather than
        // "these files do not go together".
        val capture = listOf(frameRow(50_000, "0x085", listOf(1, 2)))
        val drive = listOf(sampleRow(1000, 10.0), sampleRow(2000, 20.0))
        val d = MsCanReplay.describe(capture, drive)
        assertTrue(d.contains("NO OVERLAP"), d)
    }

    @Test
    fun `the full offline pipeline finds a planted signal`() {
        // Two files in, ranked candidates out -- the whole point of keeping
        // the raw capture.
        val capture = ArrayList<String>()
        val drive = ArrayList<String>()
        var t = 1_000L
        var heading = 0.0

        for (i in 0 until 200) {
            // Turn one way, then the other, as a real route does.
            val turnRate = when {
                i < 60 -> 12.0
                i < 120 -> -12.0
                else -> 0.0
            }
            heading = (heading + turnRate + 360.0) % 360.0
            drive += sampleRow(t, heading)

            // A byte pair carrying the yaw rate, planted at offset 2.
            val yaw = Math.toRadians(turnRate)          // per second
            val raw = ((yaw * 2000).toInt()) and 0xFFFF
            capture += frameRow(
                t + 10, "0x085",
                listOf(0, 0, (raw shr 8) and 0xFF, raw and 0xFF)
            )
            capture += frameRow(t + 20, "0x201", listOf(0x55, 0x55, 0x55, 0x55))
            t += 1000
        }

        val results = MsCanReplay.analyse(capture, drive)
        assertTrue(results.isNotEmpty(), "the pipeline produced no candidates")
        val best = results.first()
        assertEquals("0x085", best.canId, "the carrying ID must win")
        assertTrue(
            best.strength > 0.9,
            "expected a strong correlation, got ${best.correlation}"
        )
        assertTrue(
            results.none { it.canId == "0x201" && it.strength > 0.5 },
            "a constant ID must not score"
        )
    }

    @Test
    fun `describe reports what is scorable before any correlation`() {
        val capture = (0 until 50).map {
            frameRow(1000L + it * 100, "0x085", listOf(it, it))
        }
        val drive = (0 until 10).map { sampleRow(1000L + it * 500, it * 5.0) }
        val d = MsCanReplay.describe(capture, drive)
        assertTrue(d.contains("frames=50"), d)
        assertTrue(d.contains("scorable="), d)
    }
}
