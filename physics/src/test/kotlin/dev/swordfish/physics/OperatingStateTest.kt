package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three-way ambiguity this type exists to resolve.
 *
 * `DeltaVModel.effectiveIsp` returns 0.0 when stationary, when the engine is
 * off, and when road load goes negative on a descent. Rendering all three as
 * a dash tells the driver nothing — and the first and third are opposites.
 */
class OperatingStateTest {

    /** Warm idle in neutral: the ND2 sits at ~780 rpm burning ~0.20 gal/h. */
    private val idleFlow = Units.gallonsToKg(0.20 / 3600.0)

    @Test
    fun `neutral at idle is IDLE not a dash`() {
        val s = OperatingState.classify(
            rpm = 780.0,
            speedMps = 0.0,
            fuelFlowKgPerSec = idleFlow,
            roadLoadNewtons = 0.0
        )
        assertEquals(OperatingState.IDLE, s)
    }

    @Test
    fun `idling is flagged as wasting fuel`() {
        // The jet at the hold line: propellant spent, nothing bought. This
        // is the signal the panel was silent about.
        assertTrue(OperatingState.IDLE.isWastingFuel)
        assertFalse(OperatingState.CRUISE.isWastingFuel)
        assertFalse(OperatingState.DESCENT.isWastingFuel)
    }

    @Test
    fun `only cruise has a meaningful Isp`() {
        assertTrue(OperatingState.CRUISE.hasMeaningfulIsp)
        for (s in OperatingState.entries.filter { it != OperatingState.CRUISE }) {
            assertFalse(s.hasMeaningfulIsp, "$s should not claim a real Isp")
        }
    }

    @Test
    fun `engine off outranks every other classification`() {
        // Checked first because every other state presumes a running engine.
        val s = OperatingState.classify(
            rpm = 0.0,
            speedMps = 0.0,
            fuelFlowKgPerSec = 0.0,
            roadLoadNewtons = 0.0
        )
        assertEquals(OperatingState.OFF, s)
    }

    @Test
    fun `a rolling car with the engine off is still OFF`() {
        // Coasting in neutral with the key off. Rare, but it must not read
        // as DFCO — the injectors are not "cut", there is no engine.
        val s = OperatingState.classify(
            rpm = 0.0,
            speedMps = 20.0,
            fuelFlowKgPerSec = 0.0,
            roadLoadNewtons = 400.0
        )
        assertEquals(OperatingState.OFF, s)
    }

    @Test
    fun `coasting in gear with injectors shut is DFCO not IDLE`() {
        // Moving, so it is not idling; no fuel, so it is not cruising.
        val s = OperatingState.classify(
            rpm = 2000.0,
            speedMps = 20.0,
            fuelFlowKgPerSec = 0.0,
            roadLoadNewtons = 400.0
        )
        assertEquals(OperatingState.DFCO, s)
    }

    @Test
    fun `a steep descent with negative road load is DESCENT`() {
        // Gravity more than covers drag. The opposite of idling, and the
        // distinction the single-dash rendering destroyed.
        val s = OperatingState.classify(
            rpm = 2000.0,
            speedMps = 20.0,
            fuelFlowKgPerSec = 1e-4,
            roadLoadNewtons = -50.0
        )
        assertEquals(OperatingState.DESCENT, s)
    }

    @Test
    fun `normal driving is CRUISE`() {
        val s = OperatingState.classify(
            rpm = 2661.0,
            speedMps = 29.0,
            fuelFlowKgPerSec = 1e-3,
            roadLoadNewtons = 400.0
        )
        assertEquals(OperatingState.CRUISE, s)
    }

    @Test
    fun `creeping below the speed floor still counts as idling`() {
        // Stop-and-go: the car is barely moving and road load is negligible,
        // so treating it as cruise would produce a wild Isp from a
        // near-zero denominator.
        val s = OperatingState.classify(
            rpm = 900.0,
            speedMps = 0.3,
            fuelFlowKgPerSec = idleFlow,
            roadLoadNewtons = 5.0
        )
        assertEquals(OperatingState.IDLE, s)
    }

    @Test
    fun `unknown rpm does not force an OFF classification`() {
        // A dropped 010C reply must not make a moving car read as engine-off.
        val s = OperatingState.classify(
            rpm = null,
            speedMps = 25.0,
            fuelFlowKgPerSec = 1e-3,
            roadLoadNewtons = 400.0
        )
        assertEquals(OperatingState.CRUISE, s)
    }

    @Test
    fun `the model agrees that idle Isp is zero`() {
        // The classification and the physics must not disagree: this is the
        // state, and that is the number it produces.
        val isp = DeltaVModel.effectiveIsp(
            roadLoadN = 0.0,
            fuelFlowKgPerSec = idleFlow,
            speedMps = 0.0
        )
        assertEquals(0.0, isp)
    }

    @Test
    fun `idle burn is the documented ND2 figure`() {
        // Pins the calibration point the panel now displays. The ND2 survey:        // "Idle burn is ~0.20 gal/h... If a change moves this number, the
        // change is wrong."
        val galPerHour = Units.kgToGallons(idleFlow) * 3600.0
        assertTrue(galPerHour in 0.19..0.21, "expected ~0.20 gal/h, got $galPerHour")
    }
}
