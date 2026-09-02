package dev.swordfish.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import dev.swordfish.physics.ElmProtocol
import dev.swordfish.physics.MsCanCapture
import dev.swordfish.physics.MsCanIdentify
import dev.swordfish.physics.MsCanProbe
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures MS-CAN traffic for a whole drive, paired with a reference signal.
 *
 * ## Why this is a MODE and not a background task
 *
 * `ObdTransport.monitor` writes `ATMA` and then reads continuously until its
 * deadline: it OWNS the socket for the whole window, and anything else
 * written stops the monitor. `ObdPoller` also owns the socket for its
 * lifetime.
 *
 * One adapter, one socket, and two things that each need it exclusively. So
 * this cannot quietly coexist with normal telemetry -- while MS-CAN capture
 * runs, **there is no rpm, no speed, no fuel flow and therefore no delta-V**.
 *
 * That is a real cost and the reason this is an explicit mode the driver
 * turns on, rather than something the app does opportunistically. The panel
 * says so while it is running.
 *
 * ## What it captures
 *
 * Every frame, stamped with the yaw rate the phone measured at that instant.
 * An ordinary drive supplies the variance the correlation needs -- see
 * `MsCanIdentify`; no car-park manoeuvre is required, and the readiness
 * check reports whether the route turned both ways.
 *
 * ## Why the raw frames are written to disk
 *
 * The correlation runs offline afterwards, and the ANSWER is not the point --
 * the raw capture is. A better reference signal, or a different hypothesis
 * about which byte carries what, can be tried against a saved capture
 * without driving again. That is exactly how the altitude bug was solved:
 * from logged data rather than a repeat drive.
 */
