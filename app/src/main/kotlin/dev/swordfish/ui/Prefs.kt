package dev.swordfish.ui

import android.content.Context
import dev.swordfish.physics.AdultAverage
import dev.swordfish.physics.DemoFrame
import dev.swordfish.physics.DisplayTheme
import dev.swordfish.physics.Occupant
import dev.swordfish.physics.Payload
import dev.swordfish.physics.RadarLayout
import dev.swordfish.physics.UnitSystem
import dev.swordfish.physics.Units

/**
 * Persisted user preferences.
 *
 * Deliberately a thin wrapper over `SharedPreferences` rather than DataStore:
 * there are a handful of scalar settings, they are read on the render path,
 * and a synchronous read of an in-memory map is exactly what that wants.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /**
     * Display colour scheme.
     *
     * Green is the default instrument phosphor; red is the *Ghost in the
     * Machine* palette that inspired the look.
     */
    var displayTheme: DisplayTheme
        get() = DisplayTheme.fromName(sp.getString(KEY_THEME, null))
        set(value) {
            sp.edit().putString(KEY_THEME, value.name).apply()
        }

    /** Whether to draw the faint scanline overlay. */
    var scanlines: Boolean
        get() = sp.getBoolean(KEY_SCANLINES, true)
        set(value) {
            sp.edit().putBoolean(KEY_SCANLINES, value).apply()
        }

    /**
     * Whether unlit segments are drawn faintly behind the lit ones.
     *
     * On by default: it is the detail that makes a readout look like a real
     * display rather than like text. Offered as a toggle because it is also
     * the most divisive part of the look.
     */
    var ghostSegments: Boolean
        get() = sp.getBoolean(KEY_GHOST, true)
        set(value) {
            sp.edit().putBoolean(KEY_GHOST, value).apply()
        }

    /**
     * Which canned frame the panel shows when there is no live telemetry.
     *
     * Four of the five operating states are unreachable at a desk — the DHU
     * has no dongle, so without this the panel can only ever be seen in
     * CRUISE. Switching the demo frame is the only way to check that
     * `IDLE BURN`, `DFCO` and `DESCENT` render legibly without driving.
     *
     * Has no effect once real telemetry arrives; live data always wins.
     */
    var demoFrame: DemoFrame
        get() = DemoFrame.fromName(sp.getString(KEY_DEMO_FRAME, null))
        set(value) {
            sp.edit().putString(KEY_DEMO_FRAME, value.name).apply()
        }

    /**
     * What the centre column shows: the instrument panel, or the radar scope.
     *
     * **Persisted because it is a preference, not a session state.** A driver
     * who wants the scope wants it on the next drive too, and the head unit
     * recreates the car screen freely -- switching to Maps and back destroys
     * and rebuilds it -- so anything held only in memory would silently
     * revert mid-drive.
     *
     * Unlike [demoFrame] and the payload, this is written from the CAR
     * screen while driving, so it must be read fresh rather than cached in a
     * `val` at construction.
     */
    var centreContent: RadarLayout.CentreContent
        get() = RadarLayout.CentreContent.fromName(sp.getString(KEY_CENTRE_CONTENT, null))
        set(value) {
            sp.edit().putString(KEY_CENTRE_CONTENT, value.name).apply()
        }

    /**
     * Radar scope range in statute miles.
     *
     * Validated on read against [RadarLayout.RANGES_MILES]: a value written
     * by a future build with a different range set resolves to the default
     * rather than rendering a scope whose rings are labelled with a range
     * the geometry does not use.
     */
    /**
     * Which units the LOGBOOK and the radar range label use.
     *
     * The head-unit panel is metric throughout -- m/s, metres, °C -- because
     * delta-V is quoted in m/s and that is the whole conceit. The logbook was
     * written later and reached for miles and mpg. Both are defensible;
     * having both at once was just an inconsistency.
     *
     * Delta-V and Isp are never converted whatever this says. See UnitSystem.
     */
    var unitSystem: UnitSystem
        get() = UnitSystem.fromName(sp.getString(KEY_UNIT_SYSTEM, null))
        set(v) { sp.edit().putString(KEY_UNIT_SYSTEM, v.name).apply() }

    var radarRangeMiles: Int
        get() {
            val stored = sp.getInt(KEY_RADAR_RANGE, RadarLayout.DEFAULT_RANGE_MILES)
            return if (stored in RadarLayout.RANGES_MILES) stored
            else RadarLayout.DEFAULT_RANGE_MILES
        }
        set(value) {
            sp.edit().putInt(KEY_RADAR_RANGE, value).apply()
        }

    // --- crew and cargo ---
    //
    // Stored as "how the user chose", not as a resolved number, so that a
    // standard-mass choice stays a standard-mass choice. Re-resolving on read
    // means a future change to the reference figures applies automatically,
    // and it keeps the privacy property from Payload.kt intact: an average is
    // recorded as an average, never silently converted into an exact weight.

    /** Driver: a standard build, or an exact figure in pounds. */
    var driverAverage: AdultAverage?
        get() = sp.getString(KEY_DRIVER_AVG, AdultAverage.ADULT.name)
            ?.let { name -> AdultAverage.entries.firstOrNull { it.name == name } }
        set(value) {
            sp.edit().putString(KEY_DRIVER_AVG, value?.name).apply()
        }

    var driverExactLb: Double
        get() = sp.getFloat(KEY_DRIVER_LB, 180f).toDouble()
        set(value) {
            sp.edit().putFloat(KEY_DRIVER_LB, value.toFloat()).apply()
        }

    /** True when the driver is entered as an exact weight. */
    var driverIsExact: Boolean
        get() = sp.getBoolean(KEY_DRIVER_EXACT, false)
        set(value) {
            sp.edit().putBoolean(KEY_DRIVER_EXACT, value).apply()
        }

    /**
     * Whether anyone is in the passenger seat.
     *
     * Solo is the common case for a two-seat car, so this defaults off.
     */
    var hasPassenger: Boolean
        get() = sp.getBoolean(KEY_HAS_PAX, false)
        set(value) {
            sp.edit().putBoolean(KEY_HAS_PAX, value).apply()
        }

    var passengerAverage: AdultAverage?
        get() = sp.getString(KEY_PAX_AVG, AdultAverage.ADULT.name)
            ?.let { name -> AdultAverage.entries.firstOrNull { it.name == name } }
        set(value) {
            sp.edit().putString(KEY_PAX_AVG, value?.name).apply()
        }

    var passengerExactLb: Double
        get() = sp.getFloat(KEY_PAX_LB, 180f).toDouble()
        set(value) {
            sp.edit().putFloat(KEY_PAX_LB, value.toFloat()).apply()
        }

    var passengerIsExact: Boolean
        get() = sp.getBoolean(KEY_PAX_EXACT, false)
        set(value) {
            sp.edit().putBoolean(KEY_PAX_EXACT, value).apply()
        }

    /** Cargo in pounds. Zero means an empty boot. */
    var cargoLb: Double
        get() = sp.getFloat(KEY_CARGO_LB, 0f).toDouble()
        set(value) {
            sp.edit().putFloat(KEY_CARGO_LB, value.toFloat().coerceAtLeast(0f)).apply()
        }

    /**
     * Assemble the stored choices into a [Payload] for the model.
     *
     * Resolving here rather than at write time is what keeps an "average"
     * choice an average rather than a frozen number.
     */
    fun buildPayload(): Payload {
        val driver = if (driverIsExact) {
            Occupant.Exact.ofPounds(driverExactLb, "driver")
        } else {
            Occupant.Average(driverAverage ?: AdultAverage.ADULT)
        }

        val passenger = if (!hasPassenger) null else if (passengerIsExact) {
            Occupant.Exact.ofPounds(passengerExactLb, "passenger")
        } else {
            Occupant.Average(passengerAverage ?: AdultAverage.ADULT)
        }

        return Payload(
            driver = driver,
            passenger = passenger,
            cargoKg = Units.lbToKg(cargoLb)
        )
    }

    companion object {
        private const val NAME = "swordfish_prefs"
        private const val KEY_THEME = "display_theme"
        private const val KEY_DEMO_FRAME = "demo_frame"
        private const val KEY_CENTRE_CONTENT = "centre_content"
        private const val KEY_RADAR_RANGE = "radar_range_miles"
        private const val KEY_UNIT_SYSTEM = "unit_system"
        private const val KEY_SCANLINES = "scanlines"
        private const val KEY_GHOST = "ghost_segments"
        private const val KEY_DRIVER_AVG = "driver_avg"
        private const val KEY_DRIVER_LB = "driver_lb"
        private const val KEY_DRIVER_EXACT = "driver_exact"
        private const val KEY_HAS_PAX = "has_passenger"
        private const val KEY_PAX_AVG = "pax_avg"
        private const val KEY_PAX_LB = "pax_lb"
        private const val KEY_PAX_EXACT = "pax_exact"
        private const val KEY_CARGO_LB = "cargo_lb"
    }
}
