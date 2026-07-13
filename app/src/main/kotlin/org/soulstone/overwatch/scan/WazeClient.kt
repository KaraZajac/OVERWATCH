package org.soulstone.overwatch.scan

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches live Waze POLICE alerts through the OVERWATCH proxy at
 * `api.blackflagintel.com` (a Caddy vhost on the ASTROPHAGE box). The proxy
 * holds the real OpenWeb Ninja API key server-side and injects it; the app only
 * presents a scoped, revocable `X-App-Token`. So the valuable key never ships in
 * the APK — a leaked/decompiled build carries only the proxy token, which works
 * against this one endpoint and can be rotated on the server in seconds.
 *
 *   GET https://api.blackflagintel.com/waze/alerts-and-jams
 *       ?bottom_left=<minLat>,<minLon>&top_right=<maxLat>,<maxLon>&max_alerts=200
 *   Header: X-App-Token: <token from encrypted Settings, never baked into the APK>
 *
 * The token is entered once in Settings and stored encrypted (see [SecureStore]);
 * an empty token means the source is unconfigured — [isConfigured] is false and
 * the scanner surfaces that instead of calling out.
 *
 * Two verified quirks of the upstream feed drive the request/parse shape:
 *  - It echoes but does NOT honor an `alert_types` filter, and defaults to
 *    `max_alerts=20`, so POLICE reports get crowded out by HAZARD/ROAD_CLOSED.
 *    We pull the full page (200 = server ceiling) and filter to POLICE here.
 *  - Response envelope is `{ "data": { "alerts": [...], "jams": [...] } }`;
 *    each alert carries `alert_id`, `type`, `subtype` (nullable), `latitude`,
 *    `longitude`, `alert_confidence` (0-5), `alert_reliability` (0-10), and an
 *    ISO-8601 `publish_datetime_utc`. The parser also accepts Waze-native names
 *    (`location.y`/`confidence`/`pubMillis`) so a minor upstream change to the
 *    shape doesn't silently zero out detections.
 */
class WazeClient(
    private val appToken: () -> String = { "" }
) {

    companion object {
        private const val TAG = "WazeClient"
        private const val BASE = "https://api.blackflagintel.com/waze/alerts-and-jams"
        private const val TIMEOUT_MS = 10_000

        /** Never sweep a box tighter than this, so alerts the user is driving
         *  toward are already fetched by the time the precise proximity filter
         *  (applied in the scanner) starts including them. */
        private const val BBOX_MIN_RADIUS_M = 800.0
    }

    /** True when a proxy token is set. False → source is unconfigured. */
    val isConfigured: Boolean get() = appToken().isNotBlank()

    data class Alert(
        val uuid: String,
        val subtype: String?,
        val lat: Double,
        val lon: Double,
        val pubMillis: Long,
        val confidence: Int,   // 0-5
        val reliability: Int   // 0-10
    )

    /** Outcome — distinguishes "no police alerts in area" from "couldn't reach the feed." */
    sealed class FetchResult {
        data class Success(val alerts: List<Alert>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }

    suspend fun fetchPoliceNear(
        lat: Double,
        lon: Double,
        radiusMeters: Float
    ): FetchResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext FetchResult.Failed("Proxy token not set")

        val r = radiusMeters.toDouble().coerceAtLeast(BBOX_MIN_RADIUS_M)
        val latDelta = r / 111_000.0
        val lonDelta = r / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        // Double.toString() is locale-independent (always '.'), so string-building
        // the coordinates avoids any comma-decimal-separator surprise.
        val bottomLeft = "${lat - latDelta},${lon - lonDelta}"
        val topRight = "${lat + latDelta},${lon + lonDelta}"
        // max_alerts=200 (the server ceiling), not an alert_types filter — see
        // the class KDoc. The proximity-sized bbox keeps the real alert count
        // well under 200, so POLICE entries are never truncated away.
        val url = URL(
            "$BASE?bottom_left=${enc(bottomLeft)}&top_right=${enc(topRight)}&max_alerts=200"
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("X-App-Token", appToken())
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    FetchResult.Success(parsePolice(body))
                }
                401, 403 -> {
                    Log.w(TAG, "Waze proxy rejected token ($code)")
                    FetchResult.Failed("Proxy rejected token (HTTP $code)")
                }
                429 -> {
                    Log.w(TAG, "Waze feed rate-limited (429)")
                    FetchResult.Failed("Rate limited (HTTP 429)")
                }
                else -> {
                    Log.w(TAG, "Waze feed returned $code")
                    FetchResult.Failed("HTTP $code")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Waze feed fetch failed: ${e.message}")
            FetchResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePolice(body: String): List<Alert> {
        if (body.isBlank()) return emptyList()
        return try {
            val alerts = extractAlerts(JSONObject(body)) ?: return emptyList()
            val out = ArrayList<Alert>(alerts.length())
            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue

                // We request alert_types=POLICE server-side; this is a belt-and-
                // suspenders guard in case the envelope also carries other types.
                val type = firstString(a, "type", "alertType")
                if (type != null && !type.equals("POLICE", ignoreCase = true)) continue

                val loc = a.optJSONObject("location")
                val lat = doubleOrNull(a, "latitude") ?: loc?.let { doubleOrNull(it, "y") } ?: continue
                val lon = doubleOrNull(a, "longitude") ?: loc?.let { doubleOrNull(it, "x") } ?: continue
                if (lat.isNaN() || lon.isNaN()) continue

                val uuid = firstString(a, "alert_id", "uuid", "id")
                    ?: "$lat,$lon,${firstLong(a, "publish_datetime_utc") ?: i}"

                out.add(
                    Alert(
                        uuid = uuid,
                        subtype = firstString(a, "subtype"),
                        lat = lat,
                        lon = lon,
                        pubMillis = parseTimeMillis(a),
                        confidence = firstInt(a, "alert_confidence", "confidence") ?: 0,
                        reliability = firstInt(a, "alert_reliability", "reliability") ?: 0
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Waze feed response: ${e.message}")
            emptyList()
        }
    }

    /** Locate the alerts array across the known envelope variants. */
    private fun extractAlerts(root: JSONObject): JSONArray? {
        root.optJSONArray("alerts")?.let { return it }
        when (val data = root.opt("data")) {
            is JSONArray -> return data
            is JSONObject -> data.optJSONArray("alerts")?.let { return it }
        }
        root.optJSONObject("result")?.optJSONArray("alerts")?.let { return it }
        return null
    }

    /** publish_datetime_utc is an ISO-8601 string; fall back to epoch-millis
     *  fields, then to "now" so a missing timestamp never drops an alert. */
    private fun parseTimeMillis(a: JSONObject): Long {
        val iso = firstString(a, "publish_datetime_utc", "pub_datetime_utc")
        if (iso != null) {
            try {
                return Instant.parse(iso).toEpochMilli()
            } catch (_: Exception) {
                try {
                    return LocalDateTime.parse(iso.replace(' ', 'T'))
                        .toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: Exception) { /* fall through */ }
            }
        }
        return firstLong(a, "pubMillis", "pub_millis") ?: System.currentTimeMillis()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun firstString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun doubleOrNull(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }

    private fun firstInt(o: JSONObject, vararg keys: String): Int? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optInt(k, 0)
        return null
    }

    private fun firstLong(o: JSONObject, vararg keys: String): Long? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) return o.optLong(k, 0L)
        return null
    }
}
