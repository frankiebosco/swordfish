package dev.swordfish.obd

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.swordfish.physics.Attitude
import dev.swordfish.physics.FixGate
import dev.swordfish.physics.GradeEstimator
import dev.swordfish.physics.MountAutoCalibrator
import dev.swordfish.physics.NavballScale

/**
 * Feeds vehicle attitude from the phone's IMU.
 *
 * ## Why this exists before the OBD dongle
 *
 * Roll, pitch, cornering G and road grade all come from the phone, not the
 * car — the ND2's own chassis sensors sit behind MS-CAN and are probably out
 * of reach (see `docs/MX_PLUS_PROBE_PLAN.md`). So the navball and the G-force
 * readouts can be live without any adapter at all, which makes the whole
 * rendering pipeline testable before hardware arrives.
 *
 * ## Sampling rate
 *
 * `SENSOR_DELAY_GAME` is ~50 Hz, far more than a gauge needs but cheap, and it
 * gives the smoothing filter enough samples to work with. The moto g's
 * ICM-4x607 will do 400 Hz; we do not want it to.
 *
 * ## Smoothing
 *
 * Raw accelerometer output in a car is violent — every expansion joint and
 * pothole registers. A low-pass filter is applied so the navball reflects
 * sustained attitude rather than road texture. The time constant is
 * deliberately short enough to catch a real corner but long enough to ignore
 * a bump.
 */
