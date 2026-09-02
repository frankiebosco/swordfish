package dev.swordfish.physics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the crash that ended the 2026-08-26 MS-CAN drive.
 *
 * ## What happened
 *
 * The app died four times mid-drive with the same fault:
 *
 * ```
 * FATAL EXCEPTION: main
 * java.util.ConcurrentModificationException
 *   at kotlin.collections.GroupingKt__GroupingJVMKt.eachCount
 *   at MsCanCapture.idCounts(MsCanCapture.kt:129)
 *   at MsCanCapture.health(MsCanCapture.kt:184)
 *   at MsCanSession.status(MsCanSession.kt:258)
 *   at ProbeActivity$pollMsCanStatus$tick$1.run(ProbeActivity.kt:438)
 * ```
 *
 * Frames are appended from the capture thread (`swordfish-mscan`) while the
 * status poll reads counts on the main thread once a second. The backing
 * `ArrayList` was unguarded, so any read iterating when a frame landed threw.
 *
 * The race pre-existed in `readiness`, but nothing called it every second
 * until a live status poll was added -- which converted a dormant bug into a
 * crash per second. Adding an observer turned out to be the thing that
 * exposed it, which is worth remembering: a read path is not "just a read"
 * when the thing it reads is being mutated on another thread.
 *
 * ## Why these tests hammer rather than assert once
 *
 * A race reproduces probabilistically. A single call proves nothing -- the
 * original code would pass that too. Each test below runs a writer flat out
 * against thousands of reads, which reproduced the crash reliably against the
 * unguarded version.
 */
class MsCanCaptureConcurrencyTest {

    private fun bytes(vararg v: Int) = v.toList()

    /**
     * Runs [reader] repeatedly while frames stream in on another thread.
     *
     * Any exception on either thread fails the test with the original stack,
     * which is what makes this readable when it does fire.
     */
    private fun hammer(
        reads: Int = 3_000,
        frames: Int = 20_000,
        reader: (MsCanCapture) -> Unit
    ) {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = System.currentTimeMillis())

        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)

        val writer = Thread({
            try {
                started.await()
                val base = System.currentTimeMillis()
                repeat(frames) { i ->
                    // Keep the reference fresh so frames actually land.
                    if (i % 100 == 0) c.onReference(0.3, atMs = base)
                    c.onFrame("0x%03X".format(i % 24), bytes(i and 0xFF, 2), atMs = base)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "test-mscan-writer")

        writer.isDaemon = true
        writer.start()
        started.countDown()

        try {
            repeat(reads) { reader(c) }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        }

        writer.join(TimeUnit.SECONDS.toMillis(30))

        failure.get()?.let { throw AssertionError("concurrent access threw: $it", it) }
    }

    /** The exact frame in the crash log. */
    @Test
    fun `idCounts survives frames arriving mid-scan`() {
        hammer { it.idCounts() }
    }

    /** The call that crashed twice, via idCounts. */
    @Test
    fun `health survives frames arriving mid-scan`() {
        hammer { it.health(System.currentTimeMillis(), moving = true) }
    }

    /** The call that crashed once, iterating observations directly. */
    @Test
    fun `readiness survives frames arriving mid-scan`() {
        hammer { it.readiness() }
    }

    /** What the probe screen actually does every second: all three at once. */
    @Test
    fun `the full status read survives a live capture`() {
        hammer(reads = 1_500) {
            it.health(System.currentTimeMillis(), moving = true)
            it.idCounts()
            it.readiness()
            it.summary()
        }
    }

    /** The snapshot handed to the correlation must also be safe to take. */
    @Test
    fun `observations snapshot survives a live capture`() {
        hammer(reads = 500) { it.observations() }
    }

    /** Cheap scalar reads race too -- they are not exempt. */
    @Test
    fun `counters survive a live capture`() {
        hammer {
            it.size
            it.framesOffered
            it.hasEverHadReference
            it.referenceIsFresh(System.currentTimeMillis())
        }
    }

    /**
     * A snapshot must be internally consistent.
     *
     * `readiness` derives ID counts and reference signs from the same list.
     * If it re-read the live list between those steps the two could disagree
     * -- reporting more IDs than samples, say -- which would be a subtler bug
     * than the crash and harder to notice.
     */
    @Test
    fun `a snapshot is internally consistent while writes continue`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)

        val stop = AtomicReference(false)
        val writer = Thread({
            var i = 0
            while (!stop.get()) {
                c.onReference(0.3, atMs = 1000)
                c.onFrame("0x085", bytes(i and 0xFF), atMs = 1000)
                i++
            }
        }, "test-mscan-writer-2")
        writer.isDaemon = true
        writer.start()

        try {
            repeat(2_000) {
                val snap = c.observations()
                val counts = snap.groupingBy { o -> o.canId }.eachCount()
                assertEquals(
                    snap.size,
                    counts.values.sum(),
                    "counts must total the snapshot they came from"
                )
            }
        } finally {
            stop.set(true)
            writer.join(TimeUnit.SECONDS.toMillis(10))
        }
    }

    /** Clearing while a read is in flight must not throw either. */
    @Test
    fun `clear during a read does not throw`() {
        val c = MsCanCapture()
        val failure = AtomicReference<Throwable?>(null)

        val writer = Thread({
            try {
                repeat(4_000) { i ->
                    c.onReference(0.3, atMs = 1000)
                    c.onFrame("0x085", bytes(i and 0xFF), atMs = 1000)
                    if (i % 500 == 0) c.clear()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "test-mscan-clearer")
        writer.isDaemon = true
        writer.start()

        try {
            repeat(4_000) { c.health(System.currentTimeMillis(), moving = true) }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
        }

        writer.join(TimeUnit.SECONDS.toMillis(20))
        assertNull(failure.get(), "clear racing a read threw: ${failure.get()}")
    }

    /** The lock must not deadlock or starve the writer. */
    @Test
    fun `the writer still makes progress under constant reads`() {
        val c = MsCanCapture()
        c.onReference(0.3, atMs = 1000)

        val stop = AtomicReference(false)
        val reader = Thread({
            while (!stop.get()) c.health(System.currentTimeMillis(), moving = true)
        }, "test-mscan-reader")
        reader.isDaemon = true
        reader.start()

        try {
            repeat(5_000) { i ->
                c.onReference(0.3, atMs = 1000)
                c.onFrame("0x085", bytes(i and 0xFF), atMs = 1000)
            }
        } finally {
            stop.set(true)
            reader.join(TimeUnit.SECONDS.toMillis(10))
        }

        assertTrue(
            c.size >= 5_000,
            "writer was starved by readers: only ${c.size} frames landed"
        )
    }
}
