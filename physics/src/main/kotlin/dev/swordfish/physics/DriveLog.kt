package dev.swordfish.physics

/**
 * Reads a recorded drive back into something worth looking at.
 *
 * ## Why this is in :physics
 *
 * The phone app is becoming a logbook, and a logbook is only as good as the
 * numbers it puts on the page. Every one of those numbers — distance, fuel
 * burnt, the delta-V a trip cost — is arithmetic over a list of samples, so
 * it belongs where arithmetic can be tested without a phone.
 *
 * ## Deliberately tolerant
 *
 * These files come off a car. They get truncated by crashes, they predate
 * fields that exist now, and their opening rows have no fix, no seeded fuel
 * tracker and no delta-V. **A row that cannot be parsed is skipped, never
 * fatal** — a log directory that refuses to show a drive because one line
 * was cut in half is worse than one that shows it slightly short.
 *
 * ## Not a JSON library
 *
 * Same reasoning as [DriveResume]: the rows have a known shape, written by
 * `DriveRecorder`, and the fields wanted are numbers and short strings.
 */
object DriveLog {

    /** One recorded sample, with only the fields the logbook uses. */
    data class Sample(
        val tMs: Long,
        val speedMps: Double?,
        val rpm: Double?,
        val fuelKgPerSec: Double?,
        val fuelRemainingKg: Double?,
        val ispS: Double?,
        val deltaVMps: Double?,
        val altitudeM: Double?,
        val coolantC: Double?,
        val lat: Double?,
        val lon: Double?,
        /** Force opposing motion: aero drag plus rolling resistance, newtons. */
        val roadLoadN: Double? = null,
        /** Power spent climbing, watts. Negative while descending. */
        val gravityLossW: Double? = null,
        val state: String?,
        val dfco: Boolean
    ) {
        val hasFix: Boolean get() = lat != null && lon != null
    }

    /** Everything the logbook shows about one drive. */
    data class Summary(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val rows: Int,
        /** False when the log has no closing row: the app died mid-drive. */
        val endedCleanly: Boolean,
        /** True when the drive was picked up again after an interruption. */
        val wasResumed: Boolean,
        val distanceMeters: Double,
        val movingSeconds: Double,
        val idleSeconds: Double,
        val dfcoSeconds: Double,
        val maxSpeedMps: Double,
        val meanMovingSpeedMps: Double,
        val maxRpm: Double,
        val fuelUsedKg: Double,
        /** Delta-V spent: the trip's starting budget minus what is left. */
        val deltaVSpentMps: Double?,
        val deltaVStartMps: Double?,
        val deltaVEndMps: Double?,
        val meanIspS: Double?,
        val minAltitudeM: Double?,
        val maxAltitudeM: Double?,
        val maxCoolantC: Double?,
        val samplesWithFix: Int
    ) {
        val durationMs: Long get() = endedAtMs - startedAtMs

        /** Miles per gallon over the whole drive, or null with no fuel used. */
        val mpg: Double?
            get() {
                if (fuelUsedKg <= 1e-6 || distanceMeters <= 0.0) return null
                val gal = Units.kgToGallons(fuelUsedKg)
                if (gal <= 1e-9) return null
                return Units.metersToMiles(distanceMeters) / gal
            }
    }

    /**
     * Parse the sample rows of a drive log.
     *
     * @param lines the file's contents, in order.
     */
    fun parse(lines: List<String>): List<Sample> {
        val out = ArrayList<Sample>(lines.size)
        for (raw in lines) {
            val s = raw.trim()
            if (!s.startsWith("{")) continue
            if (!s.contains("\"kind\":\"sample\"")) continue
            val t = num(s, "t")?.toLong() ?: continue
            out += Sample(
                tMs = t,
                speedMps = num(s, "speed_mps"),
                rpm = num(s, "rpm"),
                fuelKgPerSec = num(s, "fuel_kg_s"),
                fuelRemainingKg = num(s, "fuel_remaining_kg"),
                ispS = num(s, "isp_s"),
                deltaVMps = num(s, "dv_mps"),
                altitudeM = num(s, "altitude_m"),
                coolantC = num(s, "coolant_c"),
                lat = num(s, "lat"),
                lon = num(s, "lon"),
                roadLoadN = num(s, "road_load_n"),
                gravityLossW = num(s, "gravity_loss_w"),
                state = str(s, "state"),
                dfco = s.contains("\"dfco\":true")
            )
        }
        return out
    }