class MsCanSession(
    private val transport: ObdTransport,
    private val filesDir: File
) {

    /** Frames paired with the reference signal. */
    val capture = MsCanCapture()

    @Volatile
    var running: Boolean = false
        private set

    /** Frames seen, whether or not they were paired. */
    @Volatile
    var framesSeen: Int = 0
        private set

    /** Lines the parser rejected -- kept, because they are what a fix needs. */
    @Volatile
    var unparsedLines: Int = 0
        private set

    /** How many unparsed lines have been written verbatim (diagnostic cap). */
    private var unparsedLogged: Int = 0

    @Volatile
    var lastError: String? = null
        private set

    /**
     * Whether the car is above the speed at which a GPS bearing is usable.
     *
     * Supplied by the reference feed, which is the only thing that knows.
     * Without it, "no reference because we are parked" and "no reference
     * because the wiring is broken" are indistinguishable -- and mistaking
     * the second for the first is exactly what cost the 2026-08-26 drive.
     */
    @Volatile
    var moving: Boolean = false

    /** Wall clock of the most recent frame, for spotting a dead socket. */
    @Volatile
    var lastFrameAtMs: Long = 0L
        private set

    private val stopping = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swordfish-mscan").apply { isDaemon = true }
    }

    private var rawFile: File? = null

    /** What the current (or last) capture was for. */
    var captureMode: MsCanProbe.CaptureMode = MsCanProbe.CaptureMode.DISCOVERY
        private set

    /**
     * Adapter overflow/stall strings seen this capture.
     *
     * `BUFFER FULL` means the adapter could not forward the bus as fast as
     * it arrived and threw frames away; `STOPPED` means the monitor died.
     * On 2026-08-28 all three occurred and NOTHING surfaced them -- they sat
     * in a diagnostic buffer that was discarded, so a capture that lost half
     * its window looked healthy. Counted separately so they can reach the
     * banner.
     */
    var overflowEvents: Int = 0
        private set

    /**
     * Frames that parsed but were SHORTER than their ID is known to carry.
     *
     * The 2026-08-28 calibration failure mode, and invisible in every other
     * counter: these are not unparsed, not errors, and provoke no adapter
     * warning. A capture with `unparsed=0 overflow=0` and a high short count
     * is a capture whose data is silently unusable.
     */
    var shortFrames: Int = 0
        private set

    private var shortLogged: Int = 0

    /**
     * Begin a capture session.
     *
     * The caller MUST have stopped the poller first -- see the class note.
     *
     * @param durationMs how long to monitor. A whole drive, not the probe's
     *   eight seconds.
     */
    fun start(
        device: BluetoothDevice,
        adapter: BluetoothAdapter?,
        durationMs: Long,
        mode: MsCanProbe.CaptureMode = MsCanProbe.CaptureMode.DISCOVERY
    ) {
        if (running) return
        captureMode = mode
        running = true
        stopping.set(false)
        capture.clear()
        framesSeen = 0
        unparsedLines = 0
        unparsedLogged = 0
        overflowEvents = 0
        shortFrames = 0
        shortLogged = 0
        lastError = null
        lastFrameAtMs = 0L

        executor.execute {
            try {
                runSession(device, adapter, durationMs, mode)
            } catch (e: Throwable) {
                lastError = e.message
                Log.w("SwordfishMsCan", "session failed: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    /** Ask the capture to finish early. */
    fun stop() {
        stopping.set(true)
    }

    private fun runSession(
        device: BluetoothDevice,
        adapter: BluetoothAdapter?,
        durationMs: Long,
        mode: MsCanProbe.CaptureMode
    ) {
        if (!transport.open(device, adapter)) {
            lastError = "could not connect to the adapter"
            return
        }

        // Select MS-CAN. These are STN-specific and an ELM327 clone will
        // reject them -- which is itself the answer, so it is recorded
        // rather than treated as a crash.
        for (cmd in MsCanProbe.setupFor(mode)) {
            val reply = transport.send(cmd.text, cmd.timeoutMs)
            if (reply == null) {
                lastError = "no reply to ${cmd.text}"
                Log.w("SwordfishMsCan", lastError ?: "")
                return
            }
            // An ELM327 clone rejects the STN-specific selector, and that
            // rejection IS the answer -- recorded rather than thrown.
            val kind = ElmProtocol.splitLines(reply).lastOrNull()
                ?.let { ElmProtocol.classify(it) }
            if (kind == ElmProtocol.ReplyKind.ERROR) {
                lastError = "adapter rejected ${cmd.text}: ${reply.trim()}"
                Log.w("SwordfishMsCan", lastError ?: "")
                return
            }
        }

        val dir = File(filesDir, "mscan").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val f = File(dir, "mscan-$stamp.ndjson")
        rawFile = f

        // ONE writer for the whole capture, NOT File.appendText per frame.
        //
        // `appendText` opens, writes, flushes and closes the file on every
        // call. At the 20 lines/sec the old re-arming loop produced, that was
        // survivable. With one continuous ATMA the bus delivers ~300-420
        // lines/sec, and a disk open/close per line inside the read callback
        // blocks the reader long enough that the adapter gives up: the
        // 2026-08-27 PNC captures ran at full rate for 1.2s and 4.7s, then
        // stopped dead with 189s and 215s of silence left in the session.
        //
        // The callback must stay cheap. Buffer, and flush once per slice --
        // a slice boundary is already the place this code flushes and checks
        // for a stop request, so nothing is lost on a crash beyond the last
        // partial slice.
        val w: BufferedWriter = f.bufferedWriter()
        w.write(
            """{"t":$stamp,"kind":"mscan","msg":"started",""" +
                """"mode":"${mode.name}"}"""
        )
        w.newLine()
        w.flush()

        // ONE `ATMA` across the whole capture, read in slices.
        //
        // Re-arming every slice cost the boundary twice over: `drain` threw
        // away whatever arrived while we were between calls, and the stop
        // handshake (write, 200ms settle, drain) was ~200ms of guaranteed
        // deafness. On the 2026-08-27 town loop that left 7 frames/sec of
        // a bus doing hundreds, with twelve dead windows of a full 10.2s --
        // one per slice -- and 95% of the capture inside a gap.
        //
        // The slice loop still exists and still does everything it was
        // written for: it bounds how long a stop request waits, flushes as
        // it goes, and survives a dropped socket. It just no longer restarts
        // the stream it is slicing.
        val deadline = System.currentTimeMillis() + durationMs
        var armed = false
        var sliceNo = 0
        var deliveredThisSlice = 0
        // What the PREVIOUS slice delivered. Separate from the live counter
        // because the live one is reset before the next slice reads it.
        var prevDelivered = -1
        var rearmedThisSlice = false
        try {
            while (System.currentTimeMillis() < deadline && !stopping.get()) {
                val slice = minOf(SLICE_MS, deadline - System.currentTimeMillis())
                if (slice <= 0) break

                // RECOVERY (2026-08-27): re-arm when the previous slice went
                // silent.
                //
                // The stream dies ~4s in, INSIDE the first slice, before any
                // boundary -- so the first slice is byte-identical to what v72
                // did, and it died there too. What kept v72 alive for 510s was
                // that the NEXT slice resent ATMA and revived it. Removing the
                // re-arm removed an accidental watchdog, which is why the
                // capture got worse rather than better.
                //
                // So: continue a HEALTHY stream (that is the real fix, and it
                // is what raised the rate 20x), but re-arm a DEAD one rather
                // than reading silence for the rest of the drive.
                // Read the PREVIOUS slice's delivery, captured before the
                // counter is reset. Reading `deliveredThisSlice` here was a
                // bug: it is zeroed at the end of the loop body, so it was
                // always 0 at this point and every slice re-armed -- which
                // silently restored the very 10 s gaps the continuing-monitor
                // change exists to remove (51% of the 2026-08-27 ridge-road capture
                // was still dead time because of it).
                val reviving = armed && prevDelivered == 0 && sliceNo > 0
                if (reviving) rearmedThisSlice = true
                transport.monitor(
                    MsCanProbe.MONITOR_ALL,
                    slice,
                    continuing = armed && !reviving,
                    leaveRunning = true
                ) { line ->
                    deliveredThisSlice++
                    val frame = MsCanProbe.parseFrame(line, System.currentTimeMillis())
                    if (frame == null) {
                        unparsedLines++

                        // An adapter WARNING is never subject to the cap.
                        //
                        // 2026-08-28: the cap filled 41s into an 80s capture,
                        // and the 5,914 lines after it -- roughly 1.5 of the
                        // 3.5 laps driven -- were dropped at the moment of
                        // capture and could not be recovered from the file.
                        // Worse, the three lines that EXPLAINED the loss
                        // (`BUFFER FULL` x2, `STOPPED`) only survived by luck
                        // of arriving early.
                        //
                        // A warning is rare, tiny, and the whole reason to
                        // read this file when a capture disappoints. Ordinary
                        // frame text stays capped; warnings always get through.
                        val warn = MsCanProbe.adapterWarning(line)
                        if (warn != null) {
                            overflowEvents++
                            w.write(
                                """{"t":${System.currentTimeMillis()},""" +
                                    """"kind":"adapter","warn":"$warn",""" +
                                    """"text":${jsonStr(line)}}"""
                            )
                            w.newLine()
                            return@monitor
                        }

                        // DIAGNOSTIC (2026-08-27): the adapter's own words are
                        // the one thing we have never looked at. 66% of every
                        // capture is "unparsed" and the TEXT was thrown away,
                        // so a BUFFER FULL / STOPPED / ERROR right before the
                        // stream dies would have been invisible. Capped so a
                        // noisy bus cannot fill the disk.
                        if (unparsedLogged < MAX_UNPARSED_LOGGED) {
                            unparsedLogged++
                            w.write(
                                """{"t":${System.currentTimeMillis()},"kind":"raw",""" +
                                    """"text":${jsonStr(line)}}"""
                            )
                            w.newLine()
                        }
                        return@monitor
                    }
                    framesSeen++
                    lastFrameAtMs = frame.atMillis

                    // A SHORT frame keeps its raw text, uncapped.
                    //
                    // This is the one failure that leaves no trace anywhere
                    // else: a short frame parses successfully, so it is not
                    // "unparsed", the adapter does not warn, and the counters
                    // all look healthy. The 2026-08-28 calibration drive was
                    // lost this way -- `215` arrived as 3 bytes for the whole
                    // turn and the summary line read `unparsed=0 overflow=0`.
                    //
                    // Keeping the adapter's own words is what cracked the
                    // 2026-08-27 bugs after three wrong diagnoses reasoned
                    // from counters. Do not replace this with a counter.
                    if (MsCanProbe.isShortFrame(frame)) {
                        shortFrames++
                        if (shortLogged < MAX_SHORT_LOGGED) {
                            shortLogged++
                            w.write(
                                """{"t":${frame.atMillis},"kind":"short",""" +
                                    """"id":"${frame.id}","got":${frame.bytes.size},""" +
                                    """"want":${MsCanProbe.expectedLength(frame.id)},""" +
                                    """"text":${jsonStr(line)}}"""
                            )
                            w.newLine()
                        }
                    }

                    capture.onFrame(frame.id, frame.bytes, frame.atMillis)
                    // Raw, always. A saved capture can be re-analysed against a
                    // better reference without driving again.
                    w.write(
                        """{"t":${frame.atMillis},"kind":"frame","id":"${frame.id}",""" +
                            """"data":[${frame.bytes.joinToString(",")}]}"""
                    )
                    w.newLine()
                }
                armed = true

                // DIAGNOSTIC (2026-08-27): one row per slice, so the shape of
                // the death is visible instead of inferred. `delivered` is
                // what the TRANSPORT saw (every line, parsed or not); frames
                // and unparsed are what we made of it. A slice with
                // delivered=0 means the socket itself went quiet -- a slice
                // with delivered>0 and frames=0 means it is still talking and
                // we stopped understanding it. Those need opposite fixes.
                w.write(
                    """{"t":${System.currentTimeMillis()},"kind":"slice",""" +
                        """"n":$sliceNo,"delivered":$deliveredThisSlice,""" +
                        """"frames":$framesSeen,"unparsed":$unparsedLines,""" +
                        """"sinceLastFrameMs":${
                            if (lastFrameAtMs > 0) System.currentTimeMillis() - lastFrameAtMs else -1
                        },"rearmed":$rearmedThisSlice}"""
                )
                w.newLine()
                sliceNo++
                prevDelivered = deliveredThisSlice
                deliveredThisSlice = 0
                rearmedThisSlice = false

                // Once per slice, not once per frame. See the writer comment.
                w.flush()
            }
        } finally {
            // The monitor is left running across slices, so SOMETHING has to
            // stop it -- on the normal path, on a stop request, and on the
            // way out of an exception. An adapter still streaming ATMA
            // answers every later command with CAN frames.
            if (armed) transport.stopMonitor()
            // Whatever happened, do not lose the buffered tail. An exception
            // mid-slice would otherwise discard up to a slice of frames AND
            // leak the handle.
            runCatching { w.flush() }
        }

        w.write(
            """{"t":${System.currentTimeMillis()},"kind":"mscan","msg":"stopped",""" +
                """"frames":$framesSeen,"paired":${capture.size},""" +
                """"unparsed":$unparsedLines,"overflow":$overflowEvents,""" +
                """"short":$shortFrames,""" +
                """"mode":"${mode.name}"}"""
        )
        w.newLine()
        w.flush()
        w.close()
        Log.i("SwordfishMsCan", "capture finished: ${capture.summary()}")
    }

    /**
     * Feed the reference signal.
     *
     * Called from the telemetry path at whatever rate the reference updates
     * -- roughly 1 Hz for a GPS-derived yaw rate.
     */
    fun onReference(value: Double, atMs: Long) {
        capture.onReference(value, atMs)
    }

    /**
     * Report whether the car is moving fast enough for a usable bearing.
     *
     * Called by the reference feed every tick, INCLUDING ticks where there is
     * no bearing to supply -- that is the case this exists to describe.
     */
    fun onMoving(isMoving: Boolean) {
        moving = isMoving
    }

    /**
     * Escape an adapter line for NDJSON.
     *
     * Adapter output is arbitrary ASCII and has already included quotes and
     * backslashes in the wild; embedding it raw would produce a capture file
     * that will not parse, which is precisely the file we would need.
     */
    private fun jsonStr(raw: String): String {
        val sb = StringBuilder("\"")
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c.code > 0x7E -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    /** Where the raw capture was written, or null. */
    fun rawPath(): String? = rawFile?.absolutePath

    /**
     * Run the correlation over what has been captured.
     *
     * Offline and cheap -- it is arithmetic over a list, not another drive.
     */
    fun analyse(): List<MsCanIdentify.Candidate> =
        MsCanIdentify.identify(capture.observations())

    /**
     * Human-readable state, for the probe screen.
     *
     * Ordered so the ONE thing that matters is first and readable at a
     * glance from the driver's seat: is this working right now? The counts
     * follow for anyone who wants them.
     *
     * The 2026-08-26 failure was legible in the old output -- "paired=0" was
     * right there -- and still went unnoticed for two full captures, because
     * it was a number among numbers rather than a verdict.
     */
    fun status(): String = buildString {
        val now = System.currentTimeMillis()

        append(capture.health(now, moving))

        append("\n\n").append(if (running) "CAPTURING" else "idle")
        append(" — frames=").append(framesSeen)
        append(" paired=").append(capture.size)
        if (unparsedLines > 0) append(" unparsed=").append(unparsedLines)
        if (overflowEvents > 0) append(" overflow=").append(overflowEvents)

        // A VERDICT, not a number among numbers.
        //
        // 2026-08-28 read `frames=2158 paired=2157 unparsed=0` -- a capture
        // that looks perfect and whose turning data was entirely unusable,
        // because 218 of 480 wheel-speed frames arrived truncated. The count
        // alone would have gone unnoticed exactly as `paired=0` did on
        // 2026-08-26, so it is stated in words.
        if (shortFrames > 0) {
            append("\n\nSHORT FRAMES: ").append(shortFrames)
            append(" — the adapter is truncating. Wheel-speed data from ")
            append("this capture may be unusable.")
        }

        // A silent socket looks identical to a quiet bus in the counters.
        if (running && lastFrameAtMs > 0 &&
            now - lastFrameAtMs > SILENT_SOCKET_MS
        ) {
            append("\n\nNO FRAMES for ${(now - lastFrameAtMs) / 1000}s — the ")
            append("adapter may have dropped the bus.")
        }

        lastError?.let { append("\nERROR: ").append(it) }

        // Progress toward a usable dataset, which is what decides whether the
        // drive was worth taking. Shown once anything has paired.
        if (capture.size > 0) {
            val counts = capture.idCounts()
            val ready = counts.count { it.value >= USABLE_MIN_PER_ID }
            append("\n\nIDs with ").append(USABLE_MIN_PER_ID).append("+ samples: ")
            append(ready).append(" of ").append(counts.size)
            counts.entries
                .sortedByDescending { it.value }
                .take(TOP_IDS_SHOWN)
                .forEach { (id, n) ->
                    append("\n  ").append(id).append("  ").append(n)
                    if (n < USABLE_MIN_PER_ID) append(" (needs ").append(USABLE_MIN_PER_ID).append(")")
                }
        }

        append("\n\n").append(capture.readiness())
    }

    private companion object {
        /**
         * How long each monitor call runs before returning.
         *
         * Long enough that the ATMA restart overhead is negligible, short
         * enough that a stop request is honoured promptly and a dropped
         * socket costs one slice rather than the whole drive.
         */
        const val SLICE_MS = 10_000L

        /**
         * Silence that means the socket has died rather than the bus is idle.
         *
         * MS-CAN carries roughly 28 frames/s on this car, so five seconds of
         * nothing is far outside normal.
         */
        const val SILENT_SOCKET_MS = 5_000L

        /**
         * Cap on verbatim unparsed lines written to the capture (diagnostic).
         *
         * Enough to see what the adapter says at the start AND around a
         * death; small enough that a chatty bus cannot fill the disk.
         */
        const val MAX_UNPARSED_LOGGED = 4_000

        /**
         * Cap on verbatim SHORT-frame lines written to the capture.
         *
         * Separate from [MAX_UNPARSED_LOGGED] on purpose: on 2026-08-28 the
         * shared cap filled with ordinary frame text 41 s in and discarded
         * everything after, including the evidence. A short frame is the
         * rarer and more diagnostic event, so it gets its own budget and
         * cannot be crowded out.
         */
        const val MAX_SHORT_LOGGED = 2_000

        /** Samples per ID before that ID can be scored -- matches readiness. */
        const val USABLE_MIN_PER_ID = 30

        /** How many IDs to list, so the panel stays readable in a car. */
        const val TOP_IDS_SHOWN = 5
    }
}
