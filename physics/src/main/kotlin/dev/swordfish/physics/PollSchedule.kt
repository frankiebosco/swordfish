package dev.swordfish.physics

/**
 * Tiered OBD-II polling schedule.
 *
 * ## Why this exists
 *
 * The transport is the binding constraint on this project. Polling all nine
 * PIDs at 10 Hz would demand 90 cmd/s; even a flat 5 Hz is 45 cmd/s with no
 * headroom for retries.
 *
 * The fix is to poll each PID at the rate its underlying quantity actually
 * changes. RPM and speed move continuously and drive the gauge; ambient air
 * temperature does not meaningfully change within a minute. Tiering the nine
 * PIDs brings the budget to ~33 cmd/s.
 *
 * ## The budget below is NOT yet achievable — read this before tuning
 *
 * The 20-50 cmd/s figure this schedule was designed against is a quoted
 * range, not a measurement. The first real test on the target hardware
 * returned **14.8 cmd/s** ([MEASURED_MX_PLUS_CMD_PER_SEC]) with zero packet
 * loss, which is below even the supposed floor.
 *
 * So the tiers here are aspirational as they stand. Re-tier them from the
 * `ElmProtocol.TUNING_VARIANTS` race result, not from the marketing range
 * and not from a guess.
 *
 * This lives in the pure physics module rather than the Android transport
 * layer so the schedule and its budget can be unit-tested without hardware.
 */
object PollSchedule {

    /**
     * Conservative and optimistic sustained command rates for Bluetooth
     * Classic SPP, from adapter comparison data. The low end is what a
     * mediocre adapter manages; the high end needs a genuine fast chipset
     * such as an STN.
     */
    const val BT_CLASSIC_MIN_CMD_PER_SEC = 20.0
    const val BT_CLASSIC_MAX_CMD_PER_SEC = 50.0

    /**
     * What an OBDLink MX+ actually delivered on the ND2, 2026-08-20.
     *
     * **14.8 cmd/s with ZERO drops** — below even
     * [BT_CLASSIC_MIN_CMD_PER_SEC], which the quoted range said was the
     * floor. The link is perfectly reliable and simply slow: 66.7 ms mean
     * round trip, 107 ms p95.
     *
     * A single-frame PID on a 500 kbit CAN bus takes under 2 ms on the
     * wire, so nearly all of that is adapter and transport overhead rather
     * than the car. `ElmProtocol.TUNING_VARIANTS` exists to find out which
     * part, and the schedule below should be re-tiered from that result
     * rather than from the marketing range.
     *
     * **Kept as a constant so the gap between assumption and measurement
     * stays visible in the code**, not just in a log file.
     */
    const val MEASURED_MX_PLUS_CMD_PER_SEC = 14.8

    /** BLE's ceiling, for the record. Far too slow for this poll set. */
    const val BLE_MAX_CMD_PER_SEC = 15.0

    /**
     * One PID and how often it should be requested.
     *
     * @param pid The Mode 01 PID string, e.g. "010C".
     * @param hz Target requests per second.
     * @param rationale Why this rate — kept in the data so the reasoning
     *   survives contact with a future tuning session.
     */
    data class Entry(
        val pid: String,
        val hz: Double,
        val rationale: String
    ) {
        /** Commands per second this entry costs. */
        val costPerSec: Double get() = hz

        /** Interval between requests, in seconds. */
        val intervalSec: Double get() = if (hz > 0.0) 1.0 / hz else Double.MAX_VALUE
    }

    /**
     * Fast tier — drives the live gauge and the Isp calculation.
     *
     * This tier IS the budget. Adding a fourth PID here costs another
     * 10 cmd/s and should be resisted; derive additional values instead.
     */
    val FAST = listOf(
        Entry(ObdPid.ENGINE_RPM, 10.0, "drives gear inference and the rpm readout"),
        Entry(ObdPid.VEHICLE_SPEED, 10.0, "drives road load, Isp, and distance integration"),
        Entry(ObdPid.MAF_RATE, 10.0, "the only fuel-flow source on this car")
    )

    /**
     * Medium tier — mixture correction. Closed-loop trims and commanded
     * lambda shift over seconds, not milliseconds.
     */
    val MEDIUM = listOf(
        Entry(ObdPid.COMMANDED_EQUIV_RATIO, 1.0, "enrichment changes over seconds"),
        Entry(ObdPid.SHORT_FUEL_TRIM_1, 1.0, "short trim oscillates slowly in closed loop"),
        Entry(ObdPid.LONG_FUEL_TRIM_1, 1.0, "long trim is near-static within a drive")
    )

    /**
     * Slow tier — near-static quantities. Tank level is slosh-filtered over
     * minutes anyway, so polling it faster would buy nothing.
     */
    val SLOW = listOf(
        Entry(ObdPid.FUEL_LEVEL, 0.1, "slosh-filtered over minutes; coarse to begin with"),
        Entry(ObdPid.BAROMETRIC_PRESSURE, 0.1, "1 kPa quantised; changes with weather, not seconds"),
        Entry(ObdPid.AMBIENT_AIR_TEMP, 0.1, "ambient air does not change within a minute"),
        // Voltage and coolant both belong here for the same reason: their
        // FACTORY gauges are three-state indicators, and the underlying
        // values move over minutes. 0.2 Hz on voltage rather than 0.1 so a
        // crank event is not missed entirely -- cranking lasts about a
        // second, and catching the sag is the one genuinely diagnostic
        // moment the electrical system offers.
        Entry(ObdPid.CONTROL_MODULE_VOLTAGE, 0.2, "battery/alternator; catches crank sag"),
        Entry(ObdPid.COOLANT_TEMP, 0.1, "warms over minutes; a three-state gauge in stock form")
    )

    /** Every scheduled PID across all tiers. */
    val ALL: List<Entry> get() = FAST + MEDIUM + SLOW

    /** Total commands per second the full schedule demands. */
    val totalCmdPerSec: Double get() = ALL.sumOf { it.costPerSec }

    /**
     * True when the schedule fits within a given sustained command rate.
     *
     * Note the schedule deliberately does NOT fit
     * [BT_CLASSIC_MIN_CMD_PER_SEC] — a mediocre adapter cannot run it. That
     * is a documented hardware requirement, not an oversight, and a test
     * pins it so nobody "fixes" the schedule by quietly starving the gauge.
     */
    fun fitsWithin(cmdPerSec: Double): Boolean = totalCmdPerSec <= cmdPerSec

    /** Fraction of a given budget left spare. Negative means over budget. */
    fun headroomFraction(cmdPerSec: Double): Double =
        if (cmdPerSec <= 0.0) -1.0 else (cmdPerSec - totalCmdPerSec) / cmdPerSec

    /**
     * A degraded schedule for adapters that cannot sustain the full rate.
     *
     * Halves the fast tier rather than dropping PIDs: RPM and speed still
     * drive the gauge, MAF still feeds Isp, everything just updates at 5 Hz
     * with held values in between. A slightly coarser gauge beats a
     * stuttering one, and beats a gauge missing its fuel-flow input entirely.
     */
    fun degraded(): List<Entry> =
        FAST.map { it.copy(hz = it.hz / 2.0) } + MEDIUM + SLOW

    /** Commands per second demanded by [degraded]. */
    val degradedCmdPerSec: Double get() = degraded().sumOf { it.costPerSec }
}
