package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surveyed-elevation source.
 *
 * Bodies here are REAL responses captured from the service on 2026-08-25,
 * not invented ones -- the `value` field is a string whose precision varies
 * between replies, which is exactly the kind of thing a hand-written fixture
 * gets wrong.
 */
class ElevationQueryTest {

    /** Sparkill NY, from the live service. */
    private val sparkill =
        """{"location":{"x":-77.9233,"y":41.0283,"spatialReference":{"wkid":4326,""" +
            """"latestWkid":4326}},"locationId":0,"value":"24.542154312",""" +
            """"rasterId":89882,"resolution":1}"""

    /** The ridge, from the live service. Note the longer resolution field. */
    private val ridge =
        """{"location":{"x":-77.91557,"y":40.99397,"spatialReference":{"wkid":4326,""" +
            """"latestWkid":4326}},"locationId":0,"value":"135.350021362",""" +
            """"rasterId":97238,"resolution":1.0000000049939985}"""

    @Test
    fun `a real Sparkill response parses`() {
        assertEquals(24.542154312, ElevationQuery.parseMeters(sparkill)!!, 1e-6)
    }

    @Test
    fun `a real ridge response parses`() {
        val v = ElevationQuery.parseMeters(ridge)!!
        assertEquals(135.35, v, 0.01)
        // Sanity against the terrain the drive actually crossed.
        assertTrue(v in 100.0..170.0, "the ridge should be well above 100 m")
    }

    @Test
    fun `varying precision does not break parsing`() {
        // The service returns anything from "24.5" to "135.350021362".
        for (s in listOf("12", "12.6", "135.350021362", "-3.25")) {
            val body = """{"value":"$s","rasterId":1}"""
            assertEquals(s.toDouble(), ElevationQuery.parseMeters(body)!!, 1e-9)
        }
    }

    @Test
    fun `the out-of-coverage sentinel is rejected`() {
        // 3DEP answers -1000000 outside its coverage. Taking that literally
        // would put the car below the Marianas Trench.
        val body = """{"value":"-1000000","rasterId":0}"""
        assertNull(ElevationQuery.parseMeters(body))
    }

    @Test
    fun `a garbled body yields null rather than throwing`() {
        assertNull(ElevationQuery.parseMeters(""))
        assertNull(ElevationQuery.parseMeters("<html>503</html>"))
        assertNull(ElevationQuery.parseMeters("""{"error":"bad request"}"""))
        assertNull(ElevationQuery.parseMeters("""{"value":"not a number"}"""))
    }

    @Test
    fun `the URL carries lon as x and lat as y`() {
        // Easy to transpose, and transposing puts the query in the Indian
        // Ocean where 3DEP has no coverage -- so it fails quietly rather
        // than loudly.
        val url = ElevationQuery.urlFor(latDeg = 41.0283, lonDeg = -77.9233)
        assertTrue(url.contains("x=-77.9233"), "longitude must be x: $url")
        assertTrue(url.contains("y=41.0283"), "latitude must be y: $url")
        assertTrue(url.contains("units=Meters"))
    }

    @Test
    fun `rate limiting is respected before anything else`() {
        // MEASURED: 14 back-to-back calls all failed; the same 14 spaced
        // 1.2 s apart all returned 200. Politeness to a free government
        // endpoint is not optional.
        assertTrue(
            !ElevationQuery.shouldQuery(
                Double.NaN, Double.NaN, 41.0, -77.9,
                sinceLastCallMs = 100
            ),
            "a call 100 ms after the last must be refused"
        )
    }

    @Test
    fun `the first fix always queries`() {
        assertTrue(
            ElevationQuery.shouldQuery(
                Double.NaN, Double.NaN, 41.0283, -77.9233,
                sinceLastCallMs = 5_000
            )
        )
    }

    @Test
    fun `standing still does not re-query`() {
        // Terrain does not change. A car at a light must not hammer the
        // service for an answer it already has.
        assertTrue(
            !ElevationQuery.shouldQuery(
                41.0283, -77.9233, 41.02831, -77.92331,
                sinceLastCallMs = 60_000
            )
        )
    }

    @Test
    fun `moving far enough re-queries`() {
        // ~200 m north of the last query point.
        assertTrue(
            ElevationQuery.shouldQuery(
                41.0283, -77.9233, 41.0301, -77.9233,
                sinceLastCallMs = 60_000
            )
        )
    }

    @Test
    fun `an unusable fix is never queried`() {
        assertTrue(
            !ElevationQuery.shouldQuery(
                Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                sinceLastCallMs = 60_000
            )
        )
    }

    @Test
    fun `interpolation applies the barometric CHANGE, never its absolute`() {
        // The whole design in one assertion. The surveyed value is the
        // authority; the barometer contributes only the delta since it was
        // taken, which is what weather cannot affect over seconds.
        //
        // A barometer reading 12 m too high (a low-pressure day) must not
        // drag the answer up by 12 m.
        val surveyed = 135.4          // USGS at the ridge
        val baroThen = 147.5          // barometer, 12.1 m offset
        val baroNow = 152.5           // climbed 5 m since
        assertEquals(
            140.4,
            ElevationQuery.interpolate(surveyed, baroThen, baroNow),
            0.01
        )
    }

    @Test
    fun `interpolation returns the surveyed value when nothing has changed`() {
        assertEquals(
            24.5,
            ElevationQuery.interpolate(24.5, 36.6, 36.6),
            1e-9
        )
    }

    @Test
    fun `the weather offset cancels out of the interpolation`() {
        // The same climb on two days with a 20 hPa pressure difference --
        // roughly 166 m of apparent barometric shift -- must produce the
        // same answer, because only the DELTA is used.
        val surveyed = 100.0
        val dayA = ElevationQuery.interpolate(surveyed, 112.0, 132.0)
        val dayB = ElevationQuery.interpolate(surveyed, 278.0, 298.0)
        assertEquals(
            dayA, dayB, 1e-9,
            "a 166 m weather shift changed the answer; only the delta may count"
        )
    }
}
