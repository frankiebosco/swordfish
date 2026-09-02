package dev.swordfish.physics

import kotlin.math.abs

/**
 * Reduces a drive to the handful of shapes worth plotting.
 *
 * ## What is here and what deliberately is not
 *
 * Three charts, chosen because each answers a question the driver would act
 * on:
 *
 * - **Isp against speed** -- where the sweet spot is. Measured on a real the ridge road
 *   drive there IS structure: a dip around 20-29 mph and peaks at 10-19 and
 *   50-59, so the chart shows something rather than a flat smear.
 * - **The delta-V waterfall** -- where the budget went, broken into aero,
 *   rolling and climbing, with DFCO credited back.
 * - **Time in state** -- CRUISE / IDLE / DESCENT / DFCO. That last drive
 *   spent 20% coasting, which is a directly improvable number.
 *
 * Speed-over-time is deliberately absent: every telematics app has it and it
 * says nothing this one is about. RPM is an input, not an outcome, so it is
 * a colour dimension at most.
 *
 * Nothing here uses altitude. It was rebuilt on surveyed data on 2026-08-26
 * and has not been verified on the road, so a chart resting on it would be
 * presenting an unproven number as fact.
 *
 * ## Binning, not smoothing
 *
 * Each chart aggregates into buckets rather than filtering. A drive is
 * already sampled at 1 Hz and a filter would invent intermediate values;
 * a bucket with a sample count beside it is honest about how much evidence
 * each point carries.
 */
object DriveCharts {

    // ---------------------------------------------------------------
    // 1. Isp against speed
    // ---------------------------------------------------------------

    /** One speed band, with what efficiency the car actually achieved in it. */
    data class SpeedBand(
        /** Lower edge, m/s. */
        val fromMps: Double,
        val toMps: Double,
        val meanIspS: Double,
        val bestIspS: Double,
        val samples: Int
    )

    /**
     * Width of a speed band, m/s.
     *
     * ~4.5 m/s is 10 mph, which is how a driver thinks about speed and
     * coarse enough that each band carries real sample counts on a normal
     * drive.
     */
    const val SPEED_BAND_MPS = 4.4704

    /**
     * Mean Isp per speed band.
     *
     * Excludes stopped samples and fuel cutoff: idling has no meaningful Isp
     * and DFCO has infinite, and either would swamp the range that actual
     * driving occupies.
     *
     * @param minSamples bands with fewer are dropped -- a band of three
     *   samples is an anecdote, and plotting it beside one of three hundred
     *   implies they carry equal weight.
     */
    fun ispBySpeed(
        samples: List<DriveLog.Sample>,
        minSamples: Int = 20
    ): List<SpeedBand> {
        val buckets = HashMap<Int, MutableList<Double>>()
        for (s in samples) {
            if (s.dfco) continue
            val v = s.speedMps ?: continue
            if (v < DriveLog.MOVING_THRESHOLD_MPS) continue
            val isp = s.ispS ?: continue
            if (isp <= 0.0) continue
            val b = (v / SPEED_BAND_MPS).toInt()
            buckets.getOrPut(b) { ArrayList() } += isp
        }
        return buckets.entries
            .filter { it.value.size >= minSamples }
            .sortedBy { it.key }
            .map { (b, v) ->
                SpeedBand(
                    fromMps = b * SPEED_BAND_MPS,
                    toMps = (b + 1) * SPEED_BAND_MPS,
                    meanIspS = v.average(),
                    bestIspS = v.max(),
                    samples = v.size
                )
            }
    }

    /**
     * The band with the highest mean Isp, among those with enough evidence.
     *
     * ## Why a thin band cannot win
     *
     * On a real ridge-road drive the raw maximum was the 10-20 mph band -- 39
     * samples against 237 in the 40-50 band -- so the chart would have
     * advised driving at 15 mph. That is not an efficiency finding; Isp is
     * fuel per unit thrust, so barely touching the throttle scores well
     * while getting nowhere, and half those samples were rolling downhill.
     *
     * A "best speed" a driver would act on has to be a speed they actually
     * drove at for a meaningful part of the journey. Requiring a share of
     * the drive's samples is what separates a recommendation from an
     * artefact.
     *
     * @param minShare fraction of all banded samples a band must hold.
     *   0.15 keeps a genuinely narrow drive from having no answer at all
     *   while excluding a band that is a rounding error.
     */
    fun sweetSpot(bands: List<SpeedBand>, minShare: Double = 0.15): SpeedBand? {
        if (bands.isEmpty()) return null
        val total = bands.sumOf { it.samples }
        if (total <= 0) return null
        val eligible = bands.filter { it.samples.toDouble() / total >= minShare }
        // If nothing clears the bar, the drive was too scattered to name a
        // best speed -- say nothing rather than name a thin one.
        return eligible.maxByOrNull { it.meanIspS }
    }

    // ---------------------------------------------------------------
    // 2. The delta-V waterfall
    // ---------------------------------------------------------------

