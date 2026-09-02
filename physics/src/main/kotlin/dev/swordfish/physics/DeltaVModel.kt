package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * The core Swordfish model: converts instantaneous vehicle telemetry into a
 * KSP-style delta-V budget.
 *
 * ## Derivation
 *
 * A rocket's specific impulse is thrust per unit weight-flow of propellant:
 *
 *     Isp = F / (mdot * g0)
 *
 * A car in steady cruise produces exactly enough tractive force to balance
 * the resistive forces acting on it, so the automotive analogue substitutes
 * total road load for thrust:
 *
 *     Isp_eff = F_resist / (mdot_fuel * g0)
 *
 * where F_resist = F_aero + F_rolling + F_grade. This has a satisfying
 * property: it is high when you overcome a lot of force per unit fuel (a
 * high, efficient gear at light load) and low when you burn a lot of fuel
 * for little useful force (a low gear at high rpm, or idling, where
 * F_resist is near zero but fuel still flows). That is precisely the
 * behaviour we want to reward.
 *
 * Equivalently, since distance per unit fuel mass is v / mdot:
 *
 *     Isp_eff = (v / mdot) * (F_resist / (v * g0))
 *
 * so Isp scales directly with fuel economy at a given road load.
 *
 * Delta-V then follows Tsiolkovsky:
 *
 *     dv = Isp_eff * g0 * ln(m0 / mf)
 *
 * ## An honest caveat, encoded in the code
 *
 * For a Miata the mass ratio is tiny -- roughly 2341 lb wet over 2268 lb dry,
 * or about 1.031 -- so ln(m0/mf) is about 0.030 and the logarithm operates on
 * its near-linear region. The delta-V figure is therefore very nearly
 * proportional to remaining fuel. This is physically honest rather than a
 * flaw: it correctly says that a car is a terrible rocket. The *interesting*
 * signal, and the one the UI should foreground, is [Readout.effectiveIsp],
 * which swings by an order of magnitude with driving style.
 *
 * We also expose [Readout.rangeEquivalentDeltaV], a linearised
 * "range as delta-V" figure that is proportional to how far you can actually
 * travel. It is the more useful number for judging whether you will make it
 * home; the Tsiolkovsky figure is the more thematically correct one.
 */
object DeltaVModel {

    /** Air density at sea level, 15C (kg/m^3). */
    const val RHO_SEA_LEVEL = 1.225

    /**
     * Fuel flow below which we treat the engine as effectively idling for
     * Isp purposes. Prevents a divide-by-near-zero producing an absurd
     * Isp spike during deceleration fuel cutoff (DFCO), when the ECU stops
     * injecting entirely and true instantaneous Isp is infinite.
     */
    const val MIN_FUEL_FLOW_KG_S = 1e-6

    /** Speed below which aero/rolling road-load Isp is meaningless. */
    const val MIN_SPEED_MPS = 0.5

    /**
     * Speed below which Isp is not merely noisy but arithmetically absurd.
     *
     * Measured on the 2026-08-21 drive: 11% of samples produced a delta-V
     * over 20,000 m/s and one reached **287,452 m/s** at 1.9 m/s of road
     * speed. The mechanism is a division blowup, not physics:
     *
     *   Isp = F_resist / (mdot * g0)
     *
     * At walking pace the numerator is FLOOR-BOUND by rolling resistance
     * (`Crr*m*g`, ~330 N, essentially independent of speed) while the
     * denominator collapses toward zero as the injectors close. The ratio
     * runs away.
     *
     * It is also physically meaningless. Isp counts force-seconds bought
     * per unit fuel, and at 1.9 m/s those seconds buy almost no distance —
     * the force is real, the *progress* is not.
     *
     * 3 m/s (~7 mph) is above stop-and-go crawling and below any speed at
     * which the aero term starts to matter. 23% of that drive was under
     * this threshold, which is exactly the traffic where the readout was
     * unusable.
     */
    const val MIN_MEANINGFUL_SPEED_MPS = 3.0

    /**
     * Fuel flow below which the engine is coasting, not working.
     *
     * ## The remaining spike source, found on the 2026-08-21 evening drive
     *
     * The speed floor above removed the stop-and-go blowups, but 10% of
     * samples still exceeded 20,000 m/s — and they were **not slow**. Their
     * median speed was 17.8 m/s (40 mph), identical to normal samples. The
     * discriminator was fuel:
     *
     *   spikes  median 0.583 L/h
     *   normal  median 3.693 L/h
     *
     * 0.58 L/h at 40 mph is **below what the engine burns at idle** (about
     * 0.76 L/h). That is the ECU cutting injectors on a trailing throttle.
     * Road load is still ~500 N because the car is still pushing air, so
     * `F/(mdot*g0)` runs away exactly as it does at a standstill.
     *
     * `inDeceleratingFuelCutoff` already catches *total* cutoff via
     * [MIN_FUEL_FLOW_KG_S], but partial cutoff sails past that floor and
     * produces a finite, absurd number instead of an honest state.
     *
     * The rule: **burning less than the engine needs to idle is not
     * efficiency, it is falling forward.** Isp is undefined there, and a
     * dash is the honest readout.
     *
     * Set from the measured ND2 idle burn of ~0.76 L/h, with margin.
     */
    const val COASTING_FUEL_KG_PER_SEC = 0.00016

