package dev.swordfish.obd

import android.content.Context
import dev.swordfish.physics.DeltaVModel
import dev.swordfish.physics.DriveResume
import dev.swordfish.physics.OperatingState
import dev.swordfish.physics.Telemetry
import dev.swordfish.physics.Units
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a whole drive to disk, one row per telemetry sample.
 *
 * ## Why this exists
 *
 * The probe already writes NDJSON, and that file is what made every OBD
 * finding so far possible — the 015E verdict, the throughput measurement,
 * the MS-CAN frames. The live poller had nothing equivalent: it wrote a
 * handful of status lines to logcat, which the ring buffer overwrites, so a
 * whole drive's telemetry evaporated the moment the buffer wrapped.
 *
 * That asymmetry is backwards. A probe is a 60-second event you can watch;
 * **a drive is an hour of data nobody can watch**, and it is the only place
 * the interesting questions live: does the fuel model track the trip
 * computer, does Isp behave through a gearshift, does the link hold at
 * speed.
 *
 * ## Format
 *
 * NDJSON, one self-describing object per line, same as the probe — it
 * survives truncation (a killed process loses the last line, not the file),
 * appends without rewriting, and reads with any tool.
 *
 * ## Sampling
 *
 * Rows are written at [ROW_INTERVAL_MS], not on every poll. At 10 Hz a
 * one-hour drive would be 36,000 rows of mostly-identical numbers; at 1 Hz
 * it is 3,600 rows that still resolve every gearshift and throttle change.
 * The raw poll rate is recorded in each row, so nothing about link
 * performance is lost by sampling the output.
 */
class DriveRecorder(private val context: Context) {

    private var file: File? = null
    private var lastRowAt = 0L
    private var rows = 0

    /** Absolute path of the current recording, or null when not recording. */
    val path: String? get() = file?.absolutePath

    val rowCount: Int get() = rows

    /**
     * The delta-V this trip started at, recovered from an interrupted log.
     *
     * Null unless [start] resumed. `GaugeScreen` reads it so the trip cost
     * survives a crash: without it the budget silently restarts from the
     * current tank and the "what did this trip cost" figure resets
     * mid-journey, which is exactly what happened on 2026-08-24.
     */
    var recoveredTripStartDeltaV: Double? = null
        private set

    /** True when [start] re-attached to an interrupted recording. */
    var resumed: Boolean = false
        private set

    /**
     * Begin recording, RESUMING an interrupted drive when there is one.
     *
     * Android Auto restarts a crashed service in about a second, so a crash
     * mid-drive reads as a flicker to the driver — but it used to begin a
     * new file each time, turning one journey into three and resetting the
     * trip's starting budget. If the newest log has no closing row and is
     * recent, this appends to it instead. See [DriveResume].
     *
     * @return the file, or null if it could not be created — a recording
     *   failure must never take the drive down with it.
     */
    fun start(): File? {
        resumed = false
        recoveredTripStartDeltaV = null

        // Try to re-attach before creating anything new.
        try {
            val dir = File(context.getExternalFilesDir(null), "drives")
            val newest = dir.listFiles { f -> f.name.endsWith(".ndjson") }
                ?.maxByOrNull { it.lastModified() }
            if (newest != null) {
                val r = DriveResume.inspect(
                    newest.readLines(), System.currentTimeMillis()
                )
                if (r != null) {
                    file = newest
                    rows = r.rows
                    lastRowAt = 0L
                    resumed = true
                    recoveredTripStartDeltaV = r.tripStartDeltaV
                    // Marked in the log itself, so a drive that was
                    // interrupted is visible as one afterwards rather than
                    // looking like an unbroken run.
                    writeRaw(
                        """{"t":${System.currentTimeMillis()},"kind":"drive",""" +
                            """"msg":"resumed","after_ms":""" +
                            """${System.currentTimeMillis() - r.lastRowAtMs}}"""
                    )
                    return newest
                }
            }
        } catch (e: Exception) {
            // Resume is a convenience. If anything about it fails — an
            // unreadable directory, a file being written by something else —
            // fall through and start fresh rather than lose the drive.
        }

        return startFresh()
    }

