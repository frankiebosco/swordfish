package dev.swordfish.physics

/**
 * The ELM327 command layer: handshake sequence, reply classification, and
 * the framing rules the transport has to obey.
 *
 * Kept in the pure physics module for the same reason [ObdPid] is — the
 * interesting logic is string handling, and string handling is testable
 * against captured real-world replies without a dongle, a phone, or a car.
 * The Android layer owns the socket and nothing else.
 *
 * ## What an ELM327 conversation looks like
 *
 * The adapter is a line-oriented modem. You write an ASCII command
 * terminated by CR; it echoes (unless disabled), emits one or more reply
 * lines, then writes `>` to say it is ready for the next one. That prompt
 * is the only reliable end-of-reply marker — replies are variable-length
 * and a fixed read size will either truncate a long one or block on a
 * short one.
 *
 * **Read until `>`, never a fixed byte count.** This is the single most
 * common way a naive OBD transport breaks: it appears to work at low rates
 * and desynchronises under load, at which point every reply is one command
 * stale and the gauge lies rather than failing.
 */
object ElmProtocol {

    /** Every command is terminated with a carriage return. Not CRLF. */
    const val TERMINATOR = '\r'

    /** The adapter writes this when it is ready for the next command. */
    const val PROMPT = '>'

    /**
     * The connect handshake, in order.
     *
     * Each step is separate because each can fail independently and the
     * probe log should say *which* one did. A single blob of setup commands
     * that either works or does not is exactly the diagnostic that wastes
     * an evening in a car park.
     */
    val HANDSHAKE: List<Command> = listOf(
        Command(
            "ATZ",
            "reset the adapter to a known state",
            expectPrefix = "ELM327",
            timeoutMs = 5000
        ),
        Command(
            "ATE0",
            "disable command echo — halves the bytes on the wire",
            expectPrefix = "OK"
        ),
        Command(
            "ATL0",
            "disable linefeeds; CR alone terminates",
            expectPrefix = "OK"
        ),
        Command(
            "ATS0",
            "disable spaces between reply bytes — fewer bytes, faster",
            expectPrefix = "OK"
        ),
        Command(
            "ATH0",
            "disable headers; we address one ECU and do not need the CAN ID",
            expectPrefix = "OK"
        ),
        Command(
            "ATSP0",
            "automatic protocol detection",
            expectPrefix = "OK"
        )
    )

    /**
     * Configuration variants to race against each other for latency.
     *
     * ## Why this is measured rather than assumed
     *
     * The first real measurement on the MX+ came back at **14.8 cmd/s with
     * zero drops** — the link is perfectly reliable and simply slow, at
     * 66.7 ms per round trip. A 010C request on a 500 kbit CAN bus takes
     * under 2 ms on the wire, so ~97% of that is adapter and transport
     * overhead, not the car.
     *
     * There are several plausible causes and no way to tell them apart by
     * reasoning. Each entry here is a hypothesis the probe can test in
     * about ten seconds, and the winner is whatever actually wins.
     *
     * **Do not "optimise" this list by deleting the baseline.** Its whole
     * purpose is to be the control that the others are compared against.
     */
    data class TuningVariant(
        val label: String,
        val setup: List<String>,
        val hypothesis: String
    )

    val TUNING_VARIANTS: List<TuningVariant> = listOf(
        TuningVariant(
            "baseline",
            emptyList(),
            "the handshake as shipped -- the control"
        ),
        TuningVariant(
            "fixed protocol",
            listOf("ATSP6"),
            "ATSP0 auto-detect may re-probe the bus on every request; " +
                "ATSP6 pins ISO 15765-4 CAN 11-bit/500k, which is the ND2"
        ),
        TuningVariant(
            "aggressive timing",
            listOf("ATAT2"),
            "the ELM waits for possible additional ECU responses before " +
                "returning; adaptive timing 2 shortens that wait"
        ),
        TuningVariant(
            "short timeout",
            listOf("ATAT0", "ATST 20"),
            "fixed 80 ms ceiling instead of an adaptive wait -- the ND2 " +
                "answers a single-frame PID far faster than that"
        ),
        TuningVariant(
            "fixed protocol + aggressive",
            listOf("ATSP6", "ATAT2"),
            "both of the above, in case they are independent wins"
        ),
        TuningVariant(
            "one ECU only",
            listOf("ATSP6", "ATAT2", "ATSH 7E0", "ATCRA 7E8"),
            "addressing the engine ECU directly and filtering replies " +
                "removes any wait for other modules to answer"
        )
    )

    /**
     * One adapter command and what a healthy reply to it looks like.
     *
     * @param text The command, without its terminator.
     * @param rationale Why we send it, kept in the data so the reasoning
     *   survives a future tuning session — same principle as
     *   [PollSchedule.Entry.rationale].
     * @param expectPrefix A reply prefix that indicates success, or null
     *   when any non-error reply counts.
     * @param timeoutMs How long to wait for the prompt. `ATZ` reboots the
     *   adapter and genuinely takes seconds; everything else is fast.
     */
    data class Command(
        val text: String,
        val rationale: String,
        val expectPrefix: String? = null,
        val timeoutMs: Long = 1000
    ) {
        /** The bytes to write, terminator included. */
        val wire: String get() = text + TERMINATOR
    }

