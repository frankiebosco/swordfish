package dev.swordfish.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Builds NOAA radar imagery requests and decides when to refresh them.
 *
 * ## Why this is in `:physics`
 *
 * Same rule as [RadarLayout]: a bounding box is arithmetic, and a URL is a
 * string. Both are testable on the JVM with no network, no head unit and no
 * Android. The `:app` side only performs the HTTP GET and draws the bitmap.
 *
 * ## The source
 *
 * NOAA nowCOAST, MRMS base reflectivity mosaic. **Free, keyless, no quota** —
 * verified against the live service 2026-08-24, which returned a 512x512 RGBA
 * PNG for a regional bounding box.
 *
 * ```
 * https://nowcoast.noaa.gov/geoserver/weather_radar/ows
 *   ?service=WMS&version=1.3.0&request=GetMap
 *   &layers=base_reflectivity_mosaic
 *   &crs=EPSG:3857&bbox=...&width=512&height=512
 *   &format=image/png&transparent=true
 * ```
 *
 * `base_reflectivity_mosaic` is the umbrella layer covering CONUS, Alaska,
 * Hawaii, Puerto Rico and Guam — the per-region layers
 * (`conus_base_reflectivity_mosaic` and friends) exist but would need the app
 * to know which region it is in, which the umbrella layer makes unnecessary.
 *
 * ## Web Mercator, and why the request is square
 *
 * The service offers EPSG:4326, CRS:84 and EPSG:3857. **3857 (web mercator)
 * is the one to ask for**: a square bbox in metres produces a square image
 * with no latitude-dependent stretch, so the scope can draw the bitmap into a
 * square destination rect and the range rings stay circular.
 *
 * In 4326 a square degree box is *not* square on screen — at 40°N a degree of
 * longitude is only 0.77 of a degree of latitude, so the picture would be
 * squashed horizontally and a storm 20 miles north would not sit at the same
 * ring radius as one 20 miles east.
 */
object RadarTile {

    /** The nowCOAST WMS endpoint. Keyless. */
    const val ENDPOINT = "https://nowcoast.noaa.gov/geoserver/weather_radar/ows"

    /** The umbrella layer: CONUS + Alaska + Hawaii + Puerto Rico + Guam. */
    const val LAYER = "base_reflectivity_mosaic"

    /**
     * Pixel size of the requested image, square.
     *
     * 512 is a compromise: large enough that the scope (typically 180-240px
     * across on a head unit) is not visibly upscaled even after rotation,
     * small enough that a fetch over a phone's connection stays quick. The
     * live probe returned 7.6 KB at this size for a quiet sky; heavy weather
     * is larger but still tens of KB.
     */
    const val IMAGE_PX = 512

    /** Earth's radius in metres, as web mercator defines it. */
    const val EARTH_RADIUS_M = 6378137.0

    /** Metres per statute mile. */
    const val METRES_PER_MILE = 1609.344

    /**
     * How long a fetched image stays good, in seconds.
     *
     * MRMS mosaics update about every 4 minutes, so anything faster is
     * bandwidth spent on the same picture. 150s (2.5 min) samples often
     * enough to catch each new mosaic reasonably soon without hammering a
     * free public service from a moving car.
     */
    const val REFRESH_SECONDS = 150.0

    /**
     * How far the car may move before the image is refetched, in miles.
     *
     * The bbox is centred on the car, so driving far enough makes a stale
     * image wrong in POSITION as well as age. A tenth of the scope range is
     * about 2 miles at the default 20-mile range — roughly two minutes of
     * motorway driving, which is well inside the refresh interval anyway.
     */
    const val REFETCH_DISTANCE_FRACTION = 0.10

    /**
     * A square bounding box in web mercator metres.
     *
     * Square by construction: the scope is a circle, and a non-square box
     * would stretch the weather.
     */
    data class BBox(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    ) {
        /** WMS 1.3.0 bbox parameter order for EPSG:3857 is minX,minY,maxX,maxY. */
        fun toParam(): String = "$minX,$minY,$maxX,$maxY"
    }

