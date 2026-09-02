package dev.swordfish.ui

import android.content.Context
import dev.swordfish.physics.DriveLog
import java.io.File

/**
 * The recorded drives on disk, as a list worth showing.
 *
 * ## Why the summaries are cached in memory
 *
 * Summarising a drive means reading and parsing every row, and a 63-minute
 * drive is 3,669 of them. Doing that for the whole directory on every scroll
 * would make the list stutter; doing it once per file and keeping the result
 * costs a few hundred bytes each.
 *
 * The cache is keyed on the file's last-modified time, so a drive still
 * being written -- the one that just ended -- re-summarises when it changes
 * and everything else is read once.
 */
class DriveLibrary(private val context: Context) {

    /** A drive on disk, with its parsed summary. */
    data class Entry(
        val file: File,
        val summary: DriveLog.Summary?,
        val sizeBytes: Long
    ) {
        /** The recording's own timestamp, from `drive-YYYYMMDD-HHMMSS.ndjson`. */
        val stamp: String get() = file.name
            .removePrefix("drive-").removeSuffix(".ndjson")

        /**
         * Worth showing in the list?
         *
         * A drive with no samples is a launch that never got telemetry --
         * usually a few seconds on a desk. There are a dozen such files in
         * any real directory and they bury the drives that matter.
         */
        val isRealDrive: Boolean
            get() = summary != null && summary.rows >= MIN_ROWS_TO_LIST
    }

    private val cache = HashMap<String, Pair<Long, DriveLog.Summary?>>()

    /** The drives directory, created on demand. */
    fun directory(): File =
        File(context.getExternalFilesDir(null), "drives").apply { mkdirs() }

    /**
     * Every recording, newest first.
     *
     * @param includeTrivial when false, launches that recorded almost
     *   nothing are omitted.
     */
    fun list(includeTrivial: Boolean = false): List<Entry> {
        val files = directory()
            .listFiles { f -> f.name.endsWith(".ndjson") }
            ?.sortedByDescending { it.name }
            ?: return emptyList()

        val out = ArrayList<Entry>(files.size)
        for (f in files) {
            val key = f.absolutePath
            val cached = cache[key]
            val summary = if (cached != null && cached.first == f.lastModified()) {
                cached.second
            } else {
                // A file being written, or one truncated by a crash, must
                // not take the whole list down with it.
                val s = try {
                    DriveLog.summarise(f.readLines())
                } catch (e: Exception) {
                    null
                }
                cache[key] = f.lastModified() to s
                s
            }
            val e = Entry(f, summary, f.length())
            if (includeTrivial || e.isRealDrive) out += e
        }
        return out
    }

    /** Total bytes held by all recordings. */
    fun totalBytes(): Long =
        directory().listFiles()?.sumOf { it.length() } ?: 0L

    /** Read one drive's rows, for the detail screen. */
    fun lines(entry: Entry): List<String> = try {
        entry.file.readLines()
    } catch (e: Exception) {
        emptyList()
    }

    /** Delete a recording. Returns true when it is gone. */
    fun delete(entry: Entry): Boolean {
        cache.remove(entry.file.absolutePath)
        return try {
            entry.file.delete()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /**
         * Below this a recording is a launch, not a drive.
         *
         * Rows land at 1 Hz, so 30 is half a minute. Every real directory
         * has a dozen sub-10-row files from opening the app at a desk.
         */
        const val MIN_ROWS_TO_LIST = 30
    }
}
