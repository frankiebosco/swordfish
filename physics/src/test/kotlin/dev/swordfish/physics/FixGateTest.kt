package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The location gate, tested against the fixes that actually broke the map.
 *
 * On the 2026-08-25 drive the retrace drew long straight lines across the
 * logbook. The cause was not GPS dropouts: 9 samples out of 2274 sat ~12 km
 * northwest of the real route, and each one drew TWO legs -- out and back.
 *
 * They were `NETWORK_PROVIDER` fixes. `ImuSource` registers FUSED, GPS and
 * NETWORK on one listener and took position from whichever fired last.
 */
class FixGateTest {

    /** Real coordinates from drive-20260825-114246, rows 1140-1156. */
    private val onRoute1 = FixGate.Fix(40.998003, -77.912733, 0L)
    private val bogus = FixGate.Fix(41.071426, -78.015139, 1_000L)
    private val onRoute2 = FixGate.Fix(40.998072, -77.912673, 2_000L)

    @Test
    fun `the bogus network fix from the real drive is rejected`() {
        // 15.4 km in one second: 33,867 mph.
        assertEquals(
            FixGate.Verdict.REJECT_TELEPORT,
            FixGate.judge(onRoute1, previousWasGood = true, candidate = bogus)
        )
    }

    @Test
    fun `the real route continues to be accepted`() {
        // The rows either side of the bogus one are ordinary driving and
        // must pass. A gate that rejects the route is worse than no gate.
        assertEquals(
            FixGate.Verdict.ACCEPT,
            FixGate.judge(onRoute1, previousWasGood = true, candidate = onRoute2)
        )
    }

    @Test
    fun `the teleport is caught with no accuracy metadata at all`() {
        // Location.accuracy is optional and providers lie about it, so the
        // speed check has to stand on its own.
        val a = FixGate.Fix(40.998003, -77.912733, 0L, accuracyM = null)
        val b = FixGate.Fix(41.071426, -78.015139, 1_000L, accuracyM = null)
        assertEquals(
            FixGate.Verdict.REJECT_TELEPORT,
            FixGate.judge(a, previousWasGood = true, candidate = b)
        )
    }

    @Test
    fun `a rough fix does not displace a recent good one`() {
        val good = FixGate.Fix(40.998, -77.912, 0L, accuracyM = 5.0)
        val roughNearby = FixGate.Fix(40.9985, -77.9125, 2_000L, accuracyM = 1500.0)
        assertEquals(
            FixGate.Verdict.REJECT_ROUGH,
            FixGate.judge(good, previousWasGood = true, candidate = roughNearby)
        )
    }

    @Test
    fun `a rough fix IS accepted once the good one goes stale`() {
        // Driving into a tunnel should degrade the position, not strand the
        // car where it no longer is.
        val good = FixGate.Fix(40.998, -77.912, 0L, accuracyM = 5.0)
        val later = FixGate.Fix(
            40.9985, -77.9125,
            FixGate.GOOD_FIX_HOLD_MS + 1_000L,
            accuracyM = 1500.0
        )
        assertEquals(
            FixGate.Verdict.ACCEPT,
            FixGate.judge(good, previousWasGood = true, candidate = later)
        )
    }

    @Test
    fun `the first fix is always taken`() {
        val rough = FixGate.Fix(41.0, -78.0, 0L, accuracyM = 3000.0)
        assertEquals(
            FixGate.Verdict.ACCEPT,
            FixGate.judge(null, previousWasGood = false, candidate = rough),
            "a rough first fix still tells the radar roughly where to look"
        )
    }

    @Test
    fun `motorway speed is not mistaken for a teleport`() {
        // 80 mph. The gate must not reject legitimate fast driving.
        val a = FixGate.Fix(40.9000, -78.0000, 0L)
        val b = FixGate.Fix(40.9003215, -78.0000, 1_000L)   // ~35.8 m in 1 s
        assertEquals(
            FixGate.Verdict.ACCEPT,
            FixGate.judge(a, previousWasGood = true, candidate = b)
        )
    }

    @Test
    fun `the distance helper agrees with the real drive`() {
        // Row 1142 -> 1143 measured 15,396.9 m in the log analysis.
        val d = FixGate.metresBetween(40.998003, -77.912733, 41.071426, -78.015139)
        assertTrue(
            d in 11_000.0..13_000.0,
            "expected ~12 km between the route and the bogus cluster, got ${"%.0f".format(d)} m"
        )
    }

    @Test
    fun `a parked car does not trip the gate`() {
        // Identical position, repeated. Zero distance over any dt is fine.
        val a = FixGate.Fix(40.998, -77.912, 0L, accuracyM = 5.0)
        val b = FixGate.Fix(40.998, -77.912, 1_000L, accuracyM = 5.0)
        assertEquals(
            FixGate.Verdict.ACCEPT,
            FixGate.judge(a, previousWasGood = true, candidate = b)
        )
    }
}
