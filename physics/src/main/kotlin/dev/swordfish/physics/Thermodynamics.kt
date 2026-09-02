package dev.swordfish.physics

/**
 * The energy-based route to specific impulse, and the honest account of where
 * the rocket analogy is rigorous and where it is a deliberate borrowing.
 *
 * ## The question this file answers
 *
 * A car is not a rocket or a jet. It expels no propellant for thrust: it burns
 * gasoline to make heat, converts some of that to shaft work, and pushes
 * against the road through the tyres. Are we accounting for that properly, or
 * quietly applying rocket equations to a vehicle they do not describe?
 *
 * ## Where the analogy is rigorous
 *
 * **Specific impulse is fine.** Isp is defined as force per unit weight-flow of
 * fuel, `F / (mdot * g0)`. That definition is agnostic about *how* the force
 * arises. Asking "how many newton-seconds of resistance does this car overcome
 * per kilogram of fuel" is a well-posed physical question with a real answer,
 * and it is dimensionally seconds either way.
 *
 * Note that an air-breathing jet already stretches the classical reading: it
 * burns fuel with atmospheric oxygen and accelerates air it never carried, so
 * most of its reaction mass was never aboard. The car is one further step along
 * the same axis, not a different kind of claim.
 *
 * **And it is verifiable.** [ispFromEnergy] derives Isp from thermal efficiency
 * and the fuel's heating value — pure thermodynamics, no momentum argument
 * anywhere — and lands on exactly the same number as
 * [DeltaVModel.effectiveIsp]. A test pins the identity. At 65 mph cruise both
 * give ~31,560 s at a tank-to-wheel efficiency of ~21%, which is the correct
 * range for a naturally aspirated gasoline engine at light load.
 *
 * ## Where the analogy is a borrowing
 *
 * **The rocket equation's derivation does not apply.** Tsiolkovsky comes from
 * conservation of momentum: a rocket accelerates *because* it throws mass
 * backward, and `dv = -v_e dm/m` integrates to the logarithm. A car's momentum
 * change is balanced by the Earth's, not by exhaust. So
 * `dv = Isp * g0 * ln(m0/mf)` is applied here by analogy rather than derived.
 *
 * This is not a hidden fudge, and it does not produce a false number — every
 * term is well defined and the arithmetic is exact. But the *reason* the
 * logarithm appears is not operative for a car, which is why
 * [DeltaVModel.rangeEquivalentDeltaV] exists alongside it: the linearised form
 * is what actually tracks achievable distance, and for a mass ratio of ~1.03
 * the two agree within 2% anyway.
 *
 * Both figures are shown. The Tsiolkovsky one is thematically correct and
 * carries the project's best fact; the linear one is the practically useful
 * budget. Neither is presented as something it is not.
 */
object Thermodynamics {

    /**
     * Lower heating value of gasoline, J/kg.
     *
     * ~43.4 MJ/kg is the standard figure for the usable energy released by
     * complete combustion, excluding the latent heat of the water vapour
     * produced (which an engine cannot recover). E10 pump fuel is a little
     * lower, around 41-42 MJ/kg.
     */
    const val GASOLINE_LHV_J_PER_KG = 43.4e6

