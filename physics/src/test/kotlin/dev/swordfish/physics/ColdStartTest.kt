package dev.swordfish.physics

import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Warm-up cost, against the same drive once warm.
 *
 * Synthetic drives pin the arithmetic; the real-log test proves the figure
 * means something on what the car actually reports (gitignored logs; skipped
 * when absent).
 */
class ColdStartTest {

    /**
     * One sample per second. Coolant ramps linearly from [startC] to 90 C
     * over [warmUpS], then holds. Speed alternates moving/idle per [idleEvery].
     */
    private fun drive(
        startC: Double,
        warmUpS: Int,
        totalS: Int,
        coldFlow: Double,
        warmFlow: Double,
        speed: Double = 15.0,
        idleEvery: Int = 0,
        idleFlowCold: Double = coldFlow / 4,
        idleFlowWarm: Double = warmFlow / 4
    ): List<DriveLog.Sample> = (0 until totalS).map { i ->
        val c = if (i >= warmUpS) 90.0 else startC + (90.0 - startC) * i / warmUpS
        val warm = c >= ColdStart.WARM_C
        val idle = idleEvery > 0 && i % idleEvery == 0
        DriveLog.Sample(
            tMs = 1_000_000L + i * 1000L,
            speedMps = if (idle) 0.0 else speed,
            rpm = 2000.0,
            fuelKgPerSec = when {
                idle && warm -> idleFlowWarm
                idle -> idleFlowCold
                warm -> warmFlow
                else -> coldFlow
            },
            fuelRemainingKg = 20.0, ispS = null, deltaVMps = null, altitudeM = null,
            coolantC = c, lat = null, lon = null, state = null, dfco = false
        )
    }

    @Test
    fun `no coolant readings means no analysis`() {
        val s = drive(40.0, 300, 1500, 0.001, 0.0008).map { it.copy(coolantC = null) }
        assertNull(ColdStart.analyse(s))
    }

    @Test
    fun `a drive that starts warm costs nothing`() {
        val r = ColdStart.analyse(drive(90.0, 1, 1500, 0.001, 0.001))!!
        assertTrue(r.startedWarm)
        assertEquals(0.0, r.excessFuelKg)
        assertEquals(0.0, r.warmUpSeconds)
    }

    @Test
    fun `excess is the extra flow over the warm-up stretch`() {
        // 25% richer while cold. The warm-up runs until coolant crosses 85 C.
        val r = ColdStart.analyse(drive(40.0, 600, 3000, 0.0010, 0.0008))!!
        val warmUp = r.warmUpSeconds!!
        assertTrue(warmUp in 500.0..600.0, "warm-up $warmUp s")
        // Moving the whole time at constant speed: excess = (cold-warm) * t.
        val expected = (0.0010 - 0.0008) * (warmUp - 1.0)
        assertEquals(expected, r.excessFuelKg!!, 1e-6)
    }

    @Test
    fun `idle during warm-up is charged against the warm idle rate`() {
        val r = ColdStart.analyse(
            drive(40.0, 600, 3000, 0.0008, 0.0008, idleEvery = 5,
                  idleFlowCold = 0.0004, idleFlowWarm = 0.0002)
        )!!
        // Moving flow is identical cold and warm, so all excess is idle:
        // ~1 in 5 warm-up seconds at +0.0002 kg/s.
        val idleSeconds = r.warmUpSeconds!! / 5.0
        assertEquals(0.0002 * idleSeconds, r.excessFuelKg!!, 0.0002 * 3)
    }

    @Test
    fun `identical cold and warm flow is zero excess`() {
        val r = ColdStart.analyse(drive(40.0, 600, 3000, 0.0008, 0.0008, idleEvery = 7))!!
        assertEquals(0.0, r.excessFuelKg!!, 1e-9)
    }

    @Test
    fun `a drive that never warms up has no excess figure`() {
        val r = ColdStart.analyse(drive(30.0, 99_999, 1200, 0.001, 0.0008))!!
        assertNull(r.warmUpSeconds)
        assertNull(r.excessFuelKg)
    }

    @Test
    fun `too little warm driving gives no baseline rather than a guess`() {
        // Warms at 600 s, then only 300 s (4.5 km) of warm driving.
        val r = ColdStart.analyse(drive(40.0, 600, 900, 0.001, 0.0008))!!
        assertNotNull(r.warmUpSeconds)
        assertNull(r.excessFuelKg)
    }

    @Test
    fun `a gap is not integrated across`() {
        val base = drive(40.0, 600, 3000, 0.0010, 0.0008)
        // Shift the second half by an hour: a crash and restart.
        val gapped = base.mapIndexed { i, s ->
            if (i >= 300) s.copy(tMs = s.tMs + 3_600_000L) else s
        }
        val r = ColdStart.analyse(gapped)!!
        val clean = ColdStart.analyse(base)!!
        assertTrue(r.warmUpFuelKg < clean.warmUpFuelKg)
    }

