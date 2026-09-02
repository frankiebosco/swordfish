package dev.swordfish.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reference feed that drives an MS-CAN capture.
 *
 * ## The drive this exists because of
 *
 * On 2026-08-26 two MS-CAN captures ran their full length against a working
 * adapter and produced NOTHING:
 *
 * ```
 * capture finished: obs=0 ids=0 droppedNoRef=735  droppedFull=0
 * capture finished: obs=0 ids=0 droppedNoRef=1385 droppedFull=0
 * ```
 *
 * 2120 CAN frames arrived and every one was dropped. The cause was not in
 * the capture — `MsCanCapture` behaved exactly as designed — but in what fed
 * it. `ProbeActivity` read `ImuSource.headingDegrees`, which is written ONLY
 * from the rotation-vector SENSOR callback, after calling `startLocation()`
 * alone. Location without sensors means that field stays null forever, so
 * `yawRateFromBearings` was never reached and `onReference` never called.
 *
 * The same drive's log carried a heading on 99.9% of its samples, from a
 * different, fully-started `ImuSource` — which is why this looked fine
 * everywhere except the one screen that mattered.
 *
 * These tests cover the DERIVATION, which is portable. The wiring itself is
 * asserted in `app`'s `MsCanReferenceWiringTest`, which is where the Android
 * types live.
 */
class MsCanReferenceFeedTest {

    private fun bytes(vararg v: Int) = v.toList()

    /**
     * The exact shape of the 2026-08-26 failure.
     *
     * A capture fed nothing keeps zero observations however long it runs and
     * however well the adapter performs — and `readiness` must say so rather
     * than reporting something that reads like partial success.
     */
    @Test
    fun `frames without a reference produce nothing and readiness says so`() {
        val c = MsCanCapture()

        // 735 frames, the first capture's real count. No onReference ever.
        repeat(735) { i ->
            c.onFrame("0x085", bytes(1, 2, 3, 4), atMs = 1000L + i * 35L)
        }

        assertEquals(0, c.size, "nothing can pair without a reference")
        assertEquals(735, c.droppedNoReference)
        assertEquals(0, c.droppedFull)
        assertEquals(0, c.idCounts().size)
        assertTrue(
            c.readiness().startsWith("NO DATA"),
            "readiness must name the failure, was: ${c.readiness()}"
        )
    }

    /**
     * The same frames, once a reference is actually supplied, are kept.
     *
     * This is the fix's payoff: nothing about the frames changed.
     */
    @Test
    fun `the same frames pair once a reference is supplied`() {
        val c = MsCanCapture()

        repeat(735) { i ->
            val t = 1000L + i * 35L
            // A reference arriving at ~2 Hz, as the feed posts it.
            if (i % 14 == 0) c.onReference(0.25, atMs = t)
            c.onFrame("0x085", bytes(1, 2, 3, 4), atMs = t)
        }

        assertEquals(735, c.size)
        assertEquals(0, c.droppedNoReference)
    }

    /**
     * Course over ground yields a usable yaw rate through a real corner.
     *
     * The feed samples every 500 ms, so this is the dt that matters. A
     * 90-degree turn taken over ~4 s is comfortably above the 0.15 rad/s
     * threshold `readiness` uses to count a manoeuvre.
     */
    @Test
    fun `a real corner clears the manoeuvre threshold at feed cadence`() {
        // 12 degrees per 500 ms tick -- an ordinary junction turn.
        val yaw = MsCanIdentify.yawRateFromBearings(0.0, 12.0, dtSec = 0.5)
        assertNotNull(yaw)
        assertTrue(
            yaw > 0.15,
            "a real corner must register as a manoeuvre, was $yaw rad/s"
        )
    }

    /**
     * Bearing noise while running straight must NOT register as a corner.
     *
     * If it did, `readiness` would report both-way manoeuvres on a straight
     * road and a capture with no real variance would look correlatable.
     */
    @Test
    fun `bearing noise on a straight road stays below the threshold`() {
        // GPS bearing jitter is on the order of a degree or two.
        val yaw = MsCanIdentify.yawRateFromBearings(180.0, 181.5, dtSec = 0.5)
        assertNotNull(yaw)
        assertTrue(
            kotlin.math.abs(yaw) < 0.15,
            "straight-road jitter must not count as a corner, was $yaw rad/s"
        )
    }

    /**
     * A null bearing yields no reference rather than a fabricated zero.
     *
     * Course over ground is null when stationary or unfixed. Treating that
     * as 0.0 would stamp "not turning" onto frames captured while the car's
     * motion is simply unknown, which is worse than dropping them.
     */
    @Test
    fun `a missing bearing yields no reference at all`() {
        assertNull(MsCanIdentify.yawRateFromBearings(null, 90.0, dtSec = 0.5))
        assertNull(MsCanIdentify.yawRateFromBearings(90.0, null, dtSec = 0.5))
    }

    /**
     * A gap in the feed does not become a huge phantom yaw rate.
     *
     * If GPS drops for several seconds the car may have turned a long way.
     * Dividing that whole change by a large dt would invent a plausible-
     * looking rate for a corner nobody observed, so the helper refuses.
     */
    @Test
    fun `a long gap between bearings is refused`() {
        assertNull(
            MsCanIdentify.yawRateFromBearings(0.0, 90.0, dtSec = 5.0),
            "a multi-second gap cannot describe a single corner"
        )
    }

    /**
     * Turning both ways is what makes a capture usable.
     *
     * A signed sensor whose negative range is never exercised has an
     * unresolvable sign convention -- see `MsCanCapture.readiness`.
     */
    @Test
    fun `a route turning both ways reaches READY`() {
        val c = MsCanCapture()
        var t = 1000L

        // 40 left-hand samples then 40 right-hand ones, each with frames.
        for (sign in listOf(1.0, -1.0)) {
            repeat(40) {
                c.onReference(sign * 0.30, atMs = t)
                c.onFrame("0x085", bytes(1, 2, 3, 4), atMs = t)
                t += 100L
            }
        }

        assertTrue(
            c.readiness().startsWith("READY"),
            "both-way route must be READY, was: ${c.readiness()}"
        )
    }

    /**
     * A one-sided route is reported as such, not as READY.
     */
    @Test
    fun `a route turning only one way is reported one-sided`() {
        val c = MsCanCapture()
        var t = 1000L
        repeat(80) {
            c.onReference(0.30, atMs = t)
            c.onFrame("0x085", bytes(1, 2, 3, 4), atMs = t)
            t += 100L
        }

        assertTrue(
            c.readiness().startsWith("ONE-SIDED"),
            "left-only route must be flagged, was: ${c.readiness()}"
        )
    }
}
