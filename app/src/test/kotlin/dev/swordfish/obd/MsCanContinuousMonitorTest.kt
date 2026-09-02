package dev.swordfish.obd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the fix for the sparse MS-CAN capture of 2026-08-27.
 *
 * ## What happened
 *
 * The town-loop capture ran its full 8m30s and saved 3596 frames -- about
 * **7 frames/sec** from a bus that carries hundreds. Worse, the frames were
 * not evenly thin: twelve windows of a full **10.2 seconds** contained no
 * frames at all, and 95% of the capture's wall-clock sat inside a gap longer
 * than a second.
 *
 * 10.2s is not a bus phenomenon. It is `SLICE_MS` (10_000). Every slice did:
 *
 * ```
 * drain(inp)                    // throw away everything since last slice
 * write(ATMA); collect(10s)
 * write(TERMINATOR); sleep(200); drain(inp)   // ~200ms deaf
 * ```
 *
 * so each boundary discarded the buffered frames AND spent ~200ms not
 * listening, and a slice whose restart overran simply produced nothing.
 *
 * ## The fix
 *
 * One `ATMA` for the whole capture, sliced only for *reading*. `continuing`
 * suppresses the re-arm and the drain; `leaveRunning` suppresses the stop.
 *
 * ## The hazard this introduces, and why the tests below exist
 *
 * An `ATMA` left running streams CAN frames into whatever command is sent
 * next, which desynchronises every later reply -- the failure the transport's
 * own docs warn about. So the capture MUST stop the monitor on *every* exit
 * path: normal end, user stop, and exception. That is what
 * `stopMonitor is reached on every exit path` pins down.
 */
class MsCanContinuousMonitorTest {

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

    private fun session() =
        codeLines(sourceFile("src/main/kotlin/dev/swordfish/obd/MsCanSession.kt"))

    private fun transport() =
        codeLines(sourceFile("src/main/kotlin/dev/swordfish/obd/ObdTransport.kt"))

    /**
     * The regression itself: the capture must not re-arm every slice.
     */
    @Test
    fun `capture slices a single continuing monitor`() {
        val src = session().joinToString("\n")
        assertTrue(
            src.contains("continuing = armed"),
            "MS-CAN slices must continue one ATMA, not restart it each slice"
        )
        assertTrue(
            src.contains("leaveRunning = true"),
            "MS-CAN slices must not tear the monitor down at every boundary"
        )
    }

    /**
     * The hazard guard. A monitor left running MUST be stopped on the way out
     * whatever happens, or the adapter poisons every subsequent command.
     */
    @Test
    fun `stopMonitor is reached on every exit path`() {
        val lines = session()
        val tryIdx = lines.indexOfFirst { it.trim() == "try {" }
        val finallyIdx = lines.indexOfFirst { it.trim().startsWith("} finally {") }
        val stopIdx = lines.indexOfFirst { it.contains("transport.stopMonitor()") }

        assertTrue(tryIdx >= 0, "the slice loop must be wrapped in try/finally")
        assertTrue(finallyIdx > tryIdx, "the slice loop needs a finally block")
        assertTrue(
            stopIdx > finallyIdx,
            "stopMonitor() must be inside finally so a stop request or an " +
                "exception cannot leave ATMA streaming"
        )
    }

    /** The stop must be guarded, so we never write to a monitor never armed. */
    @Test
    fun `stopMonitor is only called when the monitor was actually armed`() {
        val src = session().joinToString("\n")
        assertTrue(
            src.contains("if (armed) transport.stopMonitor()"),
            "stopMonitor must be guarded by the armed flag"
        )
    }

    /**
     * The slice loop must survive: it is what bounds a stop request and what
     * lets a dropped socket cost one slice instead of the whole drive. The
     * fix removes the re-arming, NOT the slicing.
     */
    @Test
    fun `slicing is preserved so a stop request stays responsive`() {
        val src = session().joinToString("\n")
        assertTrue(src.contains("!stopping.get()"), "stop flag must still bound the loop")
        assertTrue(src.contains("SLICE_MS"), "slicing must be preserved")
        val slice = Regex("SLICE_MS = (\\d[\\d_]*)L")
            .find(src)?.groupValues?.get(1)?.replace("_", "")?.toLong()
        assertTrue(
            slice != null && slice <= 10_000L,
            "a slice bounds how long a stop request waits; keep it short (got $slice)"
        )
    }

