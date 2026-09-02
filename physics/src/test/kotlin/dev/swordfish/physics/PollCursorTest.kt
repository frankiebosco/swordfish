package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scheduler, driven through simulated time.
 *
 * The failure modes here — a starved slow tier, a fast tier that drifts
 * because each PID waits a full cycle of the others — look like "the gauge
 * is a bit laggy" and are undiagnosable on a head unit at 60 mph. Driving
 * thousands of ticks in a test is the only place they are visible.
 */
class PollCursorTest {

    @Test
    fun `the first cycle fetches every PID once`() {
        // Nothing has a value yet, so everything is maximally overdue.
        // Without this the panel would show dashes for the slow tier for
        // the first ten seconds of every drive.
        val c = PollCursor()
        val seen = mutableSetOf<String>()
        var now = 0L
        repeat(PollSchedule.ALL.size) {
            val pid = c.nextDue(now)
            assertNotNull(pid, "expected every PID to be due on the first pass")
            seen += pid
            c.markPolled(pid, now)
            c.record(pid, listOf(0x00), now)
            now += 5
        }
        assertEquals(PollSchedule.ALL.map { it.pid }.toSet(), seen)
    }

    @Test
    fun `nothing is due immediately after a full sweep`() {
        val c = PollCursor()
        var now = 0L
        for (e in PollSchedule.ALL) {
            c.markPolled(e.pid, now)
            c.record(e.pid, listOf(0x00), now)
            now += 1
        }
        assertNull(c.nextDue(now))
    }

    @Test
    fun `the fast tier comes due long before the slow tier`() {
        val c = PollCursor()
        var now = 0L
        for (e in PollSchedule.ALL) {
            c.markPolled(e.pid, now)
            c.record(e.pid, listOf(0x00), now)
        }

        // 150 ms later: the 10 Hz PIDs (100 ms) are due, the 1 Hz and
        // 0.1 Hz ones are not.
        now += 150
        val due = c.nextDue(now)
        assertTrue(
            due in PollSchedule.FAST.map { it.pid },
            "expected a fast-tier PID at +150ms, got $due"
        )
    }

    @Test
    fun `the slow tier is not starved by the fast tier`() {
        // The real risk of a most-overdue scheduler: RPM comes due ten
        // times a second forever, so a naive implementation never gets to
        // tank level. Overdue-ness is measured in intervals precisely so a
        // slow PID eventually outranks a fast one.
        val c = PollCursor()
        var now = 0L
        val counts = mutableMapOf<String, Int>()

        // Simulate 30 seconds at a request every 25 ms (40 cmd/s).
        while (now < 30_000L) {
            val pid = c.nextDue(now)
            if (pid != null) {
                c.markPolled(pid, now)
                c.record(pid, listOf(0x00), now)
                counts[pid] = (counts[pid] ?: 0) + 1
            }
            now += 25
        }

        for (e in PollSchedule.SLOW) {
            val n = counts[e.pid] ?: 0
            assertTrue(n >= 2, "${e.pid} was polled only $n times in 30s — starved")
        }
    }

    @Test
    fun `the fast tier gets roughly its scheduled rate`() {
        val c = PollCursor()
        var now = 0L
        val counts = mutableMapOf<String, Int>()

        while (now < 10_000L) {
            val pid = c.nextDue(now)
            if (pid != null) {
                c.markPolled(pid, now)
                c.record(pid, listOf(0x00), now)
                counts[pid] = (counts[pid] ?: 0) + 1
            }
            now += 25
        }

        // 10 Hz over 10 s is ~100 reads. Allow slack for the 25 ms
        // granularity and for competing tiers.
        for (e in PollSchedule.FAST) {
            val n = counts[e.pid] ?: 0
            assertTrue(n in 60..110, "${e.pid} got $n reads in 10s, expected ~100")
        }
    }

    // --- Staleness ---

    @Test
    fun `a fresh reading is returned`() {
        val c = PollCursor()
        c.record(ObdPid.ENGINE_RPM, listOf(0x1A, 0xF8), 1000L)
        assertNotNull(c.fresh(ObdPid.ENGINE_RPM, 1050L))
    }

    @Test
    fun `a value past its staleness limit is withheld`() {
        // Held-forever values are how a frozen number renders as live. The
        // panel must get null and draw a dash instead.
        val c = PollCursor()
        c.record(ObdPid.ENGINE_RPM, listOf(0x1A, 0xF8), 1000L)
        // RPM polls at 10 Hz, so the limit is 3 intervals = 300 ms.
        assertNull(c.fresh(ObdPid.ENGINE_RPM, 1500L))
        assertTrue(c.isStale(ObdPid.ENGINE_RPM, 1500L))
    }

    @Test
    fun `one missed reply does not make a value stale`() {
        // A single miss is normal on a busy bus. Dashing for it would make
        // the panel flicker constantly.
        val c = PollCursor()
        c.record(ObdPid.ENGINE_RPM, listOf(0x1A, 0xF8), 1000L)
        assertNotNull(c.fresh(ObdPid.ENGINE_RPM, 1150L))
    }

    @Test
    fun `slow PIDs get proportionally longer staleness limits`() {
        // Tank level polls every 10 s by design; judging it against the
        // fast tier's limit would mark it stale almost always.
        val c = PollCursor()
        c.record(ObdPid.FUEL_LEVEL, listOf(0x80), 0L)
        assertNotNull(c.fresh(ObdPid.FUEL_LEVEL, 20_000L))
        assertNull(c.fresh(ObdPid.FUEL_LEVEL, 40_000L))
    }

    @Test
    fun `a never-seen PID is stale rather than absent`() {
        val c = PollCursor()
        assertTrue(c.isStale(ObdPid.ENGINE_RPM, 0L))
        assertNull(c.fresh(ObdPid.ENGINE_RPM, 0L))
    }

    @Test
    fun `fresh fraction reports partial degradation`() {
        val c = PollCursor()
        for (e in PollSchedule.ALL) c.record(e.pid, listOf(0x00), 0L)
        assertEquals(1.0, c.freshFraction(0L), 0.001)

        // Far enough out that everything has expired.
        assertEquals(0.0, c.freshFraction(1_000_000L), 0.001)
    }

    // --- Rate accounting ---

    @Test
    fun `achieved rate counts successes over elapsed time`() {
        val c = PollCursor()
        repeat(100) { c.record(ObdPid.ENGINE_RPM, listOf(0x00), it.toLong()) }
        assertEquals(10.0, c.achievedRate(0L, 10_000L), 0.01)
    }

    @Test
    fun `failures are counted separately from successes`() {
        val c = PollCursor()
        c.record(ObdPid.ENGINE_RPM, listOf(0x00), 0L)
        c.recordFailure()
        c.recordFailure()
        assertEquals(1, c.successCount)
        assertEquals(2, c.failureCount)
    }

    @Test
    fun `reset clears everything so stale data cannot survive a reconnect`() {
        val c = PollCursor()
        c.record(ObdPid.ENGINE_RPM, listOf(0x1A, 0xF8), 1000L)
        c.reset()
        assertNull(c.latest(ObdPid.ENGINE_RPM))
        assertEquals(0, c.successCount)
    }

    @Test
    fun `the degraded schedule is also drivable`() {
        // Halving the fast tier must not break scheduling — it is the
        // fallback the poller switches to on a slow adapter.
        val c = PollCursor(PollSchedule.degraded())
        assertNotNull(c.nextDue(0L))
    }
}
