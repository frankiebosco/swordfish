package dev.swordfish.physics

/**
 * What warming the engine up cost, measured against the same drive once warm.
 *
 * ## The comparison
 *
 * A cold engine runs rich and idles fast until the coolant comes up, so the
 * question is how much MORE fuel the warm-up stretch burned than it would
 * have with a warm engine. The baseline is this drive's own warm phase:
 *
 *     expected = warm-up distance * warm kg-per-metre
 *              + warm-up idle time * warm idle kg-per-second
 *     excess   = fuel actually burned during warm-up - expected
 *
 * Using the same drive's warm phase rather than a fixed figure cancels the
 * things that differ between drives -- the car, the fuel, the payload, the
 * fuel-flow calibration (which reads ~25% low, and cancels here because both
 * sides carry it) -- leaving the temperature effect.
 *
 * ## Validated on the logs (2026-10-19)
 *
 * Over 79 drives the excess correlated with starting coolant at r = -0.91.
 * Starting at 30-45 C cost ~80 mL over a ~7 min warm-up; 45-65 C ~51 mL;
 * 65-85 C ~29 mL; an engine already warm, nothing. Those are September and
 * October figures -- the coldest start was 30 C -- so winter will show more.
 *
 * ## Caveat that is not cancelled
 *
 * The warm-up happens on the first roads of every trip, which for a daily
 * route are always the same slower local streets. The excess therefore also
 * carries a little route effect. That is why the figure is shown as
 * approximate, and why a drive that STARTS warm reads zero rather than being
 * compared against anything.
 */
object ColdStart {

    /** Coolant at which the engine counts as warm, Celsius. */
    const val WARM_C = 85.0

    /**
     * Warm-phase distance needed for a trustworthy kg-per-metre baseline.
     * Ten kilometres spans enough mixed road that one hill or one queue does
     * not set the rate.
     */
    const val MIN_WARM_DISTANCE_M = 10_000.0

    /** Warm-phase idle needed before its rate is trusted, seconds. */
    const val MIN_WARM_IDLE_S = 30.0

    data class Result(
        /** First coolant reading of the drive. */
        val startCoolantC: Double,
        /** Time from the first sample until [WARM_C]; null if never reached. */
        val warmUpSeconds: Double?,
        val warmUpDistanceM: Double,
        val warmUpFuelKg: Double,
        /**
         * Fuel burned beyond what a warm engine would have used over the
         * same stretch. Null when the drive never warmed up or its warm phase
         * was too short to give a baseline. Can be slightly negative on a
         * near-warm start -- that is noise, and callers clamp for display.
         */
        val excessFuelKg: Double?
    ) {
        /** Already at temperature on the first reading: nothing to pay. */
        val startedWarm: Boolean get() = startCoolantC >= WARM_C
    }

