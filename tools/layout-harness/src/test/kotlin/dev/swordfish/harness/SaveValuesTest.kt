package dev.swordfish.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The save button edits real source, so its formatting must be exactly right.
 *
 * A wrong suffix here does not produce a bad layout -- it produces a repo
 * that does not compile, from a button whose whole purpose is to save time.
 */
class SaveValuesTest {

    @Test
    fun `float constants keep their f suffix`() {
        assertEquals("1.1345f", SaveValues.format("NAVBALL_SCALE", 1.1345f))
        assertEquals("-0.0137f", SaveValues.format("NAVBALL_DX", -0.0137f))
        assertEquals("0f", SaveValues.format("SCOPE_DX", 0f))
    }

    @Test
    fun `WIDE_READOUT_BAND is a Double and must NOT gain an f`() {
        // Declared `const val WIDE_READOUT_BAND = 0.17` in PanelLayout.
        // Writing 0.2969f there is a type error, and the tuner would have
        // "saved" a repo that no longer builds.
        assertEquals("0.2969", SaveValues.format("WIDE_READOUT_BAND", 0.2969f))
        assertTrue(!SaveValues.format("WIDE_READOUT_BAND", 1.0f).endsWith("f"))
    }

    @Test
    fun `negative zero is written as zero`() {
        // A drag that lands a hair below zero yields -0.0000, and "-0f" is
        // ugly in source even though it compiles.
        assertEquals("0f", SaveValues.format("ISP_DX", -0.00001f))
    }

    @Test
    fun `trailing zeros are trimmed`() {
        assertEquals("1.5f", SaveValues.format("ISP_SCALE", 1.5000f))
        assertEquals("2f", SaveValues.format("ISP_SCALE", 2.0f))
    }

    @Test
    fun `saving nothing is a no-op, not an error`() {
        val r = SaveValues.save(emptyMap())
        assertTrue(r.ok)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun `an unknown constant aborts the whole save`() {
        // Partial writes are the dangerous failure: half the values applied
        // and no clear record of which. Better to write nothing.
        val r = SaveValues.save(mapOf("NOT_A_REAL_CONSTANT_XYZ" to 1f))
        assertTrue(!r.ok, "an unknown name must fail the save")
        assertTrue(
            r.message.contains("Nothing was written"),
            "the failure must say nothing was written: ${r.message}"
        )
    }

    @Test
    fun `saving the current values changes nothing`() {
        // Idempotence: pressing Save twice must not churn the file, and the
        // second press must report "already up to date" rather than
        // rewriting identical numbers.
        val current = mapOf(
            "NAVBALL_SCALE" to dev.swordfish.physics.PanelLayout.NAVBALL_SCALE,
            "SCOPE_SCALE" to dev.swordfish.physics.PanelLayout.SCOPE_SCALE,
            "WIDE_READOUT_BAND" to
                dev.swordfish.physics.PanelLayout.WIDE_READOUT_BAND.toFloat()
        )
        val r = SaveValues.save(current)
        assertTrue(r.ok, r.message)
        assertTrue(
            r.changes.isEmpty(),
            "re-saving the values already in the file should change nothing, " +
                "but it reported: ${r.changes}"
        )
    }
}
