package dev.swordfish.physics

/**
 * Unit conversions and physical constants.
 *
 * Internal convention: SI everywhere. Convert at the boundaries only.
 * Every value that enters the model from OBD-II or the UI is converted to
 * SI immediately; every value leaving for display is converted at the edge.
 * This keeps the physics free of unit-mixing bugs, which are the single
 * most common source of silently-wrong answers in a model like this.
 */
object Units {

    /** Standard gravity (m/s^2). The g0 in the rocket equation. */
    const val G0 = 9.80665

    /**
     * Gasoline density. The commonly cited 6.17 lb/gal figure is for
     * summer-blend regular at ~60F. Real pump gas varies roughly
     * 6.0-6.3 lb/gal with blend and temperature; we expose this as a
     * tunable rather than a hard constant.
     */
    const val GASOLINE_LB_PER_GAL = 6.17
    const val GASOLINE_KG_PER_L = 0.7429

    /**
     * Stoichiometric air-fuel ratio for gasoline by mass.
     * Used for the MAF-derived fuel-flow fallback when the ECU does not
     * report PID 015E. E10 pump gas is nearer 14.1; 14.7 is the pure-gasoline
     * figure the OBD spec assumes. Tunable for the same reason as density.
     */
    const val STOICH_AFR = 14.7

    // --- Length ---
    const val MILES_PER_METER = 1.0 / 1609.344
    fun metersToMiles(m: Double) = m * MILES_PER_METER
    fun milesToMeters(mi: Double) = mi / MILES_PER_METER

    // --- Mass ---
    const val KG_PER_LB = 0.45359237
    fun lbToKg(lb: Double) = lb * KG_PER_LB
    fun kgToLb(kg: Double) = kg / KG_PER_LB

    // --- Volume ---
    const val LITERS_PER_GALLON = 3.785411784
    fun gallonsToLiters(gal: Double) = gal * LITERS_PER_GALLON
    fun litersToGallons(l: Double) = l / LITERS_PER_GALLON

    // --- Speed ---
    fun mphToMps(mph: Double) = mph * 1609.344 / 3600.0
    fun mpsToMph(mps: Double) = mps * 3600.0 / 1609.344
    fun kphToMps(kph: Double) = kph / 3.6
    fun mpsToKph(mps: Double) = mps * 3.6

    // --- Fuel mass/volume ---
    fun gallonsToKg(gal: Double) = lbToKg(gal * GASOLINE_LB_PER_GAL)
    fun kgToGallons(kg: Double) = kgToLb(kg) / GASOLINE_LB_PER_GAL

    /** Convert a fuel volume rate (L/h, as PID 015E reports) to kg/s. */
    fun literPerHourToKgPerSec(lph: Double) = lph * GASOLINE_KG_PER_L / 3600.0

    /**
     * Convert MAF air mass flow (g/s, PID 0110) to fuel mass flow (kg/s),
     * assuming stoichiometric combustion.
     *
     * This ignores fuel trims and open-loop enrichment, so it under-reports
     * fuel use under hard acceleration (where the ECU commands a rich
     * mixture, sometimes as low as 12:1). For a hypermiling app that lives
     * in the light-load regime this is an acceptable approximation, but it
     * is why PID 015E is strongly preferred when available.
     */
    fun mafToFuelKgPerSec(mafGramsPerSec: Double, afr: Double = STOICH_AFR) =
        (mafGramsPerSec / 1000.0) / afr

    /**
     * Corrected MAF-to-fuel conversion using the ECU's own mixture data.
     *
     * The plain [mafToFuelKgPerSec] assumes a fixed 14.7:1 mixture, which is
     * wrong in exactly the situations that matter most:
     *
     *  - **Under enrichment**, the ECU commands lambda well below 1.0 (as low
     *    as ~0.8 at wide-open throttle) to cool the charge. Real fuel flow is
     *    then up to 25% higher than the naive figure, so an uncorrected model
     *    silently under-reports consumption when you drive hard -- flattering
     *    precisely the behaviour the game means to penalise.
     *  - **Fuel trims** shift the actual delivered mixture a few percent from
     *    stoichiometric even in closed loop. The 2023 ND2 was observed at
     *    SHRTFT1 +6.1% / LONGFT1 +5.5% at warm idle, i.e. adding about 11.6%
     *    more fuel than the base map.
     *
     * Both corrections are available on this car, so the MAF path can be far
     * better than the usual "MAF / 14.7" approximation.
     *
     * @param mafGramsPerSec PID 0110.
     * @param lambda PID 0144 commanded equivalence ratio. 1.0 = stoichiometric.
     * @param shortTrimPercent PID 0106. Positive means the ECU is adding fuel.
     * @param longTrimPercent PID 0107. Positive means the ECU is adding fuel.
     */
    fun mafToFuelKgPerSecCorrected(
        mafGramsPerSec: Double,
        lambda: Double? = null,
        shortTrimPercent: Double? = null,
        longTrimPercent: Double? = null
    ): Double {
        // Commanded mixture. lambda < 1 is rich, so AFR falls and fuel rises.
        val afr = STOICH_AFR * (lambda ?: 1.0)
        if (afr <= 0.0) return 0.0
        var fuel = (mafGramsPerSec / 1000.0) / afr

        // Trims are corrections the ECU is already applying on top of that.
        val trim = ((shortTrimPercent ?: 0.0) + (longTrimPercent ?: 0.0)) / 100.0
        fuel *= (1.0 + trim)

        return fuel.coerceAtLeast(0.0)
    }
}
