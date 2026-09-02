package dev.swordfish.physics

/**
 * Decides whether a drive log was abandoned mid-drive, and what to restore.
 *
 * ## The problem this solves
 *
 * On the 2026-08-24 drive the app crashed twice. Android Auto restarted the
 * service in about a second, so it read as a brief flicker from the driver's
 * seat — but each restart began a NEW recording. One drive became three
 * files, and the trip's starting delta-V reset each time, so the "what did
 * this trip cost" figure silently restarted mid-journey.
 *
 * ## How an abandoned drive is recognised
 *
 * A clean shutdown always writes a closing row:
 *
 *     {"t":...,"kind":"drive","msg":"stopped","rows":321}
 *
 * A file WITHOUT one was killed mid-write. That is not a heuristic; it is
 * the same signal that identified both crashes from the logs afterwards.
 *
 * Freshness is the second half. A file with no stop row could equally be
 * from a drive that ended days ago in a crash nobody noticed, and silently
 * appending today's motorway to last Tuesday's would corrupt both. So the
 * last row must also be RECENT.
 *
 * ## Why this lives in :physics
 *
 * It is a pure function over lines of text. Keeping it out of the Android
 * layer means the whole decision — including the awkward cases, like a
 * truncated final line from a process killed mid-write — is unit-testable
 * without a phone, a car, or a crash.
 */
object DriveResume {

    /**
     * How stale a log may be and still count as the drive in progress.
     *
     * Ten minutes is chosen against the failure it must survive: a crash
     * plus Android Auto's restart is about a second, and a driver stopping
     * for fuel might leave the app dead for a few minutes. Beyond that,
     * resuming would join two genuinely separate journeys — a worse
     * outcome than starting a new file, because it produces a drive that
     * never happened.
     */
    const val MAX_RESUME_GAP_MS = 10 * 60 * 1000L

    /** What a resumable log tells us about the drive it belongs to. */
    data class Resumable(
        /** Epoch millis of the last row written. */
        val lastRowAtMs: Long,
        /** The trip's starting delta-V, if the log had got far enough to record it. */
        val tripStartDeltaV: Double?,
        /** Rows counted in the file, for the log directory. */
        val rows: Int
    )

    /**
     * Inspect a drive log's lines.
     *
     * @param lines the file's contents, in order.
     * @param nowMs current time, so this stays testable.
     * @return what to restore, or null when the log is complete, empty,
     *   or too old to belong to the drive happening now.
     */
    fun inspect(lines: List<String>, nowMs: Long): Resumable? {
        if (lines.isEmpty()) return null

        var lastT = 0L
        var dvStart: Double? = null
        var rows = 0
        var sawStop = false

        for (line in lines) {
            val s = line.trim()
            if (s.isEmpty()) continue

            // A process killed mid-write leaves a partial final line. It is
            // not JSON and must not abort the scan — the rows before it are
            // still good, and they are exactly what we came for.
            if (!s.startsWith("{")) continue

            if (s.contains("\"msg\":\"stopped\"")) {
                sawStop = true
                continue
            }
            if (s.contains("\"kind\":\"sample\"")) rows++

            longField(s, "t")?.let { if (it > lastT) lastT = it }
            doubleField(s, "dv_start")?.let { dvStart = it }
        }

        // Closed properly: nothing to resume.
        if (sawStop) return null
        if (lastT <= 0L) return null
        if (nowMs - lastT > MAX_RESUME_GAP_MS) return null
        // A clock that has gone backwards (timezone change, NTP correction)
        // should not present a future log as resumable.
        if (lastT > nowMs + 60_000L) return null

        return Resumable(lastRowAtMs = lastT, tripStartDeltaV = dvStart, rows = rows)
    }

    /**
     * Whether a completed log ended cleanly.
     *
     * Used by the log directory to mark a drive as interrupted rather than
     * to resume it — a drive that ended in a crash is worth SEEING as one.
     */
    fun endedCleanly(lines: List<String>): Boolean =
        lines.any { it.contains("\"msg\":\"stopped\"") }

    // --- minimal field extraction ---
    //
    // Deliberately NOT a JSON parser. The rows are written by DriveRecorder
    // with a known shape, the fields wanted are numbers, and pulling in a
    // parser (or writing one) to read two keys would be a lot of surface
    // for no gain. Anything unparseable is simply skipped, which is the
    // correct response to a half-written line.

    internal fun longField(json: String, key: String): Long? =
        rawField(json, key)?.toLongOrNull()

    internal fun doubleField(json: String, key: String): Double? =
        rawField(json, key)?.toDoubleOrNull()

    private fun rawField(json: String, key: String): String? {
        val needle = "\"" + key + "\":"
        val at = json.indexOf(needle)
        if (at < 0) return null
        var i = at + needle.length
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '-' || c == '+' || c == '.' || c.isDigit() ||
                c == 'e' || c == 'E'
            ) {
                sb.append(c)
                i++
            } else break
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
