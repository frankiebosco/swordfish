package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one number the ND's dashboard never shows.
 *
 * The point of these tests is the distinction the reading itself hides:
 * `0142` means **state of charge** with the engine off and **alternator
 * output** with it running, and confusing the two would let a failing
 * battery read as healthy for the entire drive home.
 */
class ElectricalStateTest {

    // --- Engine off: the reading is the battery ---

    @Test
    fun `a rested full battery reads as healthy`() {
        assertEquals(
            ElectricalState.State.RESTING,
            ElectricalState.classify(12.7, rpm = 0.0)
        )
    }

    @Test
    fun `the observed 12 point 27 volts is a real mid charge`() {
        // Measured on the ND2 in accessory mode, 2026-08-20.
        val soc = ElectricalState.stateOfCharge(12.27, rpm = 0.0)
        assertNotNull(soc)
        assertTrue(soc!! in 0.50..0.70, "expected ~55-60%, got ${soc * 100}%")
    }

    @Test
    fun `a flat battery is flagged`() {
        assertEquals(
            ElectricalState.State.LOW_CHARGE,
            ElectricalState.classify(11.9, rpm = 0.0)
        )
        assertTrue(ElectricalState.State.LOW_CHARGE.isFault)
    }

    // --- Engine running: the reading is the alternator ---

    @Test
    fun `a normal charging voltage is not reported as full charge`() {
        // THE trap. 14.2 V while running says nothing about the battery --
        // a car with a dying battery reads exactly this all the way home.
        val st = ElectricalState.classify(14.2, rpm = 2000.0)
        assertEquals(ElectricalState.State.CHARGING, st)
        assertFalse(st.isFault)

        // And state of charge must refuse to answer.
        assertNull(
            ElectricalState.stateOfCharge(14.2, rpm = 2000.0),
            "reported a charge figure from alternator output"
        )
    }

    @Test
    fun `an alternator failing to keep up is flagged`() {
        val st = ElectricalState.classify(12.8, rpm = 2000.0)
        assertEquals(ElectricalState.State.UNDERCHARGING, st)
        assertTrue(st.isFault)
    }

    @Test
    fun `an overcharging regulator is flagged`() {
        val st = ElectricalState.classify(15.4, rpm = 2000.0)
        assertEquals(ElectricalState.State.OVERCHARGING, st)
        assertTrue(st.isFault)
    }

    // --- Cranking: the genuinely diagnostic moment ---

    @Test
    fun `cranking is recognised by its rpm range`() {
        // A starter turns the engine at 150-250 rpm, far below any idle.
        assertEquals(
            ElectricalState.State.CRANKING,
            ElectricalState.classify(10.2, rpm = 200.0)
        )
    }

    @Test
    fun `excessive cranking sag condemns the battery`() {
        // A healthy battery dips to about 10 V under starter load; below
        // 9.6 V is the standard condemnation threshold. Catching it here
        // predicts a no-start weeks before the car actually refuses.
        val st = ElectricalState.classify(9.1, rpm = 200.0)
        assertEquals(ElectricalState.State.CRANK_WEAK, st)
        assertTrue(st.isFault)
    }

    @Test
    fun `a low reading while running is not mistaken for cranking`() {
        // 12.8 V at 2000 rpm is a struggling alternator, not a starter.
        assertEquals(
            ElectricalState.State.UNDERCHARGING,
            ElectricalState.classify(12.8, rpm = 2000.0)
        )
    }

    // --- Degenerate input ---

    @Test
    fun `a missing reading is unknown rather than zero`() {
        assertEquals(ElectricalState.State.UNKNOWN, ElectricalState.classify(null, 0.0))
        assertEquals(ElectricalState.State.UNKNOWN, ElectricalState.classify(0.0, 0.0))
        assertNull(ElectricalState.stateOfCharge(null, 0.0))
    }

    @Test
    fun `state of charge is monotonic in voltage`() {
        var last = -1.0
        for (v in listOf(11.8, 12.0, 12.2, 12.5, 12.7, 13.0)) {
            val soc = ElectricalState.stateOfCharge(v, rpm = 0.0)!!
            assertTrue(soc >= last, "charge fell as voltage rose at $v V")
            last = soc
        }
    }

    @Test
    fun `state of charge saturates rather than exceeding one`() {
        assertEquals(1.0, ElectricalState.stateOfCharge(13.5, rpm = 0.0))
        assertEquals(0.0, ElectricalState.stateOfCharge(10.0, rpm = 0.0))
    }

    // --- Decoding ---

    @Test
    fun `voltage decodes from the two-byte form`() {
        // 12.27 V = 12270 = 0x2FEE, the observed ND2 reading.
        assertEquals(12.27, ObdPid.decodeVoltage(listOf(0x2F, 0xEE))!!, 0.01)
    }

    @Test
    fun `a truncated voltage frame is rejected`() {
        assertNull(ObdPid.decodeVoltage(listOf(0x2F)))
    }

    @Test
    fun `coolant decodes with the standard offset`() {
        // 190 F is 88 C, which the ND2 sits at when warm.
        assertEquals(88.0, ObdPid.decodeCoolantTempC(listOf(128))!!, 0.01)
        assertEquals(-40.0, ObdPid.decodeCoolantTempC(listOf(0))!!, 0.01)
    }
}
