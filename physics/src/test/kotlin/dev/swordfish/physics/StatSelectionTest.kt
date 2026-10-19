package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The driver's choice of stats-column rows.
 *
 * What matters: an upgrade changes nothing until the driver chooses, the
 * column can never be emptied, and a stored choice survives later builds
 * adding, removing or reordering rows.
 */
class StatSelectionTest {

    @Test
    fun `never set means every row, so an upgrade changes nothing`() {
        assertEquals(StatSelection.ALL, StatSelection.decode(null))
        assertEquals(StatRow.entries.toList(), StatSelection.decode(null).ordered)
    }

    @Test
    fun `round trips through its stored form`() {
        val sel = StatSelection.of(listOf(StatRow.VOLTS, StatRow.GEAR, StatRow.TEMP))
        assertEquals(sel, StatSelection.decode(sel.encode()))
    }

    @Test
    fun `stores names not ordinals`() {
        val sel = StatSelection.of(listOf(StatRow.ALT, StatRow.VOLTS))
        assertEquals("ALT,VOLTS", sel.encode())
    }

    @Test
    fun `ordered follows display priority, not the order rows were ticked`() {
        val sel = StatSelection.of(listOf(StatRow.TEMP, StatRow.GEAR, StatRow.ALT))
        assertEquals(listOf(StatRow.GEAR, StatRow.ALT, StatRow.TEMP), sel.ordered)
    }

    @Test
    fun `the last visible row cannot be hidden`() {
        val one = StatSelection.of(listOf(StatRow.RPM))
        assertTrue(one.isLastVisible(StatRow.RPM))
        assertSame(one, one.without(StatRow.RPM))
        assertTrue(one.without(StatRow.RPM).isVisible(StatRow.RPM))
    }

    @Test
    fun `hiding a row that is not shown leaves the last one alone`() {
        val one = StatSelection.of(listOf(StatRow.RPM))
        assertEquals(one, one.without(StatRow.GEAR))
        assertFalse(one.isLastVisible(StatRow.GEAR))
    }

    @Test
    fun `with and without toggle a row`() {
        val sel = StatSelection.ALL.without(StatRow.MASS).without(StatRow.EFF)
        assertFalse(sel.isVisible(StatRow.MASS))
        assertFalse(sel.isVisible(StatRow.EFF))
        assertEquals(StatRow.entries.size - 2, sel.visible.size)
        assertTrue(sel.with(StatRow.MASS).isVisible(StatRow.MASS))
    }

    @Test
    fun `an unknown stored name is skipped, not fatal`() {
        // A row a later build removed must not discard the rest of the choice.
        val sel = StatSelection.decode("GEAR,BOOST,TEMP")
        assertEquals(listOf(StatRow.GEAR, StatRow.TEMP), sel.ordered)
    }

    @Test
    fun `a stored value that leaves nothing visible falls back to every row`() {
        assertEquals(StatSelection.ALL, StatSelection.decode(""))
        assertEquals(StatSelection.ALL, StatSelection.decode("NOT_A_ROW"))
        assertEquals(StatSelection.ALL, StatSelection.of(emptyList()))
    }

    @Test
    fun `whitespace in the stored form is tolerated`() {
        assertEquals(
            listOf(StatRow.RPM, StatRow.VOLTS),
            StatSelection.decode(" RPM , VOLTS ").ordered
        )
    }

    @Test
    fun `labels are what the panel prints`() {
        // The renderer prints row.label and the narrow strip drops TWR by it,
        // so these strings are load-bearing.
        assertEquals(
            listOf("GEAR", "RPM", "TWR", "FUEL", "MASS", "EFF", "ALT", "VOLTS", "TEMP"),
            StatRow.entries.map { it.label }
        )
    }
}
