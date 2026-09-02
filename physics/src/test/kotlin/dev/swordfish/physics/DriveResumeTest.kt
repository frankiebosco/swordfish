package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Crash recovery, checked against the shapes real logs actually take.
 *
 * The 2026-08-24 drive produced both cases: two files killed mid-write by
 * the radar bitmap crash, and two closed cleanly. Those are the fixtures
 * these tests imitate.
 */
class DriveResumeTest {

    private val now = 1_787_593_000_000L

    private fun sample(t: Long, dvStart: Double? = null): String {
        val extra = if (dvStart != null) ""","dv_start":$dvStart""" else ""
        return """{"t":$t,"kind":"sample","state":"CRUISE","dv_mps":7128.9$extra}"""
    }

    private fun started(t: Long) = """{"t":$t,"kind":"drive","msg":"started"}"""
    private fun stopped(t: Long, rows: Int) =
        """{"t":$t,"kind":"drive","msg":"stopped","rows":$rows}"""

    @Test
    fun `a log with a stop row is not resumable`() {
        val lines = listOf(
            started(now - 60_000),
            sample(now - 50_000),
            stopped(now - 40_000, 1)
        )
        assertNull(
            DriveResume.inspect(lines, now),
            "a cleanly closed drive must never be resumed"
        )
        assertTrue(DriveResume.endedCleanly(lines))
    }

    @Test
    fun `a log killed mid-write IS resumable`() {
        // The shape of drive-20260824-122444.ndjson: started, samples, and
        // then nothing, because the process died.
        val lines = listOf(
            started(now - 120_000),
            sample(now - 60_000, dvStart = 8412.0),
            sample(now - 30_000, dvStart = 8412.0)
        )
        val r = DriveResume.inspect(lines, now)
        assertNotNull(r, "a log with no stop row and a recent last row must resume")
        assertEquals(now - 30_000, r.lastRowAtMs)
        assertEquals(8412.0, r.tripStartDeltaV)
        assertEquals(2, r.rows)
        assertTrue(!DriveResume.endedCleanly(lines))
    }

    @Test
    fun `a stale unclosed log is NOT resumed`() {
        // A crash nobody noticed, days ago. Appending today's drive to it
        // would invent a journey that never happened.
        val lines = listOf(
            started(now - 5 * 24 * 3600_000L),
            sample(now - 5 * 24 * 3600_000L + 1000)
        )
        assertNull(DriveResume.inspect(lines, now))
    }

    @Test
    fun `the resume window boundary is respected`() {
        val justInside = now - DriveResume.MAX_RESUME_GAP_MS + 1000
        assertNotNull(
            DriveResume.inspect(listOf(started(justInside), sample(justInside)), now)
        )
        val justOutside = now - DriveResume.MAX_RESUME_GAP_MS - 1000
        assertNull(
            DriveResume.inspect(listOf(started(justOutside), sample(justOutside)), now)
        )
    }

    @Test
    fun `a truncated final line does not abort the scan`() {
        // A process killed mid-write leaves a partial row. The rows before
        // it are still good and are exactly what we need.
        val lines = listOf(
            started(now - 60_000),
            sample(now - 40_000, dvStart = 7000.0),
            """{"t":${now - 30_000},"kind":"sample","sta"""   // cut off
        )
        val r = DriveResume.inspect(lines, now)
        assertNotNull(r, "a half-written last line must not lose the whole file")
        assertEquals(7000.0, r.tripStartDeltaV)
    }

    @Test
    fun `an empty or header-only log is not resumable`() {
        assertNull(DriveResume.inspect(emptyList(), now))
        assertNull(DriveResume.inspect(listOf(""), now))
        // A "started" row alone carries a timestamp, so it IS resumable --
        // that is a drive that crashed before its first sample, and
        // re-attaching to it is still better than orphaning the file.
        assertNotNull(DriveResume.inspect(listOf(started(now - 5_000)), now))
    }

    @Test
    fun `a log from the future is rejected`() {
        // Clock changes happen: a timezone hop or an NTP correction can put
        // rows ahead of now. Resuming one would compute a negative drive
        // duration everywhere downstream.
        val lines = listOf(started(now + 3600_000), sample(now + 3600_000))
        assertNull(DriveResume.inspect(lines, now))
    }

    @Test
    fun `dv_start is picked up from the last row that has it`() {
        // The field is conditional: absent until the fuel tracker seeds.
        // Recovery must find it wherever it first appears.
        val lines = listOf(
            started(now - 90_000),
            sample(now - 80_000),                       // no dv_start yet
            sample(now - 70_000, dvStart = 8412.0),
            sample(now - 60_000, dvStart = 8412.0)
        )
        val r = DriveResume.inspect(lines, now)
        assertNotNull(r)
        assertEquals(
            8412.0, r.tripStartDeltaV,
            "without this the trip cost silently restarts after a crash"
        )
    }

    @Test
    fun `field extraction handles negatives and decimals`() {
        val row = """{"t":123,"lat":40.996868,"lon":-77.914743,"alt":-4.45}"""
        assertEquals(123L, DriveResume.longField(row, "t"))
        assertEquals(40.996868, DriveResume.doubleField(row, "lat"))
        assertEquals(-77.914743, DriveResume.doubleField(row, "lon"))
        assertEquals(-4.45, DriveResume.doubleField(row, "alt"))
        assertNull(DriveResume.doubleField(row, "nope"))
    }
}
