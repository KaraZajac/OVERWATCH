package org.soulstone.overwatch.scan

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Public Citizen.com endpoints (verified 2026-04-29):
 *
 *  GET /api/incident/trending?lowerLatitude=&upperLatitude=&lowerLongitude=&upperLongitude=&limit=20
 *      → { "results": ["<incidentId>", ...] }
 *
 *  GET /api/incident/{id}
 *      → { "title", "level", "ll": [lat, lon], "ts" (ms), "police", "raw", ... }
 *
 * No auth, no rate-limit headers observed. Be a good citizen (heh) — only fetch
 * detail for IDs we haven't already seen.
 */
class CitizenClient {

    companion object {
        private const val TAG = "CitizenClient"
        private const val BASE = "https://citizen.com/api/incident"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/121.0.0.0 Mobile Safari/537.36"
        private const val TIMEOUT_MS = 10_000

        /** Bounding-box half-width in degrees — ~5.5 km N-S, varies E-W. */
        private const val BBOX_HALF_DEG = 0.05
        private const val LIMIT = 30
    }

    data class Incident(
        val id: String,
        val title: String,
        val level: Int,
        val lat: Double,
        val lon: Double,
        val pubMillis: Long,
        val precinct: String?
    )

    sealed class TrendingResult {
        data class Success(val ids: List<String>) : TrendingResult()
        data class Failed(val reason: String) : TrendingResult()
    }

    suspend fun trendingNear(lat: Double, lon: Double): TrendingResult = withContext(Dispatchers.IO) {
        val top = lat + BBOX_HALF_DEG
        val bottom = lat - BBOX_HALF_DEG
        val left = lon - BBOX_HALF_DEG
        val right = lon + BBOX_HALF_DEG
        val url = URL(
            "$BASE/trending?lowerLatitude=$bottom&upperLatitude=$top" +
                "&lowerLongitude=$left&upperLongitude=$right&limit=$LIMIT"
        )
        when (val raw = httpGetJson(url)) {
            is RawResult.Success -> {
                try {
                    val arr = JSONObject(raw.body).optJSONArray("results")
                        ?: return@withContext TrendingResult.Success(emptyList())
                    val out = ArrayList<String>(arr.length())
                    for (i in 0 until arr.length()) arr.optString(i)?.takeIf { it.isNotBlank() }?.let(out::add)
                    TrendingResult.Success(out)
                } catch (e: Exception) {
                    TrendingResult.Failed("parse: ${e.message}")
                }
            }
            is RawResult.Failed -> TrendingResult.Failed(raw.reason)
        }
    }

    /** Returns null on any failure (parse, network, missing fields). */
    suspend fun fetchIncident(id: String): Incident? = withContext(Dispatchers.IO) {
        val url = URL("$BASE/$id")
        val body = (httpGetJson(url) as? RawResult.Success)?.body ?: return@withContext null
        try {
            val o = JSONObject(body)
            val ll = o.optJSONArray("ll")
            val lat = ll?.optDouble(0) ?: o.optDouble("latitude")
            val lon = ll?.optDouble(1) ?: o.optDouble("longitude")
            if (lat.isNaN() || lon.isNaN()) return@withContext null
            Incident(
                id = id,
                title = o.optString("title").ifBlank { "Citizen incident" },
                level = o.optInt("level", 0),
                lat = lat,
                lon = lon,
                pubMillis = o.optLong("ts", System.currentTimeMillis()),
                precinct = o.optString("police").ifBlank { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Citizen incident $id: ${e.message}")
            null
        }
    }

    private sealed class RawResult {
        data class Success(val body: String) : RawResult()
        data class Failed(val reason: String) : RawResult()
    }

    private fun httpGetJson(url: URL): RawResult {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,*/*")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                RawResult.Success(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                Log.w(TAG, "$url returned $code")
                RawResult.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$url failed: ${e.message}")
            RawResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
