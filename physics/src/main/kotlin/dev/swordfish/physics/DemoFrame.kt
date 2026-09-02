package dev.swordfish.physics

/**
 * Canned telemetry samples for exercising the panel without a car.
 *
 * ## Why these exist
 *
 * The panel renders five distinct operating states — [OperatingState] —
 * and four of them are unreachable at a desk. The DHU has no dongle, so it
 * always falls back to a single cruise sample and classifies as CRUISE.
 * That means `IDLE BURN`, `DFCO` and `DESCENT` could only ever be seen by
 * driving, which is a poor loop for checking whether a readout is legible.
 *
 * These are deliberately **not** random or animated. A frame that moves
 * cannot be compared against a screenshot from ten minutes ago, and the
 * whole point is to judge layout and legibility.
 *
 * Values are drawn from the real car wherever one is recorded: the idle
 * frame reproduces the ND2's documented 784 rpm at 2.31 g/s MAF, and the
 * cruise frame is the 65 mph highway sample the panel has always used.
 */
enum class DemoFrame(val label: String, val description: String) {

    /**
     * 65 mph highway cruise — the long-standing default.
     *
     * The state the panel spends most of its life in, and the one the
     * delta-V readout is tuned for.
     */
    CRUISE("Cruise", "65 mph, 6th gear — the normal case"),

    /**
     * Warm idle in neutral, ~784 rpm.
     *
     * Exercises the `IDLE BURN` readout that replaces Isp. The jet at the
     * hold line: fuel flowing, nothing bought.
     */
    IDLE("Idle", "784 rpm in neutral — shows IDLE BURN"),

    /**
     * Trailing throttle in gear, injectors shut.
     *
     * Exercises the DFCO state. True instantaneous Isp is infinite, so the
     * panel must show a state rather than a number.
     */
    DFCO("Fuel cutoff", "coasting in gear, injectors off"),

    /**
     * Steep descent where gravity more than covers drag.
     *
     * Exercises the DESCENT state — road load goes negative, which is the
     * opposite of idling and used to render identically.
     */
    DESCENT("Descent", "steep downhill, road load negative");

    /**
     * Build the telemetry sample for this frame.
     *
     * @param tankGallons how full the tank is, so the delta-V figure is
     *   plausible rather than arbitrary.
     */
    fun telemetry(tankGallons: Double = 11.9 * 0.831): Telemetry = when (this) {
        CRUISE -> {
            val speed = Units.mphToMps(65.0)
            Telemetry(
                speedMps = speed,
                rpm = 2661.0,
                // 34 mpg at 65 mph, the figure the panel has always used.
                fuelFlowKgPerSec = Units.gallonsToKg(
                    Units.metersToMiles(speed) / 34.0
                ),
                fuelRemainingKg = Units.gallonsToKg(tankGallons),
                gradeRadians = 0.0
            )
        }

        IDLE -> Telemetry(
            speedMps = 0.0,
            // The ND2's documented warm idle. The ND2 survey: "Idle burn is
            // ~0.20 gal/h (MAF 2.31 g/s at 784 rpm)."
            rpm = 784.0,
            fuelFlowKgPerSec = Units.mafToFuelKgPerSecCorrected(2.31),
            fuelRemainingKg = Units.gallonsToKg(tankGallons),
            gradeRadians = 0.0
        )

        DFCO -> Telemetry(
            speedMps = Units.mphToMps(45.0),
            // In gear, engine spinning on the road rather than on fuel.
            rpm = 2200.0,
            fuelFlowKgPerSec = 0.0,
            fuelRemainingKg = Units.gallonsToKg(tankGallons),
            gradeRadians = 0.0
        )

        DESCENT -> Telemetry(
            speedMps = Units.mphToMps(40.0),
            rpm = 2000.0,
            // A trickle: enough to be "running", not enough to be cruising.
            fuelFlowKgPerSec = Units.gallonsToKg(0.4 / 3600.0),
            fuelRemainingKg = Units.gallonsToKg(tankGallons),
            // -8%: steep enough that gravity outpaces drag at 40 mph.
            gradeRadians = Math.atan(-0.08)
        )
    }

    companion object {
        val DEFAULT = CRUISE

        fun fromName(name: String?): DemoFrame =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
