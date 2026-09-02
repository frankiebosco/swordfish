package dev.swordfish.physics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Axis arithmetic for the logbook charts.
 *
 * ## Why this is not in the view
 *
 * Choosing tick values and mapping a value to a pixel is exactly the part of
 * hand-drawn charting that goes subtly wrong -- a range that produces ticks
 * at 0, 3333, 6667 looks broken in a way no compiler catches. Keeping it
 * here means the awkward cases (a flat series, a single point, everything
 * negative) are argued about in a test rather than discovered on a phone.
 *
 * The drawing itself stays in the view, where it belongs.
 */
object ChartScale {

    /**
     * A chosen axis: rounded bounds and the ticks between them.
     */
    data class Axis(
        val min: Double,
        val max: Double,
        val ticks: List<Double>
    ) {
        val span: Double get() = max - min

        /**
         * Where a value sits on the axis, 0..1.
         *
         * Clamped: a value outside the axis maps to its edge rather than off
         * the chart, so one outlier cannot draw a bar into the next view.
         */
        fun fraction(value: Double): Double {
            if (span <= 0.0) return 0.0
            return ((value - min) / span).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Choose a "nice" axis covering [lo]..[hi].
     *
     * Ticks land on 1, 2, 2.5 or 5 times a power of ten, which is what makes
     * an axis readable -- 0/25/50/75/100 rather than 0/33/67/100.
     *
     * @param targetTicks roughly how many gridlines are wanted. Not exact:
     *   forcing a count is what produces ugly intervals.
     * @param includeZero pull the axis down to zero when the data is all
     *   positive. A bar chart whose baseline is 28,000 exaggerates small
     *   differences into dramatic ones, which is a way of lying with a
     *   truthful number.
     */
    fun niceAxis(
        lo: Double,
        hi: Double,
        targetTicks: Int = 4,
        includeZero: Boolean = true
    ): Axis {
        var low = minOf(lo, hi)
        var high = maxOf(lo, hi)

        if (includeZero) {
            if (low > 0.0) low = 0.0
            if (high < 0.0) high = 0.0
        }

        // A flat series still needs a drawable axis: give it something
        // symmetric around the value rather than a zero-height chart.
        if (abs(high - low) < 1e-9) {
            val pad = if (abs(high) < 1e-9) 1.0 else abs(high) * 0.1
            low -= pad
            high += pad
        }

        val step = niceStep((high - low) / targetTicks.coerceAtLeast(1))
        val axisMin = floor(low / step) * step
        val axisMax = ceil(high / step) * step

        val ticks = ArrayList<Double>()
        var t = axisMin
        // Guard the loop: a pathological step could otherwise spin forever.
        var guard = 0
        while (t <= axisMax + step * 0.5 && guard < 100) {
            // Snap values that are a hair off a round number, so 3.0000000004
            // does not print as "3.0000000004".
            ticks += if (abs(t) < step * 1e-9) 0.0 else t
            t += step
            guard++
        }
        return Axis(axisMin, axisMax, ticks)
    }

    /**
     * Round a raw interval up to 1, 2, 2.5 or 5 times a power of ten.
     *
     * 2.5 is included because without it a range like 0..9000 with four
     * ticks lands on a step of 5000 (two gridlines) or 2000 (five) -- 2500
     * gives four, which is what was asked for.
     */
    fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || !raw.isFinite()) return 1.0
        val exp = floor(log10(raw))
        val pow = 10.0.pow(exp)
        val frac = raw / pow
        val nice = when {
            frac <= 1.0 -> 1.0
            frac <= 2.0 -> 2.0
            frac <= 2.5 -> 2.5
            frac <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * pow
    }

    /**
     * Format a tick for display.
     *
     * Large numbers become "31k" rather than "31000": an axis label competes
     * for width with the chart itself, and Isp figures run to five digits.
     */
    fun formatTick(value: Double): String {
        val a = abs(value)
        return when {
            a < 1e-9 -> "0"
            a >= 1_000_000 -> "${trim(value / 1_000_000)}M"
            a >= 1_000 -> "${trim(value / 1_000)}k"
            a >= 10 -> "%.0f".format(value)
            a >= 1 -> trim(value)
            else -> "%.2f".format(value)
        }
    }

    /** Drop a trailing ".0" so ticks read 5k rather than 5.0k. */
    private fun trim(v: Double): String {
        val s = "%.1f".format(v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /**
     * Split a whole into drawable slices with no rounding gap.
     *
     * A donut built by rounding each slice independently leaves a visible
     * wedge of background when the fractions do not quite sum to one. This
     * accumulates instead, so the last slice closes the circle exactly.
     *
     * @return start and sweep for each input, as fractions of a full turn.
     */
    fun sliceAngles(fractions: List<Double>): List<Pair<Double, Double>> {
        val total = fractions.sum()
        if (total <= 0.0) return fractions.map { 0.0 to 0.0 }
        val out = ArrayList<Pair<Double, Double>>(fractions.size)
        var acc = 0.0
        for ((i, f) in fractions.withIndex()) {
            val start = acc
            // The last slice takes whatever is left, rather than its own
            // rounded share.
            val sweep = if (i == fractions.lastIndex) 1.0 - start else f / total
            out += start to sweep
            acc = start + sweep
        }
        return out
    }
}
