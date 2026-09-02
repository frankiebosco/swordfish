package dev.swordfish.physics

/**
 * Probing the medium-speed CAN bus for the car's own chassis sensors.
 *
 * ## What is being chased
 *
 * The ND2 measures yaw rate, lateral acceleration, individual wheel speeds
 * and steering angle — that is how the DSC telltale knows to flash. None of
 * it appears in generic OBD-II; it lives on the ABS/DSC module's bus. If it
 * is reachable, it beats the phone outright for the one thing the phone is
 * worst at: separating body roll from cornering force, which an
 * accelerometer cannot do because both tip the gravity vector identically.
 *
 * Equally important, the car's sensors are bolted to the chassis. They do
 * not slide across the passenger seat.
 *
 * ## Status: unconfirmed on this car, but plausible
 *
 * OBDLink's coverage document lists Mazda MS-CAN support and stops at the
 * 2017-2018 ND. The document is dated 12.2019, so it is **silent** about a
 * 2023 car rather than negative — and the ND2 is the same platform
 * generation with the same Ford-derived MS-CAN heritage. A mid-cycle
 * refresh does not remove a bus. Treat absence of documentation as absence
 * of evidence, not evidence of absence.
 *
 * ## Why this is a listener, not a decoder
 *
 * Mazda publishes no CAN ID mapping, so raw frames mean nothing on their
 * own. Identification is empirical and happens **offline**: capture
 * everything with synchronised timestamps, drive a known pattern, then
 * correlate. Trying to decode live in the car would mean guessing at a
 * mapping while driving.
 *
 * This type therefore parses frames into a neutral structure and summarises
 * which IDs are active and which bytes move. That summary is what makes an
 * offline mapping session tractable.
 */
object MsCanProbe {

    /**
     * ELM/STN commands to select and monitor MS-CAN.
     *
     * `STP 53` is an STN-specific protocol selector — ISO 15765, 11-bit
     * identifiers, 125 kbaud, which is the usual medium-speed
     * configuration. It exists only on genuine STN chipsets, which is one
     * of the reasons the MX+ was bought over a clone.
     *
     * `ATMA` then monitors all traffic, printing frames continuously until
     * anything is written to the adapter.
     */
    val SELECT_MS_CAN = listOf(
        Command("ATZ", "reset to a known state", 5000),
        Command("ATE0", "disable echo", 1000),
        Command("STP 53", "select MS-CAN (ISO 15765, 11-bit, 125 kbaud)", 2000),
        Command("ATH1", "headers ON -- the CAN ID is the whole point here", 1000)
    )

    /** Monitor-all command. Sent last, and answers continuously. */
    const val MONITOR_ALL = "ATMA"

    /** Anything written stops a running ATMA. A CR alone is conventional. */
    const val STOP_MONITOR = "\r"

    /**
     * What a capture is FOR, which decides how much of the bus it listens to.
     *
     * These are two different jobs that happen to share one socket, and
     * running the second through the first is what broke the 2026-08-28
     * traffic-circle capture.
     *
     * [DISCOVERY] wants everything: the whole point is finding IDs nobody
     * has identified yet, so volume is the deliverable. This is how `215`
     * and `202` were found and it must stay exactly as it was.
     *
     * [WHEEL_CALIBRATION] wants two known IDs and nothing else. Here the
     * volume is pure harm -- see [FILTER_TO_CALIBRATION_IDS].
     */
    enum class CaptureMode {
        /** Monitor the whole bus. Unfiltered, for identifying new IDs. */
        DISCOVERY,

        /** Only the frames the wheel calibration consumes. */
        WHEEL_CALIBRATION
    }

    /**
     * The IDs [CaptureMode.WHEEL_CALIBRATION] keeps.
     *
     * `215` is the four wheel speeds -- the signal being calibrated. `202`
     * carries vehicle speed in the SAME units (ratio 0.998), which makes it
     * the cross-check that catches a bad fit, so it is cheap to keep and
     * expensive to lose.
     */
    val CALIBRATION_IDS = listOf("215", "202")

