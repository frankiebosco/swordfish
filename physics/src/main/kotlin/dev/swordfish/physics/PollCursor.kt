package dev.swordfish.physics

/**
 * Decides which PID to ask for next, and remembers what came back.
 *
 * ## Why the scheduling logic lives here
 *
 * A tiered poll is easy to get subtly wrong — starving the slow tier
 * forever, or letting the fast tier drift because each PID waits for a full
 * cycle of the others. Both failures look like "the gauge is a bit laggy"
 * and neither is diagnosable on a head unit at 60 mph.
 *
 * Keeping the decision in pure Kotlin means the whole schedule can be
 * driven through thousands of simulated ticks in a unit test, and the
 * resulting rates checked against [PollSchedule]'s stated budget. The
 * Android layer just executes whatever this says.
 *
 * ## Hold-last-value, with a clock
 *
 * Slow-tier PIDs update every ten seconds *by design*, so "the current
 * value" always means "the last one seen, N milliseconds ago". That is
 * fine — tank level is slosh-filtered over minutes anyway — but it stops
 * being fine when the link drops and a frozen number keeps rendering as
 * live.
 *
 * Every reading therefore carries its own timestamp and its own staleness
 * limit, derived from its poll rate. Past that limit the value is reported
 * as stale and the panel can dash it, which is the same honesty rule
 * `PanelState` already applies to nulls.
 */
class PollCursor(
    private val schedule: List<PollSchedule.Entry> = PollSchedule.ALL,
    /**
     * How many poll intervals a value may miss before it counts as stale.
     *
     * Three rather than one: a single missed reply is normal on a busy bus
     * and dashing the gauge for it would make the panel flicker constantly.
     * Three consecutive misses is a real fault.
     */
    private val staleAfterIntervals: Double = 3.0
) {

    /** One stored reading and when it arrived. */
    data class Reading(
        val pid: String,
        val data: List<Int>,
        val atMillis: Long
    )

    private val lastPolled = mutableMapOf<String, Long>()
    private val readings = mutableMapOf<String, Reading>()

    /** Total successful readings recorded, for the achieved-rate figure. */
    var successCount: Int = 0
        private set

    /** Requests that produced nothing usable. */
    var failureCount: Int = 0
        private set

    /**
     * The PID most overdue for a request, or null when nothing is due.
     *
     * "Most overdue" rather than round-robin is what keeps the fast tier
     * honest: at 10 Hz, RPM becomes due every 100 ms and will always
     * out-rank a slow-tier PID that came due 50 ms ago. But a slow PID that
     * has been waiting 30 seconds eventually outranks everything, so it
     * cannot be starved indefinitely.
     *
     * Overdue-ness is measured in *intervals*, not milliseconds, so tiers
     * compete fairly: being one full interval late means the same thing to
     * a 10 Hz PID as to a 0.1 Hz one.
     */
    fun nextDue(nowMillis: Long): String? {
        var best: String? = null
        var bestOverdue = 0.0

        for (entry in schedule) {
            val intervalMs = entry.intervalSec * 1000.0
            if (intervalMs <= 0.0) continue

            val last = lastPolled[entry.pid]
            // Never polled: maximally overdue, so the first cycle after
            // connect fetches everything once before settling into rhythm.
            val overdue = if (last == null) {
                Double.MAX_VALUE
            } else {
                (nowMillis - last) / intervalMs
            }

            if (overdue >= 1.0 && overdue > bestOverdue) {
                bestOverdue = overdue
                best = entry.pid
            }
        }
        return best
    }

    /** Record that a request was sent, whether or not it succeeded. */
    fun markPolled(pid: String, nowMillis: Long) {
        lastPolled[pid] = nowMillis
    }

    /** Store a successful reading. */
    fun record(pid: String, data: List<Int>, nowMillis: Long) {
        readings[pid] = Reading(pid, data, nowMillis)
        successCount++
    }

    /** Note a request that produced nothing usable. */
    fun recordFailure() {
        failureCount++
    }

    /** The most recent reading for a PID, regardless of age. */
    fun latest(pid: String): Reading? = readings[pid]

    /**
     * The most recent reading, or null if it is too old to trust.
     *
     * This is the accessor the telemetry assembler should use. Reaching for
     * [latest] instead is how a frozen value ends up rendering as live.
     */
    fun fresh(pid: String, nowMillis: Long): Reading? {
        val r = readings[pid] ?: return null
        return if (isStale(pid, nowMillis)) null else r
    }

    /** True when a PID's newest reading has aged past its staleness limit. */
    fun isStale(pid: String, nowMillis: Long): Boolean {
        val r = readings[pid] ?: return true
        val entry = schedule.firstOrNull { it.pid == pid } ?: return false
        val limitMs = entry.intervalSec * 1000.0 * staleAfterIntervals
        return (nowMillis - r.atMillis) > limitMs
    }

    /**
     * Fraction of the schedule currently holding fresh data.
     *
     * A single number for the panel: 1.0 is everything current, 0.0 is a
     * dead link. Anything in between is a partial degradation worth
     * surfacing rather than silently averaging over.
     */
    fun freshFraction(nowMillis: Long): Double {
        if (schedule.isEmpty()) return 0.0
        val fresh = schedule.count { !isStale(it.pid, nowMillis) }
        return fresh.toDouble() / schedule.size
    }

    /** Achieved successful reads per second since [start]. */
    fun achievedRate(startMillis: Long, nowMillis: Long): Double {
        val elapsed = (nowMillis - startMillis) / 1000.0
        return if (elapsed > 0.0) successCount / elapsed else 0.0
    }

    /** Drop everything. Called on reconnect so stale data cannot survive. */
    fun reset() {
        lastPolled.clear()
        readings.clear()
        successCount = 0
        failureCount = 0
    }
}
