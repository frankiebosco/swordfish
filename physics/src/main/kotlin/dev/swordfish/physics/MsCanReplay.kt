package dev.swordfish.physics

/**
 * Re-analyses a saved MS-CAN capture against a reference built from a drive log.
 *
 * ## Why the capture is worth more than the answer
 *
 * `MsCanSession` writes every frame to disk. That looks redundant -- it
 * already correlates them live -- but the raw capture is the durable asset:
 * a better reference signal, a different hypothesis about which byte carries
 * what, or a wider search can all be tried against it WITHOUT DRIVING AGAIN.
 *
 * That is not theoretical. The altitude bug was solved from logged data
 * rather than a repeat drive, and it took three attempts to get right.
 *
 * ## Joining two files
 *
 * The MS-CAN capture and the drive log are separate NDJSON files written by
 * separate subsystems, joined on wall-clock time. Frames arrive far faster
 * than drive samples, so each frame takes the nearest sample within a
 * tolerance -- and frames with no sample nearby are DROPPED rather than
 * paired with a distant one, for the same reason `MsCanCapture` refuses a
 * stale reference: a blurred correlation makes a real signal look absent.
 */
object MsCanReplay {

    /** One frame read back from a capture file. */
    data class Frame(val atMs: Long, val canId: String, val data: List<Int>)

    /**
     * How near in time a drive sample must be to stamp a frame.
     *
     * Drive rows land at 1 Hz, so 750 ms means a frame always takes the
     * closer of the two surrounding samples and never one a full second out.
     */
    const val JOIN_TOLERANCE_MS = 750L

    /** Parse the frame rows of a capture file. */
    fun parseFrames(lines: List<String>): List<Frame> {
        val out = ArrayList<Frame>()
        for (raw in lines) {
            val s = raw.trim()
            if (!s.startsWith("{") || !s.contains("\"kind\":\"frame\"")) continue
            val t = DriveLog.num(s, "t")?.toLong() ?: continue
            val id = DriveLog.str(s, "id") ?: continue
            val data = parseByteArray(s) ?: continue
            out += Frame(t, id, data)
        }
        return out
    }

    /** Pull `"data":[1,2,3]` out of a row. */
    internal fun parseByteArray(json: String): List<Int>? {
        val key = "\"data\":["
        val at = json.indexOf(key)
        if (at < 0) return null
        val end = json.indexOf(']', at)
        if (end < 0) return null
        val body = json.substring(at + key.length, end).trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Yaw rate over time, derived from a drive log's heading column.
     *
     * The reference the frames are scored against. Built from `heading_deg`,
     * which `ImuSource` already holds through a stop rather than reporting
     * noise -- so only ~0.4% of samples lack it.
     *
     * Samples below [minSpeedMps] are skipped: a stationary car has no
     * direction of travel, and its bearing is noise that would be correlated
     * against real bytes.
     */
    fun yawSeries(
        driveLines: List<String>,
        minSpeedMps: Double = 3.0
    ): List<Pair<Long, Double>> {
        data class Row(val t: Long, val heading: Double, val speed: Double)
        val rows = ArrayList<Row>()
        for (raw in driveLines) {
            val s = raw.trim()
            if (!s.contains("\"kind\":\"sample\"")) continue
            val t = DriveLog.num(s, "t")?.toLong() ?: continue
            val h = DriveLog.num(s, "heading_deg") ?: continue
            val v = DriveLog.num(s, "speed_mps") ?: 0.0
            rows += Row(t, h, v)
        }

        val out = ArrayList<Pair<Long, Double>>()
        for (i in 1 until rows.size) {
            val a = rows[i - 1]
            val b = rows[i]
            if (b.speed < minSpeedMps) continue
            val dt = (b.t - a.t) / 1000.0
            val yaw = MsCanIdentify.yawRateFromBearings(a.heading, b.heading, dt)
                ?: continue
            out += b.t to yaw
        }
        return out
    }

    /**
     * Join frames to a reference series on wall-clock time.
     *
     * Both inputs must be sorted by time, which they are as written.
     */
    fun join(
        frames: List<Frame>,
        reference: List<Pair<Long, Double>>,
        toleranceMs: Long = JOIN_TOLERANCE_MS
    ): List<MsCanIdentify.Observation> {
        if (frames.isEmpty() || reference.isEmpty()) return emptyList()
        val out = ArrayList<MsCanIdentify.Observation>(frames.size)

        var i = 0
        for (f in frames) {
            // Advance to the last reference at or before this frame.
            while (i + 1 < reference.size && reference[i + 1].first <= f.atMs) i++

            val before = reference[i]
            val after = if (i + 1 < reference.size) reference[i + 1] else null

            val nearest = when {
                after == null -> before
                kotlin.math.abs(f.atMs - before.first) <=
                    kotlin.math.abs(after.first - f.atMs) -> before
                else -> after
            }
            if (kotlin.math.abs(f.atMs - nearest.first) > toleranceMs) continue
            out += MsCanIdentify.Observation(f.canId, f.data, nearest.second)
        }
        return out
    }

    /**
     * The whole offline pipeline: two files in, ranked candidates out.
     *
     * @param captureLines an `mscan-*.ndjson` written by `MsCanSession`.
     * @param driveLines the `drive-*.ndjson` recorded over the same period.
     */
    fun analyse(
        captureLines: List<String>,
        driveLines: List<String>,
        minSamples: Int = 30
    ): List<MsCanIdentify.Candidate> {
        val frames = parseFrames(captureLines)
        val yaw = yawSeries(driveLines)
        return MsCanIdentify.identify(join(frames, yaw), minSamples)
    }

    /**
     * What a capture contains, before any correlation is attempted.
     *
     * Worth printing first: a capture with 40 000 frames across 3 IDs and no
     * overlapping drive log will produce a confident-looking empty result,
     * and the reason should be visible rather than inferred.
     */
    fun describe(
        captureLines: List<String>,
        driveLines: List<String>
    ): String {
        val frames = parseFrames(captureLines)
        val yaw = yawSeries(driveLines)
        val joined = join(frames, yaw)
        val ids = joined.groupingBy { it.canId }.eachCount()
        val scorable = ids.count { it.value >= 30 }
        return buildString {
            append("frames=").append(frames.size)
            append(" reference=").append(yaw.size)
            append(" joined=").append(joined.size)
            append(" ids=").append(ids.size)
            append(" scorable=").append(scorable)
            if (frames.isNotEmpty() && joined.isEmpty()) {
                append("\nNO OVERLAP — the capture and the drive log do not ")
                append("cover the same period")
            }
        }
    }
}
