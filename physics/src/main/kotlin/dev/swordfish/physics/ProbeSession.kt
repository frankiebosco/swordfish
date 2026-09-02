package dev.swordfish.physics

/**
 * The bring-up probe: what to ask the adapter on first contact, and how to
 * judge the answers.
 *
 * ## Why a probe exists at all
 *
 * The first live test turns on three untested things at once — Android Auto,
 * the head unit, and the OBD dongle. A failure in that configuration is
 * uninformative unless something is recording which link broke. This type is
 * the record.
 *
 * It is also where the project's central assumption gets checked. The tiered
 * schedule in [PollSchedule] is built on a *quoted* 20-50 cmd/s for Bluetooth
 * Classic SPP, and demands 33.3 of it. That number has never been measured on
 * this adapter, in this car, with Android Auto running. Until it is, the poll
 * design rests on a manufacturer's marketing range.
 *
 * **Measure the rate before writing the poller.** If the adapter sustains 45
 * cmd/s the fast tier can rise; if it manages 25 the schedule must degrade.
 * Both are cheap to accommodate now and expensive to retrofit into a gauge
 * that stutters for reasons nobody can localise.
 *
 * Pure logic, no Android: the Android layer runs the socket and feeds results
 * in here.
 */
object ProbeSession {

    /** How long the throughput measurement runs. */
    const val RATE_TEST_SECONDS = 10.0

    /**
     * The PID hammered during the rate test.
     *
     * RPM is the right choice: it is two data bytes (so the reply is
     * representative of the fast tier's frame size), it is always supported,
     * and it changes constantly, so a frozen value during the test indicates
     * a cached or desynchronised reply rather than a genuinely steady engine.
     */
    const val RATE_TEST_PID = ObdPid.ENGINE_RPM

    /**
     * What a measured command rate means for the design.
     *
     * The thresholds come from [PollSchedule]: the full schedule needs
     * 33.3 cmd/s, the degraded one 18.3.
     */
    enum class RateVerdict(val summary: String) {
        /** Comfortably above the full schedule. The fast tier could rise. */
        HEADROOM("full schedule fits with room to spare"),

        /** Above the full schedule, but not by much. Ship as designed. */
        ADEQUATE("full schedule fits"),

        /** Below the full schedule, above the degraded one. Halve the fast tier. */
        DEGRADED_ONLY("full schedule will stall; degraded schedule fits"),

        /** Below even the degraded schedule. Something is wrong. */
        INSUFFICIENT("cannot sustain even the degraded schedule")
    }

    /**
     * Judge a measured rate.
     *
     * [HEADROOM][RateVerdict.HEADROOM] is set at 1.5x the full schedule
     * rather than any spare capacity at all, because a 5% margin is noise:
     * the rate varies with bus load, retries, and whatever else is on the
     * phone's Bluetooth radio at the time.
     */
    fun judge(measuredCmdPerSec: Double): RateVerdict {
        val full = PollSchedule.totalCmdPerSec
        val degraded = PollSchedule.degradedCmdPerSec
        return when {
            measuredCmdPerSec >= full * 1.5 -> RateVerdict.HEADROOM
            measuredCmdPerSec >= full -> RateVerdict.ADEQUATE
            measuredCmdPerSec >= degraded -> RateVerdict.DEGRADED_ONLY
            else -> RateVerdict.INSUFFICIENT
        }
    }