    /**
     * Analyse one drive. Null when it carries no coolant readings at all.
     */
    fun analyse(samples: List<DriveLog.Sample>): Result? {
        val first = samples.firstOrNull { it.coolantC != null } ?: return null
        val startC = first.coolantC!!
        val t0 = samples.first().tMs

        if (startC >= WARM_C) {
            return Result(startC, 0.0, 0.0, 0.0, 0.0)
        }

        var warm = false
        var warmAtMs: Long? = null
        var wuFuel = 0.0
        var wuDist = 0.0
        var wuIdleS = 0.0
        var warmMovingFuel = 0.0
        var warmDist = 0.0
        var warmIdleFuel = 0.0
        var warmIdleS = 0.0

        var prev: DriveLog.Sample? = null
        for (s in samples) {
            if (!warm && (s.coolantC ?: Double.NEGATIVE_INFINITY) >= WARM_C) {
                warm = true
                warmAtMs = s.tMs
            }
            val p = prev
            prev = s
            if (p == null) continue
            // Same gap rule as DriveLog: integrating across a crash or a
            // backgrounded app would invent fuel and distance.
            val dtMs = s.tMs - p.tMs
            if (dtMs !in 1..DriveLog.MAX_GAP_MS) continue
            val dt = dtMs / 1000.0
            val v = s.speedMps ?: continue
            val f = s.fuelKgPerSec ?: continue
            val moving = v > DriveLog.MOVING_THRESHOLD_MPS

            if (!warm) {
                wuFuel += f * dt
                if (moving) wuDist += v * dt else wuIdleS += dt
            } else if (moving) {
                warmDist += v * dt
                warmMovingFuel += f * dt
            } else {
                warmIdleS += dt
                warmIdleFuel += f * dt
            }
        }

        val warmUpS = warmAtMs?.let { (it - t0) / 1000.0 }

        // A warm-up with no idle needs no idle baseline, and vice versa.
        val needIdleRef = wuIdleS > 0.0
        val needMovingRef = wuDist > 0.0
        val baselineOk = warm &&
            (!needMovingRef || warmDist >= MIN_WARM_DISTANCE_M) &&
            (!needIdleRef || warmIdleS >= MIN_WARM_IDLE_S)

        val excess = if (!baselineOk) null else {
            val movingRate = if (warmDist > 0.0) warmMovingFuel / warmDist else 0.0
            val idleRate = if (warmIdleS > 0.0) warmIdleFuel / warmIdleS else 0.0
            wuFuel - (wuDist * movingRate + wuIdleS * idleRate)
        }

        return Result(
            startCoolantC = startC,
            warmUpSeconds = warmUpS,
            warmUpDistanceM = wuDist,
            warmUpFuelKg = wuFuel,
            excessFuelKg = excess
        )
    }

    /** One starting-temperature band across many drives. */
    data class Band(
        val fromC: Double,
        val toC: Double,
        val drives: Int,
        val medianExcessKg: Double,
        val medianWarmUpSeconds: Double?
    )

    /**
     * Band edges for the logbook's cross-drive view, Celsius.
     *
     * Chosen from the first two months of data so each band holds a useful
     * share of drives: under 45 is a car that sat for hours, 45-65 a
     * mid-length stop, 65-85 a short one, 85+ warm. Winter will populate a
     * colder band, which is why the first edge is open below.
     */
    val BAND_EDGES_C = listOf(Double.NEGATIVE_INFINITY, 45.0, 65.0, ColdStart.WARM_C, Double.POSITIVE_INFINITY)

    /**
     * Group drives by starting coolant, with the median cost in each band.
     * Medians rather than means: one queue-bound warm-up should not set the
     * band's figure. Bands with no measurable drives are omitted.
     */
    fun bands(results: List<Result>): List<Band> {
        val usable = results.filter { it.excessFuelKg != null }
        return BAND_EDGES_C.zipWithNext().mapNotNull { (lo, hi) ->
            val inBand = usable.filter { it.startCoolantC >= lo && it.startCoolantC < hi }
            if (inBand.isEmpty()) return@mapNotNull null
            val warmUps = inBand.mapNotNull { it.warmUpSeconds }
            Band(
                fromC = lo, toC = hi, drives = inBand.size,
                medianExcessKg = median(inBand.map { it.excessFuelKg!! }),
                medianWarmUpSeconds = if (warmUps.isEmpty()) null else median(warmUps)
            )
        }
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * The excess as delta-V, for a panel that counts everything in m/s.
     *
     * The fuel the warm-up wasted is fuel the budget no longer holds, so this
     * is the Tsiolkovsky difference between the tank with and without it, at
     * the budget's reference Isp.
     */
    fun excessDeltaV(vehicle: Vehicle, fuelAboardKg: Double, excessKg: Double): Double {
        if (excessKg <= 0.0 || fuelAboardKg <= 0.0) return 0.0
        val isp = DeltaVModel.REFERENCE_CRUISE_ISP
        return DeltaVModel.tsiolkovskyDeltaV(vehicle, fuelAboardKg + excessKg, isp) -
            DeltaVModel.tsiolkovskyDeltaV(vehicle, fuelAboardKg, isp)
    }
}
