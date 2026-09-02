package dev.swordfish.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadarTileTest {

    // Somewhere in the northeastern US -- the car's actual home, and the
    // coordinates the live service probe was verified against.
    private val lat = 40.85
    private val lon = -78.25

    // --- the request itself ---

    @Test
    fun `the url carries every parameter the service requires`() {
        val url = RadarTile.urlFor(lat, lon, 20)
        for (required in listOf(
            "service=WMS",
            "version=1.3.0",
            "request=GetMap",
            "layers=${RadarTile.LAYER}",
            "format=image%2Fpng",
            "transparent=true",
            "crs=EPSG%3A3857",
            "bbox=",
            "width=${RadarTile.IMAGE_PX}",
            "height=${RadarTile.IMAGE_PX}"
        )) {
            assertTrue(required in url, "missing '$required' in: $url")
        }
    }

    @Test
    fun `the url requests web mercator, not lat-lon`() {
        // EPSG:4326 would return a latitude-squashed picture: at 40N a degree
        // of longitude is 0.77 of a degree of latitude, so a storm 20 miles
        // north would not land on the same ring as one 20 miles east.
        val url = RadarTile.urlFor(lat, lon, 20)
        assertTrue("EPSG%3A3857" in url)
        assertFalse("4326" in url)
    }

    @Test
    fun `the url is keyless`() {
        // The whole reason this source was chosen. If an api key ever appears
        // here, the service changed and the choice needs revisiting.
        val url = RadarTile.urlFor(lat, lon, 20)
        assertFalse("key=" in url, "the nowCOAST endpoint takes no api key")
        assertFalse("token" in url.lowercase())
    }

    // --- the bounding box ---

    @Test
    fun `the box is square`() {
        // A non-square box stretches the weather and the range rings stop
        // meaning what they say.
        for (range in RadarLayout.RANGES_MILES) {
            val b = RadarTile.bboxFor(lat, lon, range)
            val w = b.maxX - b.minX
            val h = b.maxY - b.minY
            assertEquals(w, h, w * 1e-9, "box not square at ${range}mi")
        }
    }

    @Test
    fun `the box spans the full range in every direction`() {
        // THE HALVING TRAP: the scope shows `range` miles in EVERY direction,
        // so the box side is 2 * range, not range. Getting this wrong makes
        // every storm read as twice as close as it is.
        val range = 20
        val b = RadarTile.bboxFor(lat, lon, range)
        val (cx, _) = RadarTile.toMercator(lat, lon)

        val halfSpan = b.maxX - cx
        val groundHalfSpan = halfSpan * Math.cos(Math.toRadians(lat))
        val halfSpanMiles = groundHalfSpan / RadarTile.METRES_PER_MILE

        assertEquals(
            range.toDouble(), halfSpanMiles, 0.01,
            "half the box should be one full scope range"
        )
    }

    @Test
    fun `the box corrects for mercator scale distortion`() {
        // A metre of web mercator is not a metre on the ground -- the
        // projection stretches by 1/cos(lat). Without the correction the box
        // covers only 76% of the intended range at 40N and every ring lies.
        val range = 20
        val north = RadarTile.bboxFor(60.0, lon, range)
        val equator = RadarTile.bboxFor(0.0, lon, range)

        val northSpan = north.maxX - north.minX
        val equatorSpan = equator.maxX - equator.minX

        assertTrue(
            northSpan > equatorSpan,
            "a box at 60N must be WIDER in mercator metres than one at the " +
                "equator to cover the same ground distance"
        )
        // 1/cos(60) = 2.0
        assertEquals(2.0, northSpan / equatorSpan, 0.01)
    }

    @Test
    fun `the box is centred on the car`() {
        val b = RadarTile.bboxFor(lat, lon, 20)
        val (cx, cy) = RadarTile.toMercator(lat, lon)
        assertEquals(cx, (b.minX + b.maxX) / 2.0, 0.5)
        assertEquals(cy, (b.minY + b.maxY) / 2.0, 0.5)
    }

    @Test
    fun `a bigger range asks for a bigger box`() {
        var previous = 0.0
        for (range in RadarLayout.RANGES_MILES.sorted()) {
            val b = RadarTile.bboxFor(lat, lon, range)
            val span = b.maxX - b.minX
            assertTrue(span > previous, "range $range did not grow the box")
            previous = span
        }
    }

    @Test
    fun `the bbox parameter is in WMS 1_3_0 order`() {
        val b = RadarTile.BBox(1.0, 2.0, 3.0, 4.0)
        assertEquals("1.0,2.0,3.0,4.0", b.toParam())
    }

    // --- mercator projection ---

    @Test
    fun `the prime meridian and equator project to the origin`() {
        val (x, y) = RadarTile.toMercator(0.0, 0.0)
        assertEquals(0.0, x, 0.001)
        assertEquals(0.0, y, 0.001)
    }

    @Test
    fun `north and east are positive`() {
        val (x, y) = RadarTile.toMercator(40.0, 10.0)
        assertTrue(x > 0, "east should be positive x")
        assertTrue(y > 0, "north should be positive y")
    }

    @Test
    fun `an extreme latitude is clamped rather than sent to infinity`() {
        // Mercator runs to infinity at the poles. No road is there, but a bad
        // fix might claim one, and an infinity in a bbox produces a request
        // the service rejects outright.
        val (_, y) = RadarTile.toMercator(89.9, 0.0)
        assertTrue(y.isFinite(), "a polar latitude must not produce infinity")

        val (_, ySouth) = RadarTile.toMercator(-89.9, 0.0)
        assertTrue(ySouth.isFinite())
    }

    @Test
    fun `a box near a pole is still finite`() {
        val b = RadarTile.bboxFor(89.9, 0.0, 20)
        assertTrue(b.minX.isFinite() && b.maxX.isFinite())
        assertTrue(b.minY.isFinite() && b.maxY.isFinite())
    }

    // --- refetch policy ---

    @Test
    fun `a fresh image at a standstill is not refetched`() {
        assertFalse(RadarTile.shouldRefetch(ageSeconds = 10.0, movedMiles = 0.0, rangeMiles = 20))
    }

    @Test
    fun `an old image is refetched even parked`() {
        // The weather moves even when the car does not.
        assertTrue(
            RadarTile.shouldRefetch(
                ageSeconds = RadarTile.REFRESH_SECONDS + 1,
                movedMiles = 0.0,
                rangeMiles = 20
            )
        )
    }

    @Test
    fun `a fresh image is refetched once the car has driven far enough`() {
        // The box is centred on the car, so distance makes an image wrong in
        // POSITION even while it is still young.
        val far = 20 * RadarTile.REFETCH_DISTANCE_FRACTION + 0.1
        assertTrue(
            RadarTile.shouldRefetch(ageSeconds = 5.0, movedMiles = far, rangeMiles = 20)
        )
    }

    @Test
    fun `a longer range tolerates more movement before refetching`() {
        // Two miles matters on a 10-mile scope and is invisible on an
        // 80-mile one, so the threshold scales with the range.
        val moved = 5.0
        assertTrue(
            RadarTile.shouldRefetch(ageSeconds = 5.0, movedMiles = moved, rangeMiles = 10),
            "5 miles should refetch a 10-mile scope"
        )
        assertFalse(
            RadarTile.shouldRefetch(ageSeconds = 5.0, movedMiles = moved, rangeMiles = 80),
            "5 miles should not refetch an 80-mile scope"
        )
    }

    @Test
    fun `the refresh interval respects the source's update rate`() {
        // MRMS mosaics update about every 4 minutes. Polling faster spends a
        // driver's data on the same picture, from a free public service.
        assertTrue(
            RadarTile.REFRESH_SECONDS >= 120.0,
            "refreshing faster than the mosaic updates is wasted bandwidth"
        )
    }

    // --- fix validation ---

    @Test
    fun `a real fix is usable`() {
        assertTrue(RadarTile.isUsableFix(lat, lon))
    }

    @Test
    fun `null island is rejected`() {
        // (0,0) is the Atlantic, and is what an uninitialised Location
        // reports. Accepting it would draw the Gulf of Guinea's weather.
        assertFalse(RadarTile.isUsableFix(0.0, 0.0))
    }

    @Test
    fun `impossible coordinates are rejected`() {
        assertFalse(RadarTile.isUsableFix(91.0, 0.0))
        assertFalse(RadarTile.isUsableFix(0.0, 181.0))
        assertFalse(RadarTile.isUsableFix(Double.NaN, lon))
        assertFalse(RadarTile.isUsableFix(lat, Double.NaN))
    }

    @Test
    fun `a fix on a meridian or the equator alone is still usable`() {
        // Only BOTH being zero is the sentinel; one axis at zero is a real
        // place a car can be.
        assertTrue(RadarTile.isUsableFix(0.0, -78.25))
        assertTrue(RadarTile.isUsableFix(40.85, 0.0))
    }

    // --- distance ---

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, RadarTile.distanceMiles(lat, lon, lat, lon), 1e-9)
    }

    @Test
    fun `a degree of latitude is about sixty-nine miles`() {
        val d = RadarTile.distanceMiles(40.0, -78.0, 41.0, -78.0)
        assertEquals(69.0, d, 0.5)
    }

    @Test
    fun `distance is symmetric`() {
        val a = RadarTile.distanceMiles(40.0, -78.0, 41.0, -75.0)
        val b = RadarTile.distanceMiles(41.0, -75.0, 40.0, -78.0)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `distance across the date line is short, not half the planet`() {
        // A longitude subtraction would report ~25000 miles here. Haversine
        // does not care that the number wrapped.
        val d = RadarTile.distanceMiles(0.0, 179.9, 0.0, -179.9)
        assertTrue(d < 20.0, "date-line crossing measured as $d miles")
    }
}
