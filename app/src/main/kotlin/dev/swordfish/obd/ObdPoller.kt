package dev.swordfish.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import dev.swordfish.physics.ElmProtocol
import dev.swordfish.physics.LinkState
import dev.swordfish.physics.ObdPid
import dev.swordfish.physics.PollCursor
import dev.swordfish.physics.PollSchedule

/**
 * Runs the tiered poll on a background thread and publishes the latest
 * readings.
 *
 * ## Threading contract
 *
 * One thread owns the socket for its whole life. [cursor] and [linkState]
 * are read from the render thread at 20 Hz while this thread writes them,
 * which is safe here because readings are immutable values swapped into a
 * map — a reader sees either the old value or the new one, never a torn
 * one. Nothing takes a lock on the render path, because a stalled render
 * thread is an ANR and the panel already survived one of those.
 *
 * ## Why the schedule is not a fixed loop
 *
 * A naive implementation walks the nine PIDs in order and sleeps. That
 * couples every PID's rate to the slowest one and wastes most of the budget
 * on values that have not changed. [PollCursor] instead picks whichever PID
 * is most overdue in units of its own interval, so the fast tier keeps its
 * 10 Hz while the slow tier cannot be starved.
 *
 * ## Degradation
 *
 * If the measured rate cannot sustain [PollSchedule.ALL], the poller drops
 * to [PollSchedule.degraded] — halving the fast tier rather than dropping
 * PIDs, so the gauge coarsens instead of losing its fuel-flow input. The
 * switch is one-way within a session: flapping between schedules would make
 * the gauge behave differently minute to minute for no visible reason.
 */
