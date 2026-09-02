package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Validates crash detection against the REAL logs from the 2026-08-24 drive.
 *
 * Synthetic fixtures prove the logic; these prove the logic matches what the
 * recorder actually writes. Skipped when the logs are absent -- they are
 * gitignored (they carry VIN and location traces), so this must not fail on
 * a fresh clone.
 */
class DriveResumeRealLogTest {

    private fun logs(): List<File> {
        val dirs = listOf(
            File("tools/probe-logs/drives"),
            File("../tools/probe-logs/drives")
        )
        val dir = dirs.firstOrNull { it.isDirectory } ?: return emptyList()
        return dir.listFiles { f -> f.name.endsWith(".ndjson") }?.toList() ?: emptyList()
    }

    @Test
    fun `the two crashed drives are detected as unclean, the clean ones as clean`() {
        val files = logs()
        if (files.isEmpty()) return  // not cloned with logs; nothing to check

        // From the crash buffer: 13:09:59 and 13:25:20 killed these two.
        val crashed = setOf(
            "drive-20260824-122444.ndjson",
            "drive-20260824-131001.ndjson"
        )
        // These wrote their stop row.
        val clean = setOf(
            "drive-20260824-132522.ndjson",
            "drive-20260824-133746.ndjson"
        )

        for (f in files) {
            val lines = f.readLines()
            val ended = DriveResume.endedCleanly(lines)
            if (f.name in crashed) {
                assertTrue(
                    !ended,
                    "${f.name} was killed by the radar bitmap crash and must " +
                        "read as unclean"
                )
            }
            if (f.name in clean) {
                assertTrue(
                    ended,
                    "${f.name} shut down normally and must read as clean"
                )
            }
        }
    }

    @Test
    fun `a crashed log from today would NOT resume now`() {
        // Same files, but hours old by the time any test runs: the freshness
        // window must reject them. This is the guard that stops a stale
        // crash swallowing tomorrow's drive.
        val f = logs().firstOrNull { it.name == "drive-20260824-122444.ndjson" } ?: return
        assertTrue(
            DriveResume.inspect(f.readLines(), System.currentTimeMillis()) == null,
            "a log this old must not be resumable"
        )
    }
}
