package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Merging the REAL ridge-road drives, which is the case the design was built for.
 *
 * These recordings overlap 79.8-100%. A merge that cannot keep the two
 * directions apart on this data cannot do it anywhere.
 *
 * Skipped on a fresh clone: the logs are gitignored (VIN, location traces).
 */
class TrackMergeRealTest {

    private fun drives(): List<TrackMerge.Source> {
        val dir = listOf(
            File("tools/probe-logs/drives-20260825"),
            File("../tools/probe-logs/drives-20260825")
        ).firstOrNull { it.isDirectory } ?: return emptyList()

        return dir.listFiles { f -> f.name.startsWith("drive-20260825") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f ->
                val s = DriveLog.parse(f.readLines())
                if (s.count { it.hasFix } < 200) null
                else TrackMerge.Source(f.name, s)
            } ?: emptyList()
    }

    @Test
    fun `the real drives merge without collapsing the directions`() {
        val srcs = drives()
        if (srcs.size < 2) return

        val cells = TrackMerge.mergeByMotion(srcs)
        assertTrue(cells.isNotEmpty(), "the merge produced no cells")

        // Locations covered in more than one direction bucket: the
        // out-and-back stretches. On the ridge road there should be many.
        val byPos = cells.groupBy { c ->
            TrackMerge.cellOf(c.latDeg, TrackMerge.DEFAULT_CELL_METERS) to
                TrackMerge.cellOf(
                    c.lonDeg,
                    TrackMerge.DEFAULT_CELL_METERS /
                        Math.cos(Math.toRadians(c.latDeg))
                )
        }
        val twoWay = byPos.count { it.value.size >= 2 }
        assertTrue(
            twoWay > 20,
            "expected many stretches driven both ways on a test track; found $twoWay"
        )
        println(
            "merged ${srcs.size} drives -> ${cells.size} cells, " +
                "$twoWay driven in both directions"
        )
    }

    @Test
    fun `repeated passes accumulate rather than multiply`() {
        val srcs = drives()
        if (srcs.size < 2) return
        val cells = TrackMerge.mergeByMotion(srcs)

        val shared = cells.count { it.drives >= 2 }
        assertTrue(
            shared > 20,
            "these drives overlap heavily; expected many shared cells, got $shared"
        )
        println("cells contributed to by 2+ drives: $shared")
    }

    @Test
    fun `the merged map is smaller than the sum of its traces`() {
        // The practical benefit: one legible map instead of N stacked lines.
        val srcs = drives()
        if (srcs.size < 2) return
        val raw = srcs.sumOf { it.samples.count { s -> s.hasFix } }
        val cells = TrackMerge.mergeByMotion(srcs)
        assertTrue(
            cells.size < raw,
            "merging should reduce ${raw} points, produced ${cells.size} cells"
        )
        println("$raw positioned samples -> ${cells.size} cells")
    }
}
