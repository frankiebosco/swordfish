package dev.swordfish.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.swordfish.physics.AdultAverage
import dev.swordfish.physics.DeltaVModel
import dev.swordfish.physics.DisplayTheme
import dev.swordfish.physics.OrbitalScale
import dev.swordfish.physics.PollSchedule
import dev.swordfish.physics.Telemetry
import dev.swordfish.physics.Thermodynamics
import dev.swordfish.physics.Thrust
import dev.swordfish.physics.UnitSystem
import dev.swordfish.physics.Units
import dev.swordfish.physics.Vehicle

/**
 * Phone-side settings and diagnostics.
 *
 * The head unit is a read-only instrument — surface interaction is limited to
 * click/scale/scroll/fling — so all real configuration has to live here. See
 * `docs/INSTRUMENT_PANEL.md`.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GROUND)
            setPadding(48, 72, 48, 48)
        }

        root.addView(heading("SWORDFISH", 30f))
        root.addView(spacer(24))

        root.addView(heading("DISPLAY", 15f))
        root.addView(spacer(8))
        for (t in DisplayTheme.entries) {
            root.addView(themeButton(t))
            root.addView(spacer(6))
        }

        root.addView(spacer(14))
        root.addView(toggle("Ghost segments", prefs.ghostSegments) {
            prefs.ghostSegments = it
        })
        root.addView(toggle("Scanlines", prefs.scanlines) {
            prefs.scanlines = it
        })

        root.addView(spacer(10))
        root.addView(body("Changes apply when the car screen is next opened.", 11f))

        root.addView(spacer(28))
        root.addView(heading("CREW & CARGO", 15f))
        root.addView(spacer(4))
        root.addView(body(
            "Mass affects the delta-V budget. Standard figures are offered so " +
                "nobody has to state their weight; exact entry is there if you " +
                "want the accuracy. A 30 lb difference moves delta-V by under 2%.",
            11f
        ))
        root.addView(spacer(12))

        root.addView(occupantSection(
            title = "DRIVER",
            isExact = prefs.driverIsExact,
            average = prefs.driverAverage ?: AdultAverage.ADULT,
            exactLb = prefs.driverExactLb,
            onExactMode = { prefs.driverIsExact = it; recreate() },
            onAverage = { prefs.driverAverage = it; recreate() },
            onExactValue = { prefs.driverExactLb = it }
        ))

        root.addView(spacer(16))
        root.addView(toggle("Passenger aboard", prefs.hasPassenger) {
            prefs.hasPassenger = it
            recreate()
        })
        if (prefs.hasPassenger) {
            root.addView(occupantSection(
                title = "PASSENGER",
                isExact = prefs.passengerIsExact,
                average = prefs.passengerAverage ?: AdultAverage.ADULT,
                exactLb = prefs.passengerExactLb,
                onExactMode = { prefs.passengerIsExact = it; recreate() },
                onAverage = { prefs.passengerAverage = it; recreate() },
                onExactValue = { prefs.passengerExactLb = it }
            ))
        } else {
            root.addView(body("Flying solo.", 12f))
        }

        root.addView(spacer(16))
        root.addView(body("CARGO (lb)  0 = empty boot", 12f))
        root.addView(numberField(prefs.cargoLb) { prefs.cargoLb = it })

        root.addView(spacer(12))
        root.addView(body("Total payload: ${"%.0f".format(prefs.buildPayload().totalLb)} lb", 13f))

        // The DEMO FRAME picker stood here until 2026-08-24. The phone app
        // is a LOGBOOK now, and a control for what the car screen shows at a
        // desk did not belong beside that.
        //
        // `DemoFrame` itself is deliberately KEPT: it feeds the desktop
        // layout harness its realistic panel state, and deleting it would
        // take the preview tool's fixtures with it. `Prefs.demoFrame` is
        // still read by GaugeScreen, so a stored value keeps working; there
        // is simply no longer a UI to change it.

        root.addView(spacer(28))
        root.addView(heading("UNITS", 15f))
        root.addView(spacer(4))
        root.addView(body(
            "Which units the logbook and the radar range label use. The " +
                "instrument panel is metric throughout — delta-V is quoted in " +
                "m/s and always will be, whatever this says.",
            11f
        ))
        root.addView(spacer(8))
        for (sys in UnitSystem.entries) {
            root.addView(unitButton(sys))
            root.addView(spacer(6))
        }

        root.addView(spacer(28))
        root.addView(heading("LOGBOOK", 15f))
        root.addView(spacer(4))
        root.addView(body(
            "Every drive is recorded. Distance, economy, the delta-V it cost, " +
                "and a route retrace coloured by how efficiently each stretch " +
                "was driven. For reading afterwards — never while driving.",
            11f
        ))
        root.addView(spacer(8))
        root.addView(Button(this).apply {
            text = "OPEN LOGBOOK"
            textSize = 15f
            isAllCaps = true
            setTextColor(themeColour(prefs.displayTheme.bright))
            setBackgroundColor(WELL)
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LogbookActivity::class.java))
            }
        })

        root.addView(spacer(28))
        root.addView(heading("HARDWARE", 15f))
        root.addView(spacer(4))
        root.addView(body(
            "Bring-up probe for the OBD adapter. Run it with the ignition on " +
                "and Android Auto unplugged, so a failure means the dongle " +
                "rather than one of three things at once.",
            11f
        ))
        root.addView(spacer(8))
        root.addView(Button(this).apply {
            text = "OBD PROBE"
            textSize = 15f
            isAllCaps = true
            setTextColor(themeColour(prefs.displayTheme.bright))
            setBackgroundColor(WELL)
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ProbeActivity::class.java))
            }
        })

        root.addView(spacer(28))
        root.addView(heading("MODEL SELF-TEST", 15f))
        root.addView(spacer(8))
        root.addView(body(diagnostics(), 13f, mono = true))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(GROUND)
            addView(root)
        })

        requestBluetoothIfNeeded()
    }

    /**
     * Ask for the runtime Bluetooth permission on first launch.
     *
     * Not merely for the probe screen: the telemetry foreground service is
     * `connectedDevice` type, and Android requires BLUETOOTH_CONNECT to be
     * *granted* before `startForeground` will succeed. Without it the
     * service throws and the head-unit panel goes black — so the permission
     * has to be secured from the app's ordinary entry point, not only from
     * the diagnostic one.
     */
    private fun requestBluetoothIfNeeded() {
        val wanted = mutableListOf<String>()

        // The granular BLUETOOTH_* permissions exist only from API 31. On
        // older devices the legacy install-time BLUETOOTH permission covers
        // it and requesting these would be requesting something undefined.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_SCAN
        }

        // Location is for GPS ALTITUDE, not position tracking: it pins the
        // barometer's absolute reference, which is otherwise the standard
        // atmosphere and can be ~200 m out on a low-pressure day. The app
        // is fully functional without it -- only the absolute altitude
        // figure degrades, and grade and relative altitude are unaffected.
        //
        // COARSE goes with FINE because Android 12+ lets the user grant
        // only COARSE, and a coarse fix still carries an altitude.
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        wanted += Manifest.permission.ACCESS_COARSE_LOCATION

        val needed = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_BLUETOOTH)
        }
    }

    // --- widgets ---

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(themeColour(prefs.displayTheme.bright))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.15f
    }

    private fun body(text: String, size: Float, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(themeColour(prefs.displayTheme.mid))
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun spacer(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * resources.displayMetrics.density).toInt()
        )
    }


    private fun unitButton(sys: UnitSystem) = Button(this).apply {
        val selected = prefs.unitSystem == sys
        text = if (selected) "▸ ${sys.label.uppercase()}" else sys.label.uppercase()
        textSize = 14f
        isAllCaps = false
        setTextColor(
            if (selected) themeColour(prefs.displayTheme.bright) else Color.rgb(0x50, 0x70, 0x5C)
        )
        setBackgroundColor(WELL)
        setPadding(24, 20, 24, 20)
        setOnClickListener {
            prefs.unitSystem = sys
            recreate()
        }
    }

    private fun themeButton(t: DisplayTheme) = Button(this).apply {
        text = if (prefs.displayTheme == t) "●  ${t.label}" else "○  ${t.label}"
        textSize = 15f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        isAllCaps = false
        setTextColor(themeColour(t.bright))
        setBackgroundColor(
            if (prefs.displayTheme == t) themeColour(t.ghost) else WELL
        )
        setOnClickListener {
            prefs.displayTheme = t
            recreate()
        }
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            textSize = 14f
            isChecked = initial
            setTextColor(themeColour(prefs.displayTheme.mid))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    /**
     * One occupant: standard build or exact weight.
     *
     * Neither entry path is privileged — see `Payload.kt`. The averages exist
     * so a passenger never has to disclose a figure, and nothing here nudges
     * toward exactness.
     */
    private fun occupantSection(
        title: String,
        isExact: Boolean,
        average: AdultAverage,
        exactLb: Double,
        onExactMode: (Boolean) -> Unit,
        onAverage: (AdultAverage) -> Unit,
        onExactValue: (Double) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(body(title, 12f))

        addView(CheckBox(this@MainActivity).apply {
            text = "Enter an exact weight"
            textSize = 13f
            isChecked = isExact
            setTextColor(themeColour(prefs.displayTheme.mid))
            setOnCheckedChangeListener { _, checked -> onExactMode(checked) }
        })

        if (isExact) {
            addView(numberField(exactLb, onExactValue))
        } else {
            for (a in AdultAverage.entries) {
                addView(Button(this@MainActivity).apply {
                    text = "${if (average == a) "●" else "○"}  ${a.label} " +
                        "(${a.pounds.toInt()} lb)"
                    textSize = 13f
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(themeColour(prefs.displayTheme.mid))
                    setBackgroundColor(
                        if (average == a) themeColour(prefs.displayTheme.ghost) else WELL
                    )
                    setOnClickListener { onAverage(a) }
                })
            }
        }
    }

    private fun numberField(initial: Double, onChange: (Double) -> Unit) =
        EditText(this).apply {
            setText("%.0f".format(initial))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(themeColour(prefs.displayTheme.bright))
            setBackgroundColor(WELL)
            setPadding(24, 20, 24, 20)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    // Ignore unparseable input rather than zeroing the value:
                    // a half-typed entry should not silently become 0 lb.
                    text.toString().toDoubleOrNull()?.let(onChange)
                }
            }
        }

    private fun themeColour(rgb: Int): Int =
        Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    /**
     * Runs the real model against a highway-cruise sample, confirming
     * `:physics` is correctly linked into the Android build.
     */
    private fun diagnostics(): String {
        // Use the payload the user actually configured, so the self-test
        // reflects their car rather than a default.
        val car = Vehicle.ND2_CLUB.copy(payload = prefs.buildPayload())
        val speed = Units.mphToMps(65.0)
        val galPerSec = Units.metersToMiles(speed) / 34.0
        val flow = Units.gallonsToKg(galPerSec)
        val sample = Telemetry(
            speedMps = speed,
            rpm = 2661.0,
            fuelFlowKgPerSec = flow,
            fuelRemainingKg = Units.gallonsToKg(11.9 * 0.831),
            gradeRadians = 0.0
        )
        val r = DeltaVModel.compute(car, sample)
        val gear = DeltaVModel.inferGear(car, speed, 2661.0)
        val twr = Thrust.thrustToWeight(car, gear, sample.rpm, sample.fuelRemainingKg)
        val eta = Thermodynamics.thermalEfficiency(r.roadLoadNewtons, speed, flow)

        return buildString {
            appendLine("65 mph cruise")
            appendLine()
            appendLine("dv          ${"%.0f".format(r.deltaVRemaining)} m/s")
            appendLine("  orbital   ${OrbitalScale.label(r.deltaVRemaining)}")
            appendLine("Isp         ${"%.0f".format(r.effectiveIsp)} s")
            appendLine("road load   ${"%.0f".format(r.roadLoadNewtons)} N")
            appendLine("gear        ${gear ?: "--"}")
            appendLine("TWR         ${"%.2f".format(twr)}")
            appendLine("thermal eff ${"%.0f".format(eta * 100)}%")
            appendLine()
            appendLine("payload     ${car.payload.describe()}")
            appendLine("poll budget ${"%.1f".format(PollSchedule.totalCmdPerSec)} cmd/s")
        }
    }

    private companion object {
        const val REQ_BLUETOOTH = 2
        val GROUND = Color.rgb(0x03, 0x05, 0x04)
        val WELL = Color.rgb(0x0A, 0x0D, 0x0B)
    }
}
