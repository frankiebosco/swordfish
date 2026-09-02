package dev.swordfish.obd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.swordfish.physics.RadarTile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches NOAA radar imagery in the background and holds the latest picture.
 *
 * ## The rule this class exists to enforce
 *
 * **The instrument must never stall for want of weather.** The panel repaints
 * at 20fps on the main thread; an HTTP GET on that thread would freeze the
 * gauge and, on a slow connection, trip Android Auto's ANR watchdog — the
 * same failure the frame clock was introduced to prevent.
 *
 * So every fetch runs on a single background thread, and the render path only
 * ever reads a field. A fetch that is slow, failing, or never returns costs a
 * stale radar picture and nothing else.
 *
 * ## What it does NOT do
 *
 * No disk cache and no retry backoff. A drive is an hour, the source refreshes
 * every four minutes, and a failed fetch is simply retried at the next
 * interval — the picture on screen stays the last good one meanwhile. Adding
 * persistence would mean deciding how stale is too stale to show after a
 * restart, and the honest answer there is "refetch", which is what a cold
 * start already does.
 */
class RadarSource {

    /**
     * The most recent successfully decoded image, or null.
     *
     * `@Volatile` because it is written on the fetch thread and read on the
     * render thread. A torn read of a reference is not possible on the JVM,
     * so no lock is needed — the renderer either sees the old bitmap or the
     * new one, never a half-built one.
     */
    @Volatile
    var bitmap: Bitmap? = null
        private set

    /** Where [bitmap] is centred. NaN when there is no image. */
    @Volatile
    var imageLat: Double = Double.NaN
        private set

    @Volatile
    var imageLon: Double = Double.NaN
        private set

    /** Scope range the held image was requested for. */
    @Volatile
    var imageRangeMiles: Int = 0
        private set

    /** Monotonic nanos when the held image arrived. */
    @Volatile
    private var fetchedAtNanos: Long = 0L

    /** True once a fetch has failed and no image has ever arrived. */
    @Volatile
    var lastFetchFailed: Boolean = false
        private set

    /**
     * Guards against overlapping fetches.
     *
     * The render loop calls [maybeFetch] 20 times a second; without this a
     * slow request would pile up dozens of duplicates for the same picture.
     */
    private val fetching = AtomicBoolean(false)

    /**
     * Single-threaded, and a daemon so it can never hold the process open.
     *
     * The instrument's lifetime is the drive; a fetch thread outliving it
     * would be a leak that only shows up as a battery complaint.
     */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swordfish-radar").apply { isDaemon = true }
    }

    /** Age of the held image in seconds, or a large value when there is none. */
    fun ageSeconds(): Double {
        if (bitmap == null) return Double.MAX_VALUE
        return (System.nanoTime() - fetchedAtNanos) / 1_000_000_000.0
    }

    /**
     * Fetch a new image if the held one is stale or misplaced.
     *
     * Safe to call every frame: it returns immediately unless a fetch is
     * actually due, and never blocks.
     */
    fun maybeFetch(latDeg: Double, lonDeg: Double, rangeMiles: Int) {
        if (!RadarTile.isUsableFix(latDeg, lonDeg)) return
        if (fetching.get()) return

        val moved = if (bitmap == null || imageLat.isNaN()) {
            Double.MAX_VALUE
        } else {
            RadarTile.distanceMiles(imageLat, imageLon, latDeg, lonDeg)
        }

        // A range change invalidates the picture outright: the same pixels
        // would be drawn against rings that now mean something else, which is
        // worse than showing nothing.
        val rangeChanged = rangeMiles != imageRangeMiles

        if (!rangeChanged &&
            !RadarTile.shouldRefetch(ageSeconds(), moved, rangeMiles)
        ) {
            return
        }

        if (!fetching.compareAndSet(false, true)) return

        executor.execute {
            try {
                fetchNow(latDeg, lonDeg, rangeMiles)
            } catch (e: Throwable) {
                // ANY failure is survivable: a tunnel, a dropped connection,
                // a service outage, a malformed response. The panel keeps the
                // last good picture and tries again at the next interval.
                lastFetchFailed = true
                Log.w("SwordfishRadar", "fetch failed: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun fetchNow(latDeg: Double, lonDeg: Double, rangeMiles: Int) {
        val url = RadarTile.urlFor(latDeg, lonDeg, rangeMiles)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // Short timeouts on purpose. A car drives through dead zones, and
            // a request that hangs for 30s is holding the fetch slot against
            // the next attempt long after its picture would have been stale.
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                lastFetchFailed = true
                Log.w("SwordfishRadar", "HTTP $code from nowCOAST")
                return
            }

            val decoded = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            if (decoded == null) {
                // A 200 that is not an image: the WMS reports errors as XML
                // with a 200 status, so this is the normal shape of a bad
                // request rather than an exceptional case.
                lastFetchFailed = true
                Log.w("SwordfishRadar", "response was not a decodable image")
                return
            }

            bitmap = decoded
            imageLat = latDeg
            imageLon = lonDeg
            imageRangeMiles = rangeMiles
            fetchedAtNanos = System.nanoTime()
            lastFetchFailed = false

            // THE OLD BITMAP IS NOT RECYCLED. THIS IS DELIBERATE.
            //
            // It used to be, immediately after this swap, on the reasoning
            // that the renderer "either had the old reference (still valid)
            // or has already moved to the new one". That reasoning is wrong,
            // and it crashed the app twice on the 2026-08-24 drive:
            //
            //   MAIN (20fps)                    FETCH (this thread)
            //   val bmp = radarBitmap -> A
            //   if (bmp.isRecycled) -> false
            //                                   bitmap = decoded (B)
            //                                   previous.recycle()  frees A
            //   canvas.drawBitmap(A) -> THROWS
            //
            // Swapping before freeing protects the *reference*; it does
            // nothing for a draw already in flight holding the old one. Both
            // crashes landed within 18ms of a fetch completing.
            //
            // recycle() is an optional hint, not a required free: the GC
            // reclaims the old image once the renderer drops it. At 1 MiB per
            // 512x512 image and one fetch per 150s, that is a few MiB of
            // short-lived garbage per drive -- far cheaper than a crash, and
            // the ONLY way to be safe without a lock on the 20fps draw path.
            //
            // Do not "optimise" this back. See RadarSourceRecycleTest.

            Log.i(
                "SwordfishRadar",
                "fetched ${decoded.width}x${decoded.height} for " +
                    "$latDeg,$lonDeg at ${rangeMiles}mi"
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Drop the held image. Call when the panel goes away.
     *
     * Drops the reference WITHOUT recycling, for the same reason as the swap
     * in [fetchNow]: the renderer may still be holding this bitmap and can be
     * mid-draw on another thread. Dropping it makes it collectable, which is
     * all that is needed -- the screen is being destroyed, so nothing will
     * ask for it again.
     */
    fun clear() {
        bitmap = null
        imageLat = Double.NaN
        imageLon = Double.NaN
        imageRangeMiles = 0
        fetchedAtNanos = 0L
    }

    companion object {
        /**
         * Identifies the app to NOAA.
         *
         * A public service is entitled to know who is calling it, and a
         * default Java user-agent is the kind of thing that gets blocked
         * when a service comes under load.
         */
        const val USER_AGENT = "Swordfish/0.17 (Android Auto instrument panel)"
    }
}
