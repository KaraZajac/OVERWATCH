package org.soulstone.overwatch.scan

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.soulstone.overwatch.data.cache.LocationTraces

/**
 * Fetches DeFlock ALPR data from the Overpass API (matching the live deflock-app
 * Flutter client). The earlier `cdn.deflock.me/regions/...json` path is now
 * gated behind Cloudflare bot mitigation that we cannot pass from a mobile HTTP
 * client.
 *
 * Covers three kinds of mapped fixed surveillance, not just ALPR — see [Kind].
 * Measured node counts in a 5 km bbox (2026-09-17): northern Virginia
 * ALPR 166 / speed 31 / all-surveillance 189; Manhattan ALPR 292 / speed 12 /
 * all-surveillance 515. So pulling every `man_made=surveillance` node roughly
 * doubles the worst-case payload rather than exploding it, which is why the
 * generic camera class is worth carrying.
 *
 * Strategy:
 *  - POST one Overpass-QL union for `man_made=surveillance` (ALPR is a subtype)
 *    and `highway=speed_camera` inside a box ±0.05° around the centre of the
 *    user's 0.05° grid cell. The box is never centred on the fix, so Overpass
 *    learns only which ~5 km cell you are in. (Until v0.5.18 it was centred
 *    exactly on the fix, so its centre was your position.)
 *  - Try `overpass.deflock.org` first (less rate-limited for this use case),
 *    fall back to public `overpass-api.de`.
 *  - Cache the JSON response on disk by that cell, for the current scan only:
 *    LocationTraces deletes the files whenever scanning stops or starts, and the
 *    24 h TTL only bounds a very long scan. Every position in a cell shares the
 *    one cell-centred box, so the cached response reaches at least half a cell
 *    (0.025°, ~2.8 km north-south and ~2.2 km east-west at 38°) beyond any fix in
 *    it: more than the 1.2 km DeflockScanner evaluates, below about 64° latitude.
 *    A box centred on whichever fix first filled the cell could leave part of
 *    that circle uncovered. DeflockScanner asks for a new cell when a fix's
 *    circle reaches past the loaded box ([Cell.holds]).
 */
class DeflockClient(context: Context) {

    companion object {
        private const val TAG = "DeflockClient"
        /** Cache cell, and all Overpass ever learns of where you are. */
        private const val CELL_DEG = 0.05
        /** Query half-width around the cell centre (~5.5 km north-south). */
        private const val FETCH_RADIUS_DEG = CELL_DEG
        // Metres per degree, rounded down from their smallest WGS84 values, so
        // [Cell.holds] overstates a circle's extent in degrees rather than
        // understating it: a "yes" is safe to act on.
        private const val M_PER_DEG_LAT = 110_574.0
        private const val M_PER_DEG_LON_EQUATOR = 111_319.0
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val USER_AGENT = "OVERWATCH/0.1 (+github.com/KaraZajac/OVERWATCH)"
        private const val TIMEOUT_MS = 30_000
        private const val OVERPASS_QUERY_TIMEOUT_S = 25
        private val ENDPOINTS = listOf(
            "https://overpass.deflock.org/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        )
    }

    /**
     * What a mapped node actually is. These are scored very differently: an
     * ALPR reads and records your plate into a searchable network, a speed
     * camera is fixed enforcement that only matters if you're speeding, and a
     * generic `man_made=surveillance` node is as likely to be a shop's CCTV as
     * anything aimed at the street. Lumping them together would let a corner
     * store's camera raise the same alarm as a Flock installation.
     */
    enum class Kind { ALPR, SPEED_CAMERA, CAMERA }

    data class SurveillancePoint(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val kind: Kind = Kind.ALPR,
        val operator: String? = null,
        val manufacturer: String? = null
    )

    /**
     * One 0.05° grid cell: what Overpass is asked about and what the cache is keyed
     * by. Its box reaches [FETCH_RADIUS_DEG] either side of the cell's centre, so
     * half a cell past every edge.
     */
    data class Cell(val latIndex: Int, val lonIndex: Int) {
        val centreLat: Double get() = (latIndex + 0.5) * CELL_DEG
        val centreLon: Double get() = (lonIndex + 0.5) * CELL_DEG
        val south: Double get() = centreLat - FETCH_RADIUS_DEG
        val north: Double get() = centreLat + FETCH_RADIUS_DEG
        val west: Double get() = centreLon - FETCH_RADIUS_DEG
        val east: Double get() = centreLon + FETCH_RADIUS_DEG

        /** True when this cell's box holds the whole circle of [radiusM] around the point. */
        fun holds(lat: Double, lon: Double, radiusM: Double): Boolean {
            val dLat = radiusM / M_PER_DEG_LAT
            val dLon = radiusM / (M_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat))).coerceAtLeast(1.0)
            return lat - dLat >= south && lat + dLat <= north &&
                lon - dLon >= west && lon + dLon <= east
        }

