package dev.swordfish.obd

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import dev.swordfish.physics.ElmProtocol
import dev.swordfish.physics.MsCanProbe
import dev.swordfish.physics.ObdPid
import dev.swordfish.physics.ProbeSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the bring-up probe and writes everything it sees to a log file.
 *
 * ## Why the log is the point
 *
 * The first live test happens in a car, on a phone, with three untested links
 * in play and a driver who cannot also be a debugger. Whatever is on screen
 * at the time will be forgotten or misremembered; the file will not. Every
 * command, every reply, every timestamp goes to disk, and the interesting
 * work happens afterwards at a desk.
 *
 * The format is NDJSON — one self-describing object per line. It survives
 * truncation (a killed process loses the last line, not the file), it appends
 * without rewriting, and it reads with any tool.
 *
 * ## What it deliberately does not do
 *
 * No MS-CAN probing. That is better attempted first in the OBDLink app's own
 * terminal, which is the supported path: if `STP 53` fails there it will fail
 * here too, with far more of our own code in the way of the diagnosis. See
 * `docs/MX_PLUS_PROBE_PLAN.md`.
 *
 * No tiered polling. The schedule's budget rests on an unmeasured throughput
 * assumption, and [rateTest] is what measures it. Writing the poller first
 * would be building on the number this probe exists to find.
 */
