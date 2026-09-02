package dev.swordfish.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.swordfish.obd.DriveRecorder
import dev.swordfish.obd.MsCanSession
import dev.swordfish.obd.MsCanStatusBridge
import dev.swordfish.obd.ObdTransport
import dev.swordfish.obd.ImuSource
import dev.swordfish.physics.MsCanIdentify
import dev.swordfish.physics.MsCanProbe
import dev.swordfish.obd.ProbeRunner
import dev.swordfish.obd.TelemetryService
import dev.swordfish.physics.LinkState
import dev.swordfish.physics.PollSchedule
import dev.swordfish.physics.ProbeSession

/**
 * The bring-up probe screen.
 *
 * ## Why this is on the phone and not the head unit
 *
 * The head unit is one of the things under test. Debugging a link over that
 * same link is how a session ends with three broken things and no idea which
 * broke first. The probe runs entirely phone-side, before Android Auto is
 * plugged in at all — so a failure here is unambiguously the dongle, and a
 * success means the dongle is eliminated as a suspect for whatever the head
 * unit does next.
 *
 * Read the log on screen if it is convenient; the file is the real output.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var runButton: Button
    private lateinit var liveButton: Button
    private lateinit var liveStatus: TextView
    private var manualRecorder: DriveRecorder? = null

    private lateinit var msCanButton: Button
    private lateinit var wheelCalButton: Button
    private lateinit var msCanStatus: TextView
    private var msCanSession: MsCanSession? = null

    /**
     * The reference feed's own location subscription.
     *
     * Held so [onDestroy] can release it. Without this, leaving the
     * screen mid-capture leaves GPS running until the process dies.
     */
    private var msCanImu: ImuSource? = null

    private val ui = Handler(Looper.getMainLooper())
    private val lines = StringBuilder()
    private var selected: BluetoothDevice? = null
    private var running = false

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GROUND)
            setPadding(40, 64, 40, 48)
        }

        root.addView(heading("OBD PROBE", 24f))
        root.addView(spacer(6))
        root.addView(body(
            "Run this with the ignition ON and Android Auto UNPLUGGED. " +
                "Close the OBDLink app first — two apps cannot share one " +
                "SPP socket, and the second one to try gets a failure that " +
                "looks like a broken dongle.",
            11f
        ))

        root.addView(spacer(20))
        root.addView(heading("ADAPTER", 14f))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceList)

        root.addView(spacer(16))
        runButton = Button(this).apply {
            text = "RUN PROBE"
            textSize = 16f
            isAllCaps = true
            setTextColor(BRIGHT)
            setBackgroundColor(WELL)
            setPadding(24, 28, 24, 28)
            setOnClickListener { start() }
        }
        root.addView(runButton)

        root.addView(spacer(12))
        statusView = body("", 12f)
        root.addView(statusView)

        root.addView(spacer(24))
        root.addView(heading("LIVE DATA", 14f))
        root.addView(spacer(4))
        root.addView(body(
            "Connect the poller by hand, using the adapter picked above. " +
                "The car screen starts this automatically when it opens, but " +
                "that happens unattended \u2014 if the dongle was not ready, it " +
                "fails silently and the panel stays on demo data. This button " +
                "runs the same code with you watching, and says what happened.",
            11f
        ))
        root.addView(spacer(8))
        liveButton = Button(this).apply {
            text = "START LIVE DATA"
            textSize = 16f
            isAllCaps = true
            setTextColor(BRIGHT)
            setBackgroundColor(WELL)
            setPadding(24, 28, 24, 28)
            setOnClickListener { toggleLive() }
        }
        root.addView(liveButton)
        root.addView(spacer(8))
        liveStatus = body("", 12f, mono = true)
        root.addView(liveStatus)

        // --- MS-CAN capture ---
        root.addView(spacer(24))
        root.addView(heading("MS-CAN CAPTURE", 14f))
        root.addView(spacer(4))
        root.addView(body(
            "Records the vehicle bus for a whole drive, stamped with the yaw " +
                "rate the phone measures, so the bytes carrying yaw and " +
                "lateral acceleration can be identified by correlation. " +
                "Nothing on this bus is documented, so a normal drive is the " +
                "only way to find them — no car-park manoeuvre needed, but " +
                "the route must turn BOTH ways.\n\n" +
                "THIS REPLACES NORMAL TELEMETRY. One adapter, one socket: " +
                "while capture runs there is no rpm, speed, fuel flow or " +
                "delta-V. Raw frames are written to disk so the analysis can " +
                "be redone later without driving again.",
            11f
        ))
        root.addView(spacer(8))
        msCanButton = Button(this).apply {
            text = "START MS-CAN CAPTURE"
            textSize = 16f
            isAllCaps = true
            setTextColor(BRIGHT)
            setBackgroundColor(WELL)
            setPadding(24, 28, 24, 28)
            setOnClickListener { toggleMsCan() }
        }
        root.addView(msCanButton)

        // A SECOND button, not a mode switch on the first.
        //
        // Discovery and calibration are different jobs with different bus
        // loads, and the 2026-08-28 circle was lost to running the second
        // through the first. Two buttons make the choice explicit at the
        // moment it matters -- in the car, before pulling away -- rather
        // than hiding it in a setting that is easy to leave wrong.
        root.addView(spacer(8))
        wheelCalButton = Button(this).apply {
            text = "START WHEEL CALIBRATION"
            textSize = 16f
            isAllCaps = true
            setTextColor(BRIGHT)
            setBackgroundColor(WELL)
            setPadding(24, 28, 24, 28)
            setOnClickListener { toggleWheelCal() }
        }
        root.addView(wheelCalButton)
        root.addView(spacer(4))
        root.addView(body(
            "Filtered to the wheel-speed and vehicle-speed frames only. " +
                "Drive two or three steady laps of a traffic circle at " +
                "10-15 mph, one direction, then stop the capture.",
            11f
        ))

        root.addView(spacer(8))
        msCanStatus = body("", 12f, mono = true)
        root.addView(msCanStatus)

        root.addView(spacer(16))
        root.addView(heading("LOG", 14f))
        root.addView(spacer(4))
        logView = body("(not run yet)", 10f, mono = true)
        root.addView(logView)

        root.addView(spacer(20))
        root.addView(body(
            "Budget: ${"%.1f".format(PollSchedule.totalCmdPerSec)} cmd/s full, " +
                "${"%.1f".format(PollSchedule.degradedCmdPerSec)} degraded.",
            10f, mono = true
        ))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(GROUND)
            addView(root)
        })

        ensurePermissions()
        refreshDevices()
    }

    /**
     * Android 12+ needs BLUETOOTH_CONNECT granted at runtime before the
     * bonded-device list returns anything. Without it the list is silently
     * empty, which reads as "no dongle paired" and sends you back to the
     * settings app for no reason.
     */
    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val needed = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDevices()
    }

    override fun onDestroy() {
        // Release the reference feed's GPS subscription. The feed's own
        // Runnable stops itself when the session ends, but leaving the screen
        // mid-capture would otherwise keep location updates running for the
        // life of the process.
        msCanImu?.stopLocation()
        msCanImu = null

        // The status poll dies with the screen, so nothing would clear the
        // bridge if the activity goes away mid-capture -- leaving the panel
        // showing a frozen capture reading for the rest of the drive.
        if (msCanSession?.running != true) MsCanStatusBridge.clear()
        super.onDestroy()
    }

    private fun refreshDevices() {
        deviceList.removeAllViews()

        val a = adapter
        if (a == null) {
            deviceList.addView(body("No Bluetooth adapter on this device.", 12f))
            return
        }
        if (!a.isEnabled) {
            deviceList.addView(body("Bluetooth is off. Turn it on and reopen.", 12f))
            return
        }

        val bonded = try {
            a.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            deviceList.addView(body("Bluetooth permission not granted.", 12f))
            return
        }

        if (bonded.isEmpty()) {
            deviceList.addView(body(
                "Nothing paired. Pair the MX+ in Android Settings first.", 12f
            ))
            return
        }

        // Likely adapters float to the top, but everything is listed: the
        // name heuristic is a guess and the user knows their own dongle.
        val likely = bonded.filter { d ->
            val n = try { d.name ?: "" } catch (e: SecurityException) { "" }
            LIKELY.any { n.uppercase().contains(it) }
        }
        val rest = bonded - likely.toSet()

        if (selected == null) selected = likely.firstOrNull()

        for (d in likely + rest) {
            deviceList.addView(deviceButton(d, likely.contains(d)))
            deviceList.addView(spacer(4))
        }
    }

    private fun deviceButton(d: BluetoothDevice, likely: Boolean) = Button(this).apply {
        val name = try { d.name ?: "unnamed" } catch (e: SecurityException) { "unnamed" }
        text = "${if (selected == d) "●" else "○"}  $name" +
            (if (likely) "" else "   (probably not an adapter)")
        textSize = 13f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(if (likely) BRIGHT else MID)
        setBackgroundColor(if (selected == d) SELECTED else WELL)
        setOnClickListener {
            selected = d
            refreshDevices()
        }
    }

    /**
     * Start or stop the live poller by hand.
     *
     * ## Why a manual control exists at all
     *
     * The probe works reliably and the automatic poller did not, and the
     * difference is not the transport \u2014 it is the same `ObdTransport`
     * either way. It is the CONDITIONS:
     *
     *  - the probe runs when a person presses a button, with the adapter
     *    already awake, and prints its outcome on screen
     *  - the poller runs unattended the instant the car screen opens,
     *    possibly before the dongle has finished waking, with its result
     *    visible only as demo data on a panel
     *
     * A manual start removes every one of those differences, which makes it
     * both a workaround and a diagnostic: if this succeeds where the
     * automatic path failed, timing is the problem, not the code.
     */
    /**
     * Start or stop an MS-CAN capture.
     *
     * Deliberately manual. The capture takes the socket, so it cannot run
     * alongside normal telemetry -- see MsCanSession. Making it a button the
     * driver presses is the honest way to express "this costs you the gauge".
     */
    private fun toggleMsCan() = toggleCapture(MsCanProbe.CaptureMode.DISCOVERY)

    /**
     * Start or stop a wheel-calibration capture.
     *
     * Same machinery as [toggleMsCan]; the only difference is the mode,
     * which decides how much of the bus the adapter forwards.
     */
    private fun toggleWheelCal() =
        toggleCapture(MsCanProbe.CaptureMode.WHEEL_CALIBRATION)

    /** Reset both buttons to their idle captions. */
    private fun resetCaptureButtons() {
        msCanButton.text = "START MS-CAN CAPTURE"
        wheelCalButton.text = "START WHEEL CALIBRATION"
    }

    private fun toggleCapture(mode: MsCanProbe.CaptureMode) {
        val session = msCanSession
        if (session != null && session.running) {
            // Either button stops a running capture. Whichever one started
            // it, stopping is unambiguous and the driver should not have to
            // remember which they pressed.
            session.stop()
            resetCaptureButtons()
            append("MS-CAN capture stopped by request")
            showMsCanResults(session)
            return
        }

        val device = selected
        if (device == null) {
            msCanStatus.text = "Pick an adapter above first."
            return
        }
        if (running) {
            msCanStatus.text =
                "The probe is running. Wait for it to finish — they cannot " +
                    "share the socket."
            return
        }

        // External app storage, NOT internal filesDir. A capture that cannot
        // be pulled off the phone is a capture that never happened: internal
        // storage is unreachable on a Play Store build (run-as is refused on a
        // non-debuggable install, adb backup returns an empty tar on Android
        // 12+, and no file manager can cross the UID boundary). This matches
        // DriveRecorder and ProbeRunner, which have always written external.
        val fresh = MsCanSession(ObdTransport(), getExternalFilesDir(null) ?: filesDir)
        msCanSession = fresh
        fresh.start(device, adapter, MS_CAN_SESSION_MS, mode)
        if (mode == MsCanProbe.CaptureMode.WHEEL_CALIBRATION) {
            wheelCalButton.text = "STOP WHEEL CALIBRATION"
            append(
                "Wheel calibration started -- filtered to " +
                    MsCanProbe.CALIBRATION_IDS.joinToString("/") +
                    ". Drive steady laps in ONE direction."
            )
        } else {
            msCanButton.text = "STOP MS-CAN CAPTURE"
            append(
                "MS-CAN capture started (up to ${MS_CAN_SESSION_MS / 60000} min)"
            )
        }

        // The reference signal. Location updates are already running for the
        // radar and altitude; this reuses them rather than opening a second
        // subscription.
        startMsCanReference(fresh)
        pollMsCanStatus(fresh)
    }

    /**
     * Feed yaw rate into the capture from successive GPS bearings.
     *
     * Crude, and deliberately so: bearing is quantised and noisy, but a real
     * corner produces a yaw rate an order of magnitude above that noise. The
     * alternative -- a phone gyro -- drifts and depends on how the phone is
     * lying, which is the whole problem this is meant to solve.
     */
    /**
     * Speed above which a GPS bearing is meaningful.
     *
     * Mirrors ImuSource.MIN_BEARING_SPEED_MPS, whose companion is private.
     * Only used to EXPLAIN a stalled capture, never to gate one, so a small
     * drift between the two would cost nothing.
     */
    private val MOVING_MPS = 2.0

    private fun startMsCanReference(session: MsCanSession) {
        val imu = ImuSource(this)
        imu.startLocation()
        msCanImu = imu

        // Uses COURSE OVER GROUND, not headingDegrees.
        //
        // headingDegrees is written only from the rotation-vector SENSOR
        // callback, so it is null unless start() has registered the sensors.
        // This method used to read it after calling startLocation() alone,
        // which meant the reference was permanently null: on the 2026-08-26
        // drive both capture attempts dropped every frame (735 and 1385,
        // droppedNoRef) and wrote no file, while the drive log recorded a
        // heading on 99.9% of samples from its own fully-started ImuSource.
        //
        // Course over ground comes straight off the location fix, so the
        // capture no longer depends on sensors it does not need. It is also
        // the more correct signal: yaw rate is about where the CAR is going,
        // not where the phone is pointing.
        var lastBearing: Double? = null
        var lastAt = 0L
        var refCount = 0

        val tick = object : Runnable {
            override fun run() {
                if (!session.running) {
                    imu.stopLocation()
                    if (msCanImu === imu) msCanImu = null
                    Log.i(
                        "SwordfishMsCan",
                        "reference feed stopped after $refCount updates"
                    )
                    return
                }
                val b = imu.courseOverGroundDegrees
                val now = System.currentTimeMillis()

                // Report movement EVERY tick, including ticks with no
                // bearing -- that is the case it exists to explain. Without
                // it, "parked" and "broken" produce identical output, and
                // mistaking the second for the first is what cost the
                // 2026-08-26 drive.
                val speed = imu.freshGpsSpeedMps
                session.onMoving(speed != null && speed >= MOVING_MPS)

                if (b != null && lastBearing != null && lastAt > 0) {
                    val dt = (now - lastAt) / 1000.0
                    MsCanIdentify.yawRateFromBearings(lastBearing, b, dt)
                        ?.let {
                            session.onReference(it, now)
                            refCount++
                        }
                }
                if (b != null) { lastBearing = b; lastAt = now }
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    private fun pollMsCanStatus(session: MsCanSession) {
        val tick = object : Runnable {
            override fun run() {
                msCanStatus.text = session.status()

                // Mirror the verdict to the car panel.
                //
                // While a capture runs the phone owns the socket, so the panel
                // has no telemetry and announces LINK LOST -- true, and
                // useless. It now reports the capture in that same headline,
                // so "is it even running?" is answerable without picking up
                // the phone at the wheel.
                if (session.running) {
                    MsCanStatusBridge.publish(
                        session.capture.health(
                            System.currentTimeMillis(),
                            session.moving
                        ),
                        session.capture.size
                    )
                    ui.postDelayed(this, 1000)
                } else {
                    // Every stop path clears it. A stopped capture that kept
                    // publishing would leave the panel reporting a capture
                    // that is not running -- worse than never showing it.
                    MsCanStatusBridge.clear()
                    resetCaptureButtons()
                    showMsCanResults(session)
                }
            }
        }
        ui.post(tick)
    }

    /**
     * Report what the capture found.
     *
     * The verdict is deliberately cautious: a strong correlation over one
     * drive is suggestive, not proven, and the honest next step is a second
     * drive that the same bytes also predict.
     */
    private fun showMsCanResults(session: MsCanSession) {
        val candidates = session.analyse()
        append("MS-CAN capture finished: ${session.capture.summary()}")
        session.rawPath()?.let { append("raw capture: $it") }
        append(MsCanIdentify.verdict(candidates))
        for (c in candidates.take(5)) append("  ${c.describe()}")
        msCanStatus.text = session.status() + "\n\n" +
            MsCanIdentify.verdict(candidates)
    }

    private fun toggleLive() {
        val poller = TelemetryService.poller

        if (poller.linkState == LinkState.LIVE ||
            poller.linkState == LinkState.HANDSHAKE ||
            poller.linkState == LinkState.CAPABILITIES
        ) {
            poller.stop()
            liveButton.text = "START LIVE DATA"
            manualRecorder?.stop()
            val saved = manualRecorder?.rowCount ?: 0
            manualRecorder = null
            liveStatus.text = "stopped. $saved rows recorded."
            return
        }

        val device = selected
        if (device == null) {
            liveStatus.text = "Pick an adapter above first."
            return
        }

        // Record from the manual path too. The car screen has its own
        // recorder, but the whole point of this button is testing WITHOUT
        // the head unit -- so it needs its own file or those sessions
        // leave no trace.
        manualRecorder = DriveRecorder(this).also { it.start() }

        liveStatus.text = "connecting\u2026"
        liveButton.text = "CONNECTING\u2026"
        poller.start(device, adapter)

        // Poll the link state rather than blocking: start() returns
        // immediately and the handshake happens on its own thread.
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val st = poller.linkState
                val secs = (System.currentTimeMillis() - started) / 1000
                liveStatus.text = buildString {
                    appendLine("state    ${st.name}  (${secs}s)")
                    appendLine("hint     ${st.hint}")
                    if (poller.achievedRate > 0.0) {
                        appendLine("rate     ${"%.1f".format(poller.achievedRate)} cmd/s")
                    }
                    if (poller.isDegraded) appendLine("schedule DEGRADED")
                }

                when {
                    st == LinkState.LIVE -> {
                        liveButton.text = "STOP LIVE DATA"
                        // Keep updating so the rate is visible while driving.
                        ui.postDelayed(this, 1000)
                    }
                    st == LinkState.NO_ADAPTER || st == LinkState.LOST -> {
                        liveButton.text = "START LIVE DATA"
                    }
                    secs > 90 -> {
                        liveButton.text = "START LIVE DATA"
                        liveStatus.text = "gave up after 90s in state ${st.name}"
                    }
                    else -> ui.postDelayed(this, 500)
                }
            }
        }
        ui.post(tick)
    }

    private fun start() {
        if (running) return
        val device = selected
        if (device == null) {
            statusView.text = "Pick an adapter first."
            return
        }

        running = true
        runButton.isEnabled = false
        runButton.text = "RUNNING…"
        lines.setLength(0)
        append("probe starting — this takes about 20 seconds")

        // The probe blocks on socket I/O throughout. It cannot run on the
        // main thread, and the rate test in particular runs a tight
        // request/reply loop for ten seconds straight.
        Thread {
            val runner = ProbeRunner(this) { msg -> ui.post { append(msg) } }
            try {
                runner.run(device, adapter)
            } catch (e: Exception) {
                ui.post { append("probe threw: ${e.javaClass.simpleName}: ${e.message}") }
            }
            ui.post { finished(runner) }
        }.start()
    }

    private fun finished(runner: ProbeRunner) {
        running = false
        runButton.isEnabled = true
        runButton.text = "RUN PROBE"

        statusView.text = buildString {
            val rate = runner.rateResult
            if (rate != null) {
                appendLine(rate.describe())
            }
            val sweep = runner.sweepResult
            if (sweep != null) {
                appendLine(sweep.describe())
            }
            appendLine()
            appendLine("Logs: ${runner.logDirectory()}")
            appendLine("adb pull that directory to read them at a desk.")
        }
    }

    private fun append(msg: String) {
        lines.append(msg).append('\n')
        logView.text = lines.toString()
    }

    // --- widgets ---

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(BRIGHT)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.15f
    }

    private fun body(text: String, size: Float, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(MID)
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun spacer(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * resources.displayMetrics.density).toInt()
        )
    }

    private companion object {
        /**
         * Longest an MS-CAN capture runs before stopping itself.
         *
         * A whole coffee drive, not the probe's eight seconds -- the
         * correlation needs a route with corners in both directions, and
         * more samples only ever help. The button stops it early.
         */
        const val MS_CAN_SESSION_MS = 45 * 60 * 1000L

        val GROUND = Color.rgb(0x03, 0x05, 0x04)
        val WELL = Color.rgb(0x0A, 0x0D, 0x0B)
        val SELECTED = Color.rgb(0x0E, 0x1C, 0x12)
        val BRIGHT = Color.rgb(0x6C, 0xFF, 0xA0)
        val MID = Color.rgb(0x3C, 0x9C, 0x62)

        val LIKELY = listOf("OBD", "ELM", "VLINK", "VEEPEAK", "SCAN")
    }
}
