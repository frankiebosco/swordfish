package dev.swordfish.physics

/**
 * What the car is *doing*, as distinct from what the numbers read.
 *
 * ## Why this exists
 *
 * [DeltaVModel.effectiveIsp] returns `0.0` in three physically distinct
 * situations, and a panel that renders all three as a dash tells the driver
 * nothing:
 *
 * - **stationary with the engine running** — burning fuel, buying nothing
 * - **engine off** — nothing happening at all
 * - **steep descent with negative road load** — gravity is paying
 *
 * The first and third are opposites. One is pure waste; the other is free
 * energy. Collapsing them into the same dash throws away the most
 * interesting thing the instrument could say.
 *
 * The precedent is already in the codebase: deceleration fuel cutoff also
 * produces a degenerate Isp, and rather than showing zero it is flagged as
 * `inDeceleratingFuelCutoff` and given its own panel treatment. This type
 * generalises that decision to every degenerate case.
 *
 * ## The jet at the hold line
 *
 * `THE_JET_ANALOGY.md` is the project's reference frame, and it settles how
 * [IDLE] should read. An engine at idle on the ramp does not display
 * "Isp: 0" — it displays **fuel flow**, because flow is the meaningful
 * number when thrust is doing nothing. Swordfish should do the same: show
 * the burn rate where Isp would go.
 *
 * This is not cosmetic. At the ND2's ~0.20 gal/h idle burn, sitting in
 * neutral drains the delta-V budget with the odometer stopped — a real cost
 * the instrument is currently silent about.
 */
enum class OperatingState(val label: String) {

    /** Moving under power with a meaningful Isp. The normal case. */
    CRUISE("CRUISE"),

    /**
     * Stationary (or nearly) with the engine running.
     *
     * Neutral at a light, clutch in, warming up on the drive. Road load is
     * ~0 so Isp is honestly zero: propellant is being spent and nothing is
     * being bought. The panel shows fuel flow instead of a dash.
     */
    IDLE("IDLE"),

    /**
     * Moving with the injectors shut off — trailing throttle in gear.
     *
     * True instantaneous Isp is infinite. Already modelled as
     * [DeltaVModel.Readout.inDeceleratingFuelCutoff]; represented here so
     * one enum covers every degenerate case.
     */
    DFCO("DFCO"),

    /**
     * Descending steeply enough that road load goes negative.
     *
     * Gravity more than covers drag, so the "thrust" the car must produce is
     * zero or less. The opposite of [IDLE] in every respect, and the
     * distinction the old single-dash rendering destroyed.
     */
    DESCENT("DESCENT"),

    /** Engine not running. Nothing to report. */
    OFF("OFF");

    /**
     * True when Isp is a meaningful number worth displaying.
     *
     * Everything else should show a state name, and — for [IDLE] — the fuel
     * flow that replaces it.
     */
    val hasMeaningfulIsp: Boolean get() = this == CRUISE

    /**
     * True when fuel is being consumed for no forward progress.
     *
     * The one state the game exists to penalise, and the reason [IDLE] gets
     * a burn-rate readout rather than a dash.
     */
    val isWastingFuel: Boolean get() = this == IDLE

    companion object {
        /**
         * Engine speed below which the engine is considered stopped.
         *
         * Comfortably under any idle: the ND2 idles at ~780 rpm, and a
         * cranking engine passes through this range in well under a second.
         */
        const val ENGINE_OFF_RPM = 300.0

        /**
         * Classify a telemetry sample.
         *
         * Order matters. [OFF] is checked first because every other state
         * presumes a running engine, and [DFCO] before [IDLE] because a
         * car coasting with injectors shut is moving, not idling.
         *
         * @param rpm engine speed; null when unknown.
         * @param speedMps road speed.
         * @param fuelFlowKgPerSec current burn; null when unknown.
         * @param roadLoadNewtons total resistive force, which goes negative
         *   on a steep descent.
         */
        fun classify(
            rpm: Double?,
            speedMps: Double,
            fuelFlowKgPerSec: Double?,
            roadLoadNewtons: Double
        ): OperatingState {
            if (rpm != null && rpm < ENGINE_OFF_RPM) return OFF

            val moving = speedMps >= DeltaVModel.MIN_SPEED_MPS
            val flowing = (fuelFlowKgPerSec ?: 0.0) >= DeltaVModel.MIN_FUEL_FLOW_KG_S

            if (!moving) return IDLE
            if (!flowing) return DFCO
            if (roadLoadNewtons <= 0.0) return DESCENT
            return CRUISE
        }
    }
}
