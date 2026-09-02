package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The retrace projection.
 *
 * A track drawn wrong is worse than none: it looks authoritative and is
 * quietly lying about a route the driver knows by heart.
 */
class DriveTrackTest {

    private fun s(
        lat: Double?, lon: Double?, speed: Double = 20.0,
        isp: Double? = 30_000.0, dfco: Boolean = false
    ) = DriveLog.Sample(
        tMs = 0, speedMps = speed, rpm = 2500.0, fuelKgPerSec = 0.0006,
        fuelRemainingKg = 30.0, ispS = isp, deltaVMps = 7000.0,
        altitudeM = 30.0, coolantC = 88.0, lat = lat, lon = lon,
        state = "CRUISE", dfco = dfco
    )

    @Test
    fun `samples without a fix are dropped`() {
        val t = DriveTrack.build(listOf(s(null, null), s(null, null)))
        assertTrue(t.isEmpty, "a drive with no fixes has no track")
    }

    @Test
    fun `a single fix is not a track`() {
        val t = DriveTrack.build(listOf(s(40.99, -77.91)))
        assertTrue(t.isEmpty, "one point cannot be drawn as a path")
    }

    @Test
    fun `all points land inside the canvas`() {
        val pts = (0..20).map { s(40.99 + it * 0.001, -77.91 + it * 0.002) }
        val t = DriveTrack.build(pts)
        assertTrue(!t.isEmpty)
        for (p in t.points) {
            assertTrue(
                p.x in -0.01..1.01 && p.y in -0.01..1.01,
                "point (${p.x}, ${p.y}) falls outside the canvas"
            )
        }
    }

    @Test
    fun `north is up`() {
        // Driving north must move UP the canvas, i.e. y decreases. Getting
        // this backwards produces a mirrored route that still looks
        // plausible, which is the worst kind of wrong.
        val t = DriveTrack.build(listOf(s(40.99, -77.91), s(41.00, -77.91)))
        assertTrue(
            t.points.last().y < t.points.first().y,
            "a northward leg must go up the canvas"
        )
    }

    @Test
    fun `east is right`() {
        val t = DriveTrack.build(listOf(s(40.99, -77.92), s(40.99, -77.91)))
        assertTrue(
            t.points.last().x > t.points.first().x,
            "an eastward leg must go right"
        )
    }

    @Test
    fun `aspect ratio is preserved`() {
        // A drive twice as tall as it is wide must DRAW twice as tall.
        // Stretching to fill the box would make a straight run look curved.
        val pts = listOf(
            s(40.980, -77.910),
            s(40.990, -77.910),
            s(41.000, -77.9055)
        )
        val t = DriveTrack.build(pts)
        val xs = t.points.map { it.x }
        val ys = t.points.map { it.y }
        val drawnW = xs.max() - xs.min()
        val drawnH = ys.max() - ys.min()
        val realRatio = t.heightMeters / t.widthMeters
        val drawnRatio = drawnH / drawnW
        assertEquals(
            realRatio, drawnRatio, realRatio * 0.05,
            "the drawn shape must have the same proportions as the real one"
        )
    }

    @Test
    fun `longitude is scaled by latitude`() {
        // Without the cosine, a degree of longitude is treated as a degree
        // of latitude and the track is stretched ~25% east-west here.
        val t = DriveTrack.build(listOf(s(40.99, -77.91), s(40.99, -77.90)))
        // 0.01 deg lon at 41N is ~840 m, not ~1113 m.
        assertTrue(
            t.widthMeters in 780.0..900.0,
            "0.01 deg of longitude at 41N should be ~840 m, got ${t.widthMeters}"
        )
    }

    @Test
    fun `efficiency colours only real driving`() {
        assertNull(
            DriveTrack.ispEfficiency(s(40.99, -77.91, speed = 0.0)),
            "a stopped car has no efficiency worth painting"
        )
        assertNull(
            DriveTrack.ispEfficiency(s(40.99, -77.91, dfco = true)),
            "fuel cutoff is infinite efficiency and would swamp the ramp"
        )
        assertNull(
            DriveTrack.ispEfficiency(s(40.99, -77.91, isp = null)),
            "no Isp, no colour"
        )
        val good = DriveTrack.ispEfficiency(s(40.99, -77.91, isp = 35_000.0))
        val bad = DriveTrack.ispEfficiency(s(40.99, -77.91, isp = 10_000.0))
        assertNotNull(good); assertNotNull(bad)
        assertTrue(good > bad, "higher Isp must read as better")
        assertTrue(good in 0.0..1.0 && bad in 0.0..1.0)
    }

    @Test
    fun `efficiency is clamped at the ends of the ramp`() {
        assertEquals(0.0, DriveTrack.ispEfficiency(s(40.99, -77.91, isp = 1.0))!!)
        assertEquals(1.0, DriveTrack.ispEfficiency(s(40.99, -77.91, isp = 99_999.0))!!)
    }

    @Test
    fun `path length is close to the distance actually driven`() {
        // A straight 1 km leg north.
        val a = s(40.990, -77.910)
        val b = s(40.999, -77.910)   // ~1002 m
        val t = DriveTrack.build(listOf(a, b))
        assertEquals(
            1002.0, DriveTrack.pathLengthMeters(t), 30.0,
            "the drawn path must measure back to roughly the real distance"
        )
    }

    @Test
    fun `a stationary drive still produces a finite box`() {
        // Idling in a driveway: every fix identical. This must not divide
        // by zero or produce NaN coordinates.
        val t = DriveTrack.build(listOf(s(40.99, -77.91), s(40.99, -77.91)))
        for (p in t.points) {
            assertTrue(p.x.isFinite() && p.y.isFinite(), "NaN in the track")
        }
    }
}
