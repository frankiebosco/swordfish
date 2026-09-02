package dev.swordfish.physics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three logbook charts.
 *
 * Chosen because each answers a question the driver would act on. Verified
 * against the real ridge-road drives -- a chart that looks right on synthetic data
 * and flat on a real one is worse than no chart.
 */
class DriveChartsTest {

    private var clock = 0L

    private fun sample(
        speed: Double, isp: Double = 30_000.0, state: String = "CRUISE",
        dfco: Boolean = false, roadLoad: Double? = 460.0,
        gravity: Double? = 0.0
    ): DriveLog.Sample {
        clock += 1000
        return DriveLog.Sample(
            tMs = clock, speedMps = speed, rpm = 2500.0, fuelKgPerSec = 0.0006,
            fuelRemainingKg = 30.0, ispS = isp, deltaVMps = 7000.0,
            altitudeM = 30.0, coolantC = 88.0, lat = null, lon = null,
            roadLoadN = roadLoad, gravityLossW = gravity,
            state = state, dfco = dfco
        )
    }

    private fun realDrive(): List<DriveLog.Sample>? {
        for (p in listOf(
            "tools/probe-logs/drives-20260825pm/drive-20260825-191345.ndjson",
            "../tools/probe-logs/drives-20260825pm/drive-20260825-191345.ndjson"
        )) {
            val f = File(p)
            if (f.isFile) return DriveLog.parse(f.readLines())
        }
        return null
    }

    // --- 1. Isp by speed ---

    @Test
    fun `speed bands are ordered`() {
        clock = 0
        val s = (0 until 100).map { sample(speed = 5.0 + it * 0.2) }
        val bands = DriveCharts.ispBySpeed(s, minSamples = 5)
        assertTrue(bands.size >= 2)
        for (i in 1 until bands.size) {
            assertTrue(bands[i].fromMps > bands[i - 1].fromMps, "bands must be ordered")
        }
    }

    @Test
    fun `a thin band is dropped rather than plotted beside a thick one`() {
        // Three samples next to three hundred implies equal weight.
        clock = 0
        val many = (0 until 50).map { sample(speed = 20.0) }
        val few = (0 until 3).map { sample(speed = 40.0) }
        val bands = DriveCharts.ispBySpeed(many + few, minSamples = 20)
        assertEquals(1, bands.size, "only the well-sampled band should survive")
    }

    @Test
    fun `stopped and coasting samples are excluded`() {
        // Idling has no meaningful Isp and DFCO has infinite; either would
        // swamp the range real driving occupies.
        clock = 0
        val driving = (0 until 30).map { sample(speed = 20.0, isp = 30_000.0) }
        val parked = (0 until 30).map { sample(speed = 0.0, isp = 1.0) }
        val coasting = (0 until 30).map { sample(speed = 20.0, dfco = true) }
        val bands = DriveCharts.ispBySpeed(driving + parked + coasting, minSamples = 20)
        assertEquals(1, bands.size)
        assertEquals(30, bands.first().samples)
    }

    @Test
    fun `the sweet spot is the highest mean band`() {
        clock = 0
        val slow = (0 until 30).map { sample(speed = 10.0, isp = 20_000.0) }
        val fast = (0 until 30).map { sample(speed = 25.0, isp = 45_000.0) }
        val best = DriveCharts.sweetSpot(
            DriveCharts.ispBySpeed(slow + fast, minSamples = 20)
        )
        assertNotNull(best)
        assertTrue(best.meanIspS > 40_000.0, "expected the fast band to win")
    }

    @Test
    fun `a thin band cannot be the sweet spot`() {
        // THE TRAP, found by previewing the real drive. The raw maximum was
        // a 39-sample band against 237 in the next -- so the chart would
        // have advised driving at 15 mph. Isp is fuel per unit thrust, so
        // barely touching the throttle scores well while going nowhere.
        clock = 0
        val thin = (0 until 20).map { sample(speed = 7.0, isp = 47_000.0) }
        val thick = (0 until 200).map { sample(speed = 25.0, isp = 43_000.0) }
        val bands = DriveCharts.ispBySpeed(thin + thick, minSamples = 20)
        val best = DriveCharts.sweetSpot(bands)
        assertNotNull(best)
        assertTrue(
            best.samples > 100,
            "the sweet spot must be a speed actually driven, not a 20-sample " +
                "artefact (picked a band with ${best.samples} samples)"
        )
    }

    @Test
    fun `a scattered drive names no sweet spot rather than a thin one`() {
        // If nothing clears the evidence bar, saying nothing is honest.
        clock = 0
        val a = (0 until 25).map { sample(speed = 7.0) }
        val b = (0 until 25).map { sample(speed = 15.0) }
        val c = (0 until 25).map { sample(speed = 25.0) }
        val d = (0 until 25).map { sample(speed = 35.0) }
        val bands = DriveCharts.ispBySpeed(a + b + c + d, minSamples = 20)
        // Each band holds 25%, so all clear a 15% bar -- one should win.
        assertNotNull(DriveCharts.sweetSpot(bands))
        // But raise the bar past what any band holds and none should.
        assertEquals(null, DriveCharts.sweetSpot(bands, minShare = 0.9))
    }

    @Test
    fun `the real drive names a credible best speed`() {
        // Sanity against the car: an ND2 is most efficient cruising, not
        // crawling. Before the evidence bar this picked 10-20 mph.
        val s = realDrive() ?: return
        val best = DriveCharts.sweetSpot(DriveCharts.ispBySpeed(s)) ?: return
        assertTrue(
            best.fromMps > 8.0,
            "best band starts at ${"%.0f".format(Units.mpsToMph(best.fromMps))} mph " +
                "-- that is coasting, not an efficiency finding"
        )
    }

