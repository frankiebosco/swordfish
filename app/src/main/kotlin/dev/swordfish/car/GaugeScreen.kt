package dev.swordfish.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.swordfish.obd.DriveRecorder
import dev.swordfish.obd.ElevationSource
import dev.swordfish.obd.ImuSource
import dev.swordfish.obd.RadarSource
import dev.swordfish.obd.TelemetryService
import dev.swordfish.ui.Prefs
import dev.swordfish.physics.DeltaVModel
import dev.swordfish.physics.DemoFrame
import dev.swordfish.physics.EfficiencyBand
import dev.swordfish.physics.EfficiencyRecord
import dev.swordfish.physics.FuelTracker
import dev.swordfish.physics.ObdPid
import dev.swordfish.physics.LinkState
import dev.swordfish.physics.MsCanBanner
import dev.swordfish.obd.MsCanStatusBridge
import dev.swordfish.physics.OperatingState
import dev.swordfish.physics.RadarLayout
import dev.swordfish.physics.RadarTile
import dev.swordfish.physics.TelemetryAssembler
import dev.swordfish.physics.Telemetry
import dev.swordfish.physics.Thermodynamics
import dev.swordfish.physics.Thrust
import dev.swordfish.physics.Units
import dev.swordfish.physics.Vehicle

/**
 * The head-unit screen.
 *
 * Returns a [NavigationTemplate], which is what grants access to the drawing
 * surface. The template renders only the surrounding chrome; everything
 * Swordfish shows is painted by [GaugeRenderer].
 *
 * ## Frame pacing, and the ANR it prevents
 *
 * The first version of this class repainted on **every** IMU sample. The
 * accelerometer runs at ~50 Hz and each callback did a full physics
 * computation plus a synchronous `lockCanvas`/`unlockCanvasAndPost`. That
 * saturated the main thread and Android Auto declared the app unresponsive —
 * "Swordfish isn't responding" — within seconds.
 *
 * The fix is a fixed-rate repaint clock. Sensor callbacks now only *store* the
 * latest reading; a [Handler] posted at [FRAME_INTERVAL_MS] does the work. A
 * gauge read at arm's length does not need more than this, and decoupling
 * sample rate from frame rate is what makes the 10 Hz OBD poll safe to add
 * later.
 */
