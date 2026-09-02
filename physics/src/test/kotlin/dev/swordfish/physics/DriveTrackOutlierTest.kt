package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The retrace must not draw lines the car never travelled.
 *
 * Reported from the 2026-08-25 drive: long straight lines across the map.
 * The cause was 9 samples out of 2274 sitting ~12 km off-route -- cell-tower
 * positions from NETWORK_PROVIDER, accepted because the listener took
 * whichever provider fired last.
 *
 * `FixGate` stops new ones being recorded. This pins that logs ALREADY
 * written draw correctly too, because those 9 points are still in them.
 */
class DriveTrackOutlierTest {

    private fun realDrive(): List<String>? {
        for (p in listOf(
            "tools/probe-logs/drives-20260825/drive-20260825-114246.ndjson",
            "../tools/probe-logs/drives-20260825/drive-20260825-114246.ndjson"
        )) {
            val f = File(p)
            if (f.isFile) return f.readLines()
        }
        return null
    }

    @Test
    fun `the real drive draws no impossible legs`() {
        val lines = realDrive() ?: return   // gitignored; skip on a fresh clone
        val samples = DriveLog.parse(lines)
        val track = DriveTrack.build(samples)

        assertTrue(!track.isEmpty, "the drive should produce a track")

        // No single drawn leg may exceed what a car can cover in the gap
        // between samples. Rows land at 1 Hz, so ~90 m is the ceiling.
        val span = maxOf(track.widthMeters, track.heightMeters, 1.0)
        var worst = 0.0
        for (i in 1 until track.points.size) {
            val a = track.points[i - 1]
            val b = track.points[i]
            val d = kotlin.math.hypot((b.x - a.x) * span, (b.y - a.y) * span)
            if (d > worst) worst = d
        }
        assertTrue(
            worst < 500.0,
            "the longest drawn leg is ${"%.0f".format(worst)} m. Before the " +
                "outlier filter this drive drew a 15,397 m leg -- a cell-tower " +
                "fix 12 km off-route."
        )
    }

    @Test
    fun `dropping outliers keeps almost all of the drive`() {
        val lines = realDrive() ?: return
        val positioned = DriveLog.parse(lines).filter { it.hasFix }
        val kept = DriveTrack.dropOutliers(positioned)
        val dropped = positioned.size - kept.size

        // 72 of 2274 on this drive (3.2%). More than the 9 far-off-route
        // cell-tower fixes, because ordinary GPS jitter also produces short
        // steps above 90 m/s. Measured: dropping them changes the longest
        // drawn leg not at all (326 m either way), so the stricter bound
        // costs nothing and keeps the threshold physically honest.
        assertTrue(
            dropped in 1..120,
            "expected a small number of outliers on this drive, dropped " +
                "$dropped of ${positioned.size}"
        )
        // 96.8% survive on this drive. The bound exists to catch a filter
        // that eats the route, not to pin an exact rate.
        assertTrue(
            kept.size > positioned.size * 0.95,
            "the filter must not eat the route: kept ${kept.size} of ${positioned.size}"
        )
    }

    @Test
    fun `the bounding box shrinks to the real route`() {
        // A single 12 km outlier stretches the box so far that the actual
        // drive collapses into a corner. This is why outliers are dropped
        // BEFORE the box is measured.
        val lines = realDrive() ?: return
        val track = DriveTrack.build(DriveLog.parse(lines))
        assertTrue(
            track.widthMeters < 40_000 && track.heightMeters < 40_000,
            "bounding box is ${"%.0f".format(track.widthMeters)} x " +
                "${"%.0f".format(track.heightMeters)} m for a 20 mile drive"
        )
    }
}