        companion object {
            fun of(lat: Double, lon: Double): Cell =
                Cell(floor(lat / CELL_DEG).toInt(), floor(lon / CELL_DEG).toInt())
        }
    }

    /** Outcome of a fetch — distinguishes "no ALPRs in area" from "couldn't reach the API." */
    sealed class FetchResult {
        data class Success(val points: List<SurveillancePoint>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }

    // Cleared every time scanning stops (LocationTraces), so this is a cache for
    // the current scan, not a record of every area ever scanned.
    private val cacheDir: File =
        File(context.cacheDir, LocationTraces.CAMERA_CACHE_DIR).apply { mkdirs() }

    /** The cameras in the box of the cell holding (lat, lon), from the cache if this
     *  scan already fetched that cell. */
    suspend fun fetchAround(lat: Double, lon: Double): FetchResult = withContext(Dispatchers.IO) {
        val cell = Cell.of(lat, lon)
        val key = cacheKeyFor(cell)
        val cached = cachedJson(key)
        if (cached != null) {
            // No key in the log: it names the cell, and logcat outlives the scan.
            Log.d(TAG, "Camera cache hit")
            return@withContext FetchResult.Success(parseSafely(cached))
        }
        val query = buildQuery(cell.south, cell.west, cell.north, cell.east)
        val (body, lastError) = downloadFromAny(query)
        if (body == null) {
            return@withContext FetchResult.Failed(lastError ?: "Network error")
        }
        // Scanning may have stopped while this request was in flight, and the stop
        // has already cleared the cache: don't leave a fresh trace behind it.
        ensureActive()
        try {
            File(cacheDir, "$key.json").writeText(body)
        } catch (e: Exception) {
            Log.w(TAG, "Camera cache not written: ${e.javaClass.simpleName}")
        }
        FetchResult.Success(parseSafely(body))
    }

    private fun cacheKeyFor(cell: Cell): String {
        // One key per 0.05° grid cell, so moving around inside a cell doesn't
        // refetch. v3: v2 entries hold boxes centred on a fix rather than on the
        // cell, so a file left by an older build killed mid-scan must not be read.
        return "deflock3_${cell.latIndex}_${cell.lonIndex}"
    }

    private fun cachedJson(key: String): String? {
        val f = File(cacheDir, "$key.json")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    private fun buildQuery(south: Double, west: Double, north: Double, east: Double): String {
        val bbox = "($south,$west,$north,$east)"
        // man_made=surveillance is the superset that already contains the
        // surveillance:type=ALPR nodes, so asking for ALPR separately would
        // just duplicate them; the parser sorts the classes out by tag.
        return "[out:json][timeout:$OVERPASS_QUERY_TIMEOUT_S];" +
            "(node[\"man_made\"=\"surveillance\"]$bbox;" +
            "node[\"highway\"=\"speed_camera\"]$bbox;);out body;"
    }

    /** Try each endpoint in order until one returns 2xx. Returns body + last error message. */
    private fun downloadFromAny(query: String): Pair<String?, String?> {
        var lastError: String? = null
        for (endpoint in ENDPOINTS) {
            val (body, err) = postQuery(endpoint, query)
            if (body != null) return body to null
            lastError = err
        }
        return null to lastError
    }

    private fun postQuery(endpoint: String, query: String): Pair<String?, String?> {
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val payload = "data=" + URLEncoder.encode(query, "UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                // Overpass returns HTTP 200 with `{"remark": "runtime error: Query timed out..."}`
                // when the query exceeded server-side limits. Body has elements:[]; treat as
                // failure so we don't poison the 24h cache with empty results.
                if (looksLikeOverpassTimeout(body)) {
                    Log.w(TAG, "$endpoint returned 200 with timeout/runtime-limit remark")
                    null to "Overpass timeout"
                } else {
                    body to null
                }
            } else {
                Log.w(TAG, "$endpoint returned $code")
                null to "HTTP $code"
            }
        } catch (e: Exception) {
            Log.w(TAG, "$endpoint failed: ${e.message}")
            null to (e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun looksLikeOverpassTimeout(body: String): Boolean {
        if (!body.contains("remark", ignoreCase = true)) return false
        val lower = body.lowercase()
        return lower.contains("timed out") ||
            lower.contains("timeout") ||
            lower.contains("runtime error") ||
            lower.contains("runtime limit exceeded") ||
            lower.contains("rate_limited")
    }

    private fun parseSafely(json: String): List<SurveillancePoint> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val out = ArrayList<SurveillancePoint>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                if (el.optString("type") != "node") continue
                val lat = el.optDouble("lat")
                val lon = el.optDouble("lon")
                if (lat.isNaN() || lon.isNaN()) continue
                val tags = el.optJSONObject("tags")
                val surveillanceType = tags?.optString("surveillance:type").orEmpty()
                val kind = when {
                    surveillanceType.equals("ALPR", ignoreCase = true) -> Kind.ALPR
                    tags?.optString("highway").orEmpty()
                        .equals("speed_camera", ignoreCase = true) -> Kind.SPEED_CAMERA
                    else -> Kind.CAMERA
                }
                out.add(
                    SurveillancePoint(
                        id = el.optLong("id", 0L),
                        lat = lat,
                        lon = lon,
                        kind = kind,
                        operator = tags?.optString("operator")?.ifBlank { null }
                            ?: tags?.optString("surveillance:operator")?.ifBlank { null },
                        manufacturer = tags?.optString("manufacturer")?.ifBlank { null }
                            ?: tags?.optString("surveillance:manufacturer")?.ifBlank { null }
                            ?: tags?.optString("brand")?.ifBlank { null }
                            ?: tags?.optString("surveillance:brand")?.ifBlank { null }
                    )
                )
            }
            out
        } catch (e: Exception) {
            // Class only: Android's org.json quotes its whole input in a syntax
            // error, and this input is every camera around you, with coordinates.
            Log.w(TAG, "Failed to parse Overpass response: ${e.javaClass.simpleName}")
            emptyList()
        }
    }
}
