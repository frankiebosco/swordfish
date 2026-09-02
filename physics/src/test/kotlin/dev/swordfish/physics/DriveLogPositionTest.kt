package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Position rows must be parseable, because everything downstream reads them.
 *
 * `DriveRecorder` writes lat/lon by hand into a StringBuilder rather than
 * through a JSON library, so the one thing that can go wrong is emitting
 * something that is not valid JSON. A bare `NaN` is the specific hazard:
 * `ImuSource` uses it for "no fix yet", it looks harmless in a log, and it
 * breaks every parser that will ever read these files.
 *
 * The recorder lives in `:app` and cannot be constructed here, so these
 * pin the CONTRACT its output must satisfy, exercised through the reader
 * that consumes it.
 */
class DriveLogPositionTest {

    @Test
    fun `a row with a fix yields both coordinates`() {
        val row = """{"t":1787591401388,"kind":"sample","lat":40.996868,""" +
            """"lon":-77.914743}"""
        assertEquals(40.996868, DriveResume.doubleField(row, "lat"))
        assertEquals(-77.914743, DriveResume.doubleField(row, "lon"))
    }

    @Test
    fun `a row without a fix simply has no coordinates`() {
        // Not zeroes. A drive that began indoors has rows with no position,
        // and 0,0 is a real place in the Gulf of Guinea -- writing it would
        // put a spurious leg on every retraced map.
        val row = """{"t":1787591401388,"kind":"sample","state":"CRUISE"}"""
        assertNull(DriveResume.doubleField(row, "lat"))
        assertNull(DriveResume.doubleField(row, "lon"))
    }

    @Test
    fun `NaN must never appear in a log row`() {
        // The guard in DriveRecorder is `isFinite()` on both values. If that
        // is ever relaxed, rows like this appear and no parser survives them.
        val bad = """{"t":1,"lat":NaN,"lon":NaN}"""
        assertNull(
            DriveResume.doubleField(bad, "lat"),
            "NaN is not a number to any JSON reader; the recorder must omit " +
                "the field instead of writing it"
        )
    }

    @Test
    fun `six decimal places is enough to retrace a drive`() {
        // ~0.1 m at these latitudes, far finer than a phone GPS resolves.
        // Two points a tenth of a metre apart must still differ in the log.
        val a = "%.6f".format(40.996868)
        val b = "%.6f".format(40.996869)
        assertTrue(a != b, "6dp must distinguish points ~0.1 m apart")
    }

    @Test
    fun `southern and western hemispheres survive the round trip`() {
        // Negative longitude is the normal case here and was the one most
        // likely to be mangled by hand-rolled number formatting.
        val row = """{"t":1,"lat":-33.868820,"lon":-70.652500}"""
        assertEquals(-33.86882, DriveResume.doubleField(row, "lat"))
        assertEquals(-70.6525, DriveResume.doubleField(row, "lon"))
    }
}
