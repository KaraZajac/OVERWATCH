package org.soulstone.overwatch.scan

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches DeFlock ALPR data from the Overpass API (matching the live deflock-app
 * Flutter client). The earlier `cdn.deflock.me/regions/...json` path is now
 * gated behind Cloudflare bot mitigation that we cannot pass from a mobile HTTP
 * client.
 *
 * Strategy:
 *  - POST an Overpass-QL query for `man_made=surveillance + surveillance:type=ALPR`
 *    inside a small bbox around the user.
 *  - Try `overpass.deflock.org` first (less rate-limited for this use case),
 *    fall back to public `overpass-api.de`.
 *  - Cache the JSON response on disk by 0.05° grid cell (24h TTL). Revisits to
 *    the same cell don't re-hit the API.
 */
class DeflockClient(context: Context) {

    companion object {
        private const val TAG = "DeflockClient"
        private const val FETCH_RADIUS_DEG = 0.05  // ~5.5 km half-width bbox
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val USER_AGENT = "OVERWATCH/0.1 (+github.com/KaraZajac/OVERWATCH)"
        private const val TIMEOUT_MS = 30_000
        private const val OVERPASS_QUERY_TIMEOUT_S = 25
        private val ENDPOINTS = listOf(
            "https://overpass.deflock.org/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        )
    }

    data class AlprPoint(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val operator: String? = null,
        val manufacturer: String? = null
    )

    /** Outcome of a fetch — distinguishes "no ALPRs in area" from "couldn't reach the API." */
    sealed class FetchResult {
        data class Success(val points: List<AlprPoint>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }

    private val cacheDir: File = File(context.cacheDir, "deflock").apply { mkdirs() }

    suspend fun fetchAround(lat: Double, lon: Double): FetchResult = withContext(Dispatchers.IO) {
        val key = cacheKeyFor(lat, lon)
        val cached = cachedJson(key)
        if (cached != null) {
            Log.d(TAG, "Cache hit for $key")
            return@withContext FetchResult.Success(parseSafely(cached))
        }
        val south = lat - FETCH_RADIUS_DEG
        val north = lat + FETCH_RADIUS_DEG
        val west = lon - FETCH_RADIUS_DEG
        val east = lon + FETCH_RADIUS_DEG
        val query = buildQuery(south, west, north, east)
        val (body, lastError) = downloadFromAny(query)
        if (body == null) {
            return@withContext FetchResult.Failed(lastError ?: "Network error")
        }
        try {
            File(cacheDir, "$key.json").writeText(body)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache for $key: ${e.message}")
        }
        FetchResult.Success(parseSafely(body))
    }

    private fun cacheKeyFor(lat: Double, lon: Double): String {
        // 0.05° grid cell. Two consecutive points within the same cell get the
        // same cache key, so micro-movements don't refetch.
        val latStep = floor(lat / FETCH_RADIUS_DEG).toInt()
        val lonStep = floor(lon / FETCH_RADIUS_DEG).toInt()
        return "deflock_${latStep}_${lonStep}"
    }

    private fun cachedJson(key: String): String? {
        val f = File(cacheDir, "$key.json")
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    private fun buildQuery(south: Double, west: Double, north: Double, east: Double): String =
        "[out:json][timeout:$OVERPASS_QUERY_TIMEOUT_S];" +
            "(node[\"man_made\"=\"surveillance\"][\"surveillance:type\"=\"ALPR\"]" +
            "($south,$west,$north,$east););out body;"

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
                body to null
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

    private fun parseSafely(json: String): List<AlprPoint> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val elements = root.optJSONArray("elements") ?: return emptyList()
            val out = ArrayList<AlprPoint>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                if (el.optString("type") != "node") continue
                val lat = el.optDouble("lat")
                val lon = el.optDouble("lon")
                if (lat.isNaN() || lon.isNaN()) continue
                val tags = el.optJSONObject("tags")
                out.add(
                    AlprPoint(
                        id = el.optLong("id", 0L),
                        lat = lat,
                        lon = lon,
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
            Log.w(TAG, "Failed to parse Overpass response: ${e.message}")
            emptyList()
        }
    }
}
