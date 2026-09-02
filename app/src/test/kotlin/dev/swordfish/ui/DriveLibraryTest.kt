package dev.swordfish.ui

import dev.swordfish.physics.DriveLog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The logbook's listing rules.
 *
 * `DriveLibrary` needs a Context, which local unit tests do not have, so
 * these exercise the parts that are pure decisions over data — which is
 * where the rules that matter actually live.
 */
class DriveLibraryTest {

    private fun entry(name: String, rows: Int): DriveLibrary.Entry {
        val lines = buildList {
            add("""{"t":1,"kind":"drive","msg":"started"}""")
            repeat(rows) {
                add("""{"t":${1000 + it * 1000},"kind":"sample","speed_mps":10.0}""")
            }
        }
        return DriveLibrary.Entry(
            file = File(name),
            summary = DriveLog.summarise(lines),
            sizeBytes = 1024
        )
    }

    @Test
    fun `a desk launch is not listed as a drive`() {
        // Every real directory has a dozen of these: the app opened for a
        // few seconds, recorded nothing, and would bury the real drives.
        assertTrue(!entry("drive-20260824-120000.ndjson", 5).isRealDrive)
        assertTrue(!entry("drive-20260824-120000.ndjson", 29).isRealDrive)
    }

    @Test
    fun `a real drive is listed`() {
        assertTrue(entry("drive-20260824-120000.ndjson", 30).isRealDrive)
        assertTrue(entry("drive-20260824-120000.ndjson", 3669).isRealDrive)
    }

    @Test
    fun `an unreadable recording is never a real drive`() {
        val e = DriveLibrary.Entry(File("x.ndjson"), summary = null, sizeBytes = 0)
        assertTrue(
            !e.isRealDrive,
            "a file that would not parse must not claim a row in the list"
        )
    }

    @Test
    fun `the stamp is pulled out of the file name`() {
        assertEquals(
            "20260824-131001",
            entry("drive-20260824-131001.ndjson", 40).stamp
        )
    }
}
