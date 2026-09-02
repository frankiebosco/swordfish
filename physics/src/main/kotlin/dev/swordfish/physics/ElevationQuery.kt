package dev.swordfish.physics

/**
 * Ground elevation from surveyed data, not from a sensor.
 *
 * ## Why this replaces the barometer as the authority
 *
 * A barometer measures pressure and infers height, so its answer moves with
 * the weather. Measured on the 2026-08-25 drives: the SAME spot on the ridge road read
 * 117.7 m at midday and 106.3 m that evening -- an 11.4 m swing in eight
 * hours with the car in the identical place. For a readout meant to be
 * trusted, that is disqualifying.
 *
 * Fusing GPS altitude in was supposed to cancel that, and made it worse. GPS
 * height is referenced to the WGS84 ELLIPSOID rather than sea level, and
 * around the northeastern US the geoid sits ~32 m above it. Checked against
 * surveyed truth at 14 points along one drive:
 *
 * | source | mean error | spread |
 * |---|---|---|
 * | raw barometer | +12.1 m | 4.5 m |
 * | barometer fused with GPS | **+29.0 m** | 4.9 m |
 *
 * The fusion was adding 17 m of error. The barometer's own spread is small --
 * it follows terrain SHAPE well -- but it sits at a constant offset that is
 * the day's weather, and tomorrow that constant is different.
 *
 * ## What this uses instead
 *
 * The USGS 3DEP Elevation Point Query Service: free, no key, no quota, the
 * same class of public infrastructure as the NOAA radar this app already
 * pulls. It returns surveyed ground elevation at 1 m raster resolution.
 *
 * Sparkill NY returns 24.5 m today, tomorrow, and in a thunderstorm. That
 * repeatability is the entire point.
 *
 * ## The trade this accepts
 *
 * It needs a network. Tunnels, dead zones and patchy stretches of the ridge road will
 * all miss, so the caller holds the last good value and says it is stale
 * rather than silently drifting -- see the hold rules in the app layer.
 *
 * It also returns GROUND elevation, so on a bridge or in a parking structure
 * the car is above the number. For a road car that is nearly always right.
 */
object ElevationQuery {

    /** USGS 3DEP point query. */
    const val ENDPOINT = "https://epqs.nationalmap.gov/v1/json"

    /**
     * How far the car must move before the elevation is worth re-asking.
     *
     * Terrain does not change, so this is purely about resolution. 100 m is
     * finer than the barometer's own accuracy and coarse enough that a drive
     * makes a few hundred calls rather than thousands.
     */
    const val REFETCH_DISTANCE_M = 100.0

    /**
     * Minimum gap between calls, millis.
     *
     * MEASURED, not guessed: fourteen back-to-back requests all failed,
     * while the same fourteen spaced 1.2 s apart all returned HTTP 200. The
     * service rate-limits, and being impolite to a free government endpoint
     * is both rude and self-defeating.
     */
    const val MIN_CALL_INTERVAL_MS = 1_200L

    /**
     * Beyond this the held value is too old to show as current.
     *
     * A drive through a long tunnel should degrade the readout, not present
     * a five-minute-old elevation as though it were live.
     */
    const val STALE_AFTER_MS = 120_000L

    /** Build the query URL for a position. */
    fun urlFor(latDeg: Double, lonDeg: Double): String =
        "$ENDPOINT?x=$lonDeg&y=$latDeg&units=Meters&wkid=4326"

    /**
     * Pull the elevation out of a 3DEP response.
     *
     * The body looks like:
     * ```
     * {"location":{...},"locationId":0,"value":"135.350021362","rasterId":97238,...}
     * ```
     *
     * `value` is a STRING, and its precision varies between responses -- a
     * naive numeric regex misses it. Deliberately tolerant: a garbled reply
     * returns null and the caller keeps the value it had.
     *
     * @return metres, or null when the body carries no usable value.
     */
    fun parseMeters(body: String): Double? {
        val key = "\"value\""
        val at = body.indexOf(key)
        if (at < 0) return null
        var i = at + key.length
        // skip ": and any whitespace
        while (i < body.length && (body[i] == ':' || body[i] == ' ' || body[i] == '"')) i++
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            if (c == '-' || c == '.' || c.isDigit() || c == 'e' || c == 'E' || c == '+') {
                sb.append(c); i++
            } else break
        }
        val v = sb.toString().toDoubleOrNull() ?: return null

        // 3DEP reports -1000000 for a point outside its coverage. Treating
        // that as an elevation would put the car below the Marianas Trench.
        if (v < MIN_PLAUSIBLE_M || v > MAX_PLAUSIBLE_M) return null
        return v
    }

    /**
     * Should a new query be made?
     *
     * @param lastQueryLat position of the last successful query, or NaN.
     * @param sinceLastCallMs time since the last call of any kind.
     */
    fun shouldQuery(
        lastQueryLat: Double,
        lastQueryLon: Double,
        latDeg: Double,
        lonDeg: Double,
        sinceLastCallMs: Long
    ): Boolean {
        if (!RadarTile.isUsableFix(latDeg, lonDeg)) return false
        // Rate limit first: being polite matters more than being current.
        if (sinceLastCallMs < MIN_CALL_INTERVAL_MS) return false
        if (lastQueryLat.isNaN() || lastQueryLon.isNaN()) return true
        val moved = FixGate.metresBetween(lastQueryLat, lastQueryLon, latDeg, lonDeg)
        return moved >= REFETCH_DISTANCE_M
    }

    /**
     * Elevation between queries.
     *
     * The barometer is not thrown away -- it is DEMOTED. Between two surveyed
     * points it supplies only the CHANGE, which is what a barometer is
     * genuinely excellent at and which weather cannot affect over seconds.
     * The absolute value always comes from the survey.
     *
     * @param surveyedM the last elevation from USGS.
     * @param baroAtSurveyM the barometric reading when that was fetched.
     * @param baroNowM the barometric reading now.
     */
    fun interpolate(
        surveyedM: Double, baroAtSurveyM: Double, baroNowM: Double
    ): Double = surveyedM + (baroNowM - baroAtSurveyM)

    /** Outside this range a 3DEP reply is an error code, not terrain. */
    const val MIN_PLAUSIBLE_M = -500.0
    const val MAX_PLAUSIBLE_M = 9000.0
}
