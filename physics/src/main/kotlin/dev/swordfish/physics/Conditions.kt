package dev.swordfish.physics

import kotlin.math.sqrt

/**
 * How weather, road surface and tyre state change the numbers.
 *
 * ## Why this matters more than grip limits
 *
 * The interesting question is not "how much grip do I have" but **"why is my
 * Isp worse today than it was last week"**. Conditions move road load by up to
 * 40% between a cold wet morning on soft tyres and a warm dry afternoon on
 * properly inflated ones — which is the difference between a Duna round trip
 * and barely reaching the Mun.
 *
 * Without accounting for it, the panel would quietly punish the driver for the
 * weather and they would have no way to tell that from their own driving. That
 * is the failure mode this file exists to prevent.
 *
 * ## What we can measure versus what we must assume
 *
 * The car helps more than expected here:
 *
 * | Input | Source | Quality |
 * |---|---|---|
 * | Ambient temperature | **live** — PID 0146 (AAT) | good |
 * | Barometric pressure | **live** — PID 0133 | 1 kPa quantised, fine for density |
 * | Tyre pressure | user-entered | TPMS is not on the generic PID list |
 * | Road wetness | user-selected, or inferred | see [SurfaceState] |
 *
 * Air density is therefore genuinely measured. Rolling resistance is modelled
 * from temperature plus user input, which is weaker but still far better than
 * pretending Crr is a constant.
 */
object Conditions {

    /**
     * Reference conditions for the baseline `Crr` in [Vehicle].
     *
     * `Vehicle.rollingResistCoeff = 0.012` describes warm tyres at ~32 psi on
     * dry asphalt at 20 C. Every adjustment here is relative to that.
     */
    const val REFERENCE_TEMP_C = 20.0
    const val REFERENCE_PRESSURE_PSI = 32.0

    /**
     * How wet the road is.
     *
     * Water adds rolling resistance directly — the tyre must displace it — and
     * the effect is large enough to dominate everything else in heavy rain.
     */
    enum class SurfaceState(val crrMultiplier: Double, val label: String) {
        DRY(1.00, "dry"),
        DAMP(1.13, "damp"),
        WET(1.33, "wet"),
        STANDING_WATER(1.67, "standing water");
    }

    /**
     * Rolling-resistance multiplier from tyre temperature.
     *
     * Cold rubber is stiffer and hysteresis losses are higher, so a tyre at
     * freezing rolls appreciably worse than the same tyre at 40 C. Roughly
     * -0.35% per degree above the reference, which matches the commonly cited
     * figure that Crr falls by about a third across a 0-40 C sweep.
     *
     * Uses *ambient* temperature as a proxy for tyre temperature. That is an
     * approximation — tyres warm with use — but ambient is what we can measure,
     * and after a few miles the two track each other reasonably.
     */
    fun crrTemperatureFactor(ambientC: Double): Double {
        val delta = ambientC - REFERENCE_TEMP_C
        return (1.0 - 0.0035 * delta).coerceIn(0.7, 1.4)
    }

    /**
     * Rolling-resistance multiplier from tyre pressure.
     *
     * Crr varies roughly with the inverse square root of inflation pressure.
     * Under-inflation is the single largest efficiency factor a driver
     * actually controls: dropping from 32 to 26 psi costs about 3% of road
     * load, which is worth more than most driving-style adjustments.
     */
    fun crrPressureFactor(psi: Double): Double {
        if (psi <= 5.0) return 1.6
        return sqrt(REFERENCE_PRESSURE_PSI / psi).coerceIn(0.8, 1.6)
    }

    /**
     * Effective rolling resistance coefficient for the current conditions.
     *
     * @param baseCrr The vehicle's reference figure, normally
     *   [Vehicle.rollingResistCoeff].
     * @param ambientC Ambient air temperature, from PID 0146.
     * @param tyrePsi Cold inflation pressure, user-entered.
     * @param surface Road wetness.
     */
    fun effectiveCrr(
        baseCrr: Double,
        ambientC: Double = REFERENCE_TEMP_C,
        tyrePsi: Double = REFERENCE_PRESSURE_PSI,
        surface: SurfaceState = SurfaceState.DRY
    ): Double = baseCrr *
        crrTemperatureFactor(ambientC) *
        crrPressureFactor(tyrePsi) *
        surface.crrMultiplier

    /**
     * Air density from measured temperature and pressure.
     *
     * `rho = P / (R_specific * T)`. Both inputs are live on this car, so this
     * is a real measurement rather than a model — and it feeds the aero term
     * directly, which dominates road load at motorway speed.
     *
     * @param ambientC PID 0146.
     * @param pressureKpa PID 0133.
     */
    fun airDensity(ambientC: Double, pressureKpa: Double): Double {
        val tempK = ambientC + 273.15
        if (tempK <= 0.0) return DeltaVModel.RHO_SEA_LEVEL
        return (pressureKpa * 1000.0) / (287.058 * tempK)
    }

    /**
     * A complete set of environmental corrections.
     *
     * @param airDensity kg/m^3, from measured temperature and pressure.
     * @param effectiveCrr Adjusted rolling resistance.
     * @param roadLoadMultiplier Total road load relative to reference
     *   conditions at the same speed — the headline "how much is the weather
     *   costing me" figure.
     */
    data class Correction(
        val airDensity: Double,
        val effectiveCrr: Double,
        val roadLoadMultiplier: Double
    )

    /**
     * Compute corrections and the resulting road-load penalty.
     *
     * The multiplier is what the panel should surface: "conditions are costing
     * you 14% today" is actionable and, importantly, **exculpatory** — it tells
     * the driver the Isp drop is the weather rather than their right foot.
     */
    fun correctionFor(
        vehicle: Vehicle,
        speedMps: Double,
        massKg: Double,
        ambientC: Double = REFERENCE_TEMP_C,
        pressureKpa: Double = 101.325,
        tyrePsi: Double = REFERENCE_PRESSURE_PSI,
        surface: SurfaceState = SurfaceState.DRY
    ): Correction {
        val rho = airDensity(ambientC, pressureKpa)
        val crr = effectiveCrr(vehicle.rollingResistCoeff, ambientC, tyrePsi, surface)

        val referenceLoad =
            0.5 * DeltaVModel.RHO_SEA_LEVEL * vehicle.dragCoefficient *
                vehicle.frontalAreaM2 * speedMps * speedMps +
                vehicle.rollingResistCoeff * massKg * Units.G0

        val actualLoad =
            0.5 * rho * vehicle.dragCoefficient *
                vehicle.frontalAreaM2 * speedMps * speedMps +
                crr * massKg * Units.G0

        val multiplier = if (referenceLoad > 0.0) actualLoad / referenceLoad else 1.0

        return Correction(
            airDensity = rho,
            effectiveCrr = crr,
            roadLoadMultiplier = multiplier
        )
    }

    /**
     * Plain-language summary of what conditions are costing, for the panel.
     *
     * Deliberately phrased as a fact about the environment rather than a
     * judgement about the driver.
     */
    fun describe(correction: Correction): String {
        val pct = (correction.roadLoadMultiplier - 1.0) * 100.0
        return when {
            pct > 1.0 -> "conditions costing ${"%.0f".format(pct)}%"
            pct < -1.0 -> "conditions helping ${"%.0f".format(-pct)}%"
            else -> "conditions neutral"
        }
    }
}
