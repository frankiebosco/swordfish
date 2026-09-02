package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeSessionTest {

    // --- Rate verdicts ---
    //
    // These thresholds are the whole point of the probe. The tiered schedule
    // demands 33.3 cmd/s against a *quoted* 20-50 range that has never been
    // measured on this adapter.

    @Test
    fun `a rate below the degraded schedule is insufficient`() {
        assertEquals(
            ProbeSession.RateVerdict.INSUFFICIENT,
            ProbeSession.judge(12.0)
        )
    }

    @Test
    fun `a rate between the degraded and full schedules allows only degradation`() {
        // 25 cmd/s: the degraded schedule (18.3) fits, the full one (33.3)
        // does not. This is the outcome the tiering was designed for.
        assertEquals(
            ProbeSession.RateVerdict.DEGRADED_ONLY,
            ProbeSession.judge(25.0)
        )
    }

    @Test
    fun `a rate just above the full schedule is adequate but not roomy`() {
        assertEquals(
            ProbeSession.RateVerdict.ADEQUATE,
            ProbeSession.judge(PollSchedule.totalCmdPerSec + 1.0)
        )
    }

    @Test
    fun `headroom requires a real margin not a rounding error`() {
        // A 5% margin is noise — bus load and retries move the rate more
        // than that. Headroom means 1.5x, at which point the fast tier
        // could genuinely rise.
        assertEquals(
            ProbeSession.RateVerdict.ADEQUATE,
            ProbeSession.judge(PollSchedule.totalCmdPerSec * 1.05)
        )
        assertEquals(
            ProbeSession.RateVerdict.HEADROOM,
            ProbeSession.judge(PollSchedule.totalCmdPerSec * 1.6)
        )
    }

    @Test
    fun `the quoted bluetooth classic floor cannot run the full schedule`() {
        // Pins the documented hardware requirement: a mediocre adapter at
        // the bottom of the Bluetooth Classic range is not enough. If this
        // ever passes as ADEQUATE, either the schedule was quietly starved
        // or the constants drifted.
        val verdict = ProbeSession.judge(PollSchedule.BT_CLASSIC_MIN_CMD_PER_SEC)
        assertEquals(ProbeSession.RateVerdict.DEGRADED_ONLY, verdict)
    }

    @Test
    fun `the quoted bluetooth classic ceiling barely covers the schedule`() {
        // Adding voltage and coolant pushed the budget to 33.6 cmd/s, so
        // 1.5x is now 50.4 -- just past the 50.0 quoted ceiling. The
        // verdict is ADEQUATE rather than HEADROOM, and that is the honest
        // answer: the schedule fits a perfect adapter with nothing to
        // spare.
        //
        // Academic in any case. The MEASURED rate on this hardware is
        // 14.8-21.7 cmd/s, so the real verdict is DEGRADED_ONLY.
        assertEquals(
            ProbeSession.RateVerdict.ADEQUATE,
            ProbeSession.judge(PollSchedule.BT_CLASSIC_MAX_CMD_PER_SEC)
        )
    }

    @Test
    fun `BLE cannot sustain even the degraded schedule`() {
        // Recorded so the "could we use a BLE dongle" question stays closed.
        assertEquals(
            ProbeSession.RateVerdict.INSUFFICIENT,
            ProbeSession.judge(PollSchedule.BLE_MAX_CMD_PER_SEC)
        )
    }

    // --- Rate arithmetic ---

    @Test
    fun `the rate counts replies rather than commands sent`() {
        // An adapter that accepts 400 commands and answers 200 is running
        // at 20 cmd/s, not 40. Counting writes would flatter a dropping link.
        val r = ProbeSession.RateResult(
            commandsSent = 400,
            repliesReceived = 200,
            elapsedSeconds = 10.0
        )
        assertEquals(20.0, r.cmdPerSec, 0.01)
        assertEquals(0.5, r.dropRate, 0.01)
    }

    @Test
    fun `a zero-length test does not divide by zero`() {
        val r = ProbeSession.RateResult(0, 0, 0.0)
        assertEquals(0.0, r.cmdPerSec)
        assertEquals(0.0, r.dropRate)
    }

    @Test
    fun `worst latency is reported alongside the mean`() {
        // A 30 ms mean with a 400 ms stall reads smooth on average and
        // looks like a freeze on the display. Both figures are needed.
        val r = ProbeSession.RateResult(
            commandsSent = 5,
            repliesReceived = 5,
            elapsedSeconds = 1.0,
            latenciesMs = listOf(28.0, 31.0, 400.0, 29.0, 30.0)
        )
        assertTrue(r.meanLatencyMs < 110.0)
        assertEquals(400.0, r.worstLatencyMs)
    }

    @Test
    fun `p95 latency ignores a single outlier that dominates the worst case`() {
        val latencies = List(19) { 30.0 } + listOf(500.0)
        val r = ProbeSession.RateResult(20, 20, 1.0, latencies)
        assertEquals(500.0, r.worstLatencyMs)
        assertEquals(30.0, r.p95LatencyMs)
    }

    @Test
    fun `latency accessors tolerate an empty sample`() {
        val r = ProbeSession.RateResult(0, 0, 1.0)
        assertEquals(0.0, r.meanLatencyMs)
        assertEquals(0.0, r.worstLatencyMs)
        assertEquals(0.0, r.p95LatencyMs)
    }

    // --- PID sweep ---

    @Test
    fun `a sweep reproducing the survey reports an exact match`() {
        val sweep = ProbeSession.PidSweepResult(
            VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        )
        assertTrue(sweep.matchesBaseline)
        assertTrue(sweep.newlyFound.isEmpty())
        assertTrue(sweep.missing.isEmpty())
    }

    @Test
    fun `a sweep finding extra PIDs reports them as new`() {
        val base = VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        val sweep = ProbeSession.PidSweepResult(base + 0x0A)
        assertFalse(sweep.matchesBaseline)
        assertEquals(setOf(0x0A), sweep.newlyFound)
        assertTrue(sweep.missing.isEmpty())
    }

    @Test
    fun `a sweep missing surveyed PIDs reports them as missing`() {
        // A disappearance matters more than an addition: it means either
        // the survey or the sweep is wrong.
        val base = VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        val sweep = ProbeSession.PidSweepResult(base - 0x10)
        assertEquals(setOf(0x10), sweep.missing)
    }

    @Test
    fun `the sweep flags a fuel rate PID the survey ruled out`() {
        // 015E would replace the MAF-derived fuel flow path outright, so
        // its appearance is a headline finding rather than a footnote.
        val base = VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        val sweep = ProbeSession.PidSweepResult(base + 0x5E)
        assertTrue(sweep.foundEngineFuelRate)
        assertTrue(sweep.describe().contains("015E"))
    }

    @Test
    fun `the baseline sweep does not claim an engine fuel rate PID`() {
        // Pins the confirmed vehicle fact: 015E is NOT supported on this car.
        val sweep = ProbeSession.PidSweepResult(
            VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        )
        assertFalse(sweep.foundEngineFuelRate)
    }

    @Test
    fun `a sweep derives capabilities matching the surveyed car`() {
        val sweep = ProbeSession.PidSweepResult(
            VehicleCapabilities.ND2_2023_OBSERVED.supportedPids
        )
        val caps = sweep.capabilities
        assertTrue(caps.hasMaf)
        assertTrue(caps.canCorrectMafMixture)
        assertFalse(caps.hasEngineFuelRate)
    }

    // --- Rate test configuration ---

    @Test
    fun `the rate test hammers a PID the car definitely supports`() {
        val pidNum = ProbeSession.RATE_TEST_PID.substring(2, 4).toInt(16)
        assertTrue(
            VehicleCapabilities.ND2_2023_OBSERVED.supportedPids.contains(pidNum),
            "the rate test would measure NO DATA replies, not real throughput"
        )
    }
}
