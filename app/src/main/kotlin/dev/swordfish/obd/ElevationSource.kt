package dev.swordfish.obd

import android.util.Log
import dev.swordfish.physics.ElevationQuery
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches surveyed ground elevation in the background.
 *
 * ## Why the barometer stopped being the authority
 *
 * The same spot on the ridge road read 117.7 m at midday and 106.3 m that evening --
 * an 11.4 m swing with the car in the identical place, because a barometer
 * measures pressure and the weather had moved. Fusing GPS altitude in made
 * it worse, not better: GPS height is referenced to the WGS84 ellipsoid and
 * around here the geoid sits ~32 m above it. Against surveyed truth the
 * fused output averaged +29.0 m of error where the raw barometer averaged
 * +12.1 m.
 *
 * USGS 3DEP is surveyed data, so Sparkill returns 24.5 m today, tomorrow,
 * and in a thunderstorm. See [ElevationQuery] for the full measurement.
 *
 * ## Same shape as RadarSource, for the same reasons
 *
 * A single daemon thread does the HTTP; the render path only ever reads a
 * field. An elevation lookup on the main thread would freeze the 20fps gauge
 * and trip Android Auto's ANR watchdog, which is the failure the frame clock
 * exists to prevent.
 *
 * **Nothing here is ever recycled or freed across threads.** The radar
 * bitmap race (see the project notes) came from exactly that, and this class holds
 * only immutable doubles precisely so the question cannot arise.
 */
class ElevationSource {

    /**
     * Last surveyed elevation, metres. NaN until one arrives.
     *
     * `@Volatile` because it is written on the fetch thread and read on the
     * render thread.
     */
    @Volatile
    var surveyedM: Double = Double.NaN
        private set

    /** Barometric reading at the moment [surveyedM] was fetched. */
    @Volatile
    var baroAtSurveyM: Double = Double.NaN
        private set

    /** When [surveyedM] arrived, from `System.currentTimeMillis`. */
    @Volatile
    var surveyedAtMs: Long = 0L
        private set

    /** Where [surveyedM] was measured. */
    @Volatile
    var surveyedLat: Double = Double.NaN
        private set

    @Volatile
    var surveyedLon: Double = Double.NaN
        private set

    /** Successful lookups this session. */
    @Volatile
    var fetches: Int = 0
        private set

    /** Failed attempts this session -- dead zones, timeouts, rate limits. */
    @Volatile
    var failures: Int = 0
        private set

    /** True when the held value is too old to present as current. */
    val isStale: Boolean
        get() = surveyedAtMs == 0L ||
            System.currentTimeMillis() - surveyedAtMs > ElevationQuery.STALE_AFTER_MS

    private var lastCallAtMs = 0L
    private val fetching = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "swordfish-elevation").apply { isDaemon = true }
    }

    /**
     * The elevation to display, or null when nothing usable is held.
     *
     * The surveyed value is the ABSOLUTE authority; the barometer supplies
     * only the change since it was taken. That is the whole design: weather
     * moves both barometric readings equally, so it cancels out of the
     * difference and cannot reach the answer.
     *
     * @param baroNowM current barometric altitude, or null if unavailable.
     */
    fun elevationM(baroNowM: Double?): Double? {
        val s = surveyedM
        if (s.isNaN()) return null
        val then = baroAtSurveyM
        if (baroNowM == null || then.isNaN()) return s
        return ElevationQuery.interpolate(s, then, baroNowM)
    }

    /**
     * Fetch if the car has moved far enough and the rate limit allows.
     *
     * Safe to call every frame: returns immediately unless a query is due,
     * and never blocks.
     *
     * @param baroNowM barometric altitude to pair with the result, so the
     *   interpolation has a reference from the same moment.
     */
    fun maybeFetch(latDeg: Double, lonDeg: Double, baroNowM: Double?) {
        if (fetching.get()) return
        val now = System.currentTimeMillis()
        if (!ElevationQuery.shouldQuery(
                surveyedLat, surveyedLon, latDeg, lonDeg, now - lastCallAtMs
            )
        ) return

        if (!fetching.compareAndSet(false, true)) return
        lastCallAtMs = now

        executor.execute {
            try {
                fetchNow(latDeg, lonDeg, baroNowM)
            } catch (e: Throwable) {
                // Every failure is survivable: a tunnel, a dead zone, a rate
                // limit, a service outage. The held value stays and is
                // marked stale by age.
                failures++
                Log.w("SwordfishElev", "fetch failed: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun fetchNow(latDeg: Double, lonDeg: Double, baroNowM: Double?) {
        val conn = (URL(ElevationQuery.urlFor(latDeg, lonDeg))
            .openConnection() as HttpURLConnection).apply {
            // Short, for the same reason as the radar: a car drives through
            // dead zones and a request that hangs is holding the slot long
            // after its answer would have been superseded.
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                failures++
                Log.w("SwordfishElev", "HTTP $code from 3DEP")
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val m = ElevationQuery.parseMeters(body)
            if (m == null) {
                failures++
                Log.w("SwordfishElev", "unparseable 3DEP reply")
                return
            }

            surveyedM = m
            baroAtSurveyM = baroNowM ?: Double.NaN
            surveyedLat = latDeg
            surveyedLon = lonDeg
            surveyedAtMs = System.currentTimeMillis()
            fetches++
            Log.i(
                "SwordfishElev",
                "elevation ${"%.1f".format(m)} m at $latDeg,$lonDeg " +
                    "(baro ${baroNowM?.let { "%.1f".format(it) } ?: "-"})"
            )
        } finally {
            conn.disconnect()
        }
    }

    /** One-line summary for the drive log. */
    fun summary(): String =
        "fetches=$fetches failures=$failures stale=$isStale"

    private companion object {
        /**
         * Identifies the app to USGS.
         *
         * A public service is entitled to know who is calling it, and a
         * default Java user-agent is what gets blocked when a service comes
         * under load.
         */
        const val USER_AGENT = "Swordfish/0.17 (dev.swordfish; delta-V instrument)"
    }
}