    @Test
    fun `a loop drive recovers about all of its climb`() {
        // Measured: +2747 vs -2717 kW-s, ratio 0.989. That is not a bug --
        // a drive returning to the same elevation genuinely cancels, and
        // the chart should show it rather than hide it by netting.
        val s = realDrive() ?: return
        val w = DriveCharts.waterfall(s)
        assertTrue(
            w.recoveredFraction in 0.8..1.2,
            "a loop drive should recover most of its climb, got " +
                "${"%.2f".format(w.recoveredFraction)}"
        )
    }

    @Test
    fun `the real drive shows structure, not a flat smear`() {
        // The whole justification for this chart. Measured on the ridge road: a dip
        // around 20-29 mph with peaks either side. If a real drive produced
        // a flat line the chart would not be worth drawing.
        val s = realDrive() ?: return
        val bands = DriveCharts.ispBySpeed(s)
        assertTrue(bands.size >= 3, "expected several populated speed bands")
        val means = bands.map { it.meanIspS }
        val spread = means.max() - means.min()
        assertTrue(
            spread > 5_000.0,
            "the bands differ by only ${"%.0f".format(spread)} s -- no sweet spot to show"
        )
    }

    // --- 2. Waterfall ---

    @Test
    fun `road load work is force times distance`() {
        // 10 m/s for 10 s at 500 N = 100 m * 500 N = 50 kJ.
        clock = 0
        val s = (0..10).map { sample(speed = 10.0, roadLoad = 500.0, gravity = 0.0) }
        val w = DriveCharts.waterfall(s)
        assertEquals(50_000.0, w.roadLoadJ, 500.0)
        assertEquals(100.0, w.distanceMeters, 1.0)
    }

    @Test
    fun `climbing and descending are separated, not netted`() {
        // A drive that climbs then descends has done real work either way.
        // Netting them to zero would hide the whole story.
        clock = 0
        val up = (0..10).map { sample(speed = 10.0, gravity = 2000.0) }
        val down = (0..10).map { sample(speed = 10.0, gravity = -1000.0) }
        val w = DriveCharts.waterfall(up + down)
        assertTrue(w.climbJ > 15_000.0, "climb: ${w.climbJ}")
        assertTrue(w.descentJ > 8_000.0, "descent: ${w.descentJ}")
        assertTrue(
            w.recoveredFraction in 0.3..0.7,
            "about half the climb should come back: ${w.recoveredFraction}"
        )
    }

    @Test
    fun `a gap in the log does not invent work`() {
        clock = 0
        val a = sample(speed = 10.0, roadLoad = 500.0)
        clock += 600_000                       // ten minutes later
        val b = sample(speed = 10.0, roadLoad = 500.0)
        val w = DriveCharts.waterfall(listOf(a, b))
        assertEquals(0.0, w.roadLoadJ, 1.0, "work across a gap must not count")
    }

    @Test
    fun `the real drive produces a credible budget`() {
        val s = realDrive() ?: return
        val w = DriveCharts.waterfall(s)
        assertTrue(w.roadLoadJ > 0.0, "a real drive does work against road load")
        assertTrue(w.climbJ > 0.0, "the ridge road climbs")
        assertTrue(w.descentJ > 0.0, "and comes back down")
        assertTrue(
            w.climbFraction in 0.0..1.0,
            "climb fraction out of range: ${w.climbFraction}"
        )
    }

    // --- 3. Time in state ---

    @Test
    fun `state slices sum to the whole drive`() {
        clock = 0
        val s = (0 until 20).map { sample(speed = 20.0, state = "CRUISE") } +
            (0 until 10).map { sample(speed = 0.0, state = "IDLE") }
        val slices = DriveCharts.timeInState(s)
        assertEquals(1.0, slices.sumOf { it.fraction }, 1e-6)
    }

    @Test
    fun `slices are ordered largest first`() {
        clock = 0
        val s = (0 until 5).map { sample(speed = 0.0, state = "IDLE") } +
            (0 until 40).map { sample(speed = 20.0, state = "CRUISE") }
        val slices = DriveCharts.timeInState(s)
        assertEquals("CRUISE", slices.first().state)
    }

    @Test
    fun `DFCO is counted separately so time is not double-counted`() {
        // The log carries dfco as a FLAG that can be true during DESCENT.
        // Treating it as a fifth state would count those seconds twice.
        clock = 0
        val s = (0 until 30).map {
            sample(speed = 20.0, state = "DESCENT", dfco = true)
        }
        val slices = DriveCharts.timeInState(s)
        assertEquals(1, slices.size, "DFCO must not appear as its own slice")
        assertEquals("DESCENT", slices.first().state)
        assertTrue(DriveCharts.dfcoSeconds(s) > 25.0)
    }

    @Test
    fun `the real drive shows a meaningful coasting share`() {
        // 20% of that drive was in fuel cutoff -- free distance, and the
        // number most worth surfacing because it is directly improvable.
        val s = realDrive() ?: return
        val slices = DriveCharts.timeInState(s)
        assertTrue(slices.isNotEmpty())
        assertEquals(1.0, slices.sumOf { it.fraction }, 1e-6)
        val dfco = DriveCharts.dfcoSeconds(s)
        assertTrue(dfco > 60.0, "expected real coasting time, got ${dfco}s")
    }

    @Test
    fun `an empty drive yields empty charts rather than throwing`() {
        assertTrue(DriveCharts.ispBySpeed(emptyList()).isEmpty())
        assertTrue(DriveCharts.timeInState(emptyList()).isEmpty())
        assertEquals(0.0, DriveCharts.waterfall(emptyList()).roadLoadJ)
    }
}
