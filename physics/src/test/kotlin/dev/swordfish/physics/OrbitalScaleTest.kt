package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbitalScaleTest {

    private val car = Vehicle.ND2_CLUB
    private val fullTankKg = Units.gallonsToKg(11.9)

    // --- The headline fact ---

    @Test
    fun `a full tank at cruise is most of the way to orbit`() {
        // THE fact this project produces, in real-world terms: a tank of
        // petrol in an MX-5 is comparable to the delta-V budget for reaching
        // low Earth orbit. No prior knowledge needed to appreciate it.
        val dv = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 31_500.0)
        val pct = OrbitalScale.percentToOrbit(dv)
        assertTrue(pct in 90.0..100.0, "cruise = $pct% to orbit")
    }

    @Test
    fun `hypermiling exceeds orbital delta-V`() {
        // Crossing 100% is achievable, which makes it a goal rather than a
        // curiosity. This is why percentToOrbit beats a milestone list.
        val dv = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 45_000.0)
        assertTrue(OrbitalScale.isOrbital(dv))
        assertTrue(OrbitalScale.percentToOrbit(dv) > 130.0)
        assertTrue(OrbitalScale.label(dv).startsWith("ORBITAL +"))
    }

    @Test
    fun `wide-open throttle falls well short`() {
        val dv = DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 9_000.0)
        assertFalse(OrbitalScale.isOrbital(dv))
        val pct = OrbitalScale.percentToOrbit(dv)
        assertTrue(pct in 20.0..35.0, "WOT = $pct% to orbit")
    }

    @Test
    fun `driving style spans a five-fold range of orbital capability`() {
        val hypermiling = OrbitalScale.percentToOrbit(
            DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 45_000.0)
        )
        val wideOpen = OrbitalScale.percentToOrbit(
            DeltaVModel.tsiolkovskyDeltaV(car, fullTankKg, 9_000.0)
        )
        assertTrue(hypermiling / wideOpen > 4.5)
    }

    // --- Percentage arithmetic ---

    @Test
    fun `orbital delta-V is exactly one hundred percent`() {
        assertEquals(100.0, OrbitalScale.percentToOrbit(OrbitalScale.LOW_EARTH_ORBIT), 0.001)
    }

    @Test
    fun `the LEO figure includes launch losses, not just orbital velocity`() {
        // 9,400 m/s is the honest cost including gravity and drag losses.
        // Orbital velocity alone is ~7,800 -- using that would flatter the
        // readout by about 20%.
        assertTrue(OrbitalScale.LOW_EARTH_ORBIT > 9_000.0)
        assertTrue(OrbitalScale.LOW_EARTH_ORBIT < 10_000.0)
    }

    @Test
    fun `zero and negative budgets read as grounded`() {
        assertEquals(0.0, OrbitalScale.percentToOrbit(0.0), 1e-9)
        assertEquals(0.0, OrbitalScale.percentToOrbit(-500.0), 1e-9)
        assertEquals("GROUNDED", OrbitalScale.label(0.0))
    }

    @Test
    fun `non-finite input does not produce a non-finite percentage`() {
        assertEquals(0.0, OrbitalScale.percentToOrbit(Double.NaN), 1e-9)
        assertTrue(OrbitalScale.percentToOrbit(Double.POSITIVE_INFINITY).isFinite())
    }

    // --- Labels ---

    @Test
    fun `sub-orbital budgets read as a percentage toward orbit`() {
        assertEquals("80% TO ORBIT", OrbitalScale.label(7_520.0))
        assertEquals("50% TO ORBIT", OrbitalScale.label(4_700.0))
    }

    @Test
    fun `crossing orbital changes the wording, not just the number`() {
        // The threshold should read as an accomplishment.
        assertTrue(OrbitalScale.label(9_399.0).contains("TO ORBIT"))
        assertTrue(OrbitalScale.label(9_401.0).startsWith("ORBITAL"))
    }

    @Test
    fun `the current tank reading matches what the head unit shows`() {
        // 7,501 m/s was the figure rendered on the DHU, from the surveyed
        // 83.1% tank level. Pins the end-to-end number.
        assertEquals("80% TO ORBIT", OrbitalScale.label(7_501.0))
    }

    // --- Reference missions ---

    @Test
    fun `references are ordered by cost`() {
        val costs = OrbitalScale.REFERENCES.map { it.deltaVMps }
        assertEquals(costs.sorted(), costs)
    }

    @Test
    fun `every reference has a name and a blurb`() {
        OrbitalScale.REFERENCES.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.blurb.isNotBlank())
            assertTrue(it.deltaVMps > 0.0)
        }
    }

    @Test
    fun `references use real mission budgets`() {
        // Sanity-check a couple against published figures rather than
        // inventing numbers.
        val leo = OrbitalScale.REFERENCES.first { it.name == "LOW EARTH ORBIT" }
        assertEquals(9_400.0, leo.deltaVMps, 1.0)

        val gto = OrbitalScale.REFERENCES.first { it.name == "GEOSTATIONARY" }
        // LEO + 2,440 m/s for the transfer.
        assertEquals(11_840.0, gto.deltaVMps, 1.0)
    }

    @Test
    fun `reachable returns the most demanding affordable mission`() {
        assertNull(OrbitalScale.reachable(500.0))
        assertEquals("SUBORBITAL", OrbitalScale.reachable(2_000.0)?.name)
        assertEquals("LOW EARTH ORBIT", OrbitalScale.reachable(9_500.0)?.name)
        assertEquals("LUNAR LANDING", OrbitalScale.reachable(99_000.0)?.name)
    }

    @Test
    fun `next up reports what is just out of reach`() {
        val next = OrbitalScale.nextUp(9_000.0)
        assertNotNull(next)
        assertEquals("LOW EARTH ORBIT", next.name)
        assertEquals(400.0, OrbitalScale.deltaVToNext(9_000.0)!!, 0.01)
    }

    @Test
    fun `nothing is next above the most demanding reference`() {
        assertNull(OrbitalScale.nextUp(99_000.0))
        assertNull(OrbitalScale.deltaVToNext(99_000.0))
    }

    // --- Progress bar ---

    @Test
    fun `orbit progress is clamped to one`() {
        assertEquals(0.5, OrbitalScale.orbitProgress(4_700.0), 0.001)
        assertEquals(1.0, OrbitalScale.orbitProgress(9_400.0), 0.001)
        assertEquals(1.0, OrbitalScale.orbitProgress(20_000.0), 0.001)
        assertEquals(0.0, OrbitalScale.orbitProgress(-100.0), 1e-9)
    }

    @Test
    fun `progress never returns a non-finite value`() {
        for (dv in listOf(0.0, 1.0, 5_000.0, 9_400.0, 50_000.0, Double.NaN)) {
            val p = OrbitalScale.orbitProgress(dv)
            assertTrue(p.isFinite() && p in 0.0..1.0, "progress($dv) = $p")
        }
    }
}
