package dev.swordfish.car

import dev.swordfish.physics.Units

/**
 * Pure formatting and layout constants for the instrument panel.
 *
 * ## Why this is a separate file
 *
 * These were originally companion-object members of [GaugeRenderer], which
 * broke unit testing: that companion also holds colour constants built with
 * `android.graphics.Color.rgb()`, and Android framework methods are stubbed
 * to throw in local JVM tests. Touching *any* member forced the class
 * initializer to run, which threw `ExceptionInInitializerError`, so even a
 * pure string-formatting test could not execute.
 *
 * Keeping pure logic in an Android-free object is the general fix, and it
 * mirrors the rule the whole project follows: `:physics` never depends on
 * Android, precisely so it stays testable. Same principle, smaller scale.
 */
object GaugeFormat {

    const val LABEL = "Δv REMAINING"
    const val UNITS = "m/s"
    /** Prefix for the trip-start reference shown beside the units. */
    const val START_PREFIX = "START"
    const val NO_DATA = "----"

    /** Height fraction for the mission-capability line under the units. */
    const val MISSION_TEXT_FRACTION = 0.062f
    const val MISSION_OFFSET_FRACTION = 0.215f

    // Proportions of the stable area's height. Nothing on the panel uses a
    // fixed pixel size: head-unit geometry is unknown until runtime.
    const val LABEL_TEXT_FRACTION = 0.070f
    const val VALUE_TEXT_FRACTION = 0.340f
    const val UNIT_TEXT_FRACTION = 0.080f
    const val LABEL_OFFSET_FRACTION = 0.230f
    const val UNIT_OFFSET_FRACTION = 0.130f

    /**
     * Format delta-V for display: rounded to whole units, ungrouped.
     *
     * **No thousands separator.** On a seven-segment display a space is a
     * full-width blank *cell*, not a thin gap, so "7501" rendered as "7 501"
     * reads as a five-digit number with a hole punched in it — observed on the
     * head unit as "7 _608". Real segment displays use a decimal point or
     * nothing; they never spend a whole digit position on a separator.
     *
     * No decimals either: a fractional digit would flicker constantly at a
     * 10 Hz update rate and communicate nothing.
     *
     * Returns [NO_DATA] for negative or non-finite input rather than
     * rendering something misleading.
     */
    fun formatDeltaV(mps: Double): String {
        if (!mps.isFinite() || mps < 0.0) return NO_DATA
        return Math.round(mps).toString()
    }

    /**
     * Format an integer for a segment readout.
     *
     * Ungrouped, for the same reason as [formatDeltaV].
     */
    fun formatInteger(value: Long): String = value.toString()

    /**
     * Fuel burn rate, auto-scaled so the digits are always whole numbers.
     *
     * ## Why this returns a unit as well as a value
     *
     * **The decimal point is the least legible mark on the panel.** It is
     * drawn as a dot roughly 10px across on a glossy head-unit screen, read
     * at arm's length, often in daylight — and unlike every other glyph it
     * is a single point of failure. Miss it and "0.8" reads as "08", a
     * tenfold error with nothing to signal it.
     *
     * The rest of the panel already solved this by choosing units that need
     * no decimal: TWR is shown as "15%" rather than "0.15", and fuel in
     * litres rather than gallons, both for exactly this reason. Burn rate
     * was the only remaining decimal on the whole display. This removes it
     * rather than trying to draw a better dot.
     *
     * Below [SCALE_THRESHOLD_LPH] the value is reported in millilitres per
     * hour, where idle is a comfortable three digits (757 mL/h). Above it,
     * whole litres per hour, where the number is large enough that the
     * fraction carries nothing (57 L/h). Either way the caller draws digits
     * with no punctuation at all.
     */
    data class FlowReadout(val value: String, val unit: String)

    /**
     * Burn rates below this switch to millilitres. Chosen so the litre
     * reading never needs a fraction: at 10 L/h a whole number is within
     * 5% and the display updates several times a second anyway.
     */
    const val SCALE_THRESHOLD_LPH = 10.0

    fun formatFuelFlow(kgPerSec: Double): FlowReadout {
        val litresPerHour =
            Units.gallonsToLiters(Units.kgToGallons(kgPerSec)) * 3600.0

        if (!litresPerHour.isFinite() || litresPerHour < 0.0) {
            return FlowReadout(NO_DATA, "L/h")
        }
        return if (litresPerHour < SCALE_THRESHOLD_LPH) {
            FlowReadout(Math.round(litresPerHour * 1000.0).toString(), "mL/h")
        } else {
            FlowReadout(Math.round(litresPerHour).toString(), "L/h")
        }
    }

    /**
     * Thousands grouping for ORDINARY TYPE only.
     *
     * Still useful on the phone screen and in labels, where a space really is
     * a thin gap. Never use this for anything drawn by [SegmentDisplay].
     */
    fun groupThousands(value: Long): String =
        value.toString()
            .reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
}