    /**
     * Adapter commands to hear ONLY [CALIBRATION_IDS].
     *
     * ## Why this exists: the 2026-08-28 traffic circle
     *
     * That capture was driven correctly -- 3.5 laps counter-clockwise at a
     * steady radius, exactly the input `WheelCalibration.fitSide` wants --
     * and still could not be fitted. `ATMA` on this car delivers ~9,300
     * frames across 20 IDs in 80 seconds, and the adapter reported
     * **`BUFFER FULL` twice and `STOPPED` once**. Roughly half the capture
     * window delivered nothing while the buffer drained and the stream was
     * re-armed.
     *
     * Of that whole drive only **46** usable `215` frames survived, against
     * a threshold of 100. The bottleneck was never the driving or the
     * decoder: it was asking the adapter to forward a full bus over a
     * serial link that cannot carry it.
     *
     * A mask/filter pair moves that decision into the adapter, which drops
     * unwanted frames before they reach the buffer. `215` and `202` are
     * ~17% of frames seen, so this is roughly a 6x headroom improvement on
     * the one signal that matters.
     *
     * ## Why a mask, not `ATCRA`
     *
     * `ATCRA` sets a single receive address and cannot express "two IDs".
     * `ATCM`/`ATCF` are mask-and-filter: a frame is kept when
     * `(id AND mask) == (filter AND mask)`.
     *
     * `215` and `202` share no clean prefix, so no mask admits exactly
     * those two and nothing else. Mask `7E8` / filter `215` is the pair
     * that admits both while leaking the fewest others: exactly one, `217`,
     * which is ~165 frames against the ~8,200 this rejects. The leak costs
     * nothing -- `217` is dropped by ID at parse time -- so the filter is a
     * throughput optimisation and never a correctness gate.
     *
     * Measured against the 2026-08-28 capture: 9,278 frames observed,
     * 1,100 kept. **88% fewer frames, 8.4x the headroom** for the signal
     * that actually matters.
     *
     * The arithmetic is verified in `MsCanCaptureModeTest`, because a mask
     * that silently blocked `215` would reproduce the very failure this
     * exists to fix -- and would look exactly like a quiet bus. The first
     * draft of this constant did precisely that.
     *
     * Sent AFTER protocol selection and BEFORE `ATMA`, because a running
     * monitor consumes any write as its stop signal.
     */
    val FILTER_TO_CALIBRATION_IDS = listOf(
        Command("ATCM 7E8", "mask: which ID bits must match the filter", 1000),
        Command("ATCF 215", "filter: admits 215 and 202, rejects the rest", 1000)
    )

    /**
     * Undo a receive filter, so a later DISCOVERY capture is unfiltered.
     *
     * The adapter keeps a filter until it is cleared or reset. A stale
     * filter would make a discovery run silently blind to every ID it
     * exists to find -- and that failure looks exactly like a dead bus,
     * which is the most misleading shape a bug can take here.
     */
    val CLEAR_FILTERS = listOf(
        Command("ATCRA", "clear any receive-address filter", 1000)
    )

    /** The setup commands for a mode, in the order they must be sent. */
    fun setupFor(mode: CaptureMode): List<Command> = when (mode) {
        CaptureMode.DISCOVERY -> SELECT_MS_CAN + CLEAR_FILTERS
        CaptureMode.WHEEL_CALIBRATION -> SELECT_MS_CAN + FILTER_TO_CALIBRATION_IDS
    }

    data class Command(
        val text: String,
        val rationale: String,
        val timeoutMs: Long = 1000
    )

    /**
     * One captured CAN frame.
     *
     * @param id arbitration ID, as reported by the adapter.
     * @param bytes payload.
     * @param atMillis capture time, for correlating against phone IMU data
     *   in an offline analysis.
     */
    data class Frame(
        val id: String,
        val bytes: List<Int>,
        val atMillis: Long
    )