    /**
     * Thermal efficiency above which a reading is rejected as impossible.
     *
     * ## Why the cap belongs HERE and not on Isp
     *
     * A high Isp at low speed is not automatically wrong. From
     * `Isp = eta*LHV/(v*g0)`, Isp rises as speed falls — at 5 m/s even a
     * perfectly ordinary 35% efficiency implies 309,684 s. So capping Isp
     * directly would reject good low-speed data and accept bad high-speed
     * data.
     *
     * Efficiency has a hard physical ceiling instead. A naturally
     * aspirated petrol engine peaks near 35-38% at its very best point;
     * the ND2 was measured at ~21% in normal cruise. **Anything past 45%
     * is not a frugal moment, it is a bad sample** — a stale MAF reading,
     * a fuel figure from a different instant, or a road-load estimate that
     * does not match the conditions.
     *
     * This catches the residue the speed and coasting rules miss: 69
     * samples on the 2026-08-21 evening drive, median Isp 177,887 s at
     * ordinary speeds with ordinary fuel flow.
     */
    const val MAX_PLAUSIBLE_EFFICIENCY = 0.45

    /**
     * Full model output for one telemetry sample.
     *
     * @param effectiveIsp Seconds. The hero stat: high in the efficiency band.
     * @param deltaVRemaining m/s, via Tsiolkovsky. Thematically correct,
     *   nearly fuel-linear in practice.
     * @param rangeEquivalentDeltaV m/s. Linearised budget proportional to
     *   achievable distance; the practically useful figure.
     * @param roadLoadNewtons Total resistive force being overcome.
     * @param gravityLossWatts Power going into potential energy when
     *   climbing. Negative when descending (energy recovered).
     * @param massRatio m0/mf for the current fuel load.
     * @param inDeceleratingFuelCutoff True when fuel flow is ~zero while
     *   moving -- the engine is coasting with injectors off. Infinite
     *   instantaneous efficiency; the UI should show this as a special state
     *   rather than a number.
     */
    data class Readout(
        val effectiveIsp: Double,
        val deltaVRemaining: Double,
        val rangeEquivalentDeltaV: Double,
        val roadLoadNewtons: Double,
        val gravityLossWatts: Double,
        val massRatio: Double,
        val inDeceleratingFuelCutoff: Boolean
    )

    /**
     * Aerodynamic drag force (N): 0.5 * rho * Cd * A * v^2.
     *
     * @param airDensity Defaults to sea level; pass a pressure-corrected
     *   value once the barometer is wired in.
     */
    fun aeroDragNewtons(
        v: Vehicle,
        speedMps: Double,
        airDensity: Double = RHO_SEA_LEVEL
    ): Double = 0.5 * airDensity * v.dragCoefficient * v.frontalAreaM2 * speedMps * speedMps

    /**
     * Rolling resistance (N): Crr * m * g * cos(theta).
     *
     * The cosine term matters on a steep grade: less weight bears normal to
     * the road surface as the incline increases.
     */
    fun rollingResistanceNewtons(
        v: Vehicle,
        massKg: Double,
        gradeRadians: Double
    ): Double = v.rollingResistCoeff * massKg * Units.G0 * cos(gradeRadians)

    /**
     * Grade force (N): m * g * sin(theta). Positive uphill.
     *
     * This is the direct analogue of a rocket's gravity losses -- fuel spent
     * buying altitude rather than velocity. Negative on a descent, where
     * potential energy is returned to you.
     */
    fun gradeForceNewtons(
        massKg: Double,
        gradeRadians: Double
    ): Double = massKg * Units.G0 * sin(gradeRadians)

    /** Total road load (N), the automotive stand-in for rocket thrust. */
    fun roadLoadNewtons(
        v: Vehicle,
        massKg: Double,
        speedMps: Double,
        gradeRadians: Double,
        airDensity: Double = RHO_SEA_LEVEL
    ): Double =
        aeroDragNewtons(v, speedMps, airDensity) +
            rollingResistanceNewtons(v, massKg, gradeRadians) +
            gradeForceNewtons(massKg, gradeRadians)

