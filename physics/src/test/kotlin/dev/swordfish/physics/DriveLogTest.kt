package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The logbook's arithmetic, checked against cases real drives produce.
 */
class DriveLogTest {

    private val t0 = 1_787_591_401_388L

    private fun row(
        dt: Long, speed: Double?, fuel: Double? = null, isp: Double? = null,
        dv: Double? = null, dvStart: Double? = null, dfco: Boolean = false,
        lat: Double? = null, lon: Double? = null
    ): String {
        val sb = StringBuilder("""{"t":${t0 + dt},"kind":"sample","state":"CRUISE"""")
        speed?.let { sb.append(""","speed_mps":$it""") }
        fuel?.let { sb.append(""","fuel_kg_s":$it""") }
        isp?.let { sb.append(""","isp_s":$it""") }
        dv?.let { sb.append(""","dv_mps":$it""") }
        dvStart?.let { sb.append(""","dv_start":$it""") }
        sb.append(""","dfco":$dfco""")
        if (lat != null && lon != null) {
            sb.append(""","lat":$lat,"lon":$lon""")
        }
        sb.append("}")
        return sb.toString()
    }

    @Test
    fun `distance integrates speed over time`() {
        // 10 m/s held for 10 s = 100 m.
        val lines = (0..10).map { row(it * 1000L, 10.0) }
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        assertEquals(100.0, s.distanceMeters, 0.5)
    }

    @Test
    fun `a gap in the log does not invent distance`() {
        // A crash leaves a hole. Integrating across it would credit the car
        // with every metre it drove while nothing was recording -- distance
        // the log did not witness.
        val lines = listOf(
            row(0, 10.0),
            row(1_000, 10.0),
            row(600_000, 10.0),   // ten minutes later
            row(601_000, 10.0)
        )
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        assertEquals(
            20.0, s.distanceMeters, 0.5,
            "only the two contiguous 1 s intervals should count"
        )
    }

    @Test
    fun `moving and idle time are separated`() {
        val lines = listOf(
            row(0, 0.0), row(1000, 0.0), row(2000, 0.0),  // stopped
            row(3000, 15.0), row(4000, 15.0)              // moving
        )
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        assertEquals(2.0, s.idleSeconds, 0.01)
        assertEquals(2.0, s.movingSeconds, 0.01)
    }

    @Test
    fun `delta-V spent is start minus end`() {
        val lines = listOf(
            row(0, 10.0, dv = 8412.0, dvStart = 8412.0),
            row(1000, 10.0, dv = 8000.0, dvStart = 8412.0),
            row(2000, 10.0, dv = 7129.0, dvStart = 8412.0)
        )
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        assertEquals(8412.0, s.deltaVStartMps)
        assertEquals(7129.0, s.deltaVEndMps)
        assertEquals(1283.0, s.deltaVSpentMps!!, 0.01)
    }

    @Test
    fun `delta-V spent is null when the trip never seeded`() {
        // dv_start is absent until the fuel tracker seeds. A drive that
        // ended before then has no trip cost, and must say so rather than
        // report a fabricated zero.
        val lines = listOf(row(0, 10.0, dv = 8000.0), row(1000, 10.0, dv = 7999.0))
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        assertNull(s.deltaVSpentMps)
    }

    @Test
    fun `mpg comes out in a believable range`() {
        // 30 m/s for 100 s = 3000 m. At 0.0006 kg/s that is 0.06 kg of fuel.
        val lines = (0..100).map { row(it * 1000L, 30.0, fuel = 0.0006) }
        val s = DriveLog.summarise(lines)
        assertNotNull(s)
        val mpg = s.mpg
        assertNotNull(mpg, "a drive that used fuel and covered ground has an mpg")
        assertTrue(
            mpg in 5.0..120.0,
            "mpg of $mpg is outside anything a car produces -- check the unit " +
                "conversion, not the test"
        )
    }

    @Test
    fun `an empty or sample-free log summarises to null`() {
        assertNull(DriveLog.summarise(emptyList()))
        assertNull(DriveLog.summarise(listOf(
            """{"t":1,"kind":"drive","msg":"started"}"""
        )))
    }

    @Test
    fun `a truncated last line does not lose the drive`() {
        val lines = listOf(
            row(0, 10.0), row(1000, 10.0), """{"t":123,"kind":"sam"""
        )
        val s = DriveLog.summarise(lines)
        assertNotNull(s, "a half-written row must not discard the whole log")
        assertEquals(2, s.rows)
    }

    @Test
    fun `an unclean log is reported as unclean`() {
        val open = listOf(row(0, 10.0), row(1000, 10.0))
        assertTrue(!DriveLog.summarise(open)!!.endedCleanly)

        val closed = open + """{"t":9,"kind":"drive","msg":"stopped","rows":2}"""
        assertTrue(DriveLog.summarise(closed)!!.endedCleanly)
    }

    @Test
    fun `a resumed drive is flagged`() {
        val lines = listOf(
            row(0, 10.0),
            """{"t":5,"kind":"drive","msg":"resumed","after_ms":1200}""",
            row(1000, 10.0)
        )
        assertTrue(DriveLog.summarise(lines)!!.wasResumed)
    }

    @Test
    fun `rows with a fix are counted`() {
        val lines = listOf(
            row(0, 10.0),
            row(1000, 10.0, lat = 40.9968, lon = -77.9147),
            row(2000, 10.0, lat = 40.9969, lon = -77.9148)
        )
        assertEquals(2, DriveLog.summarise(lines)!!.samplesWithFix)
    }
}
