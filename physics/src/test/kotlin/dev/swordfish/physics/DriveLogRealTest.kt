package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the logbook's arithmetic over the REAL recorded drives.
 *
 * Synthetic rows prove the maths; these prove it survives what the recorder
 * actually writes -- including the two logs the radar crash truncated.
 * Skipped when the logs are absent (they are gitignored: VIN and location).
 */
class DriveLogRealTest {

    private fun logs(): List<File> {
        val dir = listOf(
            File("tools/probe-logs/drives"), File("../tools/probe-logs/drives")
        ).firstOrNull { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".ndjson") }
            ?.sortedBy { it.name } ?: emptyList()
    }

    @Test
    fun `every recorded drive summarises without throwing`() {
        val files = logs()
        if (files.isEmpty()) return

        for (f in files) {
            val lines = f.readLines()
            val s = DriveLog.summarise(lines) ?: continue

            assertTrue(
                s.distanceMeters >= 0.0 && s.distanceMeters < 2_000_000.0,
                "${f.name}: distance ${s.distanceMeters} m is not credible"
            )
            assertTrue(
                s.maxSpeedMps in 0.0..90.0,
                "${f.name}: max speed ${s.maxSpeedMps} m/s is not credible"
            )
            assertTrue(
                s.durationMs >= 0L,
                "${f.name}: negative duration"
            )
            s.mpg?.let {
                assertTrue(
                    it in 1.0..200.0,
                    "${f.name}: mpg $it is outside anything a car produces"
                )
            }
            println(
                "${f.name}: ${"%.2f".format(Units.metersToMiles(s.distanceMeters))} mi, " +
                    "${s.durationMs / 60000} min, ${s.rows} rows, " +
                    "mpg=${s.mpg?.let { "%.1f".format(it) } ?: "-"}, " +
                    "dv spent=${s.deltaVSpentMps?.let { "%.0f".format(it) } ?: "-"}, " +
                    "clean=${s.endedCleanly}, fixes=${s.samplesWithFix}"
            )
        }
    }
}
