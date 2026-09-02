package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EfficiencyBandTest {

    private val cruise = Units.mphToMps(65.0)

    // --- Bar scaling ---

    @Test
    fun `the bar is empty at poor efficiency and full at excellent`() {
        val poor = Thermodynamics.ispFromEnergy(EfficiencyBand.POOR_EFFICIENCY, cruise)
        val excellent = Thermodynamics.ispFromEnergy(EfficiencyBand.EXCELLENT_EFFICIENCY, cruise)

        assertEquals(0.0, EfficiencyBand.barFill(poor, cruise), 0.01)
        assertEquals(1.0, EfficiencyBand.barFill(excellent, cruise), 0.01)
    }

    @Test
    fun `the bar is clamped outside its reference range`() {
        val absurd = Thermodynamics.ispFromEnergy(0.9, cruise)
        assertEquals(1.0, EfficiencyBand.barFill(absurd, cruise), 1e-9)
        assertEquals(0.0, EfficiencyBand.barFill(1.0, cruise), 1e-9)
    }

    @Test
    fun `the observed cruise efficiency lands mid-bar`() {
        // The surveyed reference point is ~21% thermal efficiency, which sits
        // between the 10% and 30% anchors -- so the bar should be around half.
        // If this drifts to an extreme, the anchors are badly chosen.
        val mass = Vehicle.ND2_CLUB.totalMassKg(Units.gallonsToKg(11.9))
        val load = DeltaVModel.roadLoadNewtons(Vehicle.ND2_CLUB, mass, cruise, 0.0)
        val flow = Units.gallonsToKg(Units.metersToMiles(cruise) / 34.0)
        val isp = DeltaVModel.effectiveIsp(load, flow, cruise)

        val fill = EfficiencyBand.barFill(isp, cruise)
        assertTrue(fill in 0.3..0.8, "normal cruise fills the bar to $fill")
    }

    /**
     * The design decision that keeps the bar honest.
     */
    @Test
    fun `the bar is scaled to the current speed, not an absolute Isp`() {
        // Same 25% thermal efficiency at two very different speeds gives very
        // different raw Isp -- but the same bar fill, because the driver is
        // doing equally well in both cases.
        val slow = Units.mphToMps(30.0)
        val fast = Units.mphToMps(75.0)

        val ispSlow = Thermodynamics.ispFromEnergy(0.25, slow)
        val ispFast = Thermodynamics.ispFromEnergy(0.25, fast)
        assertTrue(ispSlow > ispFast * 2, "raw Isp differs hugely with speed")

        assertEquals(
            EfficiencyBand.barFill(ispSlow, slow),
            EfficiencyBand.barFill(ispFast, fast),
            0.01
        )
    }

    @Test
    fun `a stopped car has an empty bar rather than a divide by zero`() {
        assertEquals(0.0, EfficiencyBand.barFill(50_000.0, 0.0), 1e-9)
    }

    // --- Sweet spot ---

    @Test
    fun `the sweet spot needs both the right rpm and light load`() {
        assertTrue(EfficiencyBand.inSweetSpot(2_000.0, 0.30))
        // Right rpm, too much load -- climbing a hill in top gear.
        assertFalse(EfficiencyBand.inSweetSpot(2_000.0, 0.80))
        // Light load, wrong rpm -- coasting in second.
        assertFalse(EfficiencyBand.inSweetSpot(3_500.0, 0.10))
        assertFalse(EfficiencyBand.inSweetSpot(900.0, 0.10))
    }

    @Test
    fun `rpm alone suffices when load is unavailable`() {
        assertTrue(EfficiencyBand.inSweetSpot(2_000.0, null))
        assertFalse(EfficiencyBand.inSweetSpot(4_000.0, null))
    }

    @Test
    fun `the sweet spot covers a realistic top-gear cruise`() {
        // 60 mph in 6th is ~2,456 rpm on this car, which should be at or very
        // near the band. If the band excluded normal cruising it would never
        // light up.
        assertTrue(
            2_400.0 in EfficiencyBand.SWEET_SPOT_RPM ||
                2_456.0 - EfficiencyBand.SWEET_SPOT_RPM.endInclusive < 100.0,
            "band ends at ${EfficiencyBand.SWEET_SPOT_RPM.endInclusive}, cruise is 2456"
        )
    }

    // --- Lamp states ---

    @Test
    fun `the lamp brightens through its three states`() {
        assertEquals(
            EfficiencyBand.Lamp.DIM,
            EfficiencyBand.Assessment(0.5, inSweetSpot = false, isPersonalBest = false).lamp
        )
        assertEquals(
            EfficiencyBand.Lamp.LIT,
            EfficiencyBand.Assessment(0.5, inSweetSpot = true, isPersonalBest = false).lamp
        )
        assertEquals(
            EfficiencyBand.Lamp.RECORD,
            EfficiencyBand.Assessment(0.9, inSweetSpot = true, isPersonalBest = true).lamp
        )
    }

    @Test
    fun `a record outside the sweet spot does not light the record lamp`() {
        // Coasting downhill can beat the record without being good driving.
        assertEquals(
            EfficiencyBand.Lamp.DIM,
            EfficiencyBand.Assessment(1.0, inSweetSpot = false, isPersonalBest = true).lamp
        )
    }
}