    /**
     * Where the trip's budget went.
     *
     * The most KSP thing the logbook can show: a fuel budget accounted for
     * line by line rather than as one number that went down.
     *
     * All figures are ENERGY in joules -- the honest common currency, since
     * aero, rolling and climbing are all forces acting over distance. The
     * caller can express them as a fraction of the whole.
     */
    data class Waterfall(
        /** Work against aerodynamic drag and rolling resistance combined. */
        val roadLoadJ: Double,
        /** Work against gravity while climbing. */
        val climbJ: Double,
        /** Energy returned by descending. Negative work, reported positive. */
        val descentJ: Double,
        /** Seconds spent in fuel cutoff -- fuel not burnt at all. */
        val dfcoSeconds: Double,
        /** Seconds idling, where fuel burns and nothing moves. */
        val idleSeconds: Double,
        val distanceMeters: Double
    ) {
        /** Total work done to move the car. */
        val totalJ: Double get() = roadLoadJ + climbJ

        /** Climbing as a fraction of all work, 0..1. */
        val climbFraction: Double
            get() = if (totalJ <= 0.0) 0.0 else climbJ / totalJ

        /** How much of the climb the descent gave back, 0..1 (may exceed 1). */
        val recoveredFraction: Double
            get() = if (climbJ <= 0.0) 0.0 else descentJ / climbJ
    }

    /**
     * Integrate the drive into a budget breakdown.
     *
     * `road_load_n` and `gravity_loss_w` are logged separately, so this is a
     * genuine split rather than an estimate: force times distance for the
     * road load, power times time for the gravity term.
     */
    fun waterfall(samples: List<DriveLog.Sample>): Waterfall {
        var roadLoad = 0.0
        var climb = 0.0
        var descent = 0.0
        var dfco = 0.0
        var idle = 0.0
        var distance = 0.0

        var prev: DriveLog.Sample? = null
        for (s in samples) {
            val p = prev
            prev = s
            if (p == null) continue
            val dtMs = s.tMs - p.tMs
            // Same gap rule as DriveLog: integrating across a crash would
            // credit the car with work it did while nothing was recording.
            if (dtMs !in 1..DriveLog.MAX_GAP_MS) continue
            val dt = dtMs / 1000.0

            val v0 = p.speedMps ?: 0.0
            val v1 = s.speedMps ?: 0.0
            val step = (v0 + v1) / 2.0 * dt
            distance += step

            p.roadLoadN?.let { f0 ->
                val f1 = s.roadLoadN ?: f0
                roadLoad += (f0 + f1) / 2.0 * step
            }
            p.gravityLossW?.let { g0 ->
                val g1 = s.gravityLossW ?: g0
                val j = (g0 + g1) / 2.0 * dt
                if (j >= 0.0) climb += j else descent += -j
            }
            if (s.dfco) dfco += dt
            if (v1 < DriveLog.MOVING_THRESHOLD_MPS) idle += dt
        }

        return Waterfall(
            roadLoadJ = roadLoad,
            climbJ = climb,
            descentJ = descent,
            dfcoSeconds = dfco,
            idleSeconds = idle,
            distanceMeters = distance
        )
    }

    // ---------------------------------------------------------------
    // 3. Time in state
    // ---------------------------------------------------------------

    /** One operating state and how long the drive spent in it. */
    data class StateSlice(
        val state: String,
        val seconds: Double,
        /** Share of the drive, 0..1. */
        val fraction: Double
    )

    /**
     * How the drive divided between operating states.
     *
     * Measured on a real drive: 68% CRUISE, 25% DESCENT, 7% IDLE, and 20% of
     * all samples in fuel cutoff. Coasting is the number worth surfacing --
     * it is free distance, and it is directly improvable.
     *
     * DFCO is counted SEPARATELY rather than as a state, because the log
     * carries it as a flag that can be true during DESCENT. Treating it as a
     * fifth slice would double-count the time.
     */
    fun timeInState(samples: List<DriveLog.Sample>): List<StateSlice> {
        val totals = HashMap<String, Double>()
        var total = 0.0

        var prev: DriveLog.Sample? = null
        for (s in samples) {
            val p = prev
            prev = s
            if (p == null) continue
            val dtMs = s.tMs - p.tMs
            if (dtMs !in 1..DriveLog.MAX_GAP_MS) continue
            val dt = dtMs / 1000.0
            val key = s.state ?: "UNKNOWN"
            totals[key] = (totals[key] ?: 0.0) + dt
            total += dt
        }
        if (total <= 0.0) return emptyList()

        return totals.entries
            .sortedByDescending { it.value }
            .map { (k, v) -> StateSlice(k, v, v / total) }
    }

    /** Seconds spent in fuel cutoff, counted independently of state. */
    fun dfcoSeconds(samples: List<DriveLog.Sample>): Double {
        var total = 0.0
        var prev: DriveLog.Sample? = null
        for (s in samples) {
            val p = prev
            prev = s
            if (p == null) continue
            val dtMs = s.tMs - p.tMs
            if (dtMs !in 1..DriveLog.MAX_GAP_MS) continue
            if (s.dfco) total += dtMs / 1000.0
        }
        return total
    }
}
