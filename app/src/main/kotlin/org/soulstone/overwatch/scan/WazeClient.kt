package org.soulstone.overwatch.scan

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fetches Waze live-map alerts in a small bounding box around the user.
 *
 * Endpoint (recipe from REFERENCES/wazepolice):
 *   https://www.waze.com/live-map/api/georss?top=&bottom=&left=&right=&env=na&types=alerts
 *
 * Spoofs Chrome desktop headers — the public live-map endpoint requires Referer +
 * a real-looking User-Agent, otherwise returns 403.
 *
 * Response shape:
 *   { "alerts": [
 *       { "uuid", "type": "POLICE", "subtype",
 *         "location": {"x": lon, "y": lat},
 *         "pubMillis", "reportedBy", "confidence" 0-5, "reliability" 0-10 } ] }
 */
class WazeClient {

    companion object {
        private const val TAG = "WazeClient"
        private const val BASE = "https://www.waze.com/live-map/api/georss"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val REFERER = "https://www.waze.com/live-map/"
        private const val ORIGIN = "https://www.waze.com"
        private const val TIMEOUT_MS = 10_000

        /** Bounding box half-width in degrees — ~5.5 km N-S, varies E-W with latitude. */
        private const val BBOX_HALF_DEG = 0.05
    }

    data class Alert(
        val uuid: String,
        val subtype: String?,
        val lat: Double,
        val lon: Double,
        val pubMillis: Long,
        val confidence: Int,
        val reliability: Int,
        val reportedBy: String?
    )

    /** Outcome — distinguishes "no police alerts in area" from "couldn't reach Waze." */
    sealed class FetchResult {
        data class Success(val alerts: List<Alert>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }

    suspend fun fetchPoliceNear(lat: Double, lon: Double): FetchResult = withContext(Dispatchers.IO) {
        val top = lat + BBOX_HALF_DEG
        val bottom = lat - BBOX_HALF_DEG
        val left = lon - BBOX_HALF_DEG
        val right = lon + BBOX_HALF_DEG
        val url = URL("$BASE?top=$top&bottom=$bottom&left=$left&right=$right&env=na&types=alerts")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", REFERER)
            setRequestProperty("Origin", ORIGIN)
            setRequestProperty("Accept", "application/json,text/javascript,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        try {
            val code = conn.responseCode
            if (code == 403) {
                // Waze added reCAPTCHA gating to live-map in 2025/2026; mobile
                // clients can no longer hit this endpoint without browser-level
                // automation. Surface this distinctly so the UI can say so.
                Log.w(TAG, "Waze returned 403 (upstream reCAPTCHA gating)")
                return@withContext FetchResult.Failed("Upstream blocked (HTTP 403)")
            }
            if (code !in 200..299) {
                Log.w(TAG, "Waze returned $code")
                return@withContext FetchResult.Failed("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            FetchResult.Success(parsePolice(body))
        } catch (e: Exception) {
            Log.w(TAG, "Waze fetch failed: ${e.message}")
            FetchResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePolice(body: String): List<Alert> {
        if (body.isBlank()) return emptyList()
        return try {
            val root = JSONObject(body)
            val alerts = root.optJSONArray("alerts") ?: return emptyList()
            val out = ArrayList<Alert>()
            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                if (a.optString("type") != "POLICE") continue
                val loc = a.optJSONObject("location") ?: continue
                val uuid = a.optString("uuid")
                if (uuid.isBlank()) continue
                val lat = loc.optDouble("y")
                val lon = loc.optDouble("x")
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(
                    Alert(
                        uuid = uuid,
                        subtype = a.optString("subtype").ifBlank { null },
                        lat = lat,
                        lon = lon,
                        pubMillis = a.optLong("pubMillis", System.currentTimeMillis()),
                        confidence = a.optInt("confidence", 0),
                        reliability = a.optInt("reliability", 0),
                        reportedBy = a.optString("reportedBy").ifBlank { null }
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Waze response: ${e.message}")
            emptyList()
        }
    }
}
