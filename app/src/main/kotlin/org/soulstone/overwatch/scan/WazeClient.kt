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
import org.soulstone.overwatch.data.location.PositionCloak
// The alert and result types are shared with the other WAZE backend. Importing the
// nested names keeps this client's body reading the way it always has. (A typealias
// would not: Kotlin will not resolve a nested class through one.)
import org.soulstone.overwatch.scan.WazeSource.Alert
import org.soulstone.overwatch.scan.WazeSource.FetchResult

/**
 * Fetches live Waze POLICE alerts from OpenWeb Ninja's hosted Waze feed.
 *
 *   GET https://api.openwebninja.com/waze/alerts-and-jams
 *       ?bottom_left=<minLat>,<minLon>&top_right=<maxLat>,<maxLon>
 *       &max_alerts=200&max_jams=0&alert_types=POLICE
 *   Header: X-API-Key: <the user's own key, from encrypted Settings>
 *
 * **Bring your own key.** Each install authenticates with its owner's own
 * OpenWeb Ninja key, entered once in Settings and stored encrypted by
 * [SecureStore] (Android Keystore AES/GCM). Nothing is baked into the APK, so a
 * published build ships with no credential at all and nobody has to be handed
 * someone else's. Get a key at https://www.openwebninja.com by subscribing to
 * the Waze API: pay-as-you-go is ~$0.005/request, so ~7.5¢ per hour of scanning
 * at the 4-minute poll this app uses (15 requests an hour), and the free tier's
 * 100 requests/month is
 * enough to try it but not to run it continuously.
 *
 * **What OpenWeb Ninja learns.** The box is centred on the centre of the ~1 km
 * grid square [PositionCloak] puts the device in, not on the fix, and is grown by
 * [PositionCloak.MAX_OFFSET_M] so the evaluation radius around the real position
 * still fits inside it. So the provider learns which square you are in, every
 * poll, under your key. Until v0.5.18 the box was centred exactly on the fix, so
 * its centre was your position. Waze itself never sees the request.
 *
 * An empty key means the source is unconfigured — [isConfigured] is false and
 * the scanner reports that instead of calling out, so Waze stays dormant rather
 * than erroring on every poll.
 *
 * Why a hosted feed at all: `waze.com/live-map/api/georss`, the endpoint every
 * scraper used, is fronted by Google's edge and 403s automated clients outright
 * — no IP, header or browser makes it answer. A direct path does exist (the Waze
 * app's own protocol, see [org.soulstone.overwatch.scan.wazert.WazeRtClient]) but
 * it means holding a Waze account and reporting a coarse position to Google, so
 * it is opt-in and this metered backend, which keeps Waze out of it entirely,
 * stays the default. It is not anonymous: every request carries the user's key.
 *
 * Response shape (verified live 2026-09-16): `{ "data": { "alerts": [...],
 * "jams": [...] } }`, each alert carrying `alert_id`, `type`, `subtype`
 * (nullable), `latitude`, `longitude`, `alert_confidence` (0-5),
 * `alert_reliability` (0-10) and an ISO-8601 `publish_datetime_utc`. Police
 * reports frequently carry confidence/reliability 0, so scoreWaze's floor
 * matters more than its crowd-trust bonuses. The parser also accepts
 * Waze-native names (`location.y`/`confidence`/`pubMillis`) so a minor upstream
 * rename doesn't silently zero out detections.
 */
class WazeClient(
    private val apiKey: () -> String = { "" }
) : WazeSource {

    companion object {
        private const val TAG = "WazeClient"
        private const val BASE = "https://api.openwebninja.com/waze/alerts-and-jams"
        private const val TIMEOUT_MS = 10_000

        /** Never sweep a box tighter than this, so alerts the user is driving
         *  toward are already fetched by the time the precise proximity filter
         *  (applied in the scanner) starts including them. */
        private const val BBOX_MIN_RADIUS_M = 800.0
    }

    override val backendName: String = "OpenWeb Ninja"

    /** The only source of coordinates that reach OpenWeb Ninja. */
    private val cloak = PositionCloak()

    /** True when the user has entered an API key. False → source is unconfigured. */
    override val isConfigured: Boolean get() = apiKey().isNotBlank()

    override val unconfiguredReason: String =
        "OpenWeb Ninja API key not set — add it in Settings"

    /** Slow on purpose: the feed is metered (~$0.005/request) and lags live Waze
     *  by ~20 min anyway, so a faster poll would buy nothing but cost money. Kept
     *  just under the DetectionStore's 5-min retention so a standing checkpoint is
     *  re-submitted before it can expire and flicker out. */
    override val pollIntervalMs: Long = 240_000L

    override suspend fun fetchPoliceNear(
        lat: Double,
        lon: Double,
        radiusMeters: Float
    ): FetchResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext FetchResult.Failed("API key not set")

        // Centre the box on the grid square, not the fix, and grow it by the most
        // the two can differ. Distances are measured later, from the real fix.
        val pub = cloak.publicPosition(lat, lon)
        val cLat = pub[0]
        val cLon = pub[1]
        val r = radiusMeters.toDouble().coerceAtLeast(BBOX_MIN_RADIUS_M) + PositionCloak.MAX_OFFSET_M
        val latDelta = r / 111_000.0
        val lonDelta = r / (111_000.0 * cos(Math.toRadians(cLat)).coerceAtLeast(0.01))
        // Double.toString() is locale-independent (always '.'), so string-building
        // the coordinates avoids any comma-decimal-separator surprise.
        val bottomLeft = "${cLat - latDelta},${cLon - lonDelta}"
        val topRight = "${cLat + latDelta},${cLon + lonDelta}"
        // alert_types=POLICE is honored server-side (verified 2026-09-16) and
        // max_jams=0 drops the jam payload outright — together they cut a typical
        // response from ~18 KB to ~1.5 KB, which matters on cellular. max_alerts
        // stays at the 200 ceiling and parsePolice still filters by type, so if
        // the upstream ever reverts to ignoring alert_types (it did originally),
        // POLICE entries still can't be crowded out or slip through.
        val url = URL(
            "$BASE?bottom_left=${enc(bottomLeft)}&top_right=${enc(topRight)}" +
                "&max_alerts=200&max_jams=0&alert_types=POLICE"
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("X-API-Key", apiKey())
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    FetchResult.Success(parsePolice(body))
                }
                401, 403 -> {
                    Log.w(TAG, "OpenWeb Ninja rejected the API key ($code)")
                    FetchResult.Failed("Invalid or missing API key (HTTP $code)")
                }
                429 -> {
                    Log.w(TAG, "Waze feed rate-limited (429)")
                    FetchResult.Failed("Rate limit or quota exceeded (HTTP 429)")
                }
                else -> {
                    Log.w(TAG, "Waze feed returned $code")
                    FetchResult.Failed("HTTP $code")
                }
            }
        } catch (e: Exception) {
            // Class name only: some exception messages quote the request URL,
            // which carries the box, and logcat outlives the scan.
            Log.w(TAG, "Waze feed fetch failed: ${e.javaClass.simpleName}")
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