    /**
     * Effective specific impulse (seconds): F_resist / (mdot * g0).
     *
     * Returns 0.0 when stationary or when fuel flow is unusable, so callers
     * can render a dash rather than a misleading number.
     */
    fun effectiveIsp(
        roadLoadN: Double,
        fuelFlowKgPerSec: Double,
        speedMps: Double
    ): Double {
        // Below a walking pace the ratio blows up rather than degrading
        // gracefully -- see MIN_MEANINGFUL_SPEED_MPS. Returning 0.0 makes
        // the panel show a dash, which is honest: we do not know the
        // efficiency of crawling, and pretending to is worse than
        // admitting it.
        if (speedMps < MIN_MEANINGFUL_SPEED_MPS) return 0.0

        // Coasting: burning less than idle while moving. See
        // COASTING_FUEL_KG_PER_SEC -- this is partial fuel cutoff, and the
        // efficiency it implies is an artefact of dividing by nearly zero.
        if (fuelFlowKgPerSec < COASTING_FUEL_KG_PER_SEC) return 0.0

        // Reject physically impossible efficiency. See
        // MAX_PLAUSIBLE_EFFICIENCY -- the ceiling is on eta, not on Isp,
        // because Isp legitimately rises as speed falls.
        val eta = (roadLoadN * speedMps) /
            (fuelFlowKgPerSec * Thermodynamics.GASOLINE_LHV_J_PER_KG)
        if (eta > MAX_PLAUSIBLE_EFFICIENCY) return 0.0
        if (fuelFlowKgPerSec < MIN_FUEL_FLOW_KG_S) return 0.0
        // Road load can go negative on a steep descent, where gravity more
        // than covers drag. Isp is not meaningful there; clamp at zero.
        if (roadLoadN <= 0.0) return 0.0
        return roadLoadN / (fuelFlowKgPerSec * Units.G0)
    }

    /**
     * Tsiolkovsky delta-V (m/s) for the current fuel load and Isp.
     *
     * Burns down to dry mass: m0 = structural + fuel, mf = structural.
     */
    fun tsiolkovskyDeltaV(
        v: Vehicle,
        fuelKg: Double,
        isp: Double
    ): Double {
        val mf = v.structuralMassKg
        val m0 = mf + fuelKg
        if (mf <= 0.0 || m0 <= mf) return 0.0
        return isp * Units.G0 * ln(m0 / mf)
    }

    /**
     * Linearised "range as delta-V" (m/s).
     *
     * Where Tsiolkovsky asks "how much velocity could this fuel buy if I
     * spent it all accelerating a shrinking mass", this asks the more
     * practical "how much road-load-fighting can this fuel do, expressed as
     * a velocity budget". It is proportional to remaining range, so it
     * behaves the way a driver expects a fuel gauge to behave.
     *
     *     dv_range = Isp * g0 * (fuel / m_total)
     *
     * This is exactly the first-order Taylor expansion of the Tsiolkovsky
     * form, and the two converge as the mass ratio approaches 1 -- which,
     * for a car, it always does.
     */
    fun rangeEquivalentDeltaV(
        v: Vehicle,
        fuelKg: Double,
        isp: Double
    ): Double {
        val total = v.totalMassKg(fuelKg)
        if (total <= 0.0) return 0.0
        return isp * Units.G0 * (fuelKg / total)
    }

    /**
     * Power (W) currently going into gravitational potential energy.
     * Positive climbing, negative descending. This is the number that drives
     * the KSP-style "gravity losses" indicator.
     */
    fun gravityLossWatts(
        massKg: Double,
        speedMps: Double,
        gradeRadians: Double
    ): Double = massKg * Units.G0 * sin(gradeRadians) * speedMps