    /**
     * Parse one `ATMA` output line into a frame.
     *
     * With headers on, a line looks like `4B0 12 34 56 78` — the
     * arbitration ID followed by payload bytes. Returns null for anything
     * that is not a frame, which includes the adapter's own chatter and
     * partial lines caught mid-write.
     */
    fun parseFrame(raw: String, atMillis: Long = 0L): Frame? {
        val cleaned = raw.uppercase()
            .replace(">", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        if (cleaned.isEmpty()) return null

        // Adapter status strings are not frames.
        if (cleaned.contains("STOPPED") || cleaned.contains("SEARCHING")) return null

        // `<DATA ERROR` is an ANNOTATION, not a rejection.
        //
        // The adapter appends it to an otherwise COMPLETE frame when the CAN
        // checksum did not verify. On the 2026-08-27 ridge-road capture this was
        // **100% of the 128,799 "unparsed" lines** -- two thirds of every
        // capture ever taken -- and every one carried a full, valid
        // id-plus-bytes payload that was being thrown away.
        //
        // The strip must happen BEFORE the classify check: `<DATA ERROR`
        // classifies as ERROR, so testing first discarded these lines before
        // anything could look at them.
        val dataError = cleaned.contains("DATA ERROR")
        val payload = if (dataError) cleaned.substringBefore("<").trim() else cleaned
        if (payload.isEmpty()) return null
        if (!dataError && ElmProtocol.classify(payload) == ElmProtocol.ReplyKind.ERROR) return null

        val tokens = payload.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null

        // First token is the ID: 3 hex chars for 11-bit, 8 for 29-bit.
        val id = tokens[0]
        if (id.length != 3 && id.length != 8) return null
        if (!id.all { it in '0'..'9' || it in 'A'..'F' }) return null

        val bytes = mutableListOf<Int>()
        for (tok in tokens.drop(1)) {
            if (tok.length != 2) return null
            bytes += tok.toIntOrNull(16) ?: return null
        }
        if (bytes.isEmpty()) return null

        return Frame(id, bytes, atMillis)
    }

    /**
     * Payload length a frame of this ID is expected to carry, or null when
     * the ID has no established length.
     *
     * CAN frames from a given module are fixed-length, so a SHORT frame is
     * evidence of a delivery problem rather than a different message. Only
     * IDs whose length has actually been observed across whole captures are
     * listed -- this must never guess.
     */
    fun expectedLength(id: String): Int? = when (id) {
        "215" -> 8
        "202" -> 8
        else -> null
    }

    /**
     * True when a parsed frame is SHORTER than its ID is known to carry.
     *
     * ## Why this is worth a diagnostic of its own
     *
     * A short frame parses **successfully**: `parseFrame` returns a valid
     * `Frame` with fewer bytes, so it never reaches the unparsed path and
     * its original text is discarded. That is how the 2026-08-28 wheel
     * calibration failed without a single adapter warning.
     *
     * On that drive `215` arrived as **3 bytes for the entire turn**
     * (16:15:00-16:15:23, 218 frames) while the approach and exit straights
     * were a clean 8. Three bytes is one wheel and half of the second, so
     * the two wheels a side-comparison needs were simply absent -- and the
     * only samples that survived were the straights, where there is
     * correctly no side difference to find. Agreement came out 45%, which
     * reads as noise rather than as missing data.
     *
     * `202` and `217` stayed 8 bytes in the same window, so this is
     * specific to `215` and not general congestion. The frame rate roughly
     * doubled in the turn (24/s -> 45/s) as the ABS/DSC module got busier,
     * which is the correlate -- but **the mechanism is not proven**, and
     * that is exactly why the raw text is now kept instead of inferred.
     */
    /**
     * ## What the raw text proved (2026-08-29)
     *
     * The truncation is **not** the adapter clipping under load. Preserved
     * raw lines read `215 32 F1 33` -- a clean, complete line with no error
     * marker and no `<DATA ERROR`. The adapter emitted exactly three byte
     * pairs and terminated the line normally.
     *
     * Throughput is ruled out: slice delivery was flat at ~450/slice with
     * `unparsed=0` across the whole capture, *including* the truncated
     * stretch. The adapter was never behind.
     *
     * What separates the two forms is **speed**. On 2026-08-29 the split was
     * clean with no overlap: 8-byte frames spanned w0 3062-7102, 3-byte
     * frames 2465-3041, a sharp boundary near ~3050 counts (roughly 19 mph
     * on a crude GPS scale). The car appears to send a SHORTER `215` frame
     * below that speed -- a different message layout, not a damaged one.
     *
     * That makes the 8-byte assumption in [expectedLength] a statement about
     * the high-speed form only. It is still the right guard -- a 3-byte
     * frame genuinely cannot serve a four-wheel comparison -- but the label
     * "short" describes what the DECODER needs, not a fault in the bus.
     */
    fun isShortFrame(frame: Frame): Boolean {
        val want = expectedLength(frame.id) ?: return false
        return frame.bytes.size < want
    }

    /**
     * Classify an unparsable line as a known adapter WARNING, or null.
     *
     * These are the adapter telling you it could not keep up. They are the
     * difference between "the bus was quiet" and "we asked for too much",
     * and on 2026-08-28 that distinction was invisible: the capture looked
     * merely disappointing when the adapter had said `BUFFER FULL` twice.
     *
     * `BUFFER FULL` -- the receive buffer overflowed; frames were dropped
     * by the adapter before the phone ever saw them. The fix is to ask for
     * less of the bus, which is what [CaptureMode.WHEEL_CALIBRATION] does.
     *
     * `STOPPED` -- the monitor ended, usually because something was written
     * to the adapter while `ATMA` was running.
     *
     * Returns a short stable token for logging, never the raw line.
     */
    fun adapterWarning(raw: String): String? {
        val s = raw.uppercase()
        return when {
            s.contains("BUFFER FULL") -> "BUFFER_FULL"
            s.contains("STOPPED") -> "STOPPED"
            s.contains("RX ERROR") -> "RX_ERROR"
            s.contains("CAN ERROR") -> "CAN_ERROR"
            else -> null
        }
    }

    /**
     * What a capture contained, per arbitration ID.
     *
     * The useful signal for offline identification is **which bytes move**.
     * A yaw sensor's bytes change constantly while turning and sit still
     * when parked; a static configuration frame never changes at all. Byte
     * volatility is what separates them without knowing any mapping.
     */
    data class IdSummary(
        val id: String,
        val frameCount: Int,
        val payloadLength: Int,
        /** Distinct values seen per byte position. 1 = constant. */
        val byteVariety: List<Int>
    ) {
        /** Byte positions that changed during the capture. */
        val activeBytes: List<Int>
            get() = byteVariety.indices.filter { byteVariety[it] > 1 }

        /**
         * True when this ID is worth investigating.
         *
         * A frame whose every byte is constant carries configuration or
         * status, not a live measurement.
         */
        val isInteresting: Boolean get() = activeBytes.isNotEmpty()

        fun describe(): String =
            "$id  ${frameCount}f  ${payloadLength}B  " +
                if (isInteresting) {
                    "active bytes ${activeBytes.joinToString(",")}"
                } else "static"
    }

    /** Summarise a capture by arbitration ID. */
    fun summarise(frames: List<Frame>): List<IdSummary> =
        frames.groupBy { it.id }.map { (id, group) ->
            val len = group.maxOf { it.bytes.size }
            val variety = (0 until len).map { pos ->
                group.mapNotNull { it.bytes.getOrNull(pos) }.distinct().size
            }
            IdSummary(id, group.size, len, variety)
        }.sortedByDescending { it.frameCount }

    /**
     * A verdict on whether MS-CAN is reachable at all.
     *
     * Deliberately blunt: the whole point of the probe is a yes or a no,
     * and a capture of zero frames is a clean no rather than an
     * inconclusive result.
     */
    fun verdict(frames: List<Frame>): String = when {
        frames.isEmpty() ->
            "NO FRAMES — MS-CAN not reachable this way on this car"
        summarise(frames).none { it.isInteresting } ->
            "${frames.size} frames, but nothing changed — bus is quiet or static"
        else -> {
            val interesting = summarise(frames).count { it.isInteresting }
            "${frames.size} frames across ${summarise(frames).size} IDs, " +
                "$interesting with live data — MS-CAN IS REACHABLE"
        }
    }
}
