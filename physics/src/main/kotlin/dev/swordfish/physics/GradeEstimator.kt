package dev.swordfish.physics

import kotlin.math.asin
import kotlin.math.pow

/**
 * Estimates road grade by fusing barometric altitude with GPS altitude.
 *
 * Each sensor fails in a way the other covers:
 *
 *  - **Barometer** resolves altitude changes to well under a metre and
 *    responds instantly, but its absolute reading drifts as weather systems
 *    move -- tens of metres over a few hours.
 *  - **GPS altitude** does not drift over the long run but is noisy at
 *    +/-10 m or worse, and updates at only ~1 Hz.
 *
 * Complementary filter: take short-term altitude *changes* from the
 * barometer, and let GPS slowly correct the accumulated offset. Grade then
 * comes from altitude change over distance travelled, low-pass filtered so
 * the display does not jitter.
 *
 * Grade is deliberately clamped: no public road exceeds ~30%, so anything
 * beyond that is sensor error rather than terrain, and letting it through
 * would put a wild spike straight into the gravity-loss readout.
 */
class GradeEstimator(
    /**
     * GPS correction gain per second. Slow -- GPS is only pulling out
     * multi-minute barometric drift, not tracking the road.
     */
    private val gpsCorrectionRate: Double = 0.01,
    /**
     * Seed the bias outright from the first GPS altitude, rather than easing
     * toward it.
     *
     * Without this the estimate STARTS at the standard-atmosphere reading --
     * which on a real day is routinely 50-150 m out -- and crawls toward the
     * truth. There is no reason to begin a hundred metres wrong when the
     * first fix already says where the car is. Off only for tests that want
     * to watch convergence from a known offset.
     */
    private val seedBiasFromFirstFix: Boolean = true,
    /**
     * Low-pass smoothing for the grade output, per second. Higher follows
     * terrain faster but jitters more. 0.3 settles in roughly 3 seconds.
     */
    private val gradeSmoothing: Double = 0.3,
    /** Maximum believable grade as a fraction (0.30 = 30%). */
    private val maxGrade: Double = 0.30,
    /**
     * Minimum horizontal travel between grade updates (m). Below this the
     * rise/run ratio is dominated by noise -- and dividing by a near-zero
     * run is how you get a 4000% grade at a stoplight.
     */
    private val minRunM: Double = 5.0
) {
    /** Fused altitude estimate (m). Null until first sample. */
    var altitudeM: Double? = null
        private set

    /**
     * The raw barometric altitude last fed in, and the accumulated bias.
     *
     * Exposed for the drive log. Only the FUSED output was recorded before,
     * which meant a 110 m drift on the 2026-08-25 drive had to be diagnosed
     * from source rather than read off the data -- the log could not say
     * which input was moving. Two fields would have made it obvious.
     */
    var lastBarometricM: Double? = null
        private set

    /** Accumulated GPS correction to the barometric reading, metres. */
    val biasM: Double get() = baroBias

    /** Smoothed grade as a fraction (rise/run). Positive uphill. */
    var gradeFraction: Double = 0.0
        private set

    /** Smoothed grade in radians, for the physics model. */
    val gradeRadians: Double
        get() = asin(gradeFraction.coerceIn(-0.99, 0.99))

    /**
     * Accumulated correction from GPS to the barometric altitude, metres.
     *
     * Represents the error in the assumed sea-level reference. It persists
     * across samples on purpose -- see the note in [update]; a correction
     * that is recomputed and discarded each sample never converges.
     */
    private var baroBias: Double = 0.0

    /** True once [baroBias] has been seeded from a real GPS altitude. */
    private var biasSeeded: Boolean = false

    /**
     * Seconds accumulated since the last GPS altitude was applied.
     *
     * The correction gain has to be scaled by THIS, not by the frame
     * interval. See the note in [update].
     */
    private var secondsSinceGpsAlt: Double = 0.0

    private var lastAltForGrade: Double? = null
    private var runSinceLastGrade: Double = 0.0

    /**
     * Convert a barometric pressure reading to altitude via the
     * international barometric formula.
     *
     * @param pressureHpa Measured pressure.
     * @param seaLevelHpa Reference. 1013.25 is the standard atmosphere; if
     *   you have a local QNH from a nearby airport it will be more accurate,
     *   but for *relative* altitude the reference cancels out anyway.
     */
    fun pressureToAltitudeM(pressureHpa: Double, seaLevelHpa: Double = 1013.25): Double =
        44330.0 * (1.0 - (pressureHpa / seaLevelHpa).pow(1.0 / 5.255))

    /**
     * Air density (kg/m^3) at a given altitude and temperature, for the
     * aero-drag term. Thinner air at altitude means measurably less drag --
     * a nice touch of realism, and it makes mountain drives behave
     * differently from sea-level ones.
     */
    fun airDensity(altitudeM: Double, temperatureC: Double = 15.0): Double {
        val tempK = temperatureC + 273.15
        val pressurePa = 101325.0 * (1.0 - 2.25577e-5 * altitudeM).pow(5.25588)
        return pressurePa / (287.058 * tempK)
    }

    /**
     * Feed one fused sample.
     *
     * @param barometricAltM Altitude from the phone barometer, or null.
     * @param gpsAltM Altitude from GPS, or null.
     * @param horizontalDistanceM Ground distance since the previous sample.
     * @param dtSec Elapsed time.
     */
    fun update(
        barometricAltM: Double?,
        gpsAltM: Double?,
        horizontalDistanceM: Double,
        dtSec: Double
    ) {
        if (dtSec <= 0.0) return

        // Runs every frame; reset whenever a GPS altitude is applied.
        secondsSinceGpsAlt += dtSec
        if (barometricAltM != null) lastBarometricM = barometricAltM

        val current = altitudeM
        val fused = when {
            current == null -> barometricAltM ?: gpsAltM
            barometricAltM != null -> {
                // Barometer drives short-term change; GPS trims slow drift.
                //
                // The correction accumulates into a persistent BIAS rather
                // than nudging each raw reading. Applying alpha to
                // (gps - baro) every sample and then discarding it does not
                // converge at all: with a 0.01/s rate the output sits a
                // fixed 1% of the way toward GPS forever, however long you
                // drive. Carrying the offset is what makes it a correction
                // instead of a permanent small lean.
                if (gpsAltM != null) {
                    val error = gpsAltM - (barometricAltM + baroBias)
                    if (!biasSeeded && seedBiasFromFirstFix) {
                        // FIRST FIX SEEDS THE BIAS OUTRIGHT.
                        //
                        // The barometer is referenced to the standard
                        // atmosphere, so before any GPS arrives the estimate
                        // carries the whole sea-level pressure error --
                        // routinely 50-150 m. Easing toward the truth from
                        // there means the panel is badly wrong for the first
                        // part of every drive, and on a short one it never
                        // gets there at all.
                        baroBias += error
                        biasSeeded = true
                    } else {
                        // GAIN IS SCALED BY TIME SINCE THE LAST FIX, NOT dt.
                        //
                        // This was `gpsCorrectionRate * dtSec`, where dtSec
                        // is the RENDER interval (~0.05 s at 20 fps). That
                        // makes each fix contribute 0.05% of the error
                        // instead of the intended ~1% per second, so
                        // convergence needs THOUSANDS of fixes.
                        //
                        // Measured on real drives: 4472 altitude fixes
                        // corrected 89% of the bias, but 47 corrected 2.3%
                        // and 9 corrected 0.45%. The 2026-08-24 evening
                        // drives read ~100 m low the whole way -- the ridge
                        // (~120 m) showed as 20 m, and the valley floor
                        // (~20 m) as -12 m.
                        //
                        // Scaling by elapsed time means nine fixes spread
                        // over ten minutes still pull the bias most of the
                        // way in, which is what "GPS trims slow drift"
                        // always meant.
                        val alpha =
                            (gpsCorrectionRate * secondsSinceGpsAlt).coerceIn(0.0, 1.0)
                        baroBias += error * alpha
                    }
                    secondsSinceGpsAlt = 0.0
                }
                barometricAltM + baroBias
            }
            gpsAltM != null -> {
                // No barometer on this device: GPS is all there is, so it
                // gets a faster gain. Scaled by time since the last fix for
                // the same reason as the fused branch above -- using dtSec
                // here made convergence depend on frame rate rather than on
                // how much time had actually passed.
                val alpha =
                    (gpsCorrectionRate * secondsSinceGpsAlt * 10.0).coerceIn(0.0, 1.0)
                secondsSinceGpsAlt = 0.0
                current + (gpsAltM - current) * alpha
            }
            else -> current
        }
        altitudeM = fused
        if (fused == null) return

        runSinceLastGrade += horizontalDistanceM
        val prev = lastAltForGrade
        if (prev == null) {
            lastAltForGrade = fused
            runSinceLastGrade = 0.0
            return
        }

        if (runSinceLastGrade >= minRunM) {
            val rise = fused - prev
            val raw = (rise / runSinceLastGrade).coerceIn(-maxGrade, maxGrade)
            val alpha = (gradeSmoothing * dtSec).coerceIn(0.0, 1.0)
            gradeFraction += (raw - gradeFraction) * alpha
            lastAltForGrade = fused
            runSinceLastGrade = 0.0
        }
    }

    fun reset() {
        baroBias = 0.0
        biasSeeded = false
        secondsSinceGpsAlt = 0.0
        altitudeM = null
        gradeFraction = 0.0
        lastAltForGrade = null
        runSinceLastGrade = 0.0
    }
}
