package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canned frames that let the panel be checked without a car.
 *
 * The point of every test here is the same: **a demo frame that does not
 * actually produce the state it advertises is worse than no demo frame at
 * all**, because it would be used to sign off a rendering that never gets
 * exercised. Each frame is therefore run through the real classifier and
 * the real model, not merely inspected.
 */
class DemoFrameTest {

    private val car = Vehicle.ND2_CLUB

    /** Classify a frame exactly the way GaugeScreen does. */
    private fun stateOf(frame: DemoFrame): OperatingState {
        val t = frame.telemetry()
        val load = DeltaVModel.roadLoadNewtons(
            car, car.totalMassKg(t.fuelRemainingKg), t.speedMps, t.gradeRadians
        )
        return OperatingState.classify(t.rpm, t.speedMps, t.fuelFlowKgPerSec, load)
    }

    @Test
    fun `every frame produces the state it advertises`() {
        assertEquals(OperatingState.CRUISE, stateOf(DemoFrame.CRUISE))
        assertEquals(OperatingState.IDLE, stateOf(DemoFrame.IDLE))
        assertEquals(OperatingState.DFCO, stateOf(DemoFrame.DFCO))
        assertEquals(OperatingState.DESCENT, stateOf(DemoFrame.DESCENT))
    }

    @Test
    fun `the frames cover every state a driver can reach`() {
        // OFF is deliberately absent: a panel with the engine off has
        // nothing to show and needs no visual check.
        val covered = DemoFrame.entries.map { stateOf(it) }.toSet()
        val reachable = OperatingState.entries.toSet() - OperatingState.OFF
        assertEquals(reachable, covered)
    }

    @Test
    fun `the idle frame reproduces the documented ND2 burn`() {
        // The ND2 survey: "Idle burn is ~0.20 gal/h (MAF 2.31 g/s at 784 rpm).
        // This is the one real-world calibration point... If a change moves
        // this number, the change is wrong."
        val t = DemoFrame.IDLE.telemetry()
        assertEquals(784.0, t.rpm)
        val galPerHour = Units.kgToGallons(t.fuelFlowKgPerSec!!) * 3600.0
        assertTrue(
            galPerHour in 0.17..0.23,
            "idle frame burns $galPerHour gal/h, expected ~0.20"
        )
    }

    @Test
    fun `the idle frame drives a legible burn readout`() {
        // The whole reason the frame exists: to see IDLE BURN on screen.
        // If it rendered as an unreadable 0 the check would be worthless.
        val t = DemoFrame.IDLE.telemetry()
        val litresPerHour =
            Units.gallonsToLiters(Units.kgToGallons(t.fuelFlowKgPerSec!!)) * 3600.0
        assertTrue(litresPerHour < 10.0, "should scale to mL/h, got $litresPerHour L/h")
        val millilitres = Math.round(litresPerHour * 1000.0)
        assertTrue(millilitres in 600..900, "expected ~757 mL/h, got $millilitres")
    }

    @Test
    fun `the descent frame really does drive road load negative`() {
        // If the grade were too shallow, this frame would classify as
        // CRUISE and silently fail to exercise the state it exists for.
        val t = DemoFrame.DESCENT.telemetry()
        val load = DeltaVModel.roadLoadNewtons(
            car, car.totalMassKg(t.fuelRemainingKg), t.speedMps, t.gradeRadians
        )
        assertTrue(load < 0.0, "descent road load was $load N, expected negative")
    }

    @Test
    fun `the cruise frame yields a plausible highway economy`() {
        // Sanity band: this frame is what the delta-V readout has always
        // been tuned against, so a drift here changes the headline number.
        val t = DemoFrame.CRUISE.telemetry()
        val mpg = DeltaVModel.instantaneousMpg(t.speedMps, t.fuelFlowKgPerSec!!)
        assertTrue(mpg in 30.0..38.0, "cruise frame gives $mpg mpg")
    }

    @Test
    fun `the cruise frame is the default`() {
        // The state the panel spends most of its life in. A demo default of
        // DFCO would be a confusing first impression.
        assertEquals(DemoFrame.CRUISE, DemoFrame.DEFAULT)
        assertEquals(DemoFrame.CRUISE, DemoFrame.fromName(null))
        assertEquals(DemoFrame.CRUISE, DemoFrame.fromName("NONSENSE"))
    }

    @Test
    fun `frame names round-trip through preferences`() {
        for (f in DemoFrame.entries) {
            assertEquals(f, DemoFrame.fromName(f.name))
        }
    }

    @Test
    fun `every frame carries a label and a description`() {
        for (f in DemoFrame.entries) {
            assertTrue(f.label.isNotBlank(), "$f has no label")
            assertTrue(f.description.isNotBlank(), "$f has no description")
        }
    }

    @Test
    fun `every frame computes a finite readout`() {
        // A demo frame that produces NaN would render dashes and look like
        // a bug in the panel rather than a bad sample.
        for (f in DemoFrame.entries) {
            val r = DeltaVModel.compute(car, f.telemetry())
            assertTrue(r.deltaVRemaining.isFinite(), "$f: delta-V not finite")
            assertTrue(r.effectiveIsp.isFinite(), "$f: Isp not finite")
            assertTrue(r.roadLoadNewtons.isFinite(), "$f: road load not finite")
        }
    }

    @Test
    fun `only the cruise frame reports a meaningful Isp`() {
        // The other three are exactly the degenerate cases the panel had to
        // learn to distinguish.
        for (f in DemoFrame.entries) {
            val meaningful = stateOf(f).hasMeaningfulIsp
            assertEquals(
                f == DemoFrame.CRUISE, meaningful,
                "$f: hasMeaningfulIsp was $meaningful"
            )
        }
    }
}
