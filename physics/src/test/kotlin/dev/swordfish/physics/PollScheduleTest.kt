package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PollScheduleTest {

    @Test
    fun `the schedule covers the pids the model needs`() {
        val pids = PollSchedule.ALL.map { it.pid }.toSet()
        assertEquals(11, pids.size, "duplicate or missing PID in the schedule")
        assertTrue(pids.contains(ObdPid.ENGINE_RPM))
        assertTrue(pids.contains(ObdPid.VEHICLE_SPEED))
        assertTrue(pids.contains(ObdPid.MAF_RATE))
        assertTrue(pids.contains(ObdPid.COMMANDED_EQUIV_RATIO))
        assertTrue(pids.contains(ObdPid.SHORT_FUEL_TRIM_1))
        assertTrue(pids.contains(ObdPid.LONG_FUEL_TRIM_1))
        assertTrue(pids.contains(ObdPid.FUEL_LEVEL))
        assertTrue(pids.contains(ObdPid.BAROMETRIC_PRESSURE))
        assertTrue(pids.contains(ObdPid.AMBIENT_AIR_TEMP))
        assertTrue(pids.contains(ObdPid.CONTROL_MODULE_VOLTAGE))
        assertTrue(pids.contains(ObdPid.COOLANT_TEMP))
    }

    @Test
    fun `the schedule never polls engine fuel rate`() {
        // NOT because the PID is absent -- that was the Ancel survey's
        // conclusion and the MX+ sweep of 2026-08-20 disproved it: 015E is
        // enumerated and answers.
        //
        // It stays unscheduled because it has never been seen returning a
        // NON-ZERO value. Every reading so far was taken with the engine
        // off, where zero proves nothing. On a transport measured at
        // 14.8 cmd/s a slot spent on a PID that may be permanently zero is
        // a slot stolen from the gauge.
        //
        // Add it only when a warm-idle probe shows it live. See the
        // "015E VERDICT" line in the probe log.
        assertFalse(PollSchedule.ALL.any { it.pid == ObdPid.ENGINE_FUEL_RATE })
    }

    @Test
    fun `total budget is about thirty three commands per second`() {
        assertEquals(33.6, PollSchedule.totalCmdPerSec, 0.1)
    }

    @Test
    fun `the schedule does NOT fit a mediocre adapter`() {
        // This is the documented hardware requirement, pinned so nobody
        // "fixes" the over-budget condition by quietly starving the gauge.
        // A genuine fast adapter is required; a clone at the bottom of the
        // Bluetooth Classic range cannot run this.
        assertFalse(
            PollSchedule.fitsWithin(PollSchedule.BT_CLASSIC_MIN_CMD_PER_SEC),
            "33 cmd/s must not fit in 20 cmd/s -- if this passes, the schedule changed"
        )
    }

    @Test
    fun `the schedule fits a good adapter with real headroom`() {
        assertTrue(PollSchedule.fitsWithin(PollSchedule.BT_CLASSIC_MAX_CMD_PER_SEC))
        val headroom = PollSchedule.headroomFraction(PollSchedule.BT_CLASSIC_MAX_CMD_PER_SEC)
        assertTrue(headroom > 0.30, "expected >30% headroom, got ${headroom * 100}%")
    }

    @Test
    fun `BLE cannot run this schedule at all`() {
        // Pins why BLE adapters are ruled out despite being the easier
        // Android API. Not close: 33 cmd/s against a 15 cmd/s ceiling.
        assertFalse(PollSchedule.fitsWithin(PollSchedule.BLE_MAX_CMD_PER_SEC))
        assertTrue(PollSchedule.totalCmdPerSec > PollSchedule.BLE_MAX_CMD_PER_SEC * 2)
    }

    @Test
    fun `a flat ten hertz schedule would be wildly over budget`() {
        // Documents why tiering exists rather than being an optimisation.
        val flat = PollSchedule.ALL.size * 10.0
        assertEquals(110.0, flat, 0.01)
        assertTrue(flat > PollSchedule.BT_CLASSIC_MAX_CMD_PER_SEC * 1.5)
    }

    @Test
    fun `tiering saves roughly two thirds over a flat ten hertz poll`() {
        val flat = PollSchedule.ALL.size * 10.0
        val saved = (flat - PollSchedule.totalCmdPerSec) / flat
        assertTrue(saved > 0.6, "tiering should save >60%, saved ${saved * 100}%")
    }

    // --- Tier composition ---

    @Test
    fun `the fast tier is exactly the three gauge-driving pids`() {
        // The fast tier IS the budget: 30 of the 33 cmd/s. A fourth entry
        // costs another 10 cmd/s and would push a good adapter to its limit.
        assertEquals(3, PollSchedule.FAST.size)
        assertEquals(30.0, PollSchedule.FAST.sumOf { it.costPerSec }, 0.01)
    }

    @Test
    fun `fuel flow is in the fast tier because Isp depends on it`() {
        // MAF is the only fuel-flow source on this car, and Isp is the hero
        // stat. It cannot be demoted to save budget.
        assertTrue(PollSchedule.FAST.any { it.pid == ObdPid.MAF_RATE })
    }

    @Test
    fun `mixture correction sits in the medium tier`() {
        val mediumPids = PollSchedule.MEDIUM.map { it.pid }.toSet()
        assertTrue(mediumPids.contains(ObdPid.COMMANDED_EQUIV_RATIO))
        assertTrue(mediumPids.contains(ObdPid.SHORT_FUEL_TRIM_1))
        assertTrue(mediumPids.contains(ObdPid.LONG_FUEL_TRIM_1))
        assertEquals(3.0, PollSchedule.MEDIUM.sumOf { it.costPerSec }, 0.01)
    }

    @Test
    fun `near-static values cost almost nothing`() {
        // Five entries now: tank, baro, ambient, voltage, coolant.
        assertEquals(0.6, PollSchedule.SLOW.sumOf { it.costPerSec }, 0.01)
    }

    @Test
    fun `every entry carries a rationale`() {
        // The reasoning has to survive a future tuning session.
        PollSchedule.ALL.forEach {
            assertTrue(it.rationale.isNotBlank(), "${it.pid} has no rationale")
        }
    }

    @Test
    fun `intervals are the reciprocal of the rate`() {
        assertEquals(0.1, PollSchedule.FAST[0].intervalSec, 1e-9)
        assertEquals(1.0, PollSchedule.MEDIUM[0].intervalSec, 1e-9)
        assertEquals(10.0, PollSchedule.SLOW[0].intervalSec, 1e-9)
    }

    // --- Degraded mode ---

    @Test
    fun `degraded mode fits even a mediocre adapter`() {
        // The whole point: a slower gauge on a bad adapter, not no gauge.
        assertTrue(
            PollSchedule.degradedCmdPerSec <= PollSchedule.BT_CLASSIC_MIN_CMD_PER_SEC,
            "degraded schedule is ${PollSchedule.degradedCmdPerSec} cmd/s"
        )
    }

    @Test
    fun `degraded mode halves the fast tier rather than dropping pids`() {
        // Losing MAF entirely would kill Isp -- the hero stat. Better to
        // update everything at 5 Hz and hold values between samples.
        val degraded = PollSchedule.degraded()
        assertEquals(PollSchedule.ALL.size, degraded.size, "no PID should be dropped")
        val fastDegraded = degraded.filter { e -> PollSchedule.FAST.any { it.pid == e.pid } }
        fastDegraded.forEach { assertEquals(5.0, it.hz, 1e-9) }
    }

    @Test
    fun `degraded mode leaves the slower tiers untouched`() {
        val degraded = PollSchedule.degraded().associateBy { it.pid }
        PollSchedule.MEDIUM.forEach {
            assertEquals(it.hz, degraded[it.pid]!!.hz, 1e-9)
        }
        PollSchedule.SLOW.forEach {
            assertEquals(it.hz, degraded[it.pid]!!.hz, 1e-9)
        }
    }

    @Test
    fun `headroom is negative when over budget`() {
        assertTrue(PollSchedule.headroomFraction(20.0) < 0.0)
        assertEquals(-1.0, PollSchedule.headroomFraction(0.0), 1e-9)
    }

    // --- Measured reality ---

    @Test
    fun `the measured adapter rate falls below the quoted floor`() {
        // Recorded so the gap between assumption and measurement cannot be
        // quietly forgotten. The quoted Bluetooth Classic range was
        // 20-50 cmd/s; a genuine STN adapter delivered 14.8 with zero
        // packet loss.
        assertTrue(
            PollSchedule.MEASURED_MX_PLUS_CMD_PER_SEC <
                PollSchedule.BT_CLASSIC_MIN_CMD_PER_SEC,
            "if this fails the measurement was revised -- re-tier the schedule"
        )
    }

    @Test
    fun `neither the full nor the degraded schedule fits the measured rate`() {
        // The uncomfortable truth this test exists to keep visible: as
        // shipped, BOTH schedules are over budget on the real adapter.
        // Fixing it means either winning latency back (see
        // ElmProtocol.TUNING_VARIANTS) or lowering the fast tier -- not
        // pretending the budget fits.
        assertFalse(
            PollSchedule.fitsWithin(PollSchedule.MEASURED_MX_PLUS_CMD_PER_SEC)
        )
        assertTrue(
            PollSchedule.degradedCmdPerSec > PollSchedule.MEASURED_MX_PLUS_CMD_PER_SEC,
            "degraded now fits -- update this test and the schedule together"
        )
    }

}
