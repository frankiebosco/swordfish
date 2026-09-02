package dev.swordfish.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.swordfish.physics.DisplayUnits
import dev.swordfish.physics.DriveCharts
import dev.swordfish.physics.DriveLog
import dev.swordfish.physics.DriveTrack
import dev.swordfish.physics.Units
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One drive, expanded: the numbers, then the retrace.
 *
 * ## What goes at the top
 *
 * Distance, duration and mpg — the three a driver actually wants. The
 * orbital figures come next because they are what the app is *for*, and the
 * diagnostic rows last because they answer "was the recording any good"
 * rather than "how was the drive".
 */
class DriveDetailActivity : AppCompatActivity() {

    private lateinit var file: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) { finish(); return }
        file = File(path)
        if (!file.isFile) { finish(); return }
        render()
    }

    private fun render() {
        val lines = try { file.readLines() } catch (e: Exception) { emptyList() }
        val summary = DriveLog.summarise(lines)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LogbookActivity.GROUND)
            setPadding(48, 72, 48, 48)
        }

        root.addView(heading(prettyDate(file.name), 22f))
        root.addView(spacer(4))
        root.addView(body(file.name, 11f, mono = true))
        root.addView(spacer(20))

        if (summary == null) {
            root.addView(body("This recording holds no usable samples.", 13f))
            root.addView(spacer(20))
            root.addView(deleteButton())
            setContentView(ScrollView(this).apply {
                setBackgroundColor(LogbookActivity.GROUND)
                addView(root)
            })
            return
        }

        // --- headline ---
        val u = Prefs(this).unitSystem
        val dist = DisplayUnits.distance(summary.distanceMeters, u)
        root.addView(bigStat("${dist.value} ${dist.unit}"))
        root.addView(spacer(14))

        if (!summary.endedCleanly || summary.wasResumed) {
            root.addView(warnBox(summary))
            root.addView(spacer(14))
        }

        // --- the drive ---
        root.addView(sectionHeading("THE DRIVE"))
        root.addView(statRow("Duration", fmtDuration(summary.durationMs)))
        root.addView(statRow("Moving", fmtDuration((summary.movingSeconds * 1000).toLong())))
        root.addView(statRow("Stopped", fmtDuration((summary.idleSeconds * 1000).toLong())))
        root.addView(statRow(
            "Coasting (DFCO)", fmtDuration((summary.dfcoSeconds * 1000).toLong())
        ))
        root.addView(statRow(
            "Mean speed",
            DisplayUnits.speed(summary.meanMovingSpeedMps, u).toString()
        ))
        root.addView(statRow(
            "Max speed", DisplayUnits.speed(summary.maxSpeedMps, u).toString()
        ))
        root.addView(statRow("Max rpm", "${"%.0f".format(summary.maxRpm)}"))

        // --- fuel ---
        root.addView(spacer(18))
        root.addView(sectionHeading("FUEL"))
        root.addView(statRow(
            "Used", DisplayUnits.fuelVolume(summary.fuelUsedKg, u).toString()
        ))
        // Economy INVERTS between systems: mpg counts distance per volume,
        // L/100km counts volume per distance. Same number with a different
        // unit would be wrong, not merely unconverted.
        root.addView(statRow(
            "Economy",
            DisplayUnits.economy(summary.distanceMeters, summary.fuelUsedKg, u)
                ?.toString() ?: "—"
        ))
        summary.meanIspS?.let {
            root.addView(statRow("Mean Isp", "${"%.0f".format(it)} s"))
        }

        // --- the budget ---
        root.addView(spacer(18))
        root.addView(sectionHeading("DELTA-V BUDGET"))
        if (summary.deltaVSpentMps != null) {
            root.addView(statRow(
                "Spent", "${"%.0f".format(summary.deltaVSpentMps)} m/s"
            ))
            root.addView(statRow(
                "Started at", "${"%.0f".format(summary.deltaVStartMps)} m/s"
            ))
            root.addView(statRow(
                "Ended at", "${"%.0f".format(summary.deltaVEndMps)} m/s"
            ))
        } else {
            root.addView(body(
                "No trip reference: the fuel tracker had not seeded from the " +
                    "tank sensor before this drive ended.",
                11f
            ))
        }

        // --- conditions ---
        if (summary.minAltitudeM != null || summary.maxCoolantC != null) {
            root.addView(spacer(18))
            root.addView(sectionHeading("CONDITIONS"))
            val minAlt = summary.minAltitudeM
            val maxAlt = summary.maxAltitudeM
            if (minAlt != null && maxAlt != null) {
                val lo = DisplayUnits.altitude(minAlt, u)
                val hi = DisplayUnits.altitude(maxAlt, u)
                root.addView(statRow(
                    "Altitude", "${lo.value} to ${hi.value} ${hi.unit}"
                ))
            }
            summary.maxCoolantC?.let {
                root.addView(statRow(
                    "Max coolant", DisplayUnits.temperature(it, u).toString()
                ))
            }
        }

        // --- charts ---
        //
        // Inline rather than behind buttons. The logbook is read parked so
        // scrolling is free, and a chart you have to press a button to see
        // is a chart you will not look at.
        val samples = DriveLog.parse(lines)

        val bands = DriveCharts.ispBySpeed(samples)
        if (bands.isNotEmpty()) {
            root.addView(spacer(22))
            root.addView(sectionHeading("EFFICIENCY BY SPEED"))
            root.addView(chartFrame(IspBySpeedView(this).apply {
                this.bands = bands
                this.units = u
            }, 200))
            root.addView(body(
                "Mean Isp in each speed band. The lit bar is where this car " +
                    "was most efficient on this drive.",
                11f
            ))
        }

        val wf = DriveCharts.waterfall(samples)
        if (wf.totalJ > 0.0) {
            root.addView(spacer(22))
            root.addView(sectionHeading("WHERE THE ENERGY WENT"))
            root.addView(chartFrame(WaterfallView(this).apply { data = wf }, 170))
            root.addView(body(
                "Work done against road load and against gravity, with what " +
                    "the descent gave back. Climbing and descending are shown " +
                    "separately, not netted — both are real.",
                11f
            ))
        }

        val states = DriveCharts.timeInState(samples)
        if (states.isNotEmpty()) {
            root.addView(spacer(22))
            root.addView(sectionHeading("TIME IN STATE"))
            root.addView(chartFrame(StatePieView(this).apply { slices = states }, 200))
            val dfco = DriveCharts.dfcoSeconds(samples)
            root.addView(body(
                "Coasting in fuel cutoff for ${fmtDuration((dfco * 1000).toLong())} " +
                    "— free distance, and the number most worth improving.",
                11f
            ))
        }

        // --- the retrace ---
        root.addView(spacer(22))
        root.addView(sectionHeading("ROUTE"))
        root.addView(spacer(6))

        val track = DriveTrack.build(DriveLog.parse(lines))
        root.addView(TrackView(this).apply {
            this.track = track
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(LogbookActivity.WELL)
        })
        root.addView(spacer(6))
        root.addView(body(
            if (track.isEmpty) {
                "Drives recorded before position logging was added have no " +
                    "route to draw."
            } else {
                "Coloured by efficiency: red is wasteful, green is good. " +
                    "Grey is stopped or coasting. Green dot starts, amber ends."
            },
            11f
        ))

        // --- the recording itself ---
        root.addView(spacer(20))
        root.addView(sectionHeading("RECORDING"))
        root.addView(statRow("Samples", "${summary.rows}"))
        root.addView(statRow("With GPS fix", "${summary.samplesWithFix}"))
        root.addView(statRow("File size", "${"%.0f".format(file.length() / 1024.0)} KB"))
        root.addView(statRow("Units", u.label))

        root.addView(spacer(24))
        root.addView(deleteButton())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(LogbookActivity.GROUND)
            addView(root)
        })
    }

    /** A chart sitting in the panel's well, at a fixed height. */
    private fun chartFrame(v: View, heightDp: Int): View {
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (heightDp * resources.displayMetrics.density).toInt()
        )
        v.setBackgroundColor(LogbookActivity.WELL)
        return v
    }

    private fun warnBox(s: DriveLog.Summary) = TextView(this).apply {
        text = when {
            s.wasResumed && !s.endedCleanly ->
                "This drive was interrupted and picked up again, then ended " +
                    "unexpectedly. Its figures may be short."
            s.wasResumed ->
                "This drive was interrupted and picked up again. The gap is " +
                    "not counted in the distance."
            else ->
                "This drive ended unexpectedly — the app closed mid-drive, so " +
                    "the figures stop where the recording did."
        }
        textSize = 11f
        setTextColor(LogbookActivity.WARN)
        setBackgroundColor(LogbookActivity.WELL)
        setPadding(24, 18, 24, 18)
    }

    private fun deleteButton() = Button(this).apply {
        text = "DELETE THIS RECORDING"
        textSize = 13f
        setTextColor(LogbookActivity.WARN)
        setBackgroundColor(LogbookActivity.WELL)
        setPadding(24, 24, 24, 24)
        setOnClickListener {
            // Confirmed, because it is not recoverable: these files are the
            // only record of a drive and nothing syncs them anywhere.
            AlertDialog.Builder(this@DriveDetailActivity)
                .setTitle("Delete this recording?")
                .setMessage("This cannot be undone.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete") { _, _ ->
                    file.delete()
                    finish()
                }
                .show()
        }
    }

    // --- widgets ---

    private fun bigStat(text: String) = TextView(this).apply {
        this.text = text
        textSize = 40f
        setTextColor(LogbookActivity.PHOSPHOR)
        typeface = Typeface.MONOSPACE
    }

    private fun sectionHeading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(LogbookActivity.PHOSPHOR)
        letterSpacing = 0.18f
        setPadding(0, 0, 0, 10)
    }

    /** Label left, value right — the same shape as the panel's stat column. */
    private fun statRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 7, 0, 7)
        addView(TextView(this@DriveDetailActivity).apply {
            text = label
            textSize = 13f
            setTextColor(LogbookActivity.DIM)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(this@DriveDetailActivity).apply {
            text = value
            textSize = 14f
            setTextColor(LogbookActivity.BRIGHT)
            typeface = Typeface.MONOSPACE
        })
    }

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(LogbookActivity.PHOSPHOR)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun body(text: String, size: Float, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(LogbookActivity.DIM)
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun spacer(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (dp * resources.displayMetrics.density).toInt()
        )
    }

    private fun fmtDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun prettyDate(fileName: String): String {
        val stamp = fileName.removePrefix("drive-").removeSuffix(".ndjson")
        return try {
            val d = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(stamp)
            SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(d!!)
        } catch (e: Exception) {
            stamp
        }
    }

    companion object {
        const val EXTRA_PATH = "drive_path"
    }
}
