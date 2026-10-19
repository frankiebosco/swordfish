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
     *
     * ## The SAME reading five times is not five readings (fixed 2026-10-19)
     *
     * The tank PID is polled every 10 s but the caller asks for it every
     * frame, and the poll cursor hands back the cached reading for as long
     * as it is fresh. So until this took [readingAtMillis], one poll was
     * counted five times inside a second or two, its spread was zero, and
     * the slosh guard above was never actually applied: every drive seeded
     * from ONE reading, usually taken pulling out of the driveway.
     *
     * Measured over 81 drives that mattered: the seed was off by 1.25 kg on
     * average (~310 m/s of delta-V), and every drive whose delta-V later
     * ROSE by more than 100 m/s had seeded 2.7-3.7 kg low -- the "it climbs
     * after a coffee stop" report. Pass the reading's poll timestamp and a
     * repeat is ignored.
     *
     * @param readingAtMillis when the reading was POLLED, not when it was
     *   asked for. A repeat of the last accepted timestamp is skipped. Null
     *   treats every call as a distinct reading (tests, replay).
     */
    fun seed(tankLevelFraction: Double, readingAtMillis: Long? = null) {
        if (readingAtMillis != null) {
            if (readingAtMillis == lastSeedReadingAt) return
            lastSeedReadingAt = readingAtMillis
        }
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

        seedSamples.clear()

        if (isSeeded && !isConfirmed) {
            // Checking a carried-over figure: keep it unless the tank flatly
            // disagrees. A fill-up is the usual reason; driving without the
            // app is the other.
            val disagreement = abs(median - fuelKg) / capacityKg
            if (disagreement > CARRY_OVER_REJECT_FRACTION) {
                fuelKg = median
                lastConfirmation = Confirmation.TANK_OVERRODE_CARRY_OVER
            } else {
                lastConfirmation = Confirmation.CARRY_OVER_KEPT
            }
        } else {
            fuelKg = median
            lastConfirmation = Confirmation.SEEDED_FROM_TANK
        }
        isSeeded = true
        isConfirmed = true
    }

    private val seedSamples = mutableListOf<Double>()
    private var lastSeedReadingAt: Long? = null

    /**
     * True once the estimate has been checked against the tank this session:
     * either seeded from it, or a carried-over figure confirmed by it.
     *
     * [isSeeded] says the figure is usable for display; this says it is
     * settled enough to latch as the trip's STARTING budget. The two differ
     * only during the ~50 s a carried-over figure is being checked, and that
     * gap matters on a fill-up day -- latching then would freeze the pre-fill
     * figure as "start".
     */
    var isConfirmed: Boolean = false
        private set

    /** True while the tracker still wants tank readings fed to [seed]. */
    val wantsSeedReadings: Boolean get() = !isConfirmed

    /** How the estimate was settled this session, for the drive log. */
    enum class Confirmation { SEEDED_FROM_TANK, CARRY_OVER_KEPT, TANK_OVERRODE_CARRY_OVER }

    /** Null until [isConfirmed]. */
    var lastConfirmation: Confirmation? = null
        private set

    /**
     * Start from the previous drive's closing estimate.
     *
     * ## Why
     *
     * A parked car's fuel does not change, and the closing figure has had a
     * whole drive of slow filtering behind it -- far better than any reading
     * taken in the first minute, which is the sender at its noisiest (pulling
     * out, turning, a sloped driveway). Replayed over the logged drives,
     * carrying over cut the starting error from 1.16 kg to 0.52 kg mean
     * (~290 to ~130 m/s), and the worst decile from 2.9 kg to 1.1 kg.
     *
     * ## Still checked
     *
     * The figure is used for display at once but is NOT [isConfirmed] until
     * [SEED_SAMPLES] distinct tank readings agree with it to within
     * [CARRY_OVER_REJECT_FRACTION] of capacity. If they disagree by more --
     * a fill-up, or miles driven without the app -- the tank wins.
     *
     * Ignored when already seeded, or when [carriedKg] is not a plausible
     * fuel load for this tank.
     */
    fun carryOver(carriedKg: Double) {
        if (isSeeded) return
        if (!carriedKg.isFinite() || carriedKg < 0.0 || carriedKg > capacityKg) return
        fuelKg = carriedKg
        isSeeded = true
        isConfirmed = false
    }

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

    companion object {
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

        /**
         * How far the tank may disagree with a carried-over figure, as a
         * fraction of capacity, before the tank wins. 0.19 is ~6.3 kg.
         *
         * Sized from the logs (2026-10-19, 80 drive-to-drive pairs): with no
         * fill-up between drives, the median of the first five tank readings
         * landed between -5.3 and +4.5 kg of the carried figure; the
         * smallest real fill-up seen was +8.5 kg. A partial fill smaller
         * than this threshold is NOT detected here and is left to the slow
         * filter, as before.
         */
        const val CARRY_OVER_REJECT_FRACTION = 0.19
    }

}
