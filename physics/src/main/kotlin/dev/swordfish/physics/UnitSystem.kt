package dev.swordfish.physics

/**
 * Which units the human-facing text uses.
 *
 * ## Why this exists
 *
 * The head-unit panel is metric throughout -- m/s, metres, °C -- because the
 * whole conceit is a spacecraft instrument and delta-V is quoted in m/s. The
 * logbook was written later and reached for miles and mpg, which are the
 * units a US driver actually thinks in.
 *
 * Both are defensible; having both at once is not. This makes it a choice
 * rather than an accident.
 *
 * ## What is NOT converted
 *
 * **Delta-V and Isp stay in SI, always.** They are the premise. "Delta-V
 * remaining: 4,382 ft/s" is not a unit conversion, it is a different app.
 * The rocket equation is quoted in m/s everywhere it appears in the world,
 * and the orbital comparisons in [OrbitalScale] are all m/s figures.
 *
 * Everything internal stays SI regardless -- see the architecture rule in
 * the project docs. This type is a display-time formatter and nothing else.
 */
enum class UnitSystem {
    /** Miles, mpg, feet, Fahrenheit. */
    IMPERIAL,

    /** Kilometres, L/100 km, metres, Celsius. */
    METRIC;

    val label: String get() = when (this) {
        IMPERIAL -> "Imperial"
        METRIC -> "Metric"
    }

    companion object {
        /**
         * Metric, matching the head unit.
         *
         * The panel has always been metric and it is the surface the driver
         * spends the drive looking at, so the logbook agreeing with it is the
         * less surprising default.
         */
        val DEFAULT = METRIC

        fun fromName(name: String?): UnitSystem =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Formats measurements for display in the chosen system.
 *
 * Deliberately returns value AND unit as separate strings where the caller
 * lays them out apart -- the logbook puts a big number above a small unit,
 * and gluing them together would force it to split them again.
 */
object DisplayUnits {

    data class Measure(val value: String, val unit: String) {
        override fun toString() = "$value $unit"
    }

    /** Distance from metres. */
    fun distance(meters: Double, system: UnitSystem): Measure = when (system) {
        UnitSystem.IMPERIAL ->
            Measure("%.1f".format(Units.metersToMiles(meters)), "mi")
        UnitSystem.METRIC ->
            Measure("%.1f".format(meters / 1000.0), "km")
    }

    /** Speed from metres per second. */
    fun speed(mps: Double, system: UnitSystem): Measure = when (system) {
        UnitSystem.IMPERIAL ->
            Measure("%.0f".format(Units.mpsToMph(mps)), "mph")
        UnitSystem.METRIC ->
            Measure("%.0f".format(mps * 3.6), "km/h")
    }

    /** Altitude from metres. */
    fun altitude(meters: Double, system: UnitSystem): Measure = when (system) {
        UnitSystem.IMPERIAL ->
            Measure("%.0f".format(meters * FEET_PER_METER), "ft")
        UnitSystem.METRIC ->
            Measure("%.0f".format(meters), "m")
    }

    /** Volume of fuel from kilograms. */
    fun fuelVolume(kg: Double, system: UnitSystem): Measure = when (system) {
        UnitSystem.IMPERIAL ->
            Measure("%.2f".format(Units.kgToGallons(kg)), "gal")
        UnitSystem.METRIC ->
            Measure("%.1f".format(Units.kgToGallons(kg) * LITRES_PER_GALLON), "L")
    }

    /** Temperature from Celsius. */
    fun temperature(celsius: Double, system: UnitSystem): Measure = when (system) {
        UnitSystem.IMPERIAL ->
            Measure("%.0f".format(celsius * 9.0 / 5.0 + 32.0), "°F")
        UnitSystem.METRIC ->
            Measure("%.0f".format(celsius), "°C")
    }

    /**
     * Fuel economy.
     *
     * The one measurement that INVERTS between systems: imperial counts
     * distance per volume (more is better), metric counts volume per
     * distance (less is better). Returning the same number with a different
     * unit would be wrong, not merely unconverted.
     *
     * @return null when nothing was burnt or nothing was covered.
     */
    fun economy(
        meters: Double, fuelKg: Double, system: UnitSystem
    ): Measure? {
        if (meters <= 0.0 || fuelKg <= 1e-6) return null
        val gal = Units.kgToGallons(fuelKg)
        if (gal <= 1e-9) return null

        return when (system) {
            UnitSystem.IMPERIAL -> {
                val mpg = Units.metersToMiles(meters) / gal
                Measure("%.1f".format(mpg), "mpg")
            }
            UnitSystem.METRIC -> {
                val litres = gal * LITRES_PER_GALLON
                val km = meters / 1000.0
                Measure("%.1f".format(litres / km * 100.0), "L/100km")
            }
        }
    }

    /**
     * Radar scope range.
     *
     * The NOAA request is built in miles because that is what the WMS bbox
     * maths uses ([RadarTile]); this converts only the LABEL. The scope's
     * geometry is untouched -- a range is a range whatever it is called.
     */
    fun radarRange(miles: Int, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "$miles MI"
        UnitSystem.METRIC -> "${Math.round(miles * KM_PER_MILE)} KM"
    }

    const val FEET_PER_METER = 3.280839895
    const val LITRES_PER_GALLON = 3.785411784
    const val KM_PER_MILE = 1.609344
}