    @Test
    fun `excess delta-V is positive and scales with the waste`() {
        val car = Vehicle.ND2_CLUB
        val small = ColdStart.excessDeltaV(car, 20.0, 0.03)
        val big = ColdStart.excessDeltaV(car, 20.0, 0.06)
        assertTrue(small > 0.0)
        assertEquals(2.0, big / small, 0.01)
        assertEquals(0.0, ColdStart.excessDeltaV(car, 20.0, -0.01))
        // ~60 g at this mass is roughly 15 m/s -- the order the logs show.
        assertTrue(ColdStart.excessDeltaV(car, 20.0, 0.059) in 10.0..20.0)
    }

    @Test
    fun `the summary carries the cold-start result`() {
        val lines = drive(40.0, 600, 3000, 0.0010, 0.0008).map { s ->
            """{"t":${s.tMs},"kind":"sample","speed_mps":${s.speedMps},""" +
                """"fuel_kg_s":${s.fuelKgPerSec},"coolant_c":${s.coolantC}}"""
        }
        val summary = DriveLog.summarise(lines)!!
        assertNotNull(summary.coldStart)
        assertEquals(40.0, summary.coldStart!!.startCoolantC, 1e-9)
    }

    @Test
    fun `bands take the median and skip unmeasurable drives`() {
        fun r(c: Double, kg: Double?, s: Double? = 300.0) =
            ColdStart.Result(c, s, 0.0, 0.0, kg)
        val bands = ColdStart.bands(listOf(
            r(30.0, 0.08), r(35.0, 0.06), r(40.0, 0.50),   // cold: median 0.08
            r(50.0, 0.04),                                  // mid
            r(70.0, null),                                  // no baseline: skipped
            r(90.0, 0.0, 0.0)                               // warm
        ))
        assertEquals(3, bands.size, "the 65-85 band has no measurable drive")
        assertEquals(3, bands[0].drives)
        assertEquals(0.08, bands[0].medianExcessKg, 1e-12)
        assertEquals(0.04, bands[1].medianExcessKg, 1e-12)
        assertEquals(ColdStart.WARM_C, bands[2].fromC)
        assertEquals(0.0, bands[2].medianExcessKg)
    }

    @Test
    fun `an even band takes the mean of the middle two`() {
        val bands = ColdStart.bands(listOf(
            ColdStart.Result(30.0, 300.0, 0.0, 0.0, 0.02),
            ColdStart.Result(31.0, 500.0, 0.0, 0.0, 0.04)
        ))
        assertEquals(0.03, bands.single().medianExcessKg, 1e-12)
        assertEquals(400.0, bands.single().medianWarmUpSeconds!!, 1e-9)
    }

    // --- real logs ---

    private fun realLogs(): List<File> {
        val roots = listOf(File("logs"), File("../logs"))
        val root = roots.firstOrNull { it.isDirectory } ?: return emptyList()
        // Newest pull holds every drive still on the phone.
        val newest = root.listFiles { f -> f.isDirectory }
            ?.map { File(it, "drives") }?.filter { it.isDirectory }
            ?.maxByOrNull { it.parentFile.name } ?: return emptyList()
        return newest.listFiles { f -> f.name.endsWith(".ndjson") }?.toList() ?: emptyList()
    }

    @Test
    fun `on real drives a colder start costs more`() {
        val results = realLogs()
            .filter { it.length() > 300_000 }   // real drives, not desk launches
            .mapNotNull { ColdStart.analyse(DriveLog.parse(it.readLines())) }
            .filter { it.excessFuelKg != null }
        if (results.size < 20) return

        val xs = results.map { it.startCoolantC }
        val ys = results.map { it.excessFuelKg!! }
        val mx = xs.average(); val my = ys.average()
        val cov = xs.zip(ys).sumOf { (x, y) -> (x - mx) * (y - my) }
        val r = cov / sqrt(xs.sumOf { (it - mx) * (it - mx) } * ys.sumOf { (it - my) * (it - my) })
        assertTrue(r < -0.6, "expected a strong negative correlation, got r=$r over ${results.size}")

        // Warm starts cost (essentially) nothing; cold ones cost tens of grams.
        val warm = results.filter { it.startedWarm }
        val cold = results.filter { it.startCoolantC < 45.0 }
        if (warm.isNotEmpty()) assertTrue(warm.all { it.excessFuelKg!! == 0.0 })
        if (cold.size >= 5) {
            val median = cold.map { it.excessFuelKg!! }.sorted()[cold.size / 2]
            assertTrue(median in 0.02..0.2, "cold-start median excess $median kg")
        }
    }
}
