package dev.swordfish.physics

import kotlin.math.abs

/**
 * Maintains the running estimate of fuel aboard.
 *
 * Two sources, neither trustworthy alone:
 *
 *  - **Integrated fuel flow** is precise moment-to-moment but accumulates
 *    drift, since any small bias in the flow signal compounds over a tank.
 *  - **PID 012F tank level** is absolute but coarse (often 0.4% quantised,
 *    which is ~0.05 gal) and slosh-noisy, swinging wildly on corners and
 *    hills -- and in a Miata, on the kind of road you actually want to drive.
 *
 * Strategy: integrate flow for the live number, and let the tank sensor
 * gently correct long-term drift through a slow complementary filter. The
 * filter gain is deliberately tiny so cornering slosh cannot yank the
 * display around; over minutes it still pulls the integral back to truth.
 *
 * If the ECU does not report 012F at all, the user does a "just filled up"
 * reset via [resetToFull] and we run open-loop on the integral alone.
 */
class FuelTracker(
    private val vehicle: Vehicle,
    initialFuelKg: Double = Units.gallonsToKg(11.9),
    /**
     * Complementary filter gain per second toward the tank-sensor reading.
     * 0.001 pulls the estimate ~6% of the way to the sensor over a minute:
     * fast enough to correct drift over a drive, far too slow to follow slosh.
     */
    private val sensorCorrectionRate: Double = 0.001,
    /**
     * Ignore tank-sensor readings that disagree with the integral by more
     * than this fraction of capacity. Guards against a wild slosh spike or a
     * bad frame yanking the estimate. Legitimate large jumps (refuelling)
     * are handled by [resetToFull] instead.
     */
    private val sensorRejectFraction: Double = 0.15
) {
    var fuelKg: Double = initialFuelKg
        private set

    /** Cumulative fuel burned since construction or last reset (kg). */
    var burnedKg: Double = 0.0
        private set

    /** Cumulative distance since construction or last reset (m). */
    var distanceM: Double = 0.0
        private set

    private val capacityKg = Units.gallonsToKg(
        Units.litersToGallons(vehicle.tankCapacityL)
    )

    /**
     * Advance the estimate by one sample.
     *
     * @param fuelFlowKgPerSec Instantaneous flow, or null if unavailable
     *   (in which case only distance accrues).
     * @param speedMps Current speed, for distance integration.
     * @param dtSec Elapsed time since the previous sample.
     * @param tankLevelFraction PID 012F as 0.0-1.0, or null if unsupported.
     */
    fun update(
        fuelFlowKgPerSec: Double?,
        speedMps: Double,
        dtSec: Double,
        tankLevelFraction: Double? = null
    ) {
        if (dtSec <= 0.0) return

        if (fuelFlowKgPerSec != null && fuelFlowKgPerSec > 0.0) {
            val burn = fuelFlowKgPerSec * dtSec
            fuelKg = (fuelKg - burn).coerceAtLeast(0.0)
            burnedKg += burn
        }
        distanceM += speedMps * dtSec

        if (tankLevelFraction != null) {
            val sensorKg = (tankLevelFraction.coerceIn(0.0, 1.0)) * capacityKg
            val disagreement = abs(sensorKg - fuelKg) / capacityKg
            if (disagreement <= sensorRejectFraction) {
                val alpha = (sensorCorrectionRate * dtSec).coerceIn(0.0, 1.0)
                fuelKg += (sensorKg - fuelKg) * alpha
            }
        }
    }

    /**
     * True once a real tank reading has seeded the estimate.
     *
     * Until then `fuelKg` is only the constructor default, and any delta-V
     * built on it is a guess wearing a number's clothing.
     */
    var isSeeded: Boolean = false
        private set

    /**
     * Adopt the first tank reading of a session outright, instead of
     * creeping toward it at [sensorCorrectionRate].
     *
     * ## Why seeding is separate from correcting
     *
     * The complementary filter is deliberately slow — 0.001/s, so slosh
     * cannot move it. That is right for *corrections* and wrong for the
     * *first* reading: starting from a hardcoded default and creeping at
     * 0.1%/s would take most of a drive to reach the truth, and every
     * delta-V until then would be wrong.
     *
     * So the first reading is taken whole and the filter takes over after.
     *
     * **A single reading can itself be sloshed** — the 2026-08-21 drive saw
     * the sensor report anywhere from 14.4 L to 40.7 L within one session.
     * Seeding therefore requires [SEED_SAMPLES] readings that agree with
     * each other, which a slosh transient cannot fake.
     */
    fun seed(tankLevelFraction: Double) {
        val kg = tankLevelFraction.coerceIn(0.0, 1.0) * capacityKg
        seedSamples.add(kg)
        if (seedSamples.size < SEED_SAMPLES) return

        // Median of the candidates, so one wild sample cannot set the
        // baseline for a whole drive.
        val sorted = seedSamples.sorted()
        val median = sorted[sorted.size / 2]

        // Require agreement: if the readings disagree wildly the tank is
        // sloshing and none of them is trustworthy. Drop the oldest and
        // keep waiting for a calm moment.
        val spread = (sorted.last() - sorted.first()) / capacityKg
        if (spread > SEED_MAX_SPREAD) {
            seedSamples.removeAt(0)
            return
        }

        fuelKg = median
        isSeeded = true
        seedSamples.clear()
    }

    private val seedSamples = mutableListOf<Double>()

    /** User confirms a fill-up. Resets the integral to a full tank. */
    fun resetToFull() {
        fuelKg = capacityKg
        burnedKg = 0.0
        distanceM = 0.0
    }

    /** User states an exact current level, e.g. after a partial fill. */
    fun setFuel(gallons: Double) {
        fuelKg = Units.gallonsToKg(gallons).coerceIn(0.0, capacityKg)
    }

    /** Average MPG over the tracked interval. Zero until fuel is burned. */
    fun averageMpg(): Double {
        if (burnedKg <= 0.0) return 0.0
        val gal = Units.kgToGallons(burnedKg)
        if (gal <= 0.0) return 0.0
        return Units.metersToMiles(distanceM) / gal
    }

    fun fuelGallons(): Double = Units.kgToGallons(fuelKg)
    fun fuelFraction(): Double = if (capacityKg > 0.0) fuelKg / capacityKg else 0.0

    private companion object {
        /**
         * Tank readings that must agree before the estimate is seeded.
         *
         * Five at the slow tier's 0.1 Hz is roughly 50 seconds, which
         * comfortably spans the slosh from a corner or a hill.
         */
        const val SEED_SAMPLES = 5

        /**
         * Maximum spread across seed candidates, as a fraction of capacity.
         *
         * 5% of a 45 L tank is ~2 L. The 2026-08-21 drive swung 26 L, so a
         * sloshing tank fails this easily and seeding correctly waits.
         */
        const val SEED_MAX_SPREAD = 0.05
    }

}