    /**
     * How a reply line should be interpreted.
     *
     * The distinction that matters is [NO_DATA] versus [ERROR]. A PID the
     * car does not support answers `NO DATA`, which is a *fact about the
     * car* and should be recorded as a capability, not retried. A bus
     * error is a fault and should be. Conflating them produces an app that
     * hammers an unsupported PID forever and blames the adapter.
     */
    enum class ReplyKind {
        /** A normal data reply, e.g. `410C1AF8`. */
        DATA,

        /** The ECU has nothing for this PID. A capability fact, not a fault. */
        NO_DATA,

        /** Adapter acknowledgement of an AT command. */
        OK,

        /** The adapter's identity banner, in reply to `ATZ`. */
        BANNER,

        /** A bus or adapter fault. Worth retrying, worth logging loudly. */
        ERROR,

        /**
         * The adapter is still negotiating a protocol. Not an error — the
         * real reply follows on the same read. Only ever seen right after
         * connecting.
         */
        SEARCHING,

        /**
         * The adapter aborted a previous operation and is not ready.
         *
         * Seen in the field as `STOPPED` in reply to `ATZ` when a prior
         * session left the adapter mid-command -- for example an `ATMA`
         * monitor that was never terminated. **This is not a fault and not
         * a dead adapter: a second attempt clears it.** Classifying it as
         * an error made the probe give up on a perfectly good dongle, and
         * the apparent "fix" was opening the OBDLink app and closing it
         * again, which simply reset the adapter as a side effect.
         */
        BUSY,

        /** Empty or unrecognisable. */
        UNKNOWN
    }

    /**
     * True when a reply means "try again", rather than "give up".
     *
     * The distinction matters because the remedy is automatic: a retry
     * costs milliseconds, where treating it as fatal costs the whole
     * session and sends the user hunting for a hardware fault.
     */
    fun isRetryable(kind: ReplyKind): Boolean = kind == ReplyKind.BUSY

    /**
     * Error strings an ELM327 can return in place of data.
     *
     * `UNABLE TO CONNECT` and `BUS INIT` failures mean the adapter cannot
     * reach the ECU at all — usually ignition off, which is worth saying
     * plainly rather than reporting as a generic failure.
     */
    private val ERROR_MARKERS = listOf(
        "UNABLE TO CONNECT",
        "BUS INIT",
        "BUS ERROR",
        "BUS BUSY",
        "CAN ERROR",
        "DATA ERROR",
        "FB ERROR",
        "LV RESET",
        "ERROR",
        "?"
    )

    /**
     * Classify one reply line.
     *
     * Order matters: `NO DATA` and `SEARCHING` are checked before the error
     * markers because neither is an error, and a substring test for
     * `"ERROR"` would otherwise be reached by other means.
     */
    fun classify(raw: String): ReplyKind {
        val s = raw.uppercase()
            .replace(">", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()

        if (s.isEmpty()) return ReplyKind.UNKNOWN
        if (s.contains("NO DATA")) return ReplyKind.NO_DATA
        if (s.contains("SEARCHING")) return ReplyKind.SEARCHING
        if (s.contains("STOPPED")) return ReplyKind.BUSY
        if (s.startsWith("ELM327")) return ReplyKind.BANNER
        if (s == "OK" || s.endsWith(" OK") || s.startsWith("OK")) return ReplyKind.OK
        if (ERROR_MARKERS.any { s.contains(it) }) return ReplyKind.ERROR

        // Anything that is entirely hex byte pairs is a data frame. This is
        // deliberately the last check: it is the loosest, and an error
        // string that happened to be valid hex would otherwise be silently
        // decoded as telemetry.
        val hexOnly = s.replace(" ", "")
        if (hexOnly.isNotEmpty() &&
            hexOnly.length % 2 == 0 &&
            hexOnly.all { it in '0'..'9' || it in 'A'..'F' }
        ) {
            return ReplyKind.DATA
        }

        return ReplyKind.UNKNOWN
    }

    /**
     * True when a reply means "the car is not talking" rather than "this
     * PID is unsupported".
     *
     * Surfaced separately because the user-facing remedy is completely
     * different: turn the ignition on, versus accept a degraded feature.
     */
    fun indicatesNoVehicleContact(raw: String): Boolean {
        val s = raw.uppercase()
        return s.contains("UNABLE TO CONNECT") ||
            s.contains("BUS INIT") ||
            s.contains("CAN ERROR")
    }

    /**
     * Split a raw read buffer into complete reply lines.
     *
     * The adapter separates lines with CR and finishes with the prompt.
     * Blank segments are dropped; the prompt itself is not a line.
     */
    fun splitLines(buffer: String): List<String> =
        buffer.split('\r', '\n', PROMPT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * True once a read buffer holds a complete reply.
     *
     * The prompt is the terminator. Anything else — a byte count, a
     * timeout, a line count — desynchronises under load.
     */
    fun isComplete(buffer: String): Boolean = buffer.contains(PROMPT)
}