    /**
     * The outcome of a throughput measurement.
     *
     * @param commandsSent How many requests were written.
     * @param repliesReceived How many produced a usable reply. A gap between
     *   this and [commandsSent] is the interesting signal — it means frames
     *   are being dropped, which a raw rate figure alone would hide.
     * @param elapsedSeconds Wall-clock duration of the test.
     * @param latenciesMs Per-command round-trip times, in order.
     */
    data class RateResult(
        val commandsSent: Int,
        val repliesReceived: Int,
        val elapsedSeconds: Double,
        val latenciesMs: List<Double> = emptyList()
    ) {
        /** Achieved commands per second, counting only successful replies. */
        val cmdPerSec: Double
            get() = if (elapsedSeconds > 0.0) repliesReceived / elapsedSeconds else 0.0

        /** Fraction of commands that produced no usable reply. */
        val dropRate: Double
            get() = if (commandsSent > 0) {
                (commandsSent - repliesReceived).toDouble() / commandsSent
            } else 0.0

        val meanLatencyMs: Double
            get() = if (latenciesMs.isEmpty()) 0.0 else latenciesMs.average()

        /**
         * Worst-case latency, which matters more than the mean for a gauge.
         *
         * A 30 ms mean with an occasional 400 ms stall reads as a smooth
         * average and looks like a freeze on the display.
         */
        val worstLatencyMs: Double
            get() = latenciesMs.maxOrNull() ?: 0.0

        /**
         * 95th-percentile latency — the honest figure for "how long a
         * reply usually takes at worst", without a single outlier
         * dominating the way [worstLatencyMs] does.
         */
        val p95LatencyMs: Double
            get() {
                if (latenciesMs.isEmpty()) return 0.0
                val sorted = latenciesMs.sorted()
                val idx = ((sorted.size - 1) * 0.95).toInt()
                return sorted[idx]
            }

        val verdict: RateVerdict get() = judge(cmdPerSec)

        fun describe(): String = buildString {
            appendLine("rate      ${"%.1f".format(cmdPerSec)} cmd/s")
            appendLine("verdict   ${verdict.name} — ${verdict.summary}")
            appendLine("budget    ${"%.1f".format(PollSchedule.totalCmdPerSec)} cmd/s full, " +
                "${"%.1f".format(PollSchedule.degradedCmdPerSec)} degraded")
            appendLine("sent      $commandsSent, replies $repliesReceived " +
                "(${"%.1f".format(dropRate * 100)}% dropped)")
            appendLine("latency   ${"%.0f".format(meanLatencyMs)} ms mean, " +
                "${"%.0f".format(p95LatencyMs)} p95, " +
                "${"%.0f".format(worstLatencyMs)} worst")
        }
    }

    /**
     * The result of re-running the supported-PID sweep with a better adapter.
     *
     * The ND2 was surveyed with an Ancel AD310, and different scan tools
     * query different PID ranges — so a fresh sweep is cheap and might turn
     * up something the Ancel missed. Any difference either way is worth
     * knowing: an addition is a new capability, and a *disappearance* means
     * the survey or this sweep is wrong, which matters more.
     */
    data class PidSweepResult(
        val discovered: Set<Int>,
        val baseline: Set<Int> = VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
    ) {
        /** PIDs this sweep found that the Ancel survey did not. */
        val newlyFound: Set<Int> get() = discovered - baseline

        /** PIDs the survey recorded that this sweep did not see. */
        val missing: Set<Int> get() = baseline - discovered

        /** True when the sweep exactly reproduces the survey. */
        val matchesBaseline: Boolean get() = newlyFound.isEmpty() && missing.isEmpty()

        val capabilities: VehicleCapabilities
            get() = VehicleCapabilities.fromSupportedPids(discovered)

        /**
         * True if this sweep contradicts the one fact the whole fuel model
         * depends on: that `015E` engine fuel rate is absent, making MAF the
         * only path to fuel flow.
         *
         * If this ever comes back true it is a significant finding, not a
         * curiosity — a native fuel-rate PID would beat the mixture-corrected
         * MAF estimate outright.
         */
        val foundEngineFuelRate: Boolean get() = discovered.contains(0x5E)

        fun describe(): String = buildString {
            appendLine("found     ${discovered.size} PIDs " +
                "(survey recorded ${baseline.size})")
            if (matchesBaseline) {
                appendLine("baseline  exact match with the Ancel survey")
            } else {
                if (newlyFound.isNotEmpty()) {
                    appendLine("NEW       " + newlyFound.sorted().joinToString(" ") {
                        "01" + "%02X".format(it)
                    })
                }
                if (missing.isNotEmpty()) {
                    appendLine("MISSING   " + missing.sorted().joinToString(" ") {
                        "01" + "%02X".format(it)
                    })
                }
            }
            if (foundEngineFuelRate) {
                appendLine()
                appendLine("!! 015E ENGINE FUEL RATE IS PRESENT")
                appendLine("   The survey said otherwise. This would replace the")
                appendLine("   MAF-derived fuel flow path. Verify before acting.")
            }
        }
    }

    /**
     * One probe step's outcome, for the on-screen log.
     *
     * @param label What was attempted.
     * @param ok Whether it succeeded.
     * @param detail The reply, or the failure reason.
     */
    data class Step(
        val label: String,
        val ok: Boolean,
        val detail: String
    ) {
        fun describe(): String = "${if (ok) "OK  " else "FAIL"}  $label — $detail"
    }
}