    /**
     * Specific impulse derived from thermodynamics rather than from momentum.
     *
     *     P_useful = F * v          (rate of useful work against road load)
     *     P_chem   = mdot * LHV     (rate of chemical energy released)
     *     eta      = P_useful / P_chem
     *
     * Substituting into `Isp = F / (mdot * g0)`:
     *
     *     Isp = eta * LHV / (v * g0)
     *
     * There is no reaction mass anywhere in that derivation — only heat,
     * efficiency and speed. It returns the same value as the force-based
     * form, which is the reassurance that the mechanical picture and the
     * chemical picture agree.
     *
     * @param thermalEfficiency Tank-to-wheel, 0..1. ~0.20-0.30 for a modern
     *   naturally aspirated gasoline engine at light load; lower at idle,
     *   higher near peak-efficiency load.
     * @param speedMps Vehicle speed. Isp is undefined at rest.
     */
    fun ispFromEnergy(
        thermalEfficiency: Double,
        speedMps: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Double {
        if (speedMps <= 0.0 || thermalEfficiency <= 0.0) return 0.0
        return thermalEfficiency * lhvJPerKg / (speedMps * Units.G0)
    }

    /**
     * Tank-to-wheel thermal efficiency implied by the live telemetry.
     *
     * `eta = (F * v) / (mdot * LHV)` — the fraction of the fuel's chemical
     * energy actually reaching the road as useful work against road load.
     * Everything else is exhaust and radiator heat, pumping and friction
     * losses, and accessory drive.
     *
     * ## Where this number comes from — read before trusting it
     *
     * It is **computed, not assumed**, but only two of its four inputs are
     * live measurements:
     *
     * | Input | Source |
     * |---|---|
     * | `speedMps` | **live** — PID 010D |
     * | `fuelFlowKgPerSec` | **live** — PID 0110 MAF, lambda- and trim-corrected |
     * | `lhvJPerKg` | **constant** — fuel chemistry, genuinely fixed |
     * | `roadLoadN` | **MODELLED** — from Cd, frontal area, Crr, mass, grade |
     *
     * Road load is the weak link. `Cd = 0.35`, `A = 1.79 m^2` and
     * `Crr = 0.012` are literature values for an ND Miata, **not measured on
     * this car**, and efficiency scales linearly with the road-load estimate:
     * a 30% error in F moves eta from ~21% to ~15% or ~27%.
     *
     * So treat this as a figure with perhaps +/-20% relative uncertainty, not
     * a dyno measurement. Its value lies less in the precise number than in
     * the fact that it lands in the right band at all — see
     * [roadLoadPlausibility].
     *
     * ## What is deliberately NOT used
     *
     * Coolant temperature and engine load are both available on this car
     * (`0105`, `0104`, `0143`) and both strongly affect real thermal
     * efficiency — a cold engine is far less efficient, and a petrol engine
     * runs ~15% at light load against ~35% near peak-torque load. They are not
     * used here because this function *observes* efficiency from work done
     * rather than *predicting* it from operating point. That is the honest
     * direction, but it does mean eta will read low during warm-up for reasons
     * this function cannot explain.
     */
    fun thermalEfficiency(
        roadLoadN: Double,
        speedMps: Double,
        fuelFlowKgPerSec: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Double {
        if (fuelFlowKgPerSec <= 0.0 || speedMps <= 0.0 || roadLoadN <= 0.0) return 0.0
        val useful = roadLoadN * speedMps
        val chemical = fuelFlowKgPerSec * lhvJPerKg
        if (chemical <= 0.0) return 0.0
        return useful / chemical
    }

    /** Chemical power released by the fuel being burned, in watts. */
    fun chemicalPowerWatts(
        fuelFlowKgPerSec: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Double = fuelFlowKgPerSec.coerceAtLeast(0.0) * lhvJPerKg

    /** Useful power delivered against road load, in watts. */
    fun usefulPowerWatts(roadLoadN: Double, speedMps: Double): Double =
        (roadLoadN * speedMps).coerceAtLeast(0.0)

    /**
     * Power thrown away as heat and parasitic loss, in watts.
     *
     * The difference between what the fuel released and what reached the road.
     * At a 65 mph cruise this is roughly 51 kW — about four times the useful
     * output — which is a fair summary of internal combustion.
     */
    fun wastedPowerWatts(
        roadLoadN: Double,
        speedMps: Double,
        fuelFlowKgPerSec: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Double = (chemicalPowerWatts(fuelFlowKgPerSec, lhvJPerKg) -
        usefulPowerWatts(roadLoadN, speedMps)).coerceAtLeast(0.0)

    /**
     * Plausible tank-to-wheel efficiency range for a warm, naturally
     * aspirated gasoline engine.
     *
     * Roughly 15% at light load up to about 35% near the peak-efficiency
     * island. Values outside this band, on a warmed-up engine at steady
     * cruise, indicate a modelling error rather than a remarkable car.
     */
    val PLAUSIBLE_EFFICIENCY = 0.15..0.35

    /**
     * Use the computed efficiency to sanity-check the road-load model.
     *
     * This inverts the usual reasoning and is the most valuable thing the
     * energy route gives us. Because efficiency scales linearly with road
     * load, and because a warm petrol engine at steady cruise *must* sit in
     * [PLAUSIBLE_EFFICIENCY], an implied efficiency outside that band is
     * evidence that `Cd`, `A`, `Crr` or the mass figure is wrong — not that
     * the engine is extraordinary.
     *
     * Getting ~21% at the 65 mph reference point on the first attempt is
     * therefore weak positive evidence that the road-load constants are about
     * right. Had it returned 8% or 45%, that would have been an immediate
     * flag to go back and check them.
     *
     * Intended for the phone-side diagnostics view, and for validating
     * against real logged data once the dongle is streaming.
     *
     * @return true when the implied efficiency is physically believable.
     */
    fun roadLoadPlausibility(
        roadLoadN: Double,
        speedMps: Double,
        fuelFlowKgPerSec: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Boolean {
        val eta = thermalEfficiency(roadLoadN, speedMps, fuelFlowKgPerSec, lhvJPerKg)
        return eta in PLAUSIBLE_EFFICIENCY
    }

    /**
     * The road load that WOULD be implied by assuming a given efficiency.
     *
     * `F = eta * mdot * LHV / v` — the inverse of [thermalEfficiency]. Useful
     * for bracketing: feeding the ends of [PLAUSIBLE_EFFICIENCY] gives the
     * range of road loads consistent with the observed fuel burn, which can be
     * compared against what the drag model predicts.
     */
    fun impliedRoadLoadNewtons(
        thermalEfficiency: Double,
        speedMps: Double,
        fuelFlowKgPerSec: Double,
        lhvJPerKg: Double = GASOLINE_LHV_J_PER_KG
    ): Double {
        if (speedMps <= 0.0) return 0.0
        return thermalEfficiency * fuelFlowKgPerSec * lhvJPerKg / speedMps
    }

    /**
     * **Why Isp falls as speed rises, even at constant efficiency.**
     *
     * From `Isp = eta * LHV / (v * g0)`, Isp is inversely proportional to
     * speed for a fixed thermal efficiency. At 25% efficiency:
     *
     * | speed | Isp |
     * |---|---|
     * | 25 mph | ~99,000 s |
     * | 45 mph | ~55,000 s |
     * | 65 mph | ~38,000 s |
     * | 85 mph | ~29,000 s |
     *
     * This is physically real rather than an artefact, and it is the reason
     * hypermilers slow down. It also means the instrument rewards **reducing
     * speed**, not merely feathering the throttle — a distinct behaviour worth
     * having the panel encourage.
     *
     * Note this is the *efficiency-held-constant* effect. In practice road load
     * also rises with the square of speed, so the real-world penalty for going
     * faster is steeper still.
     */
    fun ispAtConstantEfficiency(
        thermalEfficiency: Double,
        speedMps: Double
    ): Double = ispFromEnergy(thermalEfficiency, speedMps)
}
