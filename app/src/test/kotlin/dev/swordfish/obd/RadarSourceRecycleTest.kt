package dev.swordfish.obd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Guards the fix for the crash that ended two drives on 2026-08-24.
 *
 * ## Why this is a SOURCE test and not a behavioural one
 *
 * The bug is a race between two threads: `RadarSource` recycled a bitmap on
 * its fetch thread while `GaugeRenderer` was drawing it on the main thread.
 * Reproducing that faithfully would need a real `Bitmap` and a real `Canvas`,
 * and Android framework classes are stubbed to throw in local unit tests
 * (see the project notes) -- so a behavioural test here would assert nothing.
 *
 * Worse, a *timing* test for a race is the kind that passes on a good day.
 * The fix was not "recycle more carefully", it was "never recycle at all",
 * and that is a property of the source that can be checked exactly.
 *
 * So this pins the invariant directly: **nothing in the radar path calls
 * `Bitmap.recycle()`.** If someone reinstates it as a memory optimisation,
 * this fails immediately with an explanation, rather than the app crashing
 * on a motorway three weeks later.
 *
 * The original crash:
 * ```
 * java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
 *   at GaugeRenderer.drawRadarImagery(GaugeRenderer.kt:1155)
 * ```
 */
class RadarSourceRecycleTest {

    private fun sourceFile(relative: String): File {
        // Tests run with the module dir as CWD; walk up if that changes.
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    /** Lines with the comment portion removed, so prose cannot trip the check. */
    private fun codeLines(f: File): List<String> =
        f.readLines()
            .map { it.substringBefore("//") }
            .filter { it.isNotBlank() }

    @Test
    fun `RadarSource never recycles a bitmap`() {
        val code = codeLines(sourceFile("src/main/kotlin/dev/swordfish/obd/RadarSource.kt"))
        val offenders = code.filter { it.contains(".recycle()") }

        assertTrue(
            offenders.isEmpty(),
            "RadarSource must never call Bitmap.recycle(). The renderer draws " +
                "the held bitmap on another thread at 20fps, so freeing it here " +
                "is a use-after-free that crashed the app twice on 2026-08-24. " +
                "The GC reclaims the old image once the renderer drops it. " +
                "Offending line(s): $offenders"
        )
    }

    @Test
    fun `the renderer never recycles the radar bitmap either`() {
        val code = codeLines(sourceFile("src/main/kotlin/dev/swordfish/car/GaugeRenderer.kt"))
        val offenders = code.filter { it.contains(".recycle()") }

        assertTrue(
            offenders.isEmpty(),
            "GaugeRenderer must not recycle the radar bitmap: it does not own " +
                "it, and a fetch may already have replaced it. Offending: $offenders"
        )
    }

    /**
     * The draw guard must read the field into a local exactly once.
     *
     * Re-reading `radarBitmap` between the null check and the draw would
     * reintroduce a window where the two reads see different objects.
     */
    @Test
    fun `drawRadarImagery reads the bitmap field once into a local`() {
        val text = sourceFile("src/main/kotlin/dev/swordfish/car/GaugeRenderer.kt").readText()
        val body = text.substringAfter("private fun drawRadarImagery")
            .substringBefore("\n    private fun ")
        val reads = Regex("""\bradarBitmap\b""").findAll(body).count()

        assertEquals(
            1, reads,
            "drawRadarImagery should touch radarBitmap exactly once (a single " +
                "read into a local). Found $reads references, which reopens the " +
                "check-then-use window the fix closed."
        )
        assertTrue(
            body.contains("val bmp = radarBitmap"),
            "expected the single read to be captured as a local `bmp`"
        )
    }

    /**
     * The catch must be `RuntimeException`.
     *
     * `BaseCanvas.throwIfCannotDraw` throws a BARE `java.lang.RuntimeException`
     * for a recycled bitmap -- an `IllegalArgumentException` clause looks
     * plausible and would not catch the actual crash.
     */
    @Test
    fun `the radar draw is wrapped in a RuntimeException guard`() {
        val text = sourceFile("src/main/kotlin/dev/swordfish/car/GaugeRenderer.kt").readText()
        val body = text.substringAfter("private fun drawRadarImagery")
            .substringBefore("\n    private fun ")

        assertTrue(
            body.contains("catch (e: RuntimeException)"),
            "the drawBitmap call must be guarded by `catch (e: RuntimeException)`: " +
                "a recycled-bitmap draw throws a bare RuntimeException, so a " +
                "narrower clause would let it kill the process."
        )
    }
}
