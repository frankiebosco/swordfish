package dev.swordfish.physics

/**
 * Turns raw PID readings into a [Telemetry] the model can consume.
 *
 * The decode step is pure — bytes in, SI units out — so the whole path from
 * a captured ELM reply to a delta-V figure is unit-testable without a
 * dongle, a phone, or a car. That is the same reason [ObdPid] lives here,
 * extended one layer up.
 *
 * ## The mixture correction is not optional
 *
 * The ND2 has no working `015E` engine fuel rate (pending the 2026-08-20
 * re-probe), so fuel flow is derived from MAF. A naive `MAF / 14.7` under-
 * reports by up to 25% under wide-open-throttle enrichment — flattering
 * exactly the driving the game exists to penalise. [Units.mafToFuelKgPerSecCorrected]
 * folds in commanded lambda and both fuel trims, and this assembler always
 * passes them when they are fresh.
 *
 * Missing mixture data degrades to lambda 1.0 rather than failing: a
 * slightly optimistic Isp beats no Isp at all, and the slow tier refreshes
 * within a second.
 */
object TelemetryAssembler {

    /**
     * Everything decoded from one poll cycle, with what was missing.
     *
     * @param telemetry the assembled sample, or null when the essentials
     *   (speed, rpm) are not available.
     * @param missing PIDs that had no fresh reading, for the panel's
     *   degradation indicator.
     * @param usedMixtureCorrection false when fuel flow fell back to
     *   stoichiometric, which matters because it biases Isp optimistic.
     */
    data class Result(
        val telemetry: Telemetry?,
        val missing: List<String>,
        val usedMixtureCorrection: Boolean,
        /**
         * Fresh tank-level reading, 0..1, or null when the PID is stale.
         *
         * Null means "unknown", NOT "half". Callers holding a
         * [FuelTracker] must keep their own figure when this is null --
         * `telemetry.fuelRemainingKg` carries a placeholder only because
         * the field is non-null.
         */
        val tankLevelFraction: Double? = null
    )

    /**
     * Assemble a telemetry sample from the cursor's fresh readings.
     *
     * @param tankCapacityGallons used to convert the tank-level percentage
     *   into a fuel mass.
     * @param gradeRadians from the phone barometer; the car's own BARO is
     *   quantised to 1 kPa (~85 m) and cannot resolve a hill.
     */
    fun assemble(
        cursor: PollCursor,
        nowMillis: Long,
        tankCapacityGallons: Double,
        gradeRadians: Double = 0.0
    ): Result {
        val missing = mutableListOf<String>()

        fun read(pid: String): List<Int>? {
            val r = cursor.fresh(pid, nowMillis)
            if (r == null) missing += pid
            return r?.data
        }

        val speedData = read(ObdPid.VEHICLE_SPEED)
        val rpmData = read(ObdPid.ENGINE_RPM)
        val mafData = read(ObdPid.MAF_RATE)

        val speed = speedData?.let { ObdPid.decodeSpeedMps(it) }
        val rpm = rpmData?.let { ObdPid.decodeRpm(it) }

        // Speed and rpm are the irreducible minimum: without them there is
        // no road load, no gear, and no Isp. Returning null here is what
        // keeps the panel on its sample frame rather than rendering zeros
        // as though they were measurements.
        if (speed == null || rpm == null) {
            return Result(null, missing, false)
        }

        // Mixture inputs are optional. Their absence costs accuracy, not
        // function, so they are read without being treated as required.
        val lambda = cursor.fresh(ObdPid.COMMANDED_EQUIV_RATIO, nowMillis)
            ?.let { ObdPid.decodeEquivalenceRatio(it.data) }
        val shortTrim = cursor.fresh(ObdPid.SHORT_FUEL_TRIM_1, nowMillis)
            ?.let { ObdPid.decodeFuelTrimPercent(it.data) }
        val longTrim = cursor.fresh(ObdPid.LONG_FUEL_TRIM_1, nowMillis)
            ?.let { ObdPid.decodeFuelTrimPercent(it.data) }

        val maf = mafData?.let { ObdPid.decodeMafGramsPerSec(it) }
        val fuelFlow = maf?.let {
            Units.mafToFuelKgPerSecCorrected(it, lambda, shortTrim, longTrim)
        }

        val tankFraction = cursor.fresh(ObdPid.FUEL_LEVEL, nowMillis)
            ?.let { ObdPid.decodeFuelLevelFraction(it.data) }

        // A STALE TANK READING IS NOT HALF A TANK.
        //
        // This used to be `(tankFraction ?: 0.5)`, so whenever the fuel-level
        // PID went stale the model was told the car had 50% of a 45 L tank --
        // ~25.8 kg. On the 2026-08-22 evening drive that fired on 24 of 1471
        // samples and threw the delta-V budget from ~4,000 up to 7,273 m/s,
        // a +3,300 spike on a gauge whose whole promise is that it only
        // drains. The real fuel was ~16 kg throughout.
        //
        // `tankLevelFraction` is now reported separately and left NULL when
        // there is no fresh reading, so the caller can hold the last known
        // value (FuelTracker already integrates flow and is the authority
        // between tank samples). The telemetry still carries a number
        // because Telemetry.fuelRemainingKg is non-null, but callers with a
        // tracker must override it -- see GaugeScreen.
        //
        // This is the documented rule -- hold-last-value paired with a
        // staleness clock -- applied to the one PID that had been exempt.
        val fuelRemaining = Units.gallonsToKg(
            (tankFraction ?: 0.5) * tankCapacityGallons
        )

        return Result(
            telemetry = Telemetry(
                speedMps = speed,
                rpm = rpm,
                fuelFlowKgPerSec = fuelFlow,
                fuelRemainingKg = fuelRemaining,
                gradeRadians = gradeRadians,
                timestampMs = nowMillis
            ),
            missing = missing,
            usedMixtureCorrection = lambda != null || shortTrim != null || longTrim != null,
            tankLevelFraction = tankFraction
        )
    }

    /**
     * Air density from the car's barometer and ambient air temperature.
     *
     * Uses AAT (`0146`), never IAT (`010F`): intake air is heat-soaked by
     * the engine bay — 45 C observed against 42 C true ambient, and the gap
     * widens after a hard run. Feeding that into a density calculation would
     * systematically under-report drag.
     *
     * Falls back to the sea-level constant when either input is missing.
     */
    fun airDensity(cursor: PollCursor, nowMillis: Long): Double {
        val baroHpa = cursor.fresh(ObdPid.BAROMETRIC_PRESSURE, nowMillis)
            ?.let { ObdPid.decodeBarometricHpa(it.data) } ?: return DeltaVModel.RHO_SEA_LEVEL
        val tempC = cursor.fresh(ObdPid.AMBIENT_AIR_TEMP, nowMillis)
            ?.let { ObdPid.decodeAmbientAirTempC(it.data) } ?: return DeltaVModel.RHO_SEA_LEVEL

        // Ideal gas law: rho = p / (R_specific * T).
        val pressurePa = baroHpa * 100.0
        val tempK = tempC + 273.15
        if (tempK <= 0.0) return DeltaVModel.RHO_SEA_LEVEL
        return pressurePa / (R_SPECIFIC_DRY_AIR * tempK)
    }

    /** Specific gas constant for dry air, J/(kg·K). */
    const val R_SPECIFIC_DRY_AIR = 287.05
}
