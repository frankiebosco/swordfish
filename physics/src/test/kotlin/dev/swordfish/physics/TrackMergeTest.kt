package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Merging drives without erasing the out-and-back.
 *
 * the ridge road gets driven repeatedly, in both directions. The measurements that shaped
 * this are in [TrackMerge]'s own docs: 79.8-100% overlap between recordings,
 * 1440-vs-3 opposite-to-same heading agreement at overlaps, and a median
 * 17,507 s Isp difference between the two directions at the same spot.
 *
 * The last number is why averaging is not an option: the same hill is a climb
 * one way and a coast the other, and that IS the map.
 */
class TrackMergeTest {

    private var clock = 0L

    private fun sample(
        lat: Double, lon: Double, isp: Double = 30_000.0, speed: Double = 20.0
    ): DriveLog.Sample {
        clock += 1000
        return DriveLog.Sample(
            tMs = clock, speedMps = speed, rpm = 2500.0, fuelKgPerSec = 0.0006,
            fuelRemainingKg = 30.0, ispS = isp, deltaVMps = 7000.0,
            altitudeM = 30.0, coolantC = 88.0, lat = lat, lon = lon,
            state = "CRUISE", dfco = false
        )
    }

    /** A straight run north along one line of longitude. */
    private fun northbound(n: Int, isp: Double) =
        (0 until n).map { sample(41.000 + it * 0.0005, -77.930, isp) }

    /** The same road, southbound. */
    private fun southbound(n: Int, isp: Double) =
        (0 until n).map { sample(41.000 + (n - 1 - it) * 0.0005, -77.930, isp) }

    @Test
    fun `an out-and-back keeps both directions separate`() {
        clock = 0
        val out = TrackMerge.Source("out", northbound(20, isp = 35_000.0))
        val back = TrackMerge.Source("back", southbound(20, isp = 12_000.0))
        val cells = TrackMerge.mergeByMotion(listOf(out, back))

        val dirs = cells.map { TrackMerge.bucketOf(it.headingDeg) }.toSet()
        assertTrue(
            dirs.size >= 2,
            "the two directions of the same road must not collapse into one; " +
                "got ${dirs.size} direction bucket(s)"
        )
    }

    @Test
    fun `the two directions keep their own efficiency`() {
        // THE POINT. A good run one way and a poor run back must remain
        // distinguishable -- averaging them to a bland middle erases the
        // uphill/downhill story the map exists to tell.
        clock = 0
        val cells = TrackMerge.mergeByMotion(listOf(
            TrackMerge.Source("out", northbound(20, isp = 38_000.0)),
            TrackMerge.Source("back", southbound(20, isp = 10_000.0))
        ))
        val effs = cells.mapNotNull { it.efficiency }
        assertTrue(effs.isNotEmpty(), "cells should carry efficiency")
        val spread = effs.max() - effs.min()
        assertTrue(
            spread > 0.4,
            "the two directions should differ strongly in efficiency; spread " +
                "was only ${"%.2f".format(spread)}"
        )
    }

    @Test
    fun `driving the same way twice DOES merge`() {
        // The other half of the contract: repeated passes in the same
        // direction are the same data and should stack, not multiply.
        clock = 0
        val a = TrackMerge.Source("a", northbound(20, isp = 30_000.0))
        clock = 0
        val b = TrackMerge.Source("b", northbound(20, isp = 30_000.0))
        val cells = TrackMerge.mergeByMotion(listOf(a, b))

        assertTrue(
            cells.any { it.drives == 2 },
            "two passes the same way must share cells; drive counts were " +
                cells.map { it.drives }.distinct()
        )
    }

    @Test
    fun `a cell records how many drives contributed`() {
        clock = 0
        val srcs = (1..3).map {
            clock = 0
            TrackMerge.Source("drive$it", northbound(20, isp = 30_000.0))
        }
        val cells = TrackMerge.mergeByMotion(srcs)
        assertTrue(
            cells.any { it.drives == 3 },
            "a stretch driven three times should say so"
        )
    }

    @Test
    fun `the best pass is retained for a future personal-best view`() {
        clock = 0
        val poor = TrackMerge.Source("poor", northbound(20, isp = 12_000.0))
        clock = 0
        val good = TrackMerge.Source("good", northbound(20, isp = 38_000.0))
        val cells = TrackMerge.mergeByMotion(listOf(poor, good))
        val shared = cells.first { it.drives == 2 }
        assertNotNull(shared.bestEfficiency)
        assertTrue(
            shared.bestEfficiency!! > shared.efficiency!!,
            "the best pass must beat the average of the passes"
        )
    }

    @Test
    fun `a stopped car contributes nothing`() {
        // Its bearing is noise, and a long wait at a light would otherwise
        // dominate whichever cell it happened to sit in.
        clock = 0
        val moving = northbound(10, isp = 30_000.0)
        val parked = (0 until 200).map { sample(41.0005, -77.930, speed = 0.0) }
        val cells = TrackMerge.mergeByMotion(
            listOf(TrackMerge.Source("d", moving + parked))
        )
        assertTrue(
            cells.all { it.samples < 100 },
            "200 stationary samples leaked into a cell"
        )
    }

    @Test
    fun `heading averaging wraps correctly around north`() {
        // 359 and 1 average to 0, not 180. A naive mean would point the
        // arrow backwards on every northbound road.
        clock = 0
        val pts = listOf(
            sample(41.0000, -77.9300), sample(41.0005, -77.9300),
            sample(41.0010, -77.9300), sample(41.0015, -77.9300)
        )
        val cells = TrackMerge.mergeByMotion(listOf(TrackMerge.Source("n", pts)))
        for (c in cells) {
            val h = c.headingDeg
            assertTrue(
                h < 45.0 || h > 315.0,
                "a due-north run averaged to ${"%.0f".format(h)} degrees"
            )
        }
    }

    @Test
    fun `direction buckets separate a reversal but tolerate a bend`() {
        assertEquals(TrackMerge.bucketOf(0.0), TrackMerge.bucketOf(30.0),
            "a 30 degree bend should stay in one bucket")
        assertTrue(TrackMerge.bucketOf(0.0) != TrackMerge.bucketOf(180.0),
            "a reversal must change bucket")
        assertTrue(TrackMerge.sameDirection(10.0, 350.0), "wrap-around")
        assertTrue(!TrackMerge.sameDirection(0.0, 180.0), "a reversal is not the same")
    }

    @Test
    fun `one cell of northward travel advances one cell`() {
        val size = TrackMerge.DEFAULT_CELL_METERS
        val a = TrackMerge.cellOf(41.0, size)
        val b = TrackMerge.cellOf(41.0 + size / 111_320.0, size)
        assertEquals(1, b - a, "${size.toInt()} m north should advance one cell")
    }

    @Test
    fun `cell size is configurable`() {
        // Exposed so the logbook can offer a detail/aggregate toggle without
        // another code change. Measured on the ridge-road drives: 25 m merges 55.8%
        // of overlapping road, 40 m merges 67.7%, 100 m merges 79.2%.
        clock = 0
        val a = TrackMerge.Source("a", northbound(40, isp = 30_000.0))
        clock = 0
        val b = TrackMerge.Source("b", northbound(40, isp = 30_000.0))

        val fine = TrackMerge.mergeByMotion(listOf(a, b), cellMeters = 20.0)
        val coarse = TrackMerge.mergeByMotion(listOf(a, b), cellMeters = 200.0)

        assertTrue(
            coarse.size < fine.size,
            "a coarser grid must produce fewer cells: ${coarse.size} vs ${fine.size}"
        )
    }
}
