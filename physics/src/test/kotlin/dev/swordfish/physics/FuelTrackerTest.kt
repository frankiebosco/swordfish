package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuelTrackerTest {

    private val car = Vehicle.ND2_CLUB
    private val fullKg = Units.gallonsToKg(11.9)

    @Test
    fun `starts with a full tank by default`() {
        val t = FuelTracker(car)
        assertEquals(11.9, t.fuelGallons(), 0.01)
        assertEquals(1.0, t.fuelFraction(), 0.01)
    }

    @Test
    fun `integrating flow draws the tank down`() {
        val t = FuelTracker(car)
        // 0.5 g/s for 100 s = 50 g burned.
        repeat(100) { t.update(0.0005, 25.0, 1.0) }
        assertEquals(fullKg - 0.05, t.fuelKg, 1e-6)
        assertEquals(0.05, t.burnedKg, 1e-6)
    }

    @Test
    fun `distance accrues even when flow is unavailable`() {
        val t = FuelTracker(car)
        repeat(10) { t.update(null, 20.0, 1.0) }
        assertEquals(200.0, t.distanceM, 1e-6)
        assertEquals(fullKg, t.fuelKg, 1e-9)
    }

    @Test
    fun `fuel never goes negative`() {
        val t = FuelTracker(car, initialFuelKg = 0.001)
        repeat(100) { t.update(0.01, 20.0, 1.0) }
        assertEquals(0.0, t.fuelKg, 1e-9)
    }

    @Test
    fun `average mpg reflects the integrated burn`() {
        val t = FuelTracker(car)
        // 30 m/s for 1000 s = 30 km = 18.64 mi.
        // Burn 0.0004 kg/s for 1000 s = 0.4 kg = 0.143 gal -> ~130 mpg.
        repeat(1000) { t.update(0.0004, 30.0, 1.0) }
        val expected = Units.metersToMiles(30000.0) / Units.kgToGallons(0.4)
        assertEquals(expected, t.averageMpg(), 0.1)
    }

    @Test
    fun `average mpg is zero before any fuel is burned`() {
        assertEquals(0.0, FuelTracker(car).averageMpg(), 1e-9)
    }

    // --- The slosh-rejection behaviour, which is the whole point ---

    @Test
    fun `tank sensor slowly corrects integration drift`() {
        // Integral has drifted low; sensor says the tank is fuller.
        val t = FuelTracker(car, initialFuelKg = fullKg * 0.50)
        val before = t.fuelKg
        // Sensor reads 55% for five minutes of samples.
        repeat(300) { t.update(0.0, 25.0, 1.0, tankLevelFraction = 0.55) }
        assertTrue(t.fuelKg > before, "estimate should be pulled up")
        assertTrue(t.fuelKg < fullKg * 0.55, "but must not snap to the sensor")
    }

    @Test
    fun `a single slosh spike barely moves the estimate`() {
        // The failure mode this guards: a hard corner sends the float to a
        // wild reading and the delta-V display jumps. One sample must not
        // meaningfully move the number.
        val t = FuelTracker(car, initialFuelKg = fullKg * 0.5)
        val before = t.fuelKg
        t.update(0.0, 25.0, 1.0, tankLevelFraction = 0.60)
        assertEquals(before, t.fuelKg, fullKg * 0.001)
    }

    @Test
    fun `wildly disagreeing sensor readings are rejected outright`() {
        // A reading 40 percentage points away from the integral is a fault,
        // not information.
        val t = FuelTracker(car, initialFuelKg = fullKg * 0.5)
        val before = t.fuelKg
        repeat(60) { t.update(0.0, 25.0, 1.0, tankLevelFraction = 0.95) }
        assertEquals(before, t.fuelKg, 1e-9)
    }

    @Test
    fun `reset to full restores the tank and clears trip counters`() {
        val t = FuelTracker(car, initialFuelKg = fullKg * 0.2)
        repeat(50) { t.update(0.0005, 25.0, 1.0) }
        t.resetToFull()
        assertEquals(11.9, t.fuelGallons(), 0.01)
        assertEquals(0.0, t.burnedKg, 1e-9)
        assertEquals(0.0, t.distanceM, 1e-9)
    }

    @Test
    fun `manual fuel entry clamps to tank capacity`() {
        val t = FuelTracker(car)
        t.setFuel(50.0)
        assertEquals(11.9, t.fuelGallons(), 0.01)
        t.setFuel(-5.0)
        assertEquals(0.0, t.fuelGallons(), 0.01)
    }

    @Test
    fun `zero or negative timestep is ignored`() {
        val t = FuelTracker(car)
        t.update(0.001, 25.0, 0.0)
        t.update(0.001, 25.0, -1.0)
        assertEquals(fullKg, t.fuelKg, 1e-9)
        assertEquals(0.0, t.distanceM, 1e-9)
    }

    // --- Seeding, and the slosh from the 2026-08-21 drive ---

    @Test
    fun `a fresh tracker is not seeded`() {
        // Until a real reading arrives the figure is only the constructor
        // default, and delta-V built on it would be a guess.
        assertFalse(FuelTracker(car).isSeeded)
    }

    @Test
    fun `agreeing readings seed the estimate outright`() {
        // The first reading must be ADOPTED, not crept toward: at
        // 0.001/s the estimate would take most of a drive to reach truth.
        val t = FuelTracker(car)
        repeat(5) { t.seed(0.5) }
        assertTrue(t.isSeeded)
        val litres = Units.gallonsToLiters(t.fuelGallons())
        assertTrue(litres in 20.0..25.0, "expected ~half of 45 L, got $litres")
    }

    @Test
    fun `a sloshing tank refuses to seed`() {
        // THE failure this guards against. On the real drive the sensor
        // reported 14.4 L to 40.7 L within one session; seeding from any
        // single one of those would be wrong for the whole drive.
        val t = FuelTracker(car)
        for (f in listOf(0.32, 0.90, 0.45, 0.78, 0.35, 0.88)) t.seed(f)
        assertFalse(t.isSeeded, "seeded from a wildly sloshing tank")
    }

    @Test
    fun `seeding resumes once the tank settles`() {
        val t = FuelTracker(car)
        // Slosh first...
        for (f in listOf(0.32, 0.90, 0.45)) t.seed(f)
        assertFalse(t.isSeeded)
        // ...then a calm stretch.
        repeat(5) { t.seed(0.60) }
        assertTrue(t.isSeeded, "never recovered after the slosh passed")
    }

    @Test
    fun `an 18 litre one-second jump is rejected`() {
        // Verbatim from the drive: the sensor "gained" 18.58 L in a single
        // sample. Physically impossible; the filter must not follow it.
        val t = FuelTracker(car)
        repeat(5) { t.seed(0.50) }
        val before = t.fuelGallons()

        // A full-tank reading one second later.
        t.update(fuelFlowKgPerSec = 0.0005, speedMps = 20.0, dtSec = 1.0,
                 tankLevelFraction = 0.95)

        val jump = Units.gallonsToLiters(kotlin.math.abs(t.fuelGallons() - before))
        assertTrue(jump < 0.5, "followed the slosh spike: moved $jump L")
    }

    @Test
    fun `a plausible reading still corrects drift over minutes`() {
        // The filter must not be SO stubborn that it never corrects. Over
        // ten minutes a small genuine disagreement should be absorbed.
        val t = FuelTracker(car)
        repeat(5) { t.seed(0.50) }
        val start = t.fuelGallons()
        repeat(600) {
            t.update(null, 20.0, 1.0, tankLevelFraction = 0.53)
        }
        assertTrue(t.fuelGallons() > start, "never corrected toward the sensor")
    }

}