    /**
     * Reduce a whole log to the figures the logbook shows.
     *
     * @return null when the log holds no usable samples — an empty drive is
     *   not worth a row in the directory.
     */
    fun summarise(lines: List<String>): Summary? {
        val samples = parse(lines)
        if (samples.isEmpty()) return null

        val dvStart = lines.firstNotNullOfOrNull { num(it, "dv_start") }

        var distance = 0.0
        var moving = 0.0
        var idle = 0.0
        var dfco = 0.0
        var maxSpeed = 0.0
        var maxRpm = 0.0
        var fuelUsed = 0.0
        var speedSum = 0.0
        var speedCount = 0
        var ispSum = 0.0
        var ispCount = 0
        var minAlt: Double? = null
        var maxAlt: Double? = null
        var maxCoolant: Double? = null
        var fixes = 0

        var prev: Sample? = null
        for (s in samples) {
            if (s.hasFix) fixes++
            s.altitudeM?.let { a ->
                minAlt = if (minAlt == null) a else minOf(minAlt!!, a)
                maxAlt = if (maxAlt == null) a else maxOf(maxAlt!!, a)
            }
            s.coolantC?.let { c ->
                maxCoolant = if (maxCoolant == null) c else maxOf(maxCoolant!!, c)
            }
            s.rpm?.let { if (it > maxRpm) maxRpm = it }
            s.speedMps?.let { if (it > maxSpeed) maxSpeed = it }
            s.ispS?.let { if (it > 0.0) { ispSum += it; ispCount++ } }

            val p = prev
            if (p != null) {
                // Gaps happen: a crash, or the app backgrounded. Integrating
                // across one would invent distance and fuel that never
                // occurred, so anything longer than a few sample intervals
                // is treated as a break rather than a very slow second.
                val dtMs = s.tMs - p.tMs
                if (dtMs in 1..MAX_GAP_MS) {
                    val dt = dtMs / 1000.0
                    // Trapezoid: speed and flow both change between samples,
                    // and at 1 Hz through a gearshift the difference between
                    // this and a rectangle is real.
                    val v0 = p.speedMps ?: 0.0
                    val v1 = s.speedMps ?: 0.0
                    distance += (v0 + v1) / 2.0 * dt

                    val f0 = p.fuelKgPerSec ?: 0.0
                    val f1 = s.fuelKgPerSec ?: 0.0
                    fuelUsed += (f0 + f1) / 2.0 * dt

                    if (v1 > MOVING_THRESHOLD_MPS) {
                        moving += dt
                        speedSum += v1
                        speedCount++
                    } else {
                        idle += dt
                    }
                    if (s.dfco) dfco += dt
                }
            }
            prev = s
        }

        val dvEnd = samples.lastOrNull { it.deltaVMps != null }?.deltaVMps
        val spent = if (dvStart != null && dvEnd != null) {
            (dvStart - dvEnd).coerceAtLeast(0.0)
        } else null

        return Summary(
            startedAtMs = samples.first().tMs,
            endedAtMs = samples.last().tMs,
            rows = samples.size,
            endedCleanly = DriveResume.endedCleanly(lines),
            wasResumed = lines.any { it.contains("\"msg\":\"resumed\"") },
            distanceMeters = distance,
            movingSeconds = moving,
            idleSeconds = idle,
            dfcoSeconds = dfco,
            maxSpeedMps = maxSpeed,
            meanMovingSpeedMps = if (speedCount > 0) speedSum / speedCount else 0.0,
            maxRpm = maxRpm,
            fuelUsedKg = fuelUsed,
            deltaVSpentMps = spent,
            deltaVStartMps = dvStart,
            deltaVEndMps = dvEnd,
            meanIspS = if (ispCount > 0) ispSum / ispCount else null,
            minAltitudeM = minAlt,
            maxAltitudeM = maxAlt,
            maxCoolantC = maxCoolant,
            samplesWithFix = fixes
        )
    }

    /**
     * Longest gap that still counts as continuous driving, in millis.
     *
     * Rows land at 1 Hz. Five seconds absorbs a stutter without absorbing a
     * crash-and-restart, which is exactly the event that must NOT contribute
     * distance — the car moved, but not in a way this log witnessed.
     */
    const val MAX_GAP_MS = 5_000L

    /** Below this the car is stopped, not crawling. */
    const val MOVING_THRESHOLD_MPS = 0.5

    // --- field extraction ---

    internal fun num(json: String, key: String): Double? {
        val needle = "\"" + key + "\":"
        val at = json.indexOf(needle)
        if (at < 0) return null
        var i = at + needle.length
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            if (c == '-' || c == '+' || c == '.' || c.isDigit() ||
                c == 'e' || c == 'E'
            ) { sb.append(c); i++ } else break
        }
        return sb.toString().toDoubleOrNull()
    }

    internal fun str(json: String, key: String): String? {
        val needle = "\"" + key + "\":\""
        val at = json.indexOf(needle)
        if (at < 0) return null
        val start = at + needle.length
        val end = json.indexOf('"', start)
        return if (end < 0) null else json.substring(start, end)
    }
}