    /**
     * Project a latitude/longitude to web mercator metres.
     *
     * Latitude is clamped to ±85.05113°, mercator's usable limit — beyond it
     * the projection runs to infinity. No road reaches that latitude, but a
     * bad GPS fix might report one and an infinity in a bbox would produce a
     * request the service rejects.
     */
    fun toMercator(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val clampedLat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val x = EARTH_RADIUS_M * Math.toRadians(lonDeg)
        val y = EARTH_RADIUS_M * ln(tan(PI / 4.0 + Math.toRadians(clampedLat) / 2.0))
        return x to y
    }

    /**
     * The square bbox to request for a scope of [rangeMiles] centred on the car.
     *
     * ## The box is the scope's BOUNDING SQUARE, not its radius
     *
     * The scope shows everything within [rangeMiles] in every direction, so
     * the box must extend a full range in each direction from the centre —
     * side length `2 * range`, not `range`. Getting this wrong halves the
     * apparent range and every storm reads as twice as close as it is.
     *
     * ## Mercator scale distortion is corrected
     *
     * A metre of web mercator is not a metre on the ground: the projection
     * stretches by `1/cos(latitude)`. At 40°N that is 1.31x, so a box built
     * from raw ground metres would cover only 76% of the intended range and
     * the range rings would lie. Dividing by `cos(lat)` cancels it.
     */
    fun bboxFor(latDeg: Double, lonDeg: Double, rangeMiles: Int): BBox {
        val (cx, cy) = toMercator(latDeg, lonDeg)
        val groundHalfSpanM = rangeMiles * METRES_PER_MILE

        // Mercator metres per ground metre at this latitude.
        val clampedLat = latDeg.coerceIn(-85.05112878, 85.05112878)
        val scale = 1.0 / cos(Math.toRadians(clampedLat))
        val halfSpan = groundHalfSpanM * scale

        return BBox(cx - halfSpan, cy - halfSpan, cx + halfSpan, cy + halfSpan)
    }

    /**
     * The full GetMap URL for a scope centred on the car.
     *
     * Everything is fixed except the bbox, so this is deliberately a plain
     * string build rather than a URI builder — it keeps the request visible
     * in one place, and it is exactly what a browser or curl can be pointed
     * at when the service misbehaves.
     */
    fun urlFor(latDeg: Double, lonDeg: Double, rangeMiles: Int): String {
        val bbox = bboxFor(latDeg, lonDeg, rangeMiles)
        return ENDPOINT +
            "?service=WMS" +
            "&version=1.3.0" +
            "&request=GetMap" +
            "&layers=$LAYER" +
            "&styles=" +
            "&format=image%2Fpng" +
            "&transparent=true" +
            "&crs=EPSG%3A3857" +
            "&bbox=${bbox.toParam()}" +
            "&width=$IMAGE_PX" +
            "&height=$IMAGE_PX"
    }

    /**
     * Whether a new image should be fetched.
     *
     * Two independent triggers, because an image goes wrong in two ways: it
     * gets OLD (the weather moved) and it gets MISPLACED (the car moved).
     * Either alone is enough.
     *
     * @param ageSeconds how long since the held image was fetched, or a large
     *   value when there is none.
     * @param movedMiles how far the car is from where the held image was
     *   centred.
     */
    fun shouldRefetch(
        ageSeconds: Double,
        movedMiles: Double,
        rangeMiles: Int
    ): Boolean {
        if (ageSeconds >= REFRESH_SECONDS) return true
        val moveLimit = rangeMiles * REFETCH_DISTANCE_FRACTION
        return movedMiles >= moveLimit
    }

    /**
     * Great-circle distance between two fixes, in statute miles.
     *
     * Haversine rather than a flat-earth approximation: the error is
     * negligible at these distances either way, but this cannot misbehave
     * near the date line, which a longitude subtraction can.
     */
    fun distanceMiles(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (EARTH_RADIUS_M * c) / METRES_PER_MILE
    }

    /**
     * True when a fix is usable for a radar request.
     *
     * (0, 0) is in the Atlantic and is what an uninitialised location object
     * reports, so it is rejected explicitly rather than producing a request
     * for the Gulf of Guinea.
     */
    fun isUsableFix(latDeg: Double, lonDeg: Double): Boolean {
        if (latDeg.isNaN() || lonDeg.isNaN()) return false
        if (abs(latDeg) > 90.0 || abs(lonDeg) > 180.0) return false
        // Exactly zero on both axes is the uninitialised sentinel, not a fix.
        return !(latDeg == 0.0 && lonDeg == 0.0)
    }
}