    /**
     * Isp used for the DELTA-V BUDGET when the instantaneous figure is
     * degenerate — idling, coasting, crawling.
     *
     * ## Why the budget must not follow instantaneous Isp
     *
     * Observed on the 2026-08-21 drive: **delta-V read 0 whenever the car
     * idled**, because `Isp = 0` at a standstill and Tsiolkovsky multiplies
     * by it. But a KSP jet sitting on the runway shows its FULL delta-V,
     * and that is the correct behaviour: **delta-V is a property of the
     * fuel in the tank, not of what the engine happens to be doing this
     * second.** The tank does not empty because you stopped at a light.
     *
     * So the budget uses a reference efficiency — what this car actually
     * achieves when it is doing its job. `effectiveIsp` still reports the
     * instantaneous value (or a dash), because *that* is the gameplay
     * signal; only the budget uses this.
     *
     * The value is measured, not invented: the median Isp across 815
     * genuine cruise samples on that drive was **39,409 s**. The
     * conservative end of the observed range is used, so the budget errs
     * toward under-promising.
     *
     * ## ALWAYS, not just when Isp is degenerate (fixed 2026-08-22)
     *
     * The first version applied this only when `isp == 0.0`, so in every
     * other moment the budget rode the *instantaneous* Isp and swung with
     * the right foot. Measured on the 65-minute 2026-08-22 drive that meant
     * delta-V spanned **2 to 41,492 m/s and INCREASED on 34% of steps** —
     * a fuel budget that refilled itself 1,280 times in one drive.
     *
     * That is not what the reference instrument does. **A KSP jet on the
     * runway shows a maximum that only ever drains**; throttle changes the
     * *rate*, never the remaining total. Afterburner drains it fast, idle
     * drains it slowly, and the number never climbs back.
     *
     * With the fixed reference the same drive reads 7877 -> 1043 m/s,
     * draining monotonically apart from tank-sensor re-seeds. **Do not
     * reintroduce a conditional here.** The budget is a property of the
     * fuel in the tank; the throttle belongs in `effectiveIsp`, which is
     * where the moment-to-moment signal lives.
     */
    const val REFERENCE_CRUISE_ISP = 30000.0

    /** Run the full model against one telemetry sample. */
    fun compute(
        v: Vehicle,
        t: Telemetry,
        airDensity: Double = RHO_SEA_LEVEL
    ): Readout {
        val mass = v.totalMassKg(t.fuelRemainingKg)
        val load = roadLoadNewtons(v, mass, t.speedMps, t.gradeRadians, airDensity)
        val flow = t.fuelFlowKgPerSec ?: 0.0

        // Partial cutoff counts as DFCO for display purposes: from the
        // driver's seat "injectors fully shut" and "injectors almost shut"
        // are the same event -- a lifted throttle -- and both produce a
        // meaningless Isp. Naming the state beats showing a huge number.
        val dfco = t.speedMps >= MIN_SPEED_MPS && flow < COASTING_FUEL_KG_PER_SEC

        val isp = effectiveIsp(load, flow, t.speedMps)

        // The BUDGET ALWAYS uses the reference efficiency. Never the
        // instantaneous figure -- see REFERENCE_CRUISE_ISP for why.
        val budgetIsp = REFERENCE_CRUISE_ISP

        return Readout(
            effectiveIsp = isp,
            deltaVRemaining = tsiolkovskyDeltaV(v, t.fuelRemainingKg, budgetIsp),
            rangeEquivalentDeltaV = rangeEquivalentDeltaV(v, t.fuelRemainingKg, budgetIsp),
            roadLoadNewtons = load,
            gravityLossWatts = gravityLossWatts(mass, t.speedMps, t.gradeRadians),
            massRatio = if (v.structuralMassKg > 0.0) mass / v.structuralMassKg else 1.0,
            inDeceleratingFuelCutoff = dfco
        )
    }

    /**
     * Instantaneous fuel economy in US MPG, for sanity-checking the model
     * against the car's own trip computer.
     */
    fun instantaneousMpg(speedMps: Double, fuelFlowKgPerSec: Double): Double {
        if (fuelFlowKgPerSec < MIN_FUEL_FLOW_KG_S) return 0.0
        val milesPerSec = Units.metersToMiles(speedMps)
        val gallonsPerSec = Units.kgToGallons(fuelFlowKgPerSec)
        if (gallonsPerSec <= 0.0) return 0.0
        return milesPerSec / gallonsPerSec
    }

    /**
     * Infer the engaged gear from the rpm/speed ratio.
     *
     * For each gear, predicted rpm = (v / (2*pi*r)) * finalDrive * gear * 60.
     * We pick the gear whose prediction is closest in relative terms.
     * Returns a 1-based gear index, or null when stopped, clutched in, or
     * no gear matches within [tolerance] (a fraction, e.g. 0.12 = 12%).
     */
    fun inferGear(
        v: Vehicle,
        speedMps: Double,
        rpm: Double,
        tolerance: Double = 0.12
    ): Int? {
        if (speedMps < MIN_SPEED_MPS || rpm < 300.0) return null
        val wheelRevPerSec = speedMps / (2.0 * Math.PI * v.tireRadiusM)

        var bestGear: Int? = null
        var bestErr = Double.MAX_VALUE
        v.gearRatios.forEachIndexed { i, ratio ->
            val predicted = wheelRevPerSec * v.finalDrive * ratio * 60.0
            if (predicted > 0.0) {
                val err = abs(predicted - rpm) / rpm
                if (err < bestErr) {
                    bestErr = err
                    bestGear = i + 1
                }
            }
        }
        return if (bestErr <= tolerance) bestGear else null
    }
}