    /**
     * The transport contract. Defaults must stay false so the OTHER caller
     * (`ProbeRunner`, a one-shot monitor) is unaffected by this change.
     */
    @Test
    fun `monitor defaults keep the one-shot behaviour for other callers`() {
        val src = transport().joinToString("\n")
        assertTrue(
            src.contains("continuing: Boolean = false"),
            "continuing must default false so existing callers re-arm as before"
        )
        assertTrue(
            src.contains("leaveRunning: Boolean = false"),
            "leaveRunning must default false so existing callers still stop ATMA"
        )
    }

    /**
     * ProbeRunner is the one-shot caller. If it ever starts leaving monitors
     * running it inherits the desync hazard, so pin that it does not.
     */
    @Test
    fun `the probe runner does not leave a monitor running`() {
        val src = codeLines(sourceFile("src/main/kotlin/dev/swordfish/obd/ProbeRunner.kt"))
            .joinToString("\n")
        assertFalse(
            src.contains("leaveRunning"),
            "ProbeRunner is one-shot; leaving ATMA running would desync its polls"
        )
    }

    /**
     * The frame callback must stay CHEAP (fixed 2026-08-27, second attempt).
     *
     * Fixing the re-arming raised delivery from ~20 lines/sec to ~300-420.
     * The callback was still calling `File.appendText` per frame, which opens,
     * writes, flushes and closes the file every time. At the new rate that
     * blocks the reader long enough for the adapter to give up: the two PNC
     * captures ran at full rate (102/sec and 145/sec) for 1.2s and 4.7s, then
     * stopped dead with 189s and 215s of silence left in the session.
     *
     * The frames arrived at a healthy ~6ms spacing right up to the last one --
     * no degradation, a clean cutoff -- which is what distinguishes "the
     * consumer stalled the reader" from "the bus went quiet".
     */
    @Test
    fun `the frame callback does not open a file per frame`() {
        val lines = session()
        val cbStart = lines.indexOfFirst { it.contains("{ line ->") }
        assertTrue(cbStart >= 0, "could not find the frame callback")
        val cbEnd = lines.drop(cbStart).indexOfFirst { it.contains("armed = true") }
        assertTrue(cbEnd > 0, "could not find the end of the slice body")
        val body = lines.subList(cbStart, cbStart + cbEnd).joinToString("\n")

        assertFalse(
            body.contains("appendText"),
            "appendText opens/flushes/closes per call; at ~400 lines/sec that " +
                "stalls the reader and the adapter drops the stream"
        )
        assertTrue(
            body.contains("w.write"),
            "frames should go through the buffered writer"
        )
    }

    /** Buffered means it must be flushed, and closed, on every path. */
    @Test
    fun `the writer is flushed per slice and survives an exception`() {
        val src = session().joinToString("\n")
        assertTrue(src.contains("w.flush()"), "the writer must be flushed")
        assertTrue(
            src.contains("runCatching { w.flush() }"),
            "an exception mid-slice must not discard the buffered tail"
        )
        assertTrue(src.contains("w.close()"), "the writer must be closed")
    }

    /** The re-arm and the drain must be jointly suppressed, never one alone. */
    @Test
    fun `continuing suppresses both the resend and the drain`() {
        val lines = transport()
        val guard = lines.indexOfFirst { it.contains("if (!continuing)") }
        assertTrue(guard >= 0, "the re-arm must be guarded by continuing")

        // Everything the guard covers, up to its closing brace.
        val block = lines.drop(guard).takeWhile { !it.trim().startsWith("}") }
            .joinToString("\n")
        assertTrue(
            block.contains("drain(inp)"),
            "a continuing slice must NOT drain -- that is the dropped-frame bug"
        )
        assertTrue(
            block.contains("out.write"),
            "a continuing slice must not resend ATMA"
        )
    }
}
