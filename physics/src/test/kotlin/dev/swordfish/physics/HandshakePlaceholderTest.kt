package dev.swordfish.physics

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The delta-V budget must not appear to refill, and the drive's headline
 * figures must be quoted from live samples only.
 *
 * ## Why this exists
 *
 * The 2026-08-23 reference drive was published for weeks as "delta-V draining
 * 7129 -> 3075 m/s". It does not. The first six samples of that log are
 * `link: HANDSHAKE`, before any tank reading exists, and the panel renders a
 * 7129 default through all of them. The first sample carrying a real fuel
 * figure reads 3924.
 *
 * So the drive is **3924 -> 3075**, a cost of 849 m/s -- and roughly 79% of
 * the drop that was being quoted was a startup artifact, not fuel burned.
 *
 * The honest claim is also the stronger one, and it is about step size rather
 * than span: across the 3662 post-handshake samples nothing moves more than
 * about 2 m/s in either direction.
 *
 * These tests pin the reasoning, not the specific log. The log is gitignored
 * (it is a GPS trace), so the file-backed cases skip when it is absent; the
 * synthetic case always runs.
 */
class HandshakePlaceholderTest {

    /** The reference log, if a copy is sitting outside the repo. */
    private fun referenceLog(): File? = listOf(
        File("tools/probe-logs/milestone-20260823-tile-working/drive-20260823-135350.ndjson"),
        File("../tools/probe-logs/milestone-20260823-tile-working/drive-20260823-135350.ndjson")
    ).firstOrNull { it.isFile }

    private data class Sample(val dv: Double, val live: Boolean)

    private fun samples(f: File): List<Sample> = f.readLines().mapNotNull { line ->
        if (!line.contains("\"kind\":\"sample\"")) return@mapNotNull null
        val dv = DriveResume.doubleField(line, "dv_mps") ?: return@mapNotNull null
        Sample(dv, line.contains("\"link\":\"LIVE\""))
    }

    @Test
    fun `a budget that is still handshaking is not a measurement`() {
        // The point in miniature, with no file needed: a run of placeholder
        // samples followed by the real reading looks like a catastrophic
        // burn if you diff the endpoints without checking link state.
        val run = listOf(
            Sample(7129.0, live = false),
            Sample(7129.0, live = false),
            Sample(7129.0, live = true),   // link up, tank reading not in yet
            Sample(3924.0, live = true),
            Sample(3900.0, live = true)
        )

        val naiveDrop = run.first().dv - run.last().dv
        val settled = run.dropWhile { it.dv > 7000.0 }
        val realDrop = settled.first().dv - settled.last().dv

        assertTrue(
            naiveDrop > 3000.0,
            "the naive reading should look enormous -- that is the trap"
        )
        assertTrue(
            realDrop < 100.0,
            "after the placeholder is dropped the real cost is small: $realDrop"
        )
        assertTrue(
            run.any { it.live && it.dv > 7000.0 },
            "LIVE alone does not mean the tank reading has arrived -- this is " +
                "why gating the DISPLAY on LinkState is not sufficient"
        )
    }

    @Test
    fun `the reference drive cost 849 m per s, not 4054`() {
        val f = referenceLog() ?: return
        val all = samples(f)
        assertTrue(all.size > 3000, "expected the full log, got ${all.size}")

        val settled = all.dropWhile { it.dv > 7000.0 }
        val start = settled.first().dv
        val end = settled.last().dv

        assertTrue(
            abs(start - 3924.0) < 5.0,
            "the first real reading should be ~3924, was $start"
        )
        assertTrue(
            abs(end - 3075.0) < 5.0,
            "the drive should end at ~3075, was $end"
        )
        assertTrue(
            abs((start - end) - 849.0) < 10.0,
            "the drive should cost ~849 m/s, was ${start - end}"
        )
    }

    @Test
    fun `no single step moves the budget more than about two metres per second`() {
        val f = referenceLog() ?: return
        val dv = samples(f).dropWhile { it.dv > 7000.0 }.map { it.dv }
        if (dv.size < 100) return

        var maxUp = 0.0
        var maxDown = 0.0
        for (i in 1 until dv.size) {
            val d = dv[i] - dv[i - 1]
            if (d > maxUp) maxUp = d
            if (-d > maxDown) maxDown = -d
        }

        // The published claim. Rises are tank slosh on corners and are real;
        // what matters is that nothing jumps.
        assertTrue(
            maxUp < 2.0,
            "largest increase should be ~1.23 m/s (tank slosh), was $maxUp"
        )
        assertTrue(
            maxDown < 3.0,
            "largest decrease should be ~2.12 m/s, was $maxDown"
        )
    }
}