class ObdPoller(
    private val onLog: (String) -> Unit = {}
) {

    @Volatile
    var linkState: LinkState = LinkState.NO_ADAPTER
        private set

    @Volatile
    var cursor: PollCursor = PollCursor()
        private set

    /** Achieved successful reads per second, for the panel and the log. */
    @Volatile
    var achievedRate: Double = 0.0
        private set

    /** True once the poller has fallen back to the degraded schedule. */
    @Volatile
    var isDegraded: Boolean = false
        private set

    private val transport = ObdTransport { onLog(it) }
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Start polling. Returns immediately; work happens on a new thread. */
    fun start(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        // LOG BEFORE ANY EARLY RETURN.
        //
        // On the 2026-08-21 drive the panel stayed on demo data and
        // `logcat -s swordfish-poll` was completely EMPTY -- no thread, no
        // error, nothing to diagnose. A silent early return is worse than a
        // crash: a crash at least leaves a stack trace.
        onLog("poll: start requested (running=$running, thread=${thread != null})")

        // `running` guards against double-start, but this object is a
        // process-wide singleton that outlives the service. If a previous
        // attempt died without clearing the flag, every later start would
        // return here forever and the gauge would sit on demo data for the
        // rest of the app's life. Recover instead of wedging.
        if (running) {
            val alive = thread?.isAlive == true
            if (alive) {
                onLog("poll: already running, ignoring")
                return
            }
            onLog("poll: stale running flag with dead thread -- recovering")
            running = false
            thread = null
        }

        running = true
        thread = Thread({
            // An uncaught exception here would leave `running` true forever
            // and permanently wedge the poller -- see the recovery above.
            try {
                loop(device, adapter)
            } catch (e: Throwable) {
                onLog("poll: thread died: ${e.javaClass.simpleName}: ${e.message}")
                linkState = LinkState.LOST
            } finally {
                running = false
                onLog("poll: thread exited")
            }
        }, "obd-poll").apply {
            // Below the render thread: a late gauge frame is worse than a
            // late PID, and the poll is deliberately ahead of what the
            // display consumes.
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        transport.close()
        linkState = LinkState.NO_ADAPTER
    }

    private fun loop(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        onLog("poll: thread started")
        linkState = LinkState.HANDSHAKE

        if (!transport.open(device, adapter)) {
            linkState = LinkState.NO_ADAPTER
            onLog("poll: could not open socket")
            running = false
            return
        }

        if (!handshake()) {
            linkState = LinkState.NO_ADAPTER
            transport.close()
            running = false
            return
        }

        linkState = LinkState.CAPABILITIES
        if (!confirmVehicleContact()) {
            // Adapter is fine, ECU is not answering. Distinct state because
            // the remedy is a key turn, not a debugging session.
            linkState = LinkState.NO_VEHICLE
            // Keep the socket and keep trying: the usual cause is starting
            // the app before the engine, which resolves itself.
            if (!waitForVehicle()) {
                transport.close()
                running = false
                return
            }
        }

        pollUntilStopped()
    }

    private fun handshake(): Boolean {
        for (cmd in ElmProtocol.HANDSHAKE + POST_HANDSHAKE) {
            var kind: ElmProtocol.ReplyKind? = null

            // Retry on BUSY -- `STOPPED` means a previous session left the
            // adapter mid-command, not that it is faulty. See the note in
            // ProbeRunner.handshake.
            for (attempt in 0 until HANDSHAKE_ATTEMPTS) {
                val reply = transport.send(cmd.text, cmd.timeoutMs)
                if (reply == null) {
                    onLog("poll: handshake stalled at ${cmd.text}")
                    return false
                }
                kind = ElmProtocol.splitLines(reply).lastOrNull()
                    ?.let { ElmProtocol.classify(it) }

                if (kind != ElmProtocol.ReplyKind.BUSY) break
                onLog("poll: adapter busy on ${cmd.text}, retrying")
                try {
                    Thread.sleep(HANDSHAKE_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }

            if (kind == ElmProtocol.ReplyKind.ERROR ||
                kind == ElmProtocol.ReplyKind.BUSY
            ) {
                onLog("poll: ${cmd.text} returned $kind")
                return false
            }
        }
        // Logged on SUCCESS too. The 13:20 log jumped straight from
        // "connected" to `timeout ... 010C` with nothing in between, which
        // made it look as though the handshake had been skipped entirely.
        // It had not -- it just said nothing when it worked.
        onLog("poll: handshake complete")
        return true
    }

    private fun confirmVehicleContact(): Boolean {
        // FIRST_CONTACT_TIMEOUT_MS, not the poll timeout.
        //
        // After ATSP0 the adapter negotiates a protocol and answers the
        // first request with `SEARCHING...` before the real reply. On the
        // ND2 that takes longer than 3 s. The probe allowed 5 s here and
        // always worked; the poller allowed 3 s and NEVER worked -- the
        // 2026-08-21 13:20 log is 13 consecutive `timeout after 3000ms:
        // 010C`, each retry re-running ATZ+ATSP0 and paying the search
        // again. Same adapter, same car, minutes apart. That two-second
        // difference was the whole bug.
        val reply = transport.send(ObdPid.ENGINE_RPM, FIRST_CONTACT_TIMEOUT_MS)
            ?: return false
        if (ElmProtocol.indicatesNoVehicleContact(reply)) return false
        return ObdPid.extractDataBytes(reply, ObdPid.ENGINE_RPM) != null
    }

    /**
     * Retry vehicle contact for a while before giving up.
     *
     * Starting the app before the engine is the normal case, not an error.
     * Bailing immediately would mean the driver has to notice the failure
     * and restart the app after turning the key.
     */
    private fun waitForVehicle(): Boolean {
        val deadline = System.currentTimeMillis() + VEHICLE_WAIT_MS
        while (running && System.currentTimeMillis() < deadline) {
            if (confirmVehicleContact()) return true
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun pollUntilStopped() {
        val startedAt = System.currentTimeMillis()
        var schedule = PollSchedule.ALL
        var fresh = PollCursor(schedule)
        cursor = fresh
        linkState = LinkState.LIVE

        var consecutiveFailures = 0
        var rateCheckedAt = startedAt

        while (running) {
            val now = System.currentTimeMillis()
            val pid = fresh.nextDue(now)

            if (pid == null) {
                // Nothing due yet. Sleeping briefly keeps the thread off the
                // CPU without meaningfully delaying the next request; the
                // fast tier's interval is 100 ms, so 5 ms costs nothing.
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }

            fresh.markPolled(pid, now)
            val reply = transport.send(pid, REQUEST_TIMEOUT_MS)
            val data = reply?.let { ObdPid.extractDataBytes(it, pid) }

            if (data != null) {
                fresh.record(pid, data, System.currentTimeMillis())
                consecutiveFailures = 0
            } else {
                fresh.recordFailure()
                consecutiveFailures++

                // A NO DATA is a fact about the car, not a fault, and must
                // not be counted toward a link drop. Only silence counts.
                if (reply != null && ElmProtocol.classify(reply) ==
                    ElmProtocol.ReplyKind.NO_DATA
                ) {
                    consecutiveFailures = 0
                }
            }

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                onLog("poll: link lost after $consecutiveFailures failures")
                linkState = LinkState.LOST
                break
            }

            achievedRate = fresh.achievedRate(startedAt, System.currentTimeMillis())

            // Decide once, after enough samples to be meaningful, whether
            // this adapter can carry the full schedule. One-way: flapping
            // would change the gauge's behaviour for no visible reason.
            val elapsed = System.currentTimeMillis() - rateCheckedAt
            if (!isDegraded && elapsed > RATE_ASSESS_MS &&
                achievedRate < PollSchedule.totalCmdPerSec
            ) {
                onLog(
                    "poll: ${"%.1f".format(achievedRate)} cmd/s cannot carry " +
                        "${"%.1f".format(PollSchedule.totalCmdPerSec)}; degrading"
                )
                schedule = PollSchedule.degraded()
                val degradedCursor = PollCursor(schedule)
                fresh = degradedCursor
                cursor = degradedCursor
                isDegraded = true
                rateCheckedAt = System.currentTimeMillis()
            }
        }

        transport.close()
        if (linkState == LinkState.LIVE) linkState = LinkState.LOST
        running = false
    }

    private companion object {
        /**
         * Timeout for the FIRST request after the handshake.
         *
         * Generous on purpose: `ATSP0` makes the adapter search for a
         * protocol, and its reply to the first PID is prefixed with
         * `SEARCHING...`. Measured at over 3 s on the ND2. Every later
         * request uses [REQUEST_TIMEOUT_MS], which is 250 ms.
         */
        const val FIRST_CONTACT_TIMEOUT_MS = 8000L

        /**
         * Applied after the standard handshake, from the measured tuning
         * race of 2026-08-21: `fixed protocol + aggressive` won at
         * 21.7 cmd/s / 46 ms against a 20.5 cmd/s / 48 ms baseline.
         *
         * `ATSP6` pins ISO 15765-4 CAN 11-bit/500k -- the ND2's protocol,
         * confirmed by the vehicle survey -- which also removes the
         * protocol search that caused the timeout above. `ATAT2` shortens
         * the adapter's wait for additional ECU responses.
         */
        val POST_HANDSHAKE = listOf(
            ElmProtocol.Command(
                "ATSP6",
                "pin ISO 15765-4 CAN 11/500 -- no protocol search",
                timeoutMs = 2000
            ),
            ElmProtocol.Command(
                "ATAT2",
                "aggressive adaptive timing -- measured fastest",
                timeoutMs = 1000
            )
        )

        /** Attempts per handshake command before giving up. */
        const val HANDSHAKE_ATTEMPTS = 3

        /** Pause between handshake retries. */
        const val HANDSHAKE_RETRY_DELAY_MS = 500L

        /**
         * Per-request timeout.
         *
         * Deliberately tight. At 10 Hz a request that takes 500 ms has
         * already cost five slots, so waiting longer buys a stale value at
         * the price of every fresh one behind it.
         */
        const val REQUEST_TIMEOUT_MS = 250L

        /** Consecutive silent requests before the link counts as lost. */
        const val MAX_CONSECUTIVE_FAILURES = 20

        /** How long to keep retrying an unresponsive ECU before giving up. */
        const val VEHICLE_WAIT_MS = 60_000L

        /** How long to run before judging whether the full schedule fits. */
        const val RATE_ASSESS_MS = 10_000L
    }
}
