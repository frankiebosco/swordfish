package dev.swordfish.physics

/**
 * Static vehicle parameters. Defaults describe a 2023 Mazda MX-5 ND2 Club
 * with the 6-speed manual.
 *
 * @param dryMassKg Curb mass excluding fuel. NOTE: a published "curb weight"
 *   *includes* a full tank, so true dry mass is curb minus full-fuel mass.
 *   See [ND2_CLUB] for the corrected figure.
 * @param payload Crew and cargo aboard. See [Payload] — occupants may be
 *   entered as standard adult masses or exact figures, whichever the user
 *   prefers.
 * @param tankCapacityL Usable tank volume.
 * @param finalDrive Differential ratio (2.866 on the 6MT Club).
 * @param gearRatios Transmission ratios, 1st through 6th.
 * @param tireRadiusM **Loaded** rolling radius, not the unloaded spec radius.
 *   205/45R17 computes to 0.3081 m unloaded (17in rim radius 0.2159 m plus
 *   45% of a 205 mm section); a loaded radial squats roughly 3%, giving
 *   ~0.299 m. Using the unloaded figure overstates speed per rev.
 * @param dragCoefficient Cd. ND2 is ~0.35 with the top up.
 * @param frontalAreaM2 A. ~1.79 m^2 for the ND.
 * @param rollingResistCoeff Crr for street tires on asphalt, ~0.010-0.015.
 */
data class Vehicle(
    val dryMassKg: Double,
    val payload: Payload,
    val tankCapacityL: Double,
    val finalDrive: Double,
    val gearRatios: List<Double>,
    val tireRadiusM: Double,
    val dragCoefficient: Double,
    val frontalAreaM2: Double,
    val rollingResistCoeff: Double
) {
    /** Payload mass aboard (crew + cargo). */
    val payloadKg: Double get() = payload.totalKg

    /** Vehicle mass excluding fuel: the "dry mass" of the rocket analogy. */
    val structuralMassKg: Double get() = dryMassKg + payloadKg

    /** Total mass at a given remaining fuel load. */
    fun totalMassKg(fuelKg: Double): Double = structuralMassKg + fuelKg

    companion object {
        /**
         * Curb weight of the surveyed car, in pounds, INCLUDING a full tank.
         *
         * 2381 lb is the soft-top Club with the full BBS / Brembo / Recaro
         * package: Brembo front calipers and Recaro seats add mass while the
         * BBS forged wheels remove some unsprung, netting about +40 lb over
         * the 2341 lb base Club. The RF is a further ~110 lb heavier, so do
         * not substitute an RF figure here.
         */
        const val ND2_CLUB_BRE_CURB_LB = 2381.0

        /**
         * The surveyed car: 2023 MX-5 ND2 Club, 6MT, **soft top**, with the
         * full BBS / Brembo / Recaro package.
         *
         * Published curb weight INCLUDES a full 11.9 gal tank (~73.4 lb of
         * fuel), so dry mass is 2381 - 73.4 = 2307.6 lb. Getting this right
         * matters: using the curb figure directly as dry mass would
         * double-count the fuel and inflate the mass ratio. A test pins it.
         *
         * Payload defaults to a lone driver at the standard adult mass; the
         * app overrides this from the crew settings screen.
         */
        val ND2_CLUB = Vehicle(
            dryMassKg = Units.lbToKg(
                ND2_CLUB_BRE_CURB_LB - 11.9 * Units.GASOLINE_LB_PER_GAL
            ),
            payload = Payload.SOLO_DEFAULT,
            tankCapacityL = Units.gallonsToLiters(11.9),
            finalDrive = 2.866,
            gearRatios = listOf(5.087, 2.991, 2.035, 1.594, 1.286, 1.000),
            tireRadiusM = 0.2989,
            dragCoefficient = 0.35,
            frontalAreaM2 = 1.79,
            rollingResistCoeff = 0.012
        )
    }
}

/**
 * One sample of live vehicle + phone state, already converted to SI.
 *
 * @param speedMps Vehicle speed from PID 010D.
 * @param rpm Engine speed from PID 010C.
 * @param fuelFlowKgPerSec Instantaneous fuel mass flow, from PID 015E when
 *   available or MAF-derived otherwise. Null when neither source is present.
 * @param fuelRemainingKg Best current estimate of fuel aboard.
 * @param gradeRadians Road inclination, positive uphill. Zero when unknown.
 * @param throttlePercent PID 0111, 0-100. Informational; not used by the model.
 * @param timestampMs Monotonic sample time, for integration.
 */
data class Telemetry(
    val speedMps: Double,
    val rpm: Double,
    val fuelFlowKgPerSec: Double?,
    val fuelRemainingKg: Double,
    val gradeRadians: Double = 0.0,
    val throttlePercent: Double? = null,
    val timestampMs: Long = 0L
)