class EfficiencyRecordTest {

    @Test
    fun `no record can be set before the averaging window has elapsed`() {
        // Otherwise the very first sample trivially becomes the best.
        val r = EfficiencyRecord(windowSeconds = 30.0)
        assertFalse(r.update(50_000.0, 1.0))
        assertEquals(0.0, r.best, 1e-9)
    }

    @Test
    fun `a sustained figure eventually sets a record`() {
        val r = EfficiencyRecord(windowSeconds = 10.0)
        var set = false
        repeat(200) { if (r.update(30_000.0, 0.1)) set = true }
        assertTrue(set, "sustained driving should set a record")
        assertTrue(r.best > 0.0)
    }

    /**
     * The reason the record is averaged rather than instantaneous.
     */
    @Test
    fun `a momentary spike cannot beat sustained good driving`() {
        val r = EfficiencyRecord(windowSeconds = 30.0)
        // Establish a solid baseline.
        repeat(600) { r.update(30_000.0, 0.1) }
        val baseline = r.best
        assertTrue(baseline > 0.0)

        // One tenth of a second of absurd Isp -- what a throttle lift looks
        // like, since fuel flow collapses while road load does not.
        r.update(500_000.0, 0.1)

        assertEquals(baseline, r.best, baseline * 0.05)
    }

    @Test
    fun `the rolling average tracks a sustained change`() {
        val r = EfficiencyRecord(windowSeconds = 10.0)
        repeat(300) { r.update(20_000.0, 0.1) }
        assertEquals(20_000.0, r.sustained, 500.0)

        repeat(300) { r.update(40_000.0, 0.1) }
        assertEquals(40_000.0, r.sustained, 1_000.0)
    }

    @Test
    fun `bad input is ignored rather than corrupting the average`() {
        val r = EfficiencyRecord(windowSeconds = 10.0)
        repeat(200) { r.update(25_000.0, 0.1) }
        val before = r.sustained

        assertFalse(r.update(Double.NaN, 0.1))
        assertFalse(r.update(-100.0, 0.1))
        assertFalse(r.update(25_000.0, 0.0))
        assertFalse(r.update(25_000.0, -1.0))

        assertEquals(before, r.sustained, 1e-9)
        assertTrue(r.sustained.isFinite())
    }

    @Test
    fun `reset clears the record and the average`() {
        val r = EfficiencyRecord(windowSeconds = 5.0)
        repeat(200) { r.update(30_000.0, 0.1) }
        assertTrue(r.best > 0.0)
        r.reset()
        assertEquals(0.0, r.best, 1e-9)
        assertEquals(0.0, r.sustained, 1e-9)
    }
}