class ImuSource(
    private val context: Context,
    /**
     * Low-pass factor per sample, 0..1. Lower is smoother.
     *
     * 0.08 at ~50 Hz gives roughly a quarter-second response: fast enough to
     * follow a corner, slow enough to ignore surface noise.
     */
    private val smoothing: Double = 0.08
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val barometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /**
     * Fused orientation, used only for compass heading.
     *
     * ROTATION_VECTOR combines accelerometer, gyroscope and magnetometer, so
     * its azimuth is a genuine compass bearing rather than something derived
     * from GPS track. That matters when stationary, where a GPS-derived
     * heading is meaningless.
     */
    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * Works out how the phone is oriented, whatever way it happens to be
     * sitting.
     *
     * There is deliberately no fixed default. Assuming a windscreen cradle was
     * wrong for a phone lying flat on the passenger seat -- the usual case --
     * and produced a level car reading as a steep descent. See
     * [MountAutoCalibrator].
     */
    val calibrator = MountAutoCalibrator()

    /**
     * Road grade from the barometer, fused with GPS altitude when available.
     *
     * This is a TRUE horizon reference, and it is the reason it matters:
     * accelerometer pitch describes how the PHONE is lying, which on a
     * passenger seat means nothing at all. Air pressure over distance
     * describes how the ROAD is lying, regardless of the phone's attitude.
     *
     * GPS is optional. The barometer carries short-term grade on its own;
     * GPS only trims multi-minute drift, so grade works before any location
     * permission is granted.
     */
    val gradeEstimator = GradeEstimator()

    /** Latest fused road grade, radians. Positive uphill. */
    val gradeRadians: Double get() = gradeEstimator.gradeRadians

    /** GPS altitude in metres, when a location fix is available. */
    @Volatile
    var gpsAltitudeM: Double? = null

    /** How many location fixes carrying an altitude have arrived. */
    var gpsAltitudeFixes: Int = 0
        private set

    /**
     * Fixes that arrived carrying NO altitude.
     *
     * The counterpart to [gpsAltitudeFixes]. A drive where both are high
     * means the providers are working and only some fixes carry altitude; a
     * drive where this is high and the other is near zero means whichever
     * provider is answering does not supply it at all.
     */
    var gpsFixesWithoutAltitude: Int = 0
        private set

    /** The last position accepted by [FixGate], for the next comparison. */
    private var lastAcceptedFix: FixGate.Fix? = null
    private var lastFixWasGood: Boolean = false

    /** Fixes turned away as physically impossible jumps. */
    var rejectedTeleports: Int = 0
        private set

    /** Fixes turned away as too rough to displace a recent good one. */
    var rejectedRough: Int = 0
        private set

    /** Every fix, by provider name. */
    val gpsFixesByProvider: MutableMap<String, Int> = HashMap()

    /** Fixes WITH altitude, by provider name. */
    val gpsAltFixesByProvider: MutableMap<String, Int> = HashMap()

    /** One-line provider breakdown, for the drive log and logcat. */
    fun gpsProviderSummary(): String =
        gpsFixesByProvider.entries.sortedBy { it.key }.joinToString(" ") { (p, n) ->
            "$p=${gpsAltFixesByProvider[p] ?: 0}/$n"
        }

    /**
     * Course over ground in degrees, or null when stationary or unfixed.
     *
     * This is the direction the CAR is travelling, not the direction the
     * phone is facing.
     */
    @Volatile
    var gpsBearingDegrees: Double? = null
        private set

    private var gpsBearingAtNanos = 0L

    /**
     * True when the GPS bearing is recent enough to trust.
     *
     * A bearing from two minutes ago describes a corner you have already
     * left. Past this age the reading is discarded rather than shown.
     */
    private val gpsBearingIsFresh: Boolean
        get() = gpsBearingAtNanos != 0L &&
            (System.nanoTime() - gpsBearingAtNanos) < BEARING_MAX_AGE_NANOS

    /**
     * Ground speed from the location fix, independent of OBD telemetry.
     *
     * See the note in the location callback: the OBD-fed [speedMps] is null
     * for the whole of an MS-CAN capture, so anything that must work during
     * one has to come off the fix.
     */
    @Volatile
    var gpsSpeedMps: Double? = null
        private set

    private var gpsSpeedAtNanos = 0L

    /**
     * Ground speed, but only while it is fresh enough to act on.
     *
     * Shares [BEARING_MAX_AGE_NANOS] with course over ground: they come from
     * the same fix and go stale together.
     */
    val freshGpsSpeedMps: Double?
        get() = if (
            gpsSpeedAtNanos != 0L &&
            (System.nanoTime() - gpsSpeedAtNanos) < BEARING_MAX_AGE_NANOS
        ) gpsSpeedMps else null

    /**
     * Course over ground, but only while it is fresh enough to act on.
     *
     * ## Why this exists separately from [headingDegrees]
     *
     * [headingDegrees] is only ever written from the ROTATION VECTOR sensor
     * callback, so it stays null unless [start] has registered the sensors.
     * On the 2026-08-26 drive the MS-CAN capture called [startLocation] only
     * -- location without sensors -- so headingDegrees never left null, the
     * capture never received a reference, and all 2120 CAN frames across two
     * attempts were dropped as `droppedNoRef`.
     *
     * Course over ground needs no sensors at all: it comes straight off the
     * location fix. Anything wanting the direction the CAR is TRAVELLING
     * should use this and be immune to whether the IMU was started, which is
     * precisely what the capture wants.
     */
    val courseOverGroundDegrees: Double?
        get() = if (gpsBearingIsFresh) gpsBearingDegrees else null

    private var lastGradeUpdateNanos = 0L

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /**
     * GPS altitude feed, used only to pin the barometer's absolute value.
     *
     * The barometer is excellent at CHANGE and poor at ABSOLUTE: converting
     * pressure to altitude needs a sea-level reference, and using the
     * standard atmosphere when the real weather is 990 hPa puts the reading
     * ~190 m out. GPS is the opposite — absolutely referenced but noisy at
     * +/-10-20 m. Fusing them takes the best of each, which is what
     * `GradeEstimator` already does internally.
     *
     * Deliberately slow: 5 s / 20 m. Altitude drift is a multi-minute
     * effect, and a 1 Hz GPS feed would cost battery for nothing.
     */
    /**
     * Last known position, for the radar scope. NaN until a fix arrives.
     *
     * The listener previously took only altitude and bearing off the fix and
     * threw the position away -- grade and the compass were all it existed
     * for. The radar needs to know WHERE to ask NOAA about, so the position
     * is now retained.
     *
     * Deliberately plain fields rather than a Location reference: the object
     * is recycled by the platform, and holding one risks reading a fix that
     * has since been overwritten with another provider's.
     */
    @Volatile
    var latitude: Double = Double.NaN
        private set

    @Volatile
    var longitude: Double = Double.NaN
        private set

    private val locationListener = LocationListener { loc: Location ->
        // POSITION -- for the radar scope and the logbook's route retrace.
        //
        // GATED. Three providers are registered (FUSED, GPS, NETWORK) and
        // they all arrive here; taking whichever fired last is what drew the
        // long straight lines across the retrace on the 2026-08-25 drive.
        // NETWORK derives position from cell towers, and 9 of its fixes out
        // of 2274 samples landed ~12 km off-route -- each one drawing two
        // legs, out and back. See FixGate.
        val candidate = FixGate.Fix(
            latDeg = loc.latitude,
            lonDeg = loc.longitude,
            tMs = System.currentTimeMillis(),
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        )
        when (FixGate.judge(lastAcceptedFix, lastFixWasGood, candidate)) {
            FixGate.Verdict.ACCEPT -> {
                latitude = loc.latitude
                longitude = loc.longitude
                lastAcceptedFix = candidate
                val acc = candidate.accuracyM
                lastFixWasGood = acc == null || acc <= FixGate.ROUGH_ACCURACY_M
            }
            FixGate.Verdict.REJECT_TELEPORT -> rejectedTeleports++
            FixGate.Verdict.REJECT_ROUGH -> rejectedRough++
        }

        // hasAltitude() matters: a fix without one reports 0.0, which would
        // drag a genuine 200 m reading toward sea level.
        // COUNTED PER PROVIDER, because the totals alone were misleading.
        //
        // Across three drives on 2026-08-24 the altitude-fix count collapsed
        // from 4472 to 47 to 9 with no code change between them, and the
        // panel read ~100 m low as a result. A single total cannot say
        // whether that is fewer fixes overall or the same number arriving
        // without altitude -- and those have completely different fixes.
        //
        // NETWORK_PROVIDER fixes essentially never carry altitude, and FUSED
        // often does not either, so which provider is answering matters.
        val provider = loc.provider ?: "unknown"
        gpsFixesByProvider[provider] = (gpsFixesByProvider[provider] ?: 0) + 1

        if (loc.hasAltitude()) {
            gpsAltitudeM = loc.altitude
            // Counted so a drive log can PROVE whether GPS altitude ever
            // arrived. Without this the 68 m drift of 2026-08-23 was
            // indistinguishable from a filter bug, and it cost an
            // investigation to narrow down by elimination.
            gpsAltitudeFixes++
            gpsAltFixesByProvider[provider] =
                (gpsAltFixesByProvider[provider] ?: 0) + 1
        } else {
            gpsFixesWithoutAltitude++
        }

        // COURSE OVER GROUND -- the fix for "where is the car pointing".
        //
        // A car moving forward is, by definition, travelling along its own
        // forward axis. GPS bearing is that direction, measured by doppler:
        // absolute, un-integrated, drift-free, and completely independent
        // of how the phone happens to be lying on the seat.
        //
        // The accelerometer route needs a hard straight-line pull to find
        // forward, confuses braking and cornering for it, and latches once
        // -- which is why the compass "kept getting thrown off and could
        // not be recovered" on the 2026-08-21 drive. This re-measures every
        // fix, so a phone that slides is corrected within seconds.
        //
        // hasBearing() matters: a stationary fix reports 0.0, which would
        // silently claim the car is pointing due north.
        if (loc.hasBearing() && loc.speed >= MIN_BEARING_SPEED_MPS) {
            gpsBearingDegrees = loc.bearing.toDouble()
            gpsBearingAtNanos = System.nanoTime()
        }

        // Speed from the FIX, not from the OBD telemetry path.
        //
        // `speedMps` is written by GaugeScreen and MainActivity from OBD
        // samples, and during an MS-CAN capture both are dead -- the capture
        // owns the socket. Anything reading `speedMps` to decide whether the
        // car is moving would therefore see null for the whole capture, which
        // is the same trap that made `headingDegrees` useless there.
        if (loc.hasSpeed()) {
            gpsSpeedMps = loc.speed.toDouble()
            gpsSpeedAtNanos = System.nanoTime()
        }
    }

    private var locationActive = false

    /** Latest vehicle speed, so the calibrator can tell stopped from moving. */
    @Volatile
    var speedMps: Double? = null

    /** Latest smoothed attitude, or null before the first sample. */
    @Volatile
    var attitude: Attitude.Reading? = null
        private set

    /** Latest yaw rate in rad/s, for the traction estimate. */
    @Volatile
    var yawRateRadPerSec: Double = 0.0
        private set

    /** Latest barometric pressure in hPa, or null if unsupported. */
    @Volatile
    var pressureHpa: Double? = null
        private set

    /**
     * Compass heading of the **vehicle**, degrees from north.
     *
     * ## Why this is not simply the azimuth from getOrientation
     *
     * `SensorManager.getOrientation` returns azimuth as the direction the
     * phone's **-Z axis** points -- out of the *back* of the device. Two
     * problems follow:
     *
     *  1. For a phone lying flat on its back, -Z points at the ground. The
     *     azimuth then degenerates to whatever the top edge happens to be
     *     doing, and reads 180 degrees from what a user expects. This was
     *     observed directly: port side facing north reported south.
     *  2. Even correctly interpreted, it is the PHONE's heading. The car's
     *     heading differs by however the phone is rotated in the seat.
     *
     * The fix uses the mount calibration we already have. Rotate the phone's
     * forward axis into world coordinates via the rotation matrix, then take
     * the bearing of that -- which is the direction the *car* points.
     *
     * Null until the mount is calibrated, because before that there is no
     * defensible answer. Decorative either way; no physics depends on it.
     */
    @Volatile
    var headingDegrees: Double? = null
        private set

    private val rotationMatrix = FloatArray(9)

    // NOTE: deliberately NO repaint callback.
    //
    // An earlier version invoked one on every accelerometer sample (~50 Hz),
    // and each invocation ran the full physics model plus a synchronous
    // surface lock. That saturated the main thread and Android Auto killed the
    // app with "Swordfish isn't responding". Sensor callbacks now only store
    // the latest reading; GaugeScreen's frame clock decides when to repaint.

    private var smoothedAccel: Attitude.Vec3? = null

    /**
     * Persisted mount calibration, so a host crash does not cost the navball.
     *
     * ## Why this is worth the storage
     *
     * Android Auto's own process crashes (see the launcher
     * tile) and takes ours down with it. Calibration lived only in memory,
     * so every crash restarted the two-stage learn from nothing: on the
     * 2026-08-21 drive that meant 118 s to re-level, losing it again 46 s
     * later, and **72% of the session with no navball** -- `drawNavball`
     * correctly refuses to draw a horizon it cannot trust.
     *
     * The phone does not move in the four seconds a crash-and-restart
     * takes, so the calibration that was valid before is still valid after.
     * `MountAutoCalibrator.hasMoved` still discards it on the next
     * stationary sample if the phone actually did shift, so restoring costs
     * nothing in correctness.
     *
     * SharedPreferences rather than a file: eight doubles written a few
     * times a minute is exactly what it is for.
     */
    private val calibPrefs =
        context.getSharedPreferences("swordfish_mount", Context.MODE_PRIVATE)

    /** Save the calibration, if there is one worth saving. */
    fun saveCalibration() {
        val snap = calibrator.snapshot() ?: return
        calibPrefs.edit()
            .putFloat(K_GX, snap.gravityX.toFloat())
            .putFloat(K_GY, snap.gravityY.toFloat())
            .putFloat(K_GZ, snap.gravityZ.toFloat())
            .putFloat(K_FX, snap.forwardX.toFloat())
            .putFloat(K_FY, snap.forwardY.toFloat())
            .putFloat(K_FZ, snap.forwardZ.toFloat())
            .putFloat(K_FS, snap.forwardStrength.toFloat())
            .putBoolean(K_HAS_FWD, snap.hasForward)
            .putLong(K_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Restore a saved calibration, if one exists and is recent enough.
     *
     * Age matters because the justification is "the phone has not moved
     * since". That holds across a crash-restart seconds later; it does not
     * hold across a day parked on a driveway with the phone in a pocket.
     * Past the cutoff the app re-levels from scratch, which takes seconds
     * when stationary and is the honest answer.
     */
    fun restoreCalibration() {
        if (!calibPrefs.contains(K_GX)) return
        val savedAt = calibPrefs.getLong(K_SAVED_AT, 0L)
        if (System.currentTimeMillis() - savedAt > MAX_CALIB_AGE_MS) return

        calibrator.restore(
            MountAutoCalibrator.Snapshot(
                gravityX = calibPrefs.getFloat(K_GX, 0f).toDouble(),
                gravityY = calibPrefs.getFloat(K_GY, 0f).toDouble(),
                gravityZ = calibPrefs.getFloat(K_GZ, 0f).toDouble(),
                forwardX = calibPrefs.getFloat(K_FX, 0f).toDouble(),
                forwardY = calibPrefs.getFloat(K_FY, 0f).toDouble(),
                forwardZ = calibPrefs.getFloat(K_FZ, 0f).toDouble(),
                forwardStrength = calibPrefs.getFloat(K_FS, 0f).toDouble(),
                hasForward = calibPrefs.getBoolean(K_HAS_FWD, false)
            )
        )
    }

    fun start() {
        restoreCalibration()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        rotationVector?.let {
            // A compass strip does not need to be fast, and this sensor is
            // relatively expensive.
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        // Save on the way out. This covers an orderly teardown; the
        // periodic save in onAccelSample covers the crash case, which is
        // the one that actually motivated this.
        saveCalibration()
        sensorManager.unregisterListener(this)
    }

    /** True when the phone can supply attitude at all. */
    /**
     * Advance the grade estimate on each barometer sample.
     *
     * Driven by the barometer rather than a timer because it is the slowest
     * of the inputs (~5 Hz) and there is nothing to recompute between its
     * samples.
     *
     * Horizontal distance comes from road speed integrated over the sample
     * interval. That is why grade needs the OBD link: without a speed the
     * run is unknown, and `rise / run` has no denominator. GPS could supply
     * it, but far more coarsely.
     */
    private fun updateGrade() {
        val pressure = pressureHpa ?: return
        val now = System.nanoTime()
        val last = lastGradeUpdateNanos
        lastGradeUpdateNanos = now
        if (last == 0L) return

        val dt = (now - last) / 1_000_000_000.0
        // Guard against a stale timestamp after the app is backgrounded:
        // a multi-second dt would fold a whole junction into one sample.
        if (dt <= 0.0 || dt > 2.0) return

        // Unknown speed means zero DISTANCE, not a skipped update.
        //
        // Only GRADE needs the run; altitude does not. Returning early here
        // meant altitude stayed null whenever the OBD link was down --
        // including on the DHU, where the ALT row silently never appeared.
        // With zero distance the estimator still tracks altitude and simply
        // never accumulates enough run to recompute a grade, which is the
        // correct behaviour for a parked car.
        val speed = speedMps ?: 0.0
        // EACH GPS ALTITUDE IS CONSUMED ONCE.
        //
        // `gpsAltitudeM` used to be a held field, passed on every call. This
        // runs at the SENSOR rate (~50 Hz) while altitude fixes arrive about
        // twice a second, so the same reading was re-fed ~28 times -- and the
        // estimator treats every non-null value as a fresh fix.
        //
        // Two things broke. The `secondsSinceGpsAlt` clock that scales the
        // correction was reset ~50x/second, so convergence went straight back
        // to being frame-rate bound. And the estimator was repeatedly told a
        // minutes-old altitude was current: as the car climbed, the barometer
        // rose correctly while the stale GPS value dragged the bias down.
        //
        // Measured on the 2026-08-25 drive: the panel seeded correctly at
        // 66 m and then drifted to -20 m over twenty minutes, falling 110 m
        // between t+20 and t+24 while driving through the valley.
        //
        // Taking the value and clearing it means null genuinely means "no new
        // fix", which is what GradeEstimator has always assumed.
        val freshGpsAlt = gpsAltitudeM
        gpsAltitudeM = null

        gradeEstimator.update(
            barometricAltM = gradeEstimator.pressureToAltitudeM(pressure),
            gpsAltM = freshGpsAlt,
            horizontalDistanceM = speed * dt,
            dtSec = dt
        )
    }

    /**
     * Begin GPS altitude updates, if permitted.
     *
     * Returns quietly when location is not granted. Grade and relative
     * altitude both work without it — only the ABSOLUTE altitude figure
     * suffers, and it degrades to "referenced to the standard atmosphere"
     * rather than to nothing.
     */
    fun startLocation() {
        if (locationActive) return
        val lm = locationManager ?: return
        val granted = context.checkSelfPermission(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        // Subscribe to EVERY provider that can supply an altitude, not just
        // raw GPS.
        //
        // GPS_PROVIDER alone was the original wiring, and it is the worst
        // case for how this phone is actually used: lying flat on a
        // passenger seat, inside a metal car, under a windscreen that is
        // often heated or coated. On the 2026-08-23 drive the barometric
        // altitude drifted 68 m between leaving the driveway and returning
        // to it -- a filter that never converged, which is what a GPS
        // altitude that never arrives looks like.
        //
        // FUSED_PROVIDER (API 31+) is the one that actually performs in a
        // car: it blends GNSS with sensors and cell/wifi. NETWORK_PROVIDER
        // rarely carries an altitude but costs nothing to ask for. Each is
        // registered independently so a device missing one still gets the
        // others -- requestLocationUpdates throws IllegalArgumentException
        // per unknown provider, so they cannot share a try block.
        val providers = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(
                    provider,
                    GPS_MIN_INTERVAL_MS,
                    GPS_MIN_DISTANCE_M,
                    locationListener
                )
                locationActive = true
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call.
            } catch (e: IllegalArgumentException) {
                // This device has no such provider; try the next.
            }
        }
    }

    fun stopLocation() {
        if (!locationActive) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: SecurityException) {
            // Nothing useful to do.
        }
        locationActive = false
    }

    fun isAvailable(): Boolean = accelerometer != null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(event)
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
            Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0].toDouble()
                updateGrade()
            }
            Sensor.TYPE_ROTATION_VECTOR -> onRotationVector(event)
        }
    }

    private fun onRotationVector(event: SensorEvent) {
        // GPS COURSE WINS whenever it is available.
        //
        // It needs no mount calibration at all, so the compass works from
        // the first moment the car is moving -- including while the mount
        // calibrator is still showing DRIVE TO ORIENT, which on the
        // 2026-08-21 drive was the entire journey.
        if (gpsBearingIsFresh) {
            headingDegrees = gpsBearingDegrees

            // Use the same bearing to SOLVE the forward axis, which is what
            // retires DRIVE TO ORIENT. The rotation matrix maps phone
            // coordinates into world coordinates; its transpose maps back.
            // So take the world-space heading vector and rotate it into
            // phone space, and that is the car's forward axis as the phone
            // sees it.
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val rad = Math.toRadians(gpsBearingDegrees ?: 0.0)
            // World frame is X east, Y north, Z up. A bearing of 0 is north.
            val wx = Math.sin(rad)
            val wy = Math.cos(rad)

            // R^T * w  -- the transpose, because R maps phone -> world.
            val px = rotationMatrix[0] * wx + rotationMatrix[3] * wy
            val py = rotationMatrix[1] * wx + rotationMatrix[4] * wy
            val pz = rotationMatrix[2] * wx + rotationMatrix[5] * wy
            calibrator.setForwardFromWorld(Attitude.Vec3(px, py, pz))
            return
        }

        // Falling back to the phone: without a calibrated mount we do not
        // know which way the car faces, and reporting the phone's own
        // bearing would be misleading.
        val cal = calibrator.calibration ?: run {
            headingDegrees = null
            return
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // R maps phone coordinates into world coordinates (X east, Y north,
        // Z up). Rotating the vehicle's forward axis through it gives the
        // direction the car is pointing, in world terms.
        val f = cal.forward
        val eastComponent =
            rotationMatrix[0] * f.x + rotationMatrix[1] * f.y + rotationMatrix[2] * f.z
        val northComponent =
            rotationMatrix[3] * f.x + rotationMatrix[4] * f.y + rotationMatrix[5] * f.z

        // Uses the tested helper rather than an inline atan2: the
        // clockwise-from-north convention is exactly the sort of thing that
        // looks fine on screen while being 90 or 180 degrees wrong.
        headingDegrees = NavballScale.bearingFromWorldVector(
            east = eastComponent.toDouble(),
            north = northComponent.toDouble()
        )
    }

    private fun onAccel(event: SensorEvent) {
        val raw = Attitude.Vec3(
            event.values[0].toDouble(),
            event.values[1].toDouble(),
            event.values[2].toDouble()
        )

        // Calibration sees RAW samples: the low-pass filter would hide the
        // sharp pull that reveals which way is forward.
        val stateBefore = calibrator.state
        calibrator.onAccelSample(raw, speedMps)

        // Persist on every state CHANGE, plus periodically thereafter.
        //
        // stop() cannot be relied on: the case this exists for is the host
        // crashing and taking our process with it, where no orderly
        // teardown runs at all. Writing on transitions catches the moment a
        // calibration becomes worth keeping; the periodic write keeps the
        // forward axis current as it is refined by better pulls.
        val now = System.currentTimeMillis()
        if (calibrator.state != stateBefore ||
            (calibrator.state != MountAutoCalibrator.State.UNCALIBRATED &&
                now - lastCalibSaveMs > CALIB_SAVE_INTERVAL_MS)
        ) {
            lastCalibSaveMs = now
            saveCalibration()
        }

        // Low-pass: the navball should show sustained attitude, not potholes.
        val prev = smoothedAccel
        val smoothed = if (prev == null) raw else Attitude.Vec3(
            prev.x + (raw.x - prev.x) * smoothing,
            prev.y + (raw.y - prev.y) * smoothing,
            prev.z + (raw.z - prev.z) * smoothing
        )
        smoothedAccel = smoothed

        // No attitude until the phone has at least been levelled. A dash is
        // better than a confidently wrong horizon.
        val cal = calibrator.calibration ?: return
        val reading = Attitude.fromVehicleFrameAccel(cal.toVehicleFrame(smoothed))

        // Subtract the MOUNT's own tilt from roll.
        //
        // Gravity fixes "up" and GPS fixes "forward", but rotation ABOUT
        // forward -- which is roll -- stays unknown: a phone lying on its
        // edge in the seat produces the same gravity vector as a level
        // phone in a leaning car. The 2026-08-21 drive showed a large
        // constant fake roll for exactly this reason.
        //
        // Averaged over a drive a car IS level, so the mean roll is the
        // mount's tilt rather than the car's. See observeRoll.
        calibrator.observeRoll(reading.rollRadians)
        attitude = reading.copy(
            rollRadians = reading.rollRadians - calibrator.rollBias
        )
    }

    private fun onGyro(event: SensorEvent) {
        // Yaw is rotation about the vehicle's vertical axis. Rotate the gyro
        // vector into vehicle frame and take the vertical component.
        val raw = Attitude.Vec3(
            event.values[0].toDouble(),
            event.values[1].toDouble(),
            event.values[2].toDouble()
        )
        val cal = calibrator.calibration ?: return
        yawRateRadPerSec = cal.toVehicleFrame(raw).y
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not acted on. Accuracy dips are common in a car (magnetic
        // interference, temperature) and do not invalidate the accelerometer
        // readings we rely on.
    }

    private var lastCalibSaveMs = 0L

    private companion object {
        // Mount-calibration persistence.
        const val K_GX = "gx"
        const val K_GY = "gy"
        const val K_GZ = "gz"
        const val K_FX = "fx"
        const val K_FY = "fy"
        const val K_FZ = "fz"
        const val K_FS = "fstrength"
        const val K_HAS_FWD = "has_forward"
        const val K_SAVED_AT = "saved_at"

        /**
         * How stale a saved calibration may be and still be trusted.
         *
         * The justification for restoring is "the phone has not moved
         * since", which holds across a crash-restart seconds later and not
         * across a night parked up. Six hours covers any plausible
         * same-journey interruption -- including a long stop with the
         * engine off -- while refusing to carry yesterday's mount into
         * today's drive.
         */
        const val MAX_CALIB_AGE_MS = 6L * 60L * 60L * 1000L

        /** How often to re-save while calibrated, ms. */
        const val CALIB_SAVE_INTERVAL_MS = 30_000L

        /**
         * GPS update interval.
         *
         * Was 5 s when altitude was the only consumer -- drift is a
         * multi-minute effect. Bearing is different: it changes through
         * every corner, so the feed is now 1 s. Still cheap, and the
         * bearing is what makes the compass usable.
         */
        const val GPS_MIN_INTERVAL_MS = 1_000L

        /**
         * Speed below which GPS bearing is meaningless.
         *
         * Below a walking pace the doppler solution is dominated by noise
         * and the reported bearing spins randomly. 2 m/s is comfortably
         * above that and well below any real driving speed.
         */
        const val MIN_BEARING_SPEED_MPS = 2.0f

        /**
         * How long a GPS bearing stays usable after the last fix.
         *
         * Ten seconds: long enough to ride through a tunnel or an urban
         * canyon, short enough that a stale reading cannot describe a
         * corner already taken.
         */
        const val BEARING_MAX_AGE_NANOS = 10_000_000_000L

        /**
         * Minimum movement between GPS fixes, metres.
         *
         * Was 20 m for altitude. Dropped to 5 m so bearing keeps up
         * through a corner, where 20 m of travel can be a large heading
         * change.
         */
        const val GPS_MIN_DISTANCE_M = 5f
    }

}