class GaugeScreen(
    carContext: CarContext,
    private val renderer: GaugeRenderer
) : Screen(carContext) {

    private val imu = ImuSource(carContext)

    /**
     * Vehicle with the user's configured crew and cargo.
     *
     * Read once at construction: the car screen is recreated when the app is
     * reopened, which is the natural point to pick up a settings change.
     */
    private val prefs = Prefs(carContext)
    private val car = Vehicle.ND2_CLUB.copy(payload = prefs.buildPayload())

    /**
     * Which canned frame to show without live telemetry.
     *
     * Read once at construction, like the payload: the car screen is
     * recreated when the app is reopened, which is the natural point to
     * pick up a settings change.
     */
    private val demoFrame = prefs.demoFrame
    private val record = EfficiencyRecord()

    /**
     * Whole-drive recorder.
     *
     * The probe writes NDJSON and that file produced every OBD finding so
     * far. The live poller had nothing equivalent — a drive's telemetry
     * existed only as logcat lines the ring buffer eventually overwrote.
     * A drive is the case that most needs recording: it is an hour long and
     * nobody can watch it happen.
     */
    private val recorder = DriveRecorder(carContext)

    /**
     * NOAA radar imagery for the scope.
     *
     * Lives on the screen rather than in the renderer so the renderer keeps
     * no network dependency: it is handed a bitmap and draws it, or is handed
     * nothing and says so.
     */
    private val radar = RadarSource()

    /**
     * Surveyed ground elevation, which is now the ALTITUDE AUTHORITY.
     *
     * The barometer measures pressure, so its answer moves with the weather:
     * the same spot on the ridge road read 117.7 m at midday and 106.3 m that evening.
     * Fusing GPS in made it worse (+29.0 m mean error against +12.1 m for
     * the raw barometer) because GPS height uses the WGS84 ellipsoid, ~32 m
     * off sea level here. Surveyed data has neither problem.
     *
     * The barometer is not discarded -- it supplies the CHANGE between
     * surveyed points, which is what it is genuinely excellent at and which
     * weather cannot affect over seconds.
     */
    private val elevation = ElevationSource()

    /**
     * Slosh-filtered fuel estimate.
     *
     * PID 012F is unusable raw. Over the 2026-08-21 drive the sensor swung
     * **26.3 L while the car actually burned 1.52 L** — the noise was 17x
     * the signal, and one sample "gained" 18.58 litres in a second. Delta-V
     * built on that is tracking fuel sloshing round corners, not fuel being
     * consumed.
     *
     * `FuelTracker` integrates flow for the live figure and lets the tank
     * sensor correct drift at 0.001/s, rejecting anything more than 15%
     * from the integral. Replayed against that drive it cuts the swing to
     * 2.4 L and the worst one-second jump from 18.58 L to 0.015 L.
     */
    private val fuelTracker = FuelTracker(car)

    private var lastFuelUpdateNanos = 0L

    /**
     * Delta-V when this drive began, for the panel's START readout.
     *
     * Survives the screen being destroyed and recreated -- Android Auto does
     * that freely when switching to Maps and back -- because it belongs to
     * the DRIVE, not to the screen. It resets when the service stops, which
     * is the closest thing the app has to "a new journey".
     */
    private var tripStartDeltaV: Double? = null

    /**
     * Recent Isp samples, for a rolling median.
     *
     * The p10-p90 spread on the real drive was 11-13x across EVERY
     * efficiency metric, so much of the swing is genuine signal rather
     * than noise. A median over a few seconds keeps that response while
     * removing the single-sample jitter that makes the number unreadable.
     */
    private val ispWindow = ArrayDeque<Double>()
    private val handler = Handler(Looper.getMainLooper())

    private var lastFrameNanos = 0L
    private var running = false

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            pushState()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(renderer)

                // Restore the display mode the driver last chose.
                //
                // Read HERE rather than cached at construction because the
                // screen is recreated on every return from Maps, and the
                // renderer outlives it -- so this is the point where the two
                // must be brought back into agreement.
                renderer.centreContent = prefs.centreContent
                renderer.radarRangeMiles = prefs.radarRangeMiles
                renderer.unitSystem = prefs.unitSystem

                // Attitude comes from the phone, so the navball and G-force
                // readouts are live with no OBD hardware at all. The source
                // only stores samples -- it does NOT trigger repaints.
                if (imu.isAvailable()) imu.start()

                // GPS pins the barometer's ABSOLUTE altitude. Without it the
                // reading is referenced to the standard atmosphere and can
                // be a couple of hundred metres out on a low-pressure day.
                // Returns quietly when location is not granted -- grade and
                // relative altitude both work regardless.
                imu.startLocation()

                // The poll must outlive the screen going dark, which is the
                // normal state while projecting, so it lives in a foreground
                // service rather than on this lifecycle.
                //
                // Wrapped because the INSTRUMENT MUST NOT DIE FOR WANT OF
                // TELEMETRY. A missing runtime permission made
                // startForeground throw, which killed the process and left
                // the head unit rendering a black screen -- strictly worse
                // than a gauge on sample data. Anything that goes wrong here
                // costs live numbers, never the panel.
                try {
                    TelemetryService.start(carContext)
                } catch (e: Exception) {
                    // Nothing to do but carry on with the sample frame.
                }

                // Record the whole drive. Starts even before the link comes
                // up, so the file also captures HOW LONG it took to connect
                // and what state it sat in -- which is exactly what was
                // missing when the panel stayed on demo data.
                recorder.start()

                // A resumed drive brings its ORIGINAL starting budget with
                // it. Without this the reference re-seeds from the current
                // tank and the trip cost silently restarts — which is what
                // the two crashes on 2026-08-24 did to that drive's figures.
                recorder.recoveredTripStartDeltaV?.let { tripStartDeltaV = it }

                running = true
                handler.post(frameTick)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                running = false
                handler.removeCallbacks(frameTick)
                imu.stop()
                imu.stopLocation()
                // WHICH provider answered, and how many of its fixes carried
                // an altitude. Logged at drive end because the totals alone
                // were misleading: across three drives on 2026-08-24 the
                // altitude-fix count fell 4472 -> 47 -> 9 with no code change,
                // and a single number cannot say whether that is fewer fixes
                // or the same fixes arriving without altitude.
                android.util.Log.i(
                    "SwordfishElev", elevation.summary()
                )
                android.util.Log.i(
                    "SwordfishGps",
                    "alt=${imu.gpsAltitudeFixes} noalt=${imu.gpsFixesWithoutAltitude} " +
                        "byProvider[alt/total]: ${imu.gpsProviderSummary()}"
                )

                recorder.stop()
                radar.clear()

                // The telemetry service is deliberately NOT stopped here.
                // Android Auto destroys and recreates this screen freely --
                // switching to Maps and back, split-view changes -- and
                // tearing down the OBD link each time would mean a fresh
                // handshake and several seconds of dashes on every return.
            }
        })
    }

    /**
     * The action strip is the ONLY input this app has on the real car.
     *
     * The ND2's head unit has no touchscreen while the engine is running --
     * the screen is driven by the rotary controller, which navigates and
     * clicks action-strip buttons. So `SurfaceCallback.onClick` is not an
     * option for anything the driver must reach in motion, and the phone is
     * worse: picking it up to change a display mode is exactly the thing the
     * projected panel exists to avoid.
     *
     * The strip title is rebuilt on every `onGetTemplate`, so it names what
     * the button will do NEXT rather than what mode is current. A button that
     * cannot be looked at should still be predictable by memory.
     */
    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            // REQUIRED. NavigationTemplate.Builder.build() throws
            // IllegalStateException("Action strip for this template must be
            // set") without one -- it is not optional, and the failure only
            // shows up at runtime on the head unit. See
            // GaugeScreenTemplateTest, which pins this.
            .addAction(
                Action.Builder()
                    .setTitle(
                        if (renderer.centreContent == RadarLayout.CentreContent.RADAR) {
                            "PANEL"
                        } else "RADAR"
                    )
                    .setOnClickListener { toggleCentreContent() }
                    .build()
            )

        // The range button only exists while the scope does. An inert
        // control on a screen the driver cannot touch is worse than an
        // absent one -- it invites a glance that returns nothing.
        if (renderer.centreContent == RadarLayout.CentreContent.RADAR) {
            strip.addAction(
                Action.Builder()
                    .setTitle("${renderer.radarRangeMiles} MI")
                    .setOnClickListener { cycleRadarRange() }
                    .build()
            )
        }

        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setBackgroundColor(CarColor.PRIMARY)
            .build()
    }

    /**
     * Switch the centre column between the instrument panel and the radar.
     *
     * Written to `Prefs` immediately rather than on teardown: Android Auto
     * destroys and recreates this screen freely -- switching to Maps and back
     * does it -- and `onDestroy` is not a reliable place to persist anything
     * the driver just asked for.
     */
    private fun toggleCentreContent() {
        val next = renderer.centreContent.next()
        renderer.centreContent = next
        prefs.centreContent = next
        // Rebuilds the strip so the button names the NEXT action and the
        // range button appears or disappears with the scope.
        invalidate()
    }

    private fun cycleRadarRange() {
        val next = RadarLayout.nextRange(renderer.radarRangeMiles)
        renderer.radarRangeMiles = next
        prefs.radarRangeMiles = next
        invalidate()
    }

    /**
     * Build a panel snapshot and hand it to the renderer.
     *
     * Called from the frame clock, never from a sensor callback.
     *
     * Prefers live OBD telemetry from [TelemetryService]; falls back to a
     * fixed highway-cruise sample when the link is not live, so the panel
     * always renders something rather than a screen of dashes. The two are
     * never blended — [PanelState.linkState] says which is on screen, and a
     * DEMO badge appears whenever it is not the car.
     */
    private fun pushState() {
        val poller = TelemetryService.poller
        val now = System.currentTimeMillis()

        val live = if (poller.linkState == LinkState.LIVE) {
            TelemetryAssembler.assemble(
                poller.cursor,
                now,
                tankCapacityGallons = TANK_CAPACITY_GAL,
                // Grade comes from the PHONE barometer, never the car's.
                // PID 0133 is quantised to 1 kPa -- about 85 m per count --
                // so it cannot resolve a hill at all. The phone's bmp580
                // resolves centimetres.
                //
                // This also feeds the gravity-loss readout, which was
                // reading a constant zero while this was hardcoded.
                gradeRadians = imu.gradeRadians
            ).telemetry
        } else null

        val sample = live ?: sampleTelemetry()
        val isLive = live != null

        val airDensity = if (isLive) {
            TelemetryAssembler.airDensity(poller.cursor, now)
        } else DeltaVModel.RHO_SEA_LEVEL

        // Feed real road speed to the mount calibrator.
        //
        // It uses this to tell stopped from moving, which gates BOTH the
        // gravity average and the phone-has-slid check. Until the OBD link
        // existed there was no speed to give it, so it fell back to
        // gravity-steadiness alone -- which cannot distinguish a stopped car
        // from a smooth motorway cruise. With live speed the distinction is
        // exact.
        imu.speedMps = if (isLive) sample.speedMps else null

        // Feed the fuel tracker, then substitute its slosh-filtered figure
        // for the raw tank reading before the model sees it.
        val nowNanos = System.nanoTime()
        val fuelDt = if (lastFuelUpdateNanos == 0L) 0.0
        else (nowNanos - lastFuelUpdateNanos) / 1_000_000_000.0
        lastFuelUpdateNanos = nowNanos

        val tankFraction = if (isLive) {
            poller.cursor.fresh(ObdPid.FUEL_LEVEL, now)
                ?.let { ObdPid.decodeFuelLevelFraction(it.data) }
        } else null

        // Seeding takes the first agreeing readings whole; the slow filter
        // only takes over once there is something to correct.
        if (tankFraction != null && !fuelTracker.isSeeded) {
            fuelTracker.seed(tankFraction)
        }
        if (fuelDt > 0.0 && fuelDt < 2.0) {
            fuelTracker.update(
                fuelFlowKgPerSec = sample.fuelFlowKgPerSec,
                speedMps = sample.speedMps,
                dtSec = fuelDt,
                tankLevelFraction = if (fuelTracker.isSeeded) tankFraction else null
            )
        }

        // ONCE SEEDED, THE TRACKER IS THE AUTHORITY -- link state included.
        //
        // This used to require `isLive` as well, so a momentary link blip
        // handed the model the assembler's placeholder instead. That
        // placeholder was "half a tank" (TelemetryAssembler's old
        // `?: 0.5`), which on the 2026-08-22 evening drive threw delta-V
        // from ~4,000 to 7,273 m/s on 24 samples -- a +3,300 spike on a
        // gauge whose entire promise is that it only ever drains.
        //
        // The tracker integrates flow and survives a dropped link by
        // design; a stale reading of the real tank beats a fabricated one.
        // Its value is only wrong once the car has burned fuel the tracker
        // never saw, which needs the link down for minutes, not the
        // fraction of a second a blip lasts.
        val modelSample = if (fuelTracker.isSeeded) {
            sample.copy(fuelRemainingKg = fuelTracker.fuelKg)
        } else sample

        val readout = DeltaVModel.compute(car, modelSample, airDensity)

        // Latch the trip's STARTING budget, once, on the first live sample
        // with a settled fuel figure.
        //
        // Both conditions matter. Latching on app launch would capture a
        // demo frame or a pre-handshake sample; latching before the tracker
        // is seeded would capture the assembler's placeholder, which is the
        // same fabricated "half a tank" that produced the 7,273 m/s spikes.
        // Waiting for a seeded tracker means the reference is a real
        // reading of the real tank.
        if (tripStartDeltaV == null && isLive && fuelTracker.isSeeded &&
            readout.deltaVRemaining > 0.0
        ) {
            tripStartDeltaV = readout.deltaVRemaining
        }

        // Rolling median for the DISPLAYED Isp. The model keeps the raw
        // value; only the readout is smoothed.
        val rawIsp = readout.effectiveIsp
        if (rawIsp > 0.0) {
            ispWindow.addLast(rawIsp)
            while (ispWindow.size > ISP_WINDOW) ispWindow.removeFirst()
        } else {
            // A degenerate sample clears the window rather than dragging a
            // stale cruise figure into an idle readout.
            ispWindow.clear()
        }
        val smoothedIsp = if (ispWindow.isEmpty()) 0.0 else {
            val sorted = ispWindow.sorted()
            sorted[sorted.size / 2]
        }
        val shownReadout = readout.copy(effectiveIsp = smoothedIsp)

        val state = OperatingState.classify(
            rpm = sample.rpm,
            speedMps = sample.speedMps,
            fuelFlowKgPerSec = sample.fuelFlowKgPerSec,
            roadLoadNewtons = readout.roadLoadNewtons
        )

        val gear = DeltaVModel.inferGear(car, sample.speedMps, sample.rpm)
        val twr = Thrust.thrustToWeight(car, gear, sample.rpm, sample.fuelRemainingKg)
        val eta = Thermodynamics.thermalEfficiency(
            readout.roadLoadNewtons, sample.speedMps, sample.fuelFlowKgPerSec ?: 0.0
        )

        val nanos = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0
        else (nanos - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = nanos

        // Only bank a personal best on real data. A sample frame would set
        // an unbeatable record on first launch and permanently flatten the
        // one signal the efficiency bar exists to give.
        val isBest = if (isLive) record.update(readout.effectiveIsp, dt) else false

        val assessment = EfficiencyBand.Assessment(
            fill = EfficiencyBand.barFill(readout.effectiveIsp, sample.speedMps),
            inSweetSpot = EfficiencyBand.inSweetSpot(sample.rpm, null),
            isPersonalBest = isBest
        )

        // One row per second, rate-limited inside the recorder. Sampled
        // here rather than in the poller because this is where the assembled
        // Telemetry and the model output already exist together.
        recorder.sample(
            telemetry = live,
            readout = readout,
            state = state,
            cmdPerSec = poller.achievedRate,
            linkState = poller.linkState.name,
            gradeRadians = if (isLive) imu.gradeRadians else null,
            altitudeM = elevation.elevationM(imu.gradeEstimator.lastBarometricM)
                ?: imu.gradeEstimator.altitudeM,
            gpsAltFixes = imu.gpsAltitudeFixes,
            tripStartDeltaV = tripStartDeltaV,
            headingDegrees = imu.headingDegrees,
            mountState = imu.calibrator.state.name,
            busVolts = poller.cursor.fresh(ObdPid.CONTROL_MODULE_VOLTAGE, now)
                ?.let { ObdPid.decodeVoltage(it.data) },
            coolantC = poller.cursor.fresh(ObdPid.COOLANT_TEMP, now)
                ?.let { ObdPid.decodeCoolantTempC(it.data) },
            latitude = imu.latitude,
            longitude = imu.longitude,
            gpsNoAltFixes = imu.gpsFixesWithoutAltitude,
            baroAltM = imu.gradeEstimator.lastBarometricM,
            altBiasM = imu.gradeEstimator.biasM,
            gpsRejects = imu.rejectedTeleports + imu.rejectedRough,
            surveyedElevM = elevation.surveyedM,
            rollRadians = imu.attitude?.rollRadians,
            lateralG = imu.attitude?.lateralG,
            longitudinalG = imu.attitude?.longitudinalG
        )

        // RADAR -- fetched off-thread, drawn from whatever has arrived.
        //
        // Called every frame but returns immediately unless a fetch is due:
        // the picture only changes every few minutes and the fetcher is what
        // decides that, not this call site.
        if (renderer.centreContent == RadarLayout.CentreContent.RADAR) {
            radar.maybeFetch(imu.latitude, imu.longitude, renderer.radarRangeMiles)
        }

        // Surveyed elevation. Called every frame; returns immediately unless
        // the car has moved 100 m AND the rate limit allows -- USGS is a
        // free government endpoint and fourteen back-to-back calls were
        // measured to fail where the same fourteen spaced 1.2 s apart all
        // succeeded.
        elevation.maybeFetch(
            imu.latitude, imu.longitude, imu.gradeEstimator.lastBarometricM
        )
        renderer.radarBitmap = radar.bitmap
        renderer.radarFetchFailed = radar.lastFetchFailed

        renderer.update(
            PanelState(
                readout = shownReadout,
                efficiency = assessment,
                attitude = imu.attitude,
                speedMps = sample.speedMps,
                rpm = sample.rpm,
                gear = gear,
                twr = twr,
                totalMassKg = car.totalMassKg(sample.fuelRemainingKg),
                fuelGallons = Units.kgToGallons(modelSample.fuelRemainingKg),
                thermalEfficiency = eta,
                tripStartDeltaV = tripStartDeltaV,
                inFuelCutoff = readout.inDeceleratingFuelCutoff,
                isLive = isLive,
                linkState = if (isLive) LinkState.LIVE else poller.linkState,

                // A running MS-CAN capture takes over the status headline.
                //
                // The capture owns the socket, so isLive is false and the
                // panel would announce LINK LOST -- accurate, and no use to
                // the driver, who started the capture on purpose and wants to
                // know whether it is collecting. Same slot, shorter words.
                msCanBanner = MsCanStatusBridge.health?.let { h ->
                    MsCanBannerText(
                        label = MsCanBanner.label(h, MsCanStatusBridge.paired),
                        hint = MsCanBanner.hint(h),
                        isFault = MsCanBanner.isFault(h)
                    )
                },
                operatingState = state,
                fuelFlowKgPerSec = sample.fuelFlowKgPerSec,
                busVolts = if (isLive) {
                    poller.cursor.fresh(ObdPid.CONTROL_MODULE_VOLTAGE, now)
                        ?.let { ObdPid.decodeVoltage(it.data) }
                } else null,
                coolantC = if (isLive) {
                    poller.cursor.fresh(ObdPid.COOLANT_TEMP, now)
                        ?.let { ObdPid.decodeCoolantTempC(it.data) }
                } else null,
                gradeRadians = if (isLive) imu.gradeRadians else null,
                altitudeM = elevation.elevationM(imu.gradeEstimator.lastBarometricM)
                ?: imu.gradeEstimator.altitudeM,
                mountState = imu.calibrator.state,
                headingDegrees = imu.headingDegrees,
                hasLocationFix = RadarTile.isUsableFix(imu.latitude, imu.longitude)
            )
        )
    }

    /**
     * The fallback frame, chosen in the phone-side settings.
     *
     * Kept so the panel is never blank — on a desk, before the engine
     * starts, or if the dongle is unplugged. It is always labelled as DEMO
     * so it cannot be mistaken for a measurement.
     *
     * Selectable because four of the five operating states are otherwise
     * unreachable without driving. See [DemoFrame].
     */
    private fun sampleTelemetry(): Telemetry = demoFrame.telemetry()

    companion object {
        /**
         * Repaint interval. ~20 fps.
         *
         * A gauge read at arm's length gains nothing from more, and every
         * frame costs a full surface lock. Keeping this well below the sensor
         * rate is what prevents the main-thread saturation that caused the
         * original ANR.
         */
        const val FRAME_INTERVAL_MS = 50L

        /**
         * ND2 tank capacity, US gallons.
         *
         * PID 012F reports a percentage, not a volume, so the capacity has
         * to come from somewhere. The published figure for the ND is 11.9.
         */
        const val TANK_CAPACITY_GAL = 11.9

        /**
         * Samples in the Isp rolling median.
         *
         * 5 at 20 fps is a quarter-second — enough to kill single-frame
         * jitter, short enough that the readout still responds to the
         * right foot, which is the entire point of showing Isp.
         */
        const val ISP_WINDOW = 5
    }
}
