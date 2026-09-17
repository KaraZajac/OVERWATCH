package org.soulstone.overwatch.scan

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live aircraft positions from the community ADS-B networks.
 *
 *   GET https://opendata.adsb.fi/api/v2/lat/<lat>/lon/<lon>/dist/<nm>
 *   GET https://api.adsb.lol/v2/lat/<lat>/lon/<lon>/dist/<nm>      (fallback)
 *
 * **No API key, no account.** Both are volunteer, data-in/data-out networks
 * running the same readsb-derived schema, so one parser covers both and either
 * can carry the source alone — the same belt-and-braces shape as the two
 * Overpass endpoints in [DeflockClient].
 *
 * Community networks matter here specifically: the commercial trackers
 * (FlightAware, Flightradar24) filter military and sensitive law-enforcement
 * flights at government request, which is exactly the traffic this source
 * exists to see. These don't filter.
 *
 * Response is `{ "ac": [...] }` (adsb.fi) or `{ "aircraft": [...] }` (wider
 * queries and adsb.lol) — verified both shapes live 2026-09-17, and the parser
 * accepts either because reading only one silently returns zero aircraft.
 * Fields used: `hex` (the ICAO address matched against [LeAircraft]), `flight`,
 * `lat`, `lon`, `alt_baro`, `gs`, `track`, `r` (registration), `t` (type).
 */
class AircraftClient {

    companion object {
        private const val TAG = "AircraftClient"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "OVERWATCH/0.5 (+github.com/KaraZajac/OVERWATCH)"
        /** Query radius. Aircraft are fast and visible from far off, so this is
         *  deliberately much wider than any ground-source radius; the scanner
         *  decides what is actually close enough to matter. */
        const val QUERY_RADIUS_NM = 30
    }

    data class Contact(
        val icaoHex: String,
        val callsign: String?,
        val registration: String?,
        val type: String?,
        val lat: Double,
        val lon: Double,
        /** Barometric altitude in feet; null when the aircraft reports "ground". */
        val altitudeFt: Int?,
        val groundSpeedKt: Double?,
        val trackDeg: Double?
    )

    sealed class FetchResult {
        data class Success(val contacts: List<Contact>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }

    private val endpoints = listOf(
        "https://opendata.adsb.fi/api/v2",
        "https://api.adsb.lol/v2"
    )

    suspend fun fetchAround(lat: Double, lon: Double): FetchResult = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (base in endpoints) {
            val url = "$base/lat/${"%.4f".format(lat)}/lon/${"%.4f".format(lon)}/dist/$QUERY_RADIUS_NM"
            when (val r = get(url)) {
                is Raw.Ok -> return@withContext FetchResult.Success(parse(r.body))
                is Raw.Err -> lastError = r.reason
            }
        }
        FetchResult.Failed(lastError ?: "No ADS-B endpoint reachable")
    }

    private sealed class Raw {
        data class Ok(val body: String) : Raw()
        data class Err(val reason: String) : Raw()
    }

    private fun get(url: String): Raw {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                Raw.Ok(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                Log.w(TAG, "$url returned $code")
                Raw.Err("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$url failed: ${e.message}")
            Raw.Err(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Contact> {
        if (body.isBlank()) return emptyList()
        return try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: return emptyList()
            val out = ArrayList<Contact>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val hex = a.optString("hex").trim().lowercase()
                if (hex.length != 6) continue
                val lat = a.optDouble("lat", Double.NaN)
                val lon = a.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(
                    Contact(
                        icaoHex = hex,
                        callsign = a.optString("flight").trim().ifBlank { null },
                        registration = a.optString("r").trim().ifBlank { null },
                        type = a.optString("t").trim().ifBlank { null },
                        lat = lat,
                        lon = lon,
                        // alt_baro is "ground" (a string) for parked aircraft,
                        // so optInt would quietly turn that into 0 ft.
                        // alt_baro is the string "ground" for parked aircraft,
                        // so optInt would quietly report that as 0 ft.
                        altitudeFt = if (a.opt("alt_baro") is Number) a.optInt("alt_baro") else null,
                        groundSpeedKt = a.optDouble("gs", Double.NaN)
                            .takeIf { !it.isNaN() },
                        trackDeg = a.optDouble("track", Double.NaN).takeIf { !it.isNaN() }
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ADS-B response: ${e.message}")
            emptyList()
        }
    }
}
