package dev.swordfish.physics

/**
 * The reward loop: is the driver in the efficiency sweet spot, and is this
 * their best yet?
 *
 * ## Why this is a separate concept from Isp
 *
 * [DeltaVModel.effectiveIsp] answers "how efficient am I right now" as a raw
 * number. That is the truth, but a raw number is a poor *reward*: it has no
 * ceiling, no memory, and no sense of whether 31,000 s is good or bad in the
 * current situation.
 *
 * This class supplies the missing context. It knows what a plausible Isp range
 * looks like for the current speed, so the bar can fill meaningfully, and it
 * remembers the best sustained figure so a genuine improvement can be
 * celebrated.
 *
 * ## The speed problem, and why the band is relative
 *
 * A fixed 0-50,000 s scale would be misleading, because Isp falls with speed
 * even at constant efficiency — see [Thermodynamics.ispAtConstantEfficiency].
 * At 25 mph an Isp of 60,000 s is unremarkable; at 75 mph it would be
 * extraordinary. Scaling the bar against *what is achievable at this speed*
 * keeps it honest, and stops the instrument rewarding you merely for slowing
 * down.
 *
 * Slowing down is still rewarded — it genuinely raises Δv — but that shows up
 * in the delta-V figure, which is where it belongs. The bar is specifically
 * about how well you are driving *at the speed you have chosen*.
 */
object EfficiencyBand {

    /**
     * Thermal efficiency a well-driven naturally aspirated petrol engine can
     * reach at steady cruise. Used as the "full bar" reference.
     */
    const val EXCELLENT_EFFICIENCY = 0.30

    /** Thermal efficiency below which the driver is clearly wasting fuel. */
    const val POOR_EFFICIENCY = 0.10

    /** The rpm window where a SkyActiv-G is happiest at light load. */
    val SWEET_SPOT_RPM = 1_600.0..2_400.0

    /** Engine load fraction above which we are no longer at light load. */
    const val LIGHT_LOAD_CEILING = 0.45

    /**
     * How the driver is doing, as a 0..1 bar fill plus a discrete state.
     *
     * @param fill 0..1, for the Isp bar. Clamped.
     * @param inSweetSpot rpm and load are both in the efficient window.
     * @param isPersonalBest sustained Isp has exceeded the previous best.
     */
    data class Assessment(
        val fill: Double,
        val inSweetSpot: Boolean,
        val isPersonalBest: Boolean
    ) {
        /** Lamp state for the panel. */
        val lamp: Lamp get() = when {
            isPersonalBest && inSweetSpot -> Lamp.RECORD
            inSweetSpot -> Lamp.LIT
            else -> Lamp.DIM
        }
    }

    enum class Lamp { DIM, LIT, RECORD }

    /**
     * The Isp achievable at this speed at [EXCELLENT_EFFICIENCY].
     *
     * This is the bar's full-scale value: hitting it means the engine is doing
     * about as well as it can at the current speed.
     */
    fun fullScaleIsp(speedMps: Double): Double =
        Thermodynamics.ispFromEnergy(EXCELLENT_EFFICIENCY, speedMps)

    /**
     * Bar fill for a given Isp at a given speed, 0..1.
     *
     * Scaled between the poor and excellent efficiency references so the bar
     * spends its range on the part that matters, rather than compressing all
     * real driving into the bottom fifth.
     */
    fun barFill(effectiveIsp: Double, speedMps: Double): Double {
        if (speedMps <= 0.0 || effectiveIsp <= 0.0) return 0.0
        val floor = Thermodynamics.ispFromEnergy(POOR_EFFICIENCY, speedMps)
        val ceiling = Thermodynamics.ispFromEnergy(EXCELLENT_EFFICIENCY, speedMps)
        if (ceiling <= floor) return 0.0
        return ((effectiveIsp - floor) / (ceiling - floor)).coerceIn(0.0, 1.0)
    }

    /**
     * True when engine speed and load are both in the efficient window.
     *
     * @param loadFraction PID 0104 or 0143, expressed 0..1.
     */
    fun inSweetSpot(rpm: Double, loadFraction: Double?): Boolean {
        if (rpm !in SWEET_SPOT_RPM) return false
        // Load is optional: without it, rpm alone is a reasonable proxy.
        val load = loadFraction ?: return true
        return load <= LIGHT_LOAD_CEILING
    }
}

/**
 * Tracks the best sustained efficiency seen, so a genuine improvement can be
 * recognised.
 *
 * ## Why "sustained" rather than instantaneous
 *
 * Instantaneous Isp spikes enormously in transient conditions — the moment you
 * lift off the throttle, fuel flow collapses while road load does not, and Isp
 * momentarily reads absurdly high. Celebrating that would reward lifting off
 * for a fraction of a second, which is not driving well.
 *
 * So the record is kept against a rolling average over [windowSeconds]. To
 * beat it you have to hold good technique, which is the behaviour worth
 * rewarding.
 */
class EfficiencyRecord(
    /** Averaging window. Long enough that a momentary lift cannot win. */
    private val windowSeconds: Double = 30.0,
    /**
     * Reject samples more than this multiple of the current average.
     *
     * Slowing the filter is not enough on its own: with a 30 s window and
     * 10 Hz samples, a single 500,000 s reading still drags the average up by
     * thousands. Transient Isp genuinely does spike that far -- on a throttle
     * lift, fuel flow collapses to near zero while road load does not, so the
     * instantaneous figure briefly approaches infinity.
     *
     * Those samples are physically real but they are not *driving*, so they
     * are discarded rather than smoothed. This is what a real instrument does
     * with an out-of-range reading.
     */
    private val outlierFactor: Double = 3.0
) {
    private var rollingAverage: Double = 0.0
    private var elapsed: Double = 0.0
    private var seeded: Boolean = false

    /** Best sustained Isp seen so far. */
    var best: Double = 0.0
        private set

    /** Current rolling-average Isp. */
    val sustained: Double get() = rollingAverage

    /**
     * Feed a sample.
     *
     * @return true when this update set a new record.
     */
    fun update(effectiveIsp: Double, dtSec: Double): Boolean {
        if (dtSec <= 0.0 || !effectiveIsp.isFinite() || effectiveIsp < 0.0) return false

        if (!seeded) {
            // Start at the first real value rather than climbing from zero,
            // which would otherwise take a full window to become meaningful.
            rollingAverage = effectiveIsp
            seeded = true
        } else {
            // Reject transient spikes outright -- see outlierFactor.
            if (rollingAverage > 0.0 && effectiveIsp > rollingAverage * outlierFactor) {
                elapsed += dtSec
                return false
            }
            // Exponential moving average.
            //
            // NOTE the 3x: a plain alpha = dt/window is a *time constant*, not
            // an averaging window -- it reaches only ~63% of a step change
            // after `window` seconds. Using 3 time constants per window means
            // the average genuinely settles within windowSeconds (~95%), which
            // is what the parameter name promises and what the spike-rejection
            // behaviour depends on.
            val alpha = (3.0 * dtSec / windowSeconds).coerceIn(0.0, 1.0)
            rollingAverage += (effectiveIsp - rollingAverage) * alpha
        }
        elapsed += dtSec

        // Do not allow a record until the average has had time to settle,
        // otherwise the first sample trivially becomes the best.
        if (elapsed < windowSeconds) return false

        if (rollingAverage > best) {
            best = rollingAverage
            return true
        }
        return false
    }

    /** Clear the record, e.g. on a new trip. */
    fun reset() {
        rollingAverage = 0.0
        elapsed = 0.0
        best = 0.0
        seeded = false
    }
}