    private fun startFresh(): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return try {
            val dir = File(context.getExternalFilesDir(null), "drives").apply { mkdirs() }
            val f = File(dir, "drive-$stamp.ndjson")
            file = f
            rows = 0
            lastRowAt = 0L
            writeRaw(
                """{"t":${System.currentTimeMillis()},"kind":"drive","msg":"started"}"""
            )
            f
        } catch (e: Exception) {
            file = null
            null
        }
    }

    fun stop() {
        if (file != null) {
            writeRaw(
                """{"t":${System.currentTimeMillis()},"kind":"drive",""" +
                    """"msg":"stopped","rows":$rows}"""
            )
        }
        file = null
    }

    /**
     * Record one sample, if enough time has passed since the last row.
     *
     * Called from the render loop, so it must be cheap and must never throw.
     *
     * @param telemetry the assembled sample; null while the link is down.
     * @param readout the model output for that sample.
     * @param state what the car is doing.
     * @param cmdPerSec achieved poll rate at this moment.
     * @param linkState the poller's link state name.
     * @param gradeRadians road grade from the barometer.
     * @param altitudeM fused altitude.
     * @param gpsAltFixes count of location fixes carrying an altitude. Zero
     *   over a whole drive means the barometer is running unreferenced and
     *   any absolute altitude is only as good as the standard atmosphere.
     */
    fun sample(
        telemetry: Telemetry?,
        readout: DeltaVModel.Readout?,
        state: OperatingState,
        cmdPerSec: Double,
        linkState: String,
        gradeRadians: Double?,
        altitudeM: Double?,
        gpsAltFixes: Int = 0,
        tripStartDeltaV: Double? = null,
        headingDegrees: Double? = null,
        mountState: String? = null,
        busVolts: Double? = null,
        coolantC: Double? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        gpsNoAltFixes: Int = 0,
        baroAltM: Double? = null,
        altBiasM: Double? = null,
        gpsRejects: Int = 0,
        surveyedElevM: Double? = null,
        // ATTITUDE, so a drive can be re-analysed offline.
        //
        // 2026-08-29: the roll decomposition (Attitude.decomposeRoll) could
        // not be validated against any real drive, because roll and lateral
        // G were computed live and never written down. Wheel-derived yaw is
        // in the MS-CAN capture and the phone's answer was not in the drive
        // log, so the two could not be compared after the fact. Three fields
        // on a row that already carries twenty.
        rollRadians: Double? = null,
        lateralG: Double? = null,
        longitudinalG: Double? = null
    ) {
        if (file == null) return
        val now = System.currentTimeMillis()
        if (now - lastRowAt < ROW_INTERVAL_MS) return
        lastRowAt = now

        val sb = StringBuilder(320)
        sb.append("""{"t":""").append(now)
        sb.append(""","kind":"sample"""")
        sb.append(""","state":"""").append(state.name).append('"')
        sb.append(""","link":"""").append(linkState).append('"')
        sb.append(""","cmd_per_sec":""").append(fmt(cmdPerSec))

        telemetry?.let { t ->
            sb.append(""","speed_mps":""").append(fmt(t.speedMps))
            sb.append(""","rpm":""").append(fmt(t.rpm))
            t.fuelFlowKgPerSec?.let {
                sb.append(""","fuel_kg_s":""").append(fmt(it, 6))
                // Litres per hour as well: it is what the panel shows and
                // what a trip computer can be compared against without
                // anyone doing arithmetic in their head.
                sb.append(""","fuel_lph":""")
                    .append(fmt(Units.gallonsToLiters(Units.kgToGallons(it)) * 3600.0))
            }
            sb.append(""","fuel_remaining_kg":""").append(fmt(t.fuelRemainingKg))
        }

        readout?.let { r ->
            sb.append(""","isp_s":""").append(fmt(r.effectiveIsp))
            sb.append(""","dv_mps":""").append(fmt(r.deltaVRemaining))
            sb.append(""","road_load_n":""").append(fmt(r.roadLoadNewtons))
            sb.append(""","gravity_loss_w":""").append(fmt(r.gravityLossWatts))
            sb.append(""","dfco":""").append(r.inDeceleratingFuelCutoff)
        }

        rollRadians?.let {
            if (it.isFinite()) sb.append(""","roll_rad":""").append(fmt(it, 4))
        }
        lateralG?.let {
            if (it.isFinite()) sb.append(""","lat_g":""").append(fmt(it, 4))
        }
        longitudinalG?.let {
            if (it.isFinite()) sb.append(""","lon_g":""").append(fmt(it, 4))
        }

        busVolts?.let { sb.append(""","volts":""").append(fmt(it, 2)) }
        coolantC?.let { sb.append(""","coolant_c":""").append(fmt(it, 1)) }

        // POSITION — what makes a drive retraceable after the fact.
        //
        // Six decimal places is ~0.1 m, far finer than a phone GPS resolves,
        // and cheap: two fields on a row that already carries a dozen.
        //
        // Written only when BOTH are real numbers. `ImuSource` says "no fix
        // yet" with NaN, and a bare NaN is not valid JSON — it would break
        // every parser that reads these logs, including the ones that do not
        // exist yet. A row without lat/lon means the fix had not arrived,
        // NOT that the car was at the origin off the coast of Africa.
        if (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite()
        ) {
            sb.append(""","lat":""").append(fmt(latitude, 6))
            sb.append(""","lon":""").append(fmt(longitude, 6))
        }
        headingDegrees?.let { sb.append(""","heading_deg":""").append(fmt(it, 1)) }
        mountState?.let { sb.append(""","mount":"""").append(esc(it)).append('"') }
        gradeRadians?.let { sb.append(""","grade_rad":""").append(fmt(it, 5)) }
        altitudeM?.let { sb.append(""","altitude_m":""").append(fmt(it)) }
        sb.append(""","gps_alt_fixes":""").append(gpsAltFixes)
        // The counterpart: fixes that arrived with NO altitude. Both counts
        // are needed to tell "few fixes" from "fixes without altitude" --
        // see the note in ImuSource. On its own gps_alt_fixes cannot
        // distinguish them, and they have different fixes.
        sb.append(""","gps_noalt_fixes":""").append(gpsNoAltFixes)

        // THE RAW INPUTS behind altitude_m, not just the fused output.
        //
        // A 110 m drift on 2026-08-25 had to be diagnosed by reading source,
        // because the log recorded only the answer. With the barometric
        // reading and the accumulated bias beside it, "which input moved" is
        // a column comparison instead of an investigation.
        baroAltM?.let { sb.append(""","baro_alt_m":""").append(fmt(it)) }
        altBiasM?.let { sb.append(""","alt_bias_m":""").append(fmt(it)) }

        // Positions turned away by FixGate. A retrace with a straight line
        // across it should be explainable from the log.
        sb.append(""","gps_rejects":""").append(gpsRejects)

        // Surveyed ground elevation from USGS, beside the barometric and
        // fused figures. Having all three in one row is what turned the last
        // altitude investigation from source-reading into a column compare.
        surveyedElevM?.let {
            if (it.isFinite()) sb.append(""","usgs_elev_m":""").append(fmt(it))
        }
        // The drive's starting budget, repeated on every row so any slice
        // of the log is self-contained -- analysing "how much did this trip
        // cost" should not require finding the first row.
        tripStartDeltaV?.let { sb.append(""","dv_start":""").append(fmt(it)) }

        sb.append("}")
        writeRaw(sb.toString())
        rows++
    }

    /** Record a one-off event — a link drop, a state change worth marking. */
    fun event(message: String) {
        if (file == null) return
        writeRaw(
            """{"t":${System.currentTimeMillis()},"kind":"event",""" +
                """"msg":"${esc(message)}"}"""
        )
    }

    private fun writeRaw(line: String) {
        try {
            file?.appendText(line + "\n")
        } catch (e: Exception) {
            // A failed write must never take the drive down. Losing the log
            // is a nuisance; losing the instrument is a safety problem.
            file = null
        }
    }

    /** Finite check, because NaN and Infinity are not valid JSON. */
    private fun fmt(v: Double, decimals: Int = 3): String =
        if (!v.isFinite()) "null" else "%.${decimals}f".format(v)

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> if (c < ' ') append(' ') else append(c)
        }
    }

    /** Where recordings are written, for the adb pull hint. */
    fun directory(): String =
        File(context.getExternalFilesDir(null), "drives").absolutePath

    private companion object {
        /**
         * Seconds between recorded rows.
         *
         * 1 Hz: an hour of driving is 3,600 rows, which still resolves every
         * gearshift and throttle change, where 10 Hz would be 36,000 rows of
         * near-identical numbers. The achieved poll rate is stored per row,
         * so link performance is still visible despite the sampling.
         */
        const val ROW_INTERVAL_MS = 1000L
    }
}
