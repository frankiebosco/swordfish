package dev.swordfish.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.swordfish.physics.DisplayUnits
import dev.swordfish.physics.DriveLog
import dev.swordfish.physics.UnitSystem
import dev.swordfish.physics.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The logbook: every recorded drive, newest first.
 *
 * ## Why this is a phone screen and not a head-unit one
 *
 * Reading back a drive is a parked activity. The head unit exists to be
 * glanced at while driving; a directory of past journeys is the opposite of
 * that, and putting it there would invite exactly the behaviour the
 * instrument was designed to avoid.
 */
class LogbookActivity : AppCompatActivity() {

    private lateinit var library: DriveLibrary

    /** Read once per render so every row agrees. */
    private var units: UnitSystem = UnitSystem.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = DriveLibrary(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // A drive may have been deleted from the detail screen.
        render()
    }

    private fun render() {
        units = Prefs(this).unitSystem
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(GROUND)
            setPadding(48, 72, 48, 48)
        }

        root.addView(heading("LOGBOOK", 30f))
        root.addView(spacer(6))

        val entries = library.list()
        val totalMb = library.totalBytes() / 1_048_576.0

        if (entries.isEmpty()) {
            root.addView(body(
                "No drives recorded yet.\n\nA recording starts when the panel " +
                    "opens on the head unit and ends when it closes. Drives " +
                    "shorter than " + DriveLibrary.MIN_ROWS_TO_LIST +
                    " seconds are not listed.",
                13f
            ))
        } else {
            val totalMeters = entries.sumOf { it.summary?.distanceMeters ?: 0.0 }
            val total = DisplayUnits.distance(totalMeters, units)
            root.addView(body(
                "${entries.size} drives · ${total.value} ${total.unit} · " +
                    "${"%.1f".format(totalMb)} MB",
                12f
            ))
            root.addView(spacer(18))

            for (e in entries) {
                root.addView(driveCard(e))
                root.addView(spacer(10))
            }
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(GROUND)
            addView(root)
        })
    }

    /** One drive, as a tappable summary card. */
    private fun driveCard(e: DriveLibrary.Entry): View {
        val s = e.summary
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WELL)
            setPadding(28, 24, 28, 24)
            isClickable = true
            setOnClickListener {
                startActivity(
                    Intent(this@LogbookActivity, DriveDetailActivity::class.java)
                        .putExtra(DriveDetailActivity.EXTRA_PATH, e.file.absolutePath)
                )
            }
        }

        card.addView(TextView(this).apply {
            text = prettyDate(e.file.name)
            textSize = 16f
            setTextColor(PHOSPHOR)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        if (s == null) {
            card.addView(body("unreadable", 12f))
            return card
        }

        card.addView(spacer(6))
        card.addView(TextView(this).apply {
            val d = DisplayUnits.distance(s.distanceMeters, units)
            val econ = DisplayUnits.economy(s.distanceMeters, s.fuelUsedKg, units)
            text = "${d.value} ${d.unit}   " +
                "${s.durationMs / 60000} min   " +
                (econ?.toString() ?: "—")
            textSize = 15f
            setTextColor(BRIGHT)
            typeface = Typeface.MONOSPACE
        })

        s.deltaVSpentMps?.let {
            card.addView(TextView(this).apply {
                text = "Δv spent  ${"%.0f".format(it)} m/s"
                textSize = 12f
                setTextColor(DIM)
                typeface = Typeface.MONOSPACE
            })
        }

        // A drive that ended in a crash is worth SEEING as one -- it is the
        // difference between "I drove 8 miles" and "the app died at mile 8".
        if (!s.endedCleanly || s.wasResumed) {
            card.addView(spacer(4))
            card.addView(TextView(this).apply {
                text = when {
                    s.wasResumed && !s.endedCleanly -> "⚠ interrupted, resumed, then cut short"
                    s.wasResumed -> "⚠ resumed after an interruption"
                    else -> "⚠ ended unexpectedly — app closed mid-drive"
                }
                textSize = 11f
                setTextColor(WARN)
            })
        }

        return card
    }

    /** `drive-20260824-131001.ndjson` -> `Sun 24 Aug, 13:10`. */
    private fun prettyDate(fileName: String): String {
        val stamp = fileName.removePrefix("drive-").removeSuffix(".ndjson")
        return try {
            val d = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp)
            SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(d!!)
        } catch (e: Exception) {
            stamp
        }
    }

    // --- widgets, matching MainActivity's house style ---

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(PHOSPHOR)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.18f
    }

    private fun body(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(DIM)
    }

    private fun spacer(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * resources.displayMetrics.density).toInt()
        )
    }

    internal companion object {
        val GROUND = Color.rgb(0x03, 0x05, 0x04)
        val WELL = Color.rgb(0x0A, 0x0D, 0x0B)
        val PHOSPHOR = Color.rgb(0x39, 0xE0, 0x7A)
        val BRIGHT = Color.rgb(0xC8, 0xFF, 0xDC)
        val DIM = Color.rgb(0x50, 0x70, 0x5C)
        val WARN = Color.rgb(0xFF, 0xB0, 0x30)
    }
}
