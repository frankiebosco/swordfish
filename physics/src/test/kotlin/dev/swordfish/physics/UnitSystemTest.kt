package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Display units.
 *
 * The head-unit panel is metric throughout; the logbook was written later and
 * reached for miles and mpg. Both are defensible, having both at once was an
 * inconsistency, and this makes it a choice.
 */
class UnitSystemTest {

    @Test
    fun `distance converts both ways`() {
        val m = 32_186.88   // exactly 20 miles
        assertEquals("20.0", DisplayUnits.distance(m, UnitSystem.IMPERIAL).value)
        assertEquals("mi", DisplayUnits.distance(m, UnitSystem.IMPERIAL).unit)
        assertEquals("32.2", DisplayUnits.distance(m, UnitSystem.METRIC).value)
        assertEquals("km", DisplayUnits.distance(m, UnitSystem.METRIC).unit)
    }

    @Test
    fun `speed converts both ways`() {
        val mps = 26.8224   // exactly 60 mph
        assertEquals("60", DisplayUnits.speed(mps, UnitSystem.IMPERIAL).value)
        assertEquals("97", DisplayUnits.speed(mps, UnitSystem.METRIC).value)
        assertEquals("km/h", DisplayUnits.speed(mps, UnitSystem.METRIC).unit)
    }

    @Test
    fun `altitude converts both ways`() {
        assertEquals("120", DisplayUnits.altitude(120.0, UnitSystem.METRIC).value)
        assertEquals("394", DisplayUnits.altitude(120.0, UnitSystem.IMPERIAL).value)
        assertEquals("ft", DisplayUnits.altitude(120.0, UnitSystem.IMPERIAL).unit)
    }

    @Test
    fun `temperature converts both ways`() {
        assertEquals("88", DisplayUnits.temperature(88.0, UnitSystem.METRIC).value)
        assertEquals("190", DisplayUnits.temperature(88.0, UnitSystem.IMPERIAL).value)
    }

    @Test
    fun `economy INVERTS between systems`() {
        // The one measurement where the number does not merely rescale:
        // mpg counts distance per volume, L/100km counts volume per distance.
        // A good drive has a HIGH mpg and a LOW L/100km.
        val meters = 100_000.0
        val fuelKg = 5.0

        val imp = DisplayUnits.economy(meters, fuelKg, UnitSystem.IMPERIAL)
        val met = DisplayUnits.economy(meters, fuelKg, UnitSystem.METRIC)
        assertNotNull(imp); assertNotNull(met)
        assertEquals("mpg", imp.unit)
        assertEquals("L/100km", met.unit)

        // A MORE efficient drive: same distance, half the fuel.
        val impBetter = DisplayUnits.economy(meters, fuelKg / 2, UnitSystem.IMPERIAL)!!
        val metBetter = DisplayUnits.economy(meters, fuelKg / 2, UnitSystem.METRIC)!!
        assertTrue(
            impBetter.value.toDouble() > imp.value.toDouble(),
            "better economy must RAISE mpg"
        )
        assertTrue(
            metBetter.value.toDouble() < met.value.toDouble(),
            "better economy must LOWER L/100km"
        )
    }

    @Test
    fun `economy is null when nothing was burnt or covered`() {
        assertNull(DisplayUnits.economy(0.0, 5.0, UnitSystem.METRIC))
        assertNull(DisplayUnits.economy(1000.0, 0.0, UnitSystem.METRIC))
    }

    @Test
    fun `the radar range label converts but the range does not`() {
        // The NOAA request is built in miles; only the LABEL changes. A range
        // is the same distance whatever it is called.
        assertEquals("20 MI", DisplayUnits.radarRange(20, UnitSystem.IMPERIAL))
        assertEquals("32 KM", DisplayUnits.radarRange(20, UnitSystem.METRIC))
        assertEquals("129 KM", DisplayUnits.radarRange(80, UnitSystem.METRIC))
    }

    @Test
    fun `every scope range has a sensible metric label`() {
        for (mi in RadarLayout.RANGES_MILES) {
            val label = DisplayUnits.radarRange(mi, UnitSystem.METRIC)
            assertTrue(
                label.endsWith(" KM") && label.length <= 7,
                "range label '$label' is too long for the scope corner"
            )
        }
    }

    @Test
    fun `the default is metric, matching the panel`() {
        assertEquals(UnitSystem.METRIC, UnitSystem.DEFAULT)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromName(null))
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromName("FURLONGS"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromName("IMPERIAL"))
    }

    @Test
    fun `delta-V and Isp are never offered for conversion`() {
        // They are the premise. "Delta-V remaining: 4,382 ft/s" is not a unit
        // conversion, it is a different app -- and OrbitalScale's comparisons
        // are all m/s figures. If a deltaV() formatter ever appears here,
        // this is the conversation to have first.
        val names = DisplayUnits::class.java.methods.map { it.name }
        assertTrue(
            names.none { it.contains("deltaV", true) || it.contains("isp", true) },
            "DisplayUnits should not convert delta-V or Isp"
        )
    }
}
