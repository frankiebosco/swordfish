package dev.swordfish.obd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Guards the fix for the MS-CAN capture that could not be retrieved on
 * 2026-08-27.
 *
 * ## What happened
 *
 * A full out-and-back capture ran against the Play Store build and wrote
 * `mscan-1787842171979.ndjson` to `/data/user/0/dev.swordfish/files/mscan/`.
 * That file was never recovered. Internal app storage is unreachable on a
 * release install:
 *
 * - `run-as dev.swordfish` -> "package not debuggable"
 * - direct `adb shell ls` -> "Permission denied"
 * - `adb backup -noapk`   -> completes, produces a 47-byte header-only .ab
 *   (Android 12+ ignores android:allowBackup for adb backup)
 * - file manager / Drive / share sheet -> cannot cross the UID boundary
 *
 * The data was collected correctly and was simply stranded. A capture that
 * cannot leave the phone is worth exactly as much as one that never ran.
 *
 * ## The invariant
 *
 * `MsCanSession` takes its directory by injection, so the choice is made at
 * the single call site in `ProbeActivity`. Every other logger in the app --
 * `DriveRecorder` and `ProbeRunner` -- writes to `getExternalFilesDir(null)`,
 * which is pullable over adb with no debuggable build and no root. The MS-CAN
 * path was the lone inconsistency.
 *
 * ## Why this is a SOURCE test
 *
 * `getExternalFilesDir` is an Activity API, stubbed to throw in local unit
 * tests, and this module has no Robolectric -- same reasoning as
 * `MsCanReferenceWiringTest`. The invariant is a property of the source and
 * can be checked exactly.
 */
class MsCanCaptureReachabilityTest {

    private fun sourceFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    /** Lines with the comment portion removed, so prose cannot trip the check. */
    private fun codeLines(f: File): List<String> =
        f.readLines()
            .map { it.substringBefore("//") }
            .filter { it.isNotBlank() }

    private fun probeActivity() =
        sourceFile("src/main/kotlin/dev/swordfish/ui/ProbeActivity.kt")

    /**
     * The regression itself: the capture must not be handed internal storage.
     */
    @Test
    fun `MsCanSession is not constructed with bare internal filesDir`() {
        val offending = codeLines(probeActivity())
            .filter { it.contains("MsCanSession(") }
            .filter { Regex("""MsCanSession\([^)]*,\s*filesDir\s*\)""").containsMatchIn(it) }

        assertTrue(
            offending.isEmpty(),
            "MS-CAN capture is writing to internal filesDir, which cannot be " +
                "pulled from a Play Store build. Use getExternalFilesDir(null). " +
                "Offending: $offending"
        )
    }

    /** The positive form: it must be given external storage. */
    @Test
    fun `MsCanSession is constructed with external files dir`() {
        val ctor = codeLines(probeActivity())
            .filter { it.contains("MsCanSession(") }

        assertFalse(ctor.isEmpty(), "no MsCanSession construction site found")
        assertTrue(
            ctor.any { it.contains("getExternalFilesDir") },
            "MsCanSession must be given getExternalFilesDir(null) so captures " +
                "are retrievable over adb. Found: $ctor"
        )
    }

    /**
     * The consistency rule that would have prevented this. All three loggers
     * write where the data can actually be collected from.
     */
    @Test
    fun `all drive and capture loggers write to external storage`() {
        val loggers = mapOf(
            "DriveRecorder" to "src/main/kotlin/dev/swordfish/obd/DriveRecorder.kt",
            "ProbeRunner" to "src/main/kotlin/dev/swordfish/obd/ProbeRunner.kt"
        )
        for ((name, path) in loggers) {
            val lines = codeLines(sourceFile(path))
            assertTrue(
                lines.any { it.contains("getExternalFilesDir") },
                "$name must write to external storage so its logs are retrievable"
            )
        }
    }
}