class ProbeRunner(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    private val transport = ObdTransport { msg -> emit("transport", msg) }
    private var logFile: File? = null

    /** Steps recorded so far, for the on-screen summary. */
    val steps = mutableListOf<ProbeSession.Step>()

    var rateResult: ProbeSession.RateResult? = null
        private set

    var sweepResult: ProbeSession.PidSweepResult? = null
        private set

    /**
     * Run the whole probe against one device.
     *
     * Blocking. Call from a background thread.
     *
     * Each phase is guarded by the previous one succeeding, because a rate
     * test against a failed handshake measures nothing and would fill the
     * log with noise that looks like data.
     */
    fun run(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        openLog()
        emit("probe", "started")

        if (!transport.open(device, adapter)) {
            record("SPP socket", false, "could not open — is the adapter powered and paired?")
            finish()
            return
        }
        record("SPP socket", true, transport.deviceLabel)

        if (!handshake()) {
            finish()
            return
        }

        // Ignition check before anything that assumes the car is talking.
        // "UNABLE TO CONNECT" here means the adapter is fine and the car is
        // asleep, which is a completely different remedy from a bad dongle.
        if (!vehicleContact()) {
            finish()
            return
        }

        sweep()
        rate()
        tuneLatency()
        sampleOnce()
        interrogateNewPids()

        // LAST, because it reconfigures the adapter onto a different bus.
        // Everything above needs the normal HS-CAN connection, and STP 53
        // takes that away.
        probeMsCan()

        finish()
    }

    // --- phases ---

    private fun handshake(): Boolean {
        for (cmd in ElmProtocol.HANDSHAKE) {
            var reply: String? = null
            var kind = ElmProtocol.ReplyKind.UNKNOWN
            var lines: List<String> = emptyList()

            // Retry on BUSY.
            //
            // Observed 2026-08-20: ATZ answered `STOPPED` because a previous
            // session had left the adapter mid-command, and the probe gave
            // up on a working dongle. The apparent fix was opening the
            // OBDLink app and closing it again -- which only reset the
            // adapter as a side effect. A retry does the same thing without
            // the detour.
            for (attempt in 0 until HANDSHAKE_ATTEMPTS) {
                reply = transport.send(cmd.text, cmd.timeoutMs)
                emit("cmd", cmd.text, reply = reply)

                if (reply == null) break

                lines = ElmProtocol.splitLines(reply)
                kind = lines.lastOrNull()?.let { ElmProtocol.classify(it) }
                    ?: ElmProtocol.ReplyKind.UNKNOWN

                if (!ElmProtocol.isRetryable(kind)) break

                record(
                    cmd.text, true,
                    "adapter busy (${lines.joinToString(" | ")}), retrying"
                )
                try {
                    Thread.sleep(HANDSHAKE_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }

            if (reply == null) {
                record(cmd.text, false, "no reply (${cmd.rationale})")
                return false
            }

            // ATZ answers with a banner, the AT commands with OK. Anything
            // classified as an error fails the step; anything else is
            // accepted, because adapters vary in what they echo and being
            // strict here would reject working hardware.
            val ok = kind != ElmProtocol.ReplyKind.ERROR &&
                kind != ElmProtocol.ReplyKind.BUSY
            record(
                cmd.text, ok,
                lines.joinToString(" | ").ifEmpty { "(empty)" } +
                    if (kind == ElmProtocol.ReplyKind.BUSY) {
                        "  [still busy after $HANDSHAKE_ATTEMPTS attempts]"
                    } else ""
            )
            if (!ok) return false
        }
        return true
    }

    /**
     * Confirm the ECU is reachable before drawing conclusions from silence.
     */
    private fun vehicleContact(): Boolean {
        val reply = transport.send(ObdPid.ENGINE_RPM, 5000)
        emit("cmd", ObdPid.ENGINE_RPM, reply = reply)

        if (reply == null) {
            record("vehicle contact", false, "no reply to 010C")
            return false
        }
        if (ElmProtocol.indicatesNoVehicleContact(reply)) {
            record(
                "vehicle contact", false,
                "adapter OK, ECU not responding — ignition on?"
            )
            return false
        }

        val rpm = ObdPid.extractDataBytes(reply, ObdPid.ENGINE_RPM)
            ?.let { ObdPid.decodeRpm(it) }
        record(
            "vehicle contact", rpm != null,
            if (rpm != null) "engine at ${"%.0f".format(rpm)} rpm" else "unparseable: $reply"
        )
        return rpm != null
    }

    /**
     * Re-run the supported-PID sweep.
     *
     * Cheap, and different scan tools query different ranges — the Ancel
     * survey may have missed something this adapter sees.
     */
    private fun sweep() {
        val found = mutableSetOf<Int>()

        // Walk the ranges by following the continuation bit rather than
        // firing all eight blindly. The Ancel survey stopped at 0160 and
        // consequently missed sixteen PIDs including 015E; following the
        // chain asks exactly as many questions as the car has answers for.
        var query: String? = ObdPid.SUPPORT_QUERIES.first()
        while (query != null) {
            val reply = transport.send(query, 2000)
            emit("cmd", query, reply = reply)

            val base = query.substring(2, 4).toIntOrNull(16) ?: break
            val data = reply?.let { ObdPid.extractDataBytes(it, query!!) }
            if (data != null) found += ObdPid.decodeSupportedPids(data, base)

            query = ObdPid.nextSupportQuery(found, base)
        }

        val result = ProbeSession.PidSweepResult(found)
        sweepResult = result
        emit("sweep", "supported PIDs", extra = mapOf(
            "count" to found.size,
            "new" to result.newlyFound.size,
            "missing" to result.missing.size,
            "pids" to found.sorted().joinToString(" ") { "%02X".format(it) }
        ))
        record(
            "PID sweep",
            found.isNotEmpty(),
            "${found.size} PIDs" + when {
                result.matchesBaseline -> ", matches the survey"
                else -> ", ${result.newlyFound.size} new / ${result.missing.size} missing"
            }
        )
    }

    /**
     * Measure achieved throughput.
     *
     * This is the headline number. The tiered schedule needs 33.3 cmd/s and
     * has never been measured on real hardware — everything about the poll
     * design follows from what this returns.
     */
    private fun rate() {
        val durationMs = (ProbeSession.RATE_TEST_SECONDS * 1000).toLong()
        val start = System.currentTimeMillis()
        val deadline = start + durationMs

        var sent = 0
        var received = 0
        val latencies = mutableListOf<Double>()

        while (System.currentTimeMillis() < deadline) {
            val t0 = System.nanoTime()
            val reply = transport.send(ProbeSession.RATE_TEST_PID, 500)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            sent++

            // Only count replies we could actually decode. A reply that
            // arrives but does not parse is not throughput, and counting it
            // would flatter an adapter that is returning junk under load.
            val usable = reply != null &&
                ObdPid.extractDataBytes(reply, ProbeSession.RATE_TEST_PID) != null
            if (usable) {
                received++
                latencies += elapsedMs
            }
        }

        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        val result = ProbeSession.RateResult(sent, received, elapsed, latencies)
        rateResult = result

        emit("rate", "throughput", extra = mapOf(
            "cmd_per_sec" to result.cmdPerSec,
            "sent" to sent,
            "received" to received,
            "drop_rate" to result.dropRate,
            "mean_latency_ms" to result.meanLatencyMs,
            "p95_latency_ms" to result.p95LatencyMs,
            "worst_latency_ms" to result.worstLatencyMs,
            "verdict" to result.verdict.name
        ))
        record(
            "rate test",
            result.verdict != ProbeSession.RateVerdict.INSUFFICIENT,
            "${"%.1f".format(result.cmdPerSec)} cmd/s — ${result.verdict.summary}"
        )
    }

    /**
     * One reading of every scheduled PID.
     *
     * Establishes that each PID the poller will depend on actually answers
     * on this adapter, and captures a real reply string for each — which is
     * directly useful back at the desk, since the frame parser is currently
     * tested only against synthetic frames.
     */
    private fun sampleOnce() {
        var ok = 0
        val scheduled = dev.swordfish.physics.PollSchedule.ALL
        for (entry in scheduled) {
            val reply = transport.send(entry.pid, 1000)
            emit("cmd", entry.pid, reply = reply)

            val data = reply?.let { ObdPid.extractDataBytes(it, entry.pid) }
            if (data != null) ok++
            record(
                "sample ${entry.pid}",
                data != null,
                if (data != null) {
                    data.joinToString(" ") { "%02X".format(it) } + "  → " + decode(entry.pid, data)
                } else {
                    "no usable reply: ${reply?.trim() ?: "(null)"}"
                }
            )
        }
        record("scheduled PIDs", ok == scheduled.size, "$ok of ${scheduled.size} answered")
    }

    /**
     * Race adapter configurations against each other for latency.
     *
     * ## Why this exists
     *
     * The first real measurement came back at **14.8 cmd/s with zero
     * drops** — a perfectly reliable link that is simply slow, at 66.7 ms
     * per round trip. A single-frame PID on a 500 kbit CAN bus takes under
     * 2 ms on the wire, so almost all of that is adapter and transport
     * overhead rather than the car.
     *
     * Several configurations could plausibly explain it and reasoning
     * cannot separate them. Each variant is therefore *measured*, over the
     * same PID, back to back in one session, so the comparison is fair.
     *
     * The result decides the poll schedule. At 15 cmd/s the fast tier can
     * only be 3 Hz; if a variant reaches 30 cmd/s it can be 8 Hz. That is
     * the difference between a gauge that steps and one that moves.
     */
    private fun tuneLatency() {
        record("latency tuning", true, "racing ${ElmProtocol.TUNING_VARIANTS.size} configs")

        var best: String? = null
        var bestRate = 0.0

        for (variant in ElmProtocol.TUNING_VARIANTS) {
            // Re-apply the base handshake first so each variant starts from
            // the same state -- otherwise variant N inherits whatever
            // variant N-1 configured and the comparison is meaningless.
            if (!handshake()) {
                record("tune ${variant.label}", false, "handshake failed, skipping")
                continue
            }

            var setupOk = true
            for (cmd in variant.setup) {
                val reply = transport.send(cmd, 2000)
                emit("tune-cmd", cmd, reply = reply)
                val kind = reply?.let { ElmProtocol.classify(it) }
                if (reply == null || kind == ElmProtocol.ReplyKind.ERROR) {
                    record(
                        "tune ${variant.label}", false,
                        "$cmd rejected: ${reply?.trim() ?: "no reply"}"
                    )
                    setupOk = false
                    break
                }
            }
            if (!setupOk) continue

            // WARM UP before timing anything.
            //
            // Every variant re-runs the handshake, which ends in ATSP0 or
            // ATSP6. With ATSP0 the adapter must re-negotiate the protocol
            // on the next request, and that takes SECONDS -- far past the
            // 500 ms per-request timeout used while measuring.
            //
            // The first run of this race scored 0.0 cmd/s and "100%
            // DROPPED" for every variant WITHOUT ATSP6, including the
            // baseline control, which was obviously wrong: the plain rate
            // test had just managed 17.9 cmd/s moments earlier. The
            // adapter was fine; the measurement was timing the protocol
            // search.
            //
            // A few generous-timeout requests absorb the search so the
            // measured window sees a settled link.
            repeat(TUNE_WARMUP_REQUESTS) {
                transport.send(ProbeSession.RATE_TEST_PID, 5000)
            }

            val result = measureRate(TUNE_SAMPLE_COUNT)
            emit("tune", variant.label, extra = mapOf(
                "setup" to variant.setup.joinToString(" "),
                "cmd_per_sec" to result.cmdPerSec,
                "mean_latency_ms" to result.meanLatencyMs,
                "p95_latency_ms" to result.p95LatencyMs,
                "drop_rate" to result.dropRate,
                "hypothesis" to variant.hypothesis
            ))

            record(
                "tune ${variant.label}", result.repliesReceived > 0,
                "${"%.1f".format(result.cmdPerSec)} cmd/s, " +
                    "${"%.0f".format(result.meanLatencyMs)} ms mean" +
                    if (result.dropRate > 0.01) {
                        ", ${"%.0f".format(result.dropRate * 100)}% DROPPED"
                    } else ""
            )

            // A faster variant that drops frames is not faster. Requiring
            // near-zero loss keeps this from "winning" by timing out early.
            if (result.cmdPerSec > bestRate && result.dropRate < 0.02) {
                bestRate = result.cmdPerSec
                best = variant.label
            }
        }

        if (best != null) {
            val gain = if (rateResult != null && rateResult!!.cmdPerSec > 0) {
                bestRate / rateResult!!.cmdPerSec
            } else 1.0
            val fastTierHz = ((bestRate - 3.3) / 3.0).coerceAtLeast(0.0)
            record(
                "TUNING VERDICT", true,
                "$best wins at ${"%.1f".format(bestRate)} cmd/s " +
                    "(${"%.1fx".format(gain)} baseline) -> " +
                    "fast tier can be ${"%.1f".format(fastTierHz)} Hz"
            )
            emit("verdict", "latency tuning", extra = mapOf(
                "winner" to best,
                "cmd_per_sec" to bestRate,
                "gain_over_baseline" to gain,
                "supportable_fast_tier_hz" to fastTierHz
            ))
        } else {
            record("TUNING VERDICT", false, "no variant completed")
        }
    }

    /**
     * Measure achieved rate over a fixed number of requests.
     *
     * Counting REQUESTS rather than seconds keeps the variants comparable:
     * a fixed duration would give a slow config fewer samples and a noisier
     * average, which is exactly backwards.
     */
    private fun measureRate(samples: Int): ProbeSession.RateResult {
        val start = System.currentTimeMillis()
        var sent = 0
        var received = 0
        val latencies = mutableListOf<Double>()

        repeat(samples) {
            val t0 = System.nanoTime()
            val reply = transport.send(ProbeSession.RATE_TEST_PID, 500)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            sent++
            if (reply != null &&
                ObdPid.extractDataBytes(reply, ProbeSession.RATE_TEST_PID) != null
            ) {
                received++
                latencies += ms
            }
        }

        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        return ProbeSession.RateResult(sent, received, elapsed, latencies)
    }

    /**
     * Read the PIDs the Ancel survey never asked about.
     *
     * The MX+ report of 2026-08-20 enumerated sixteen PIDs above `0x4C`,
     * including `015E` engine fuel rate — which the ND2 survey recorded as absent
     * and the entire fuel model is built on the absence of.
     *
     * **That report was taken in accessory mode, engine off, so every value
     * read zero.** Presence was established; live behaviour was not. This
     * phase exists to settle it with the engine running, which is why the
     * log records engine rpm alongside each reading: a zero fuel rate at
     * 0 rpm proves nothing, and a zero at 780 rpm proves the PID is
     * enumerated but dead.
     *
     * Nothing in the model changes on this evidence alone. The purpose is to
     * produce the reading that justifies changing it.
     */
    private fun interrogateNewPids() {
        // Engine state first, so every reading below can be judged against
        // it. Without this the whole phase is uninterpretable.
        val rpmReply = transport.send(ObdPid.ENGINE_RPM, 1000)
        val rpm = rpmReply
            ?.let { ObdPid.extractDataBytes(it, ObdPid.ENGINE_RPM) }
            ?.let { ObdPid.decodeRpm(it) }
        val running = (rpm ?: 0.0) > 400.0

        emit("engine", "state at interrogation", extra = mapOf(
            "rpm" to (rpm ?: -1.0),
            "running" to running
        ))
        record(
            "engine state",
            running,
            if (running) "${"%.0f".format(rpm)} rpm — readings below are live"
            else "${"%.0f".format(rpm ?: 0.0)} rpm — ENGINE OFF, zeros prove nothing"
        )

        val candidates = listOf(
            ObdPid.ENGINE_FUEL_RATE_ALT to "engine fuel rate (survey said absent)",
            ObdPid.ENGINE_VEHICLE_FUEL_RATE to "engine + vehicle fuel rate",
            ObdPid.ACTUAL_ENGINE_TORQUE_PCT to "actual torque %",
            ObdPid.ENGINE_REFERENCE_TORQUE to "reference torque",
            ObdPid.TRANSMISSION_ACTUAL_GEAR to "gear ratio",
            ObdPid.ODOMETER to "odometer",
            ObdPid.TIMING_ADVANCE to "timing advance (octane / knock-retard evidence)",
            ObdPid.FUEL_TYPE to "ECU fuel type config"
        )

        for ((pid, label) in candidates) {
            val reply = transport.send(pid, 1500)
            emit("cmd", pid, reply = reply)

            val data = reply?.let { ObdPid.extractDataBytes(it, pid) }
            if (data == null) {
                record("probe $pid", false, "$label — no usable reply")
                continue
            }

            val decoded = decodeNew(pid, data)
            val nonZero = data.any { it != 0 }

            emit("newpid", pid, extra = mapOf(
                "label" to label,
                "bytes" to data.joinToString(" ") { "%02X".format(it) },
                "decoded" to decoded,
                "non_zero" to nonZero,
                "engine_running" to running
            ))

            // A live reading is only meaningful with the engine turning.
            // Reporting "OK" for a zero at idle would be the exact mistake
            // the accessory-mode report already made once.
            record(
                "probe $pid",
                nonZero || !running,
                "$label — $decoded" +
                    if (running && !nonZero) "  [ENUMERATED BUT ZERO AT IDLE]" else ""
            )
        }

        // The headline. Stated explicitly so it survives a skim of the log.
        val fuelRate = transport.send(ObdPid.ENGINE_FUEL_RATE_ALT, 1500)
            ?.let { ObdPid.extractDataBytes(it, ObdPid.ENGINE_FUEL_RATE_ALT) }
            ?.let { ObdPid.decodeEngineFuelRateKgPerSec(it) }

        if (running && fuelRate != null && fuelRate > 0.0) {
            // Both units on purpose. The panel is SI (L/h), but the survey
            // pins the idle calibration point in gal/h — printing only one
            // would force a conversion in the reader's head at exactly the
            // moment they are comparing against the recorded figure.
            val galPerHour = dev.swordfish.physics.Units.kgToGallons(fuelRate) * 3600.0
            val litresPerHour = dev.swordfish.physics.Units.gallonsToLiters(galPerHour)
            record(
                "015E VERDICT", true,
                "LIVE at ${"%.2f".format(litresPerHour)} L/h " +
                    "(${"%.3f".format(galPerHour)} gal/h) — " +
                    "the survey was wrong, MAF is no longer the only path"
            )
            emit("verdict", "015E is live", extra = mapOf(
                "litres_per_hour" to litresPerHour,
                "gal_per_hour" to galPerHour,
                "kg_per_sec" to fuelRate
            ))
        } else if (running) {
            record(
                "015E VERDICT", false,
                "enumerated but reads zero with the engine running — " +
                    "MAF remains the only fuel-flow path"
            )
            emit("verdict", "015E enumerated but dead")
        } else {
            record(
                "015E VERDICT", false,
                "INCONCLUSIVE — engine was not running. Re-run at idle."
            )
            emit("verdict", "015E inconclusive, engine off")
        }
    }

    /**
     * Probe the medium-speed CAN bus for the car's chassis sensors.
     *
     * Chasing yaw rate, lateral acceleration, wheel speeds and steering
     * angle — the DSC module's inputs, none of which appear in generic
     * OBD-II. If they are reachable they beat the phone outright for
     * separating body roll from cornering force, and unlike a phone on the
     * passenger seat they are bolted to the chassis.
     *
     * OBDLink's coverage document stops at the 2017-2018 ND, but it is
     * dated 12.2019 — silent about a 2023 car rather than negative. The ND2
     * is the same platform generation with the same Ford-derived MS-CAN
     * heritage, and a mid-cycle refresh does not remove a bus.
     *
     * Runs LAST because `STP 53` reconfigures the adapter onto a different
     * bus, which takes away the connection every other phase needs.
     *
     * Captures raw frames only. Mazda publishes no CAN ID mapping, so
     * identification is an offline exercise: capture with timestamps, drive
     * a known pattern, correlate afterwards.
     */
    private fun probeMsCan() {
        for (cmd in MsCanProbe.SELECT_MS_CAN) {
            val reply = transport.send(cmd.text, cmd.timeoutMs)
            emit("mscan-cmd", cmd.text, reply = reply)

            if (reply == null) {
                record("MS-CAN ${cmd.text}", false, "no reply (${cmd.rationale})")
                return
            }
            val kind = ElmProtocol.splitLines(reply).lastOrNull()
                ?.let { ElmProtocol.classify(it) }
            if (kind == ElmProtocol.ReplyKind.ERROR) {
                record(
                    "MS-CAN ${cmd.text}", false,
                    "rejected: ${reply.trim()} — adapter may not support STP"
                )
                return
            }
            record("MS-CAN ${cmd.text}", true, cmd.rationale)
        }

        val frames = mutableListOf<MsCanProbe.Frame>()
        var rawLines = 0

        val delivered = transport.monitor(
            MsCanProbe.MONITOR_ALL,
            MS_CAN_CAPTURE_MS
        ) { line ->
            rawLines++
            // Every raw line goes to the log, parsed or not: a line this
            // parser rejects is exactly what a future fix needs to see.
            emit("mscan-raw", line)
            MsCanProbe.parseFrame(line, System.currentTimeMillis())
                ?.let { frames += it }
        }

        emit("mscan-capture", "monitor finished", extra = mapOf(
            "raw_lines" to rawLines,
            "delivered" to delivered,
            "frames" to frames.size,
            "duration_ms" to MS_CAN_CAPTURE_MS
        ))

        val summaries = MsCanProbe.summarise(frames)
        for (sum in summaries.take(MS_CAN_LOG_IDS)) {
            emit("mscan-id", sum.id, extra = mapOf(
                "frames" to sum.frameCount,
                "payload_len" to sum.payloadLength,
                "active_bytes" to sum.activeBytes.joinToString(","),
                "interesting" to sum.isInteresting
            ))
        }

        val verdict = MsCanProbe.verdict(frames)
        record("MS-CAN VERDICT", frames.isNotEmpty(), verdict)
        emit("verdict", verdict, extra = mapOf(
            "frames" to frames.size,
            "ids" to summaries.size,
            "live_ids" to summaries.count { it.isInteresting }
        ))

        if (summaries.any { it.isInteresting }) {
            record(
                "MS-CAN next step", true,
                "drive a known pattern (steady circle, then straight) and " +
                    "correlate the active bytes offline"
            )
        }
    }

    /** Decode the newly-discovered PIDs for the on-screen log. */
    private fun decodeNew(pid: String, data: List<Int>): String = when (pid) {
        ObdPid.ENGINE_FUEL_RATE_ALT ->
            ObdPid.decodeEngineFuelRateKgPerSec(data)?.let {
                "${"%.3f".format(dev.swordfish.physics.Units.gallonsToLiters(
                    dev.swordfish.physics.Units.kgToGallons(it)) * 3600.0)} L/h"
            }
        ObdPid.ENGINE_VEHICLE_FUEL_RATE ->
            ObdPid.decodeEngineVehicleFuelRate(data)?.let { (eng, veh) ->
                "engine ${"%.4f".format(eng * 1000)} g/s, vehicle ${"%.4f".format(veh * 1000)} g/s"
            }
        ObdPid.ACTUAL_ENGINE_TORQUE_PCT ->
            ObdPid.decodeActualTorquePercent(data)?.let { "${"%.0f".format(it)}%" }
        ObdPid.ENGINE_REFERENCE_TORQUE ->
            ObdPid.decodeReferenceTorqueNm(data)?.let { "${"%.0f".format(it)} Nm" }
        ObdPid.TRANSMISSION_ACTUAL_GEAR ->
            ObdPid.decodeGearRatio(data)?.let { "ratio ${"%.3f".format(it)}" }
        ObdPid.TIMING_ADVANCE ->
            ObdPid.decodeTimingAdvanceDegrees(data)?.let {
                "${"%.1f".format(it)} deg BTDC"
            }
        ObdPid.FUEL_TYPE -> data.firstOrNull()?.let {
            // 1 = gasoline. Anything else on this car would mean the fuel
            // constants in Units/Thermodynamics are wrong.
            if (it == 1) "gasoline (expected)" else "TYPE $it -- NOT GASOLINE"
        }
        ObdPid.ODOMETER ->
            ObdPid.decodeOdometerKm(data)?.let {
                "${"%.1f".format(dev.swordfish.physics.Units.metersToMiles(it * 1000))} mi"
            }
        else -> null
    } ?: "unparseable (${data.joinToString(" ") { "%02X".format(it) }})"

    /** Human-readable decode, so the on-screen log shows real units. */
    private fun decode(pid: String, data: List<Int>): String = when (pid) {
        ObdPid.ENGINE_RPM -> ObdPid.decodeRpm(data)?.let { "${"%.0f".format(it)} rpm" }
        ObdPid.VEHICLE_SPEED -> ObdPid.decodeSpeedMps(data)?.let {
            "${"%.1f".format(it * 2.23694)} mph"
        }
        ObdPid.MAF_RATE -> ObdPid.decodeMafGramsPerSec(data)?.let { "${"%.2f".format(it)} g/s" }
        ObdPid.FUEL_LEVEL -> ObdPid.decodeFuelLevelFraction(data)?.let {
            "${"%.0f".format(it * 100)}% tank"
        }
        ObdPid.BAROMETRIC_PRESSURE -> ObdPid.decodeBarometricHpa(data)?.let {
            "${"%.0f".format(it)} hPa"
        }
        ObdPid.AMBIENT_AIR_TEMP -> ObdPid.decodeAmbientAirTempC(data)?.let {
            "${"%.0f".format(it)} C"
        }
        ObdPid.COMMANDED_EQUIV_RATIO -> ObdPid.decodeEquivalenceRatio(data)?.let {
            "lambda ${"%.3f".format(it)}"
        }
        ObdPid.SHORT_FUEL_TRIM_1, ObdPid.LONG_FUEL_TRIM_1 ->
            ObdPid.decodeFuelTrimPercent(data)?.let { "${"%.1f".format(it)}%" }
        else -> null
    } ?: "?"

    // --- logging ---

    private fun openLog() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "probe").apply { mkdirs() }
        logFile = File(dir, "probe-$stamp.ndjson")
        onLog("log: ${logFile?.absolutePath}")
    }

    /**
     * Append one NDJSON record.
     *
     * Values are escaped rather than concatenated raw: an ELM reply can
     * contain quotes and control characters, and one malformed line would
     * break every parser downstream.
     */
    private fun emit(
        kind: String,
        message: String,
        reply: String? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val sb = StringBuilder()
        sb.append("{\"t\":").append(System.currentTimeMillis())
        sb.append(",\"kind\":\"").append(esc(kind)).append('"')
        sb.append(",\"msg\":\"").append(esc(message)).append('"')
        if (reply != null) sb.append(",\"reply\":\"").append(esc(reply)).append('"')
        for ((k, v) in extra) {
            sb.append(",\"").append(esc(k)).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(esc(v.toString())).append('"')
            }
        }
        sb.append("}\n")

        try {
            logFile?.appendText(sb.toString())
        } catch (e: Exception) {
            // A failed log write must never take down the probe: the probe
            // is what we came for, the log is a convenience.
            onLog("log write failed: ${e.message}")
        }
    }

    private fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private fun record(label: String, ok: Boolean, detail: String) {
        val step = ProbeSession.Step(label, ok, detail)
        steps += step
        emit("step", label, extra = mapOf("ok" to ok, "detail" to detail))
        onLog(step.describe())
    }

    private fun finish() {
        emit("probe", "finished")
        transport.close()
        onLog("--- probe complete ---")
        logFile?.let { onLog("saved: ${it.name}") }
    }

    private companion object {
        /**
         * Requests per tuning variant.
         *
         * 40 is enough for a stable mean at these latencies (~3 s per
         * variant) without making the whole race tediously long.
         */
        const val TUNE_SAMPLE_COUNT = 40

        /**
         * Requests to discard before timing a variant.
         *
         * Absorbs protocol re-negotiation after ATSP0. Three is enough:
         * the search completes on the first request and the next two
         * confirm the link has settled.
         */
        const val TUNE_WARMUP_REQUESTS = 3

        /** Attempts per handshake command before giving up. */
        const val HANDSHAKE_ATTEMPTS = 3

        /** Pause between handshake retries, letting the adapter settle. */
        const val HANDSHAKE_RETRY_DELAY_MS = 500L

        /**
         * How long to sit on ATMA.
         *
         * Long enough that a quiet bus is distinguishable from an absent
         * one, short enough that the probe stays a parked-car exercise.
         */
        const val MS_CAN_CAPTURE_MS = 8_000L

        /** How many arbitration IDs to summarise on screen. */
        const val MS_CAN_LOG_IDS = 20
    }

    /** Where logs are written, for the adb pull hint on screen. */
    fun logDirectory(): String =
        File(context.getExternalFilesDir(null), "probe").absolutePath
}
