package org.soulstone.overwatch.scan

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import org.json.JSONArray

/**
 * Fetches DeFlock ALPR tile data from the public CDN, with a 24h on-disk cache.
 *
 * Tile scheme (from REFERENCES/deflock/serverless/alpr_cache):
 *   tile_lat = floor(lat / 20) * 20
 *   tile_lon = floor(lon / 20) * 20
 *   url      = https://cdn.deflock.me/regions/{tile_lat}/{tile_lon}.json
 *   body     = JSON array of { id: number, lat: number, lon: number, tags: {…} }
 *
 * 20° tiles → ≤16 tiles cover the entire globe; one user typically only ever touches one.
 */
class DeflockClient(context: Context) {

    companion object {
        private const val TAG = "DeflockClient"
        private const val TILE_SIZE_DEG = 20
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val CDN_BASE = "https://cdn.deflock.me/regions"
        private const val USER_AGENT = "OVERWATCH/0.1 (+github.com/KaraZajac/OVERWATCH)"
        private const val TIMEOUT_MS = 15_000
    }

    data class AlprPoint(
        val id: Long,
        val lat: Double,
        val lon: Double,
        val operator: String? = null,
        val manufacturer: String? = null
    )

    data class TileKey(val tileLat: Int, val tileLon: Int) {
        fun fileName() = "deflock_${tileLat}_${tileLon}.json"
    }

    private val cacheDir: File = File(context.cacheDir, "deflock").apply { mkdirs() }

    fun tileFor(lat: Double, lon: Double): TileKey = TileKey(
        tileLat = floor(lat / TILE_SIZE_DEG).toInt() * TILE_SIZE_DEG,
        tileLon = floor(lon / TILE_SIZE_DEG).toInt() * TILE_SIZE_DEG
    )

    /** Returns parsed ALPR points for the tile; empty list on any failure (logged). */
    suspend fun fetchTile(tile: TileKey): List<AlprPoint> = withContext(Dispatchers.IO) {
        val cached = cachedJson(tile)
        if (cached != null) {
            return@withContext parseSafely(cached)
        }
        val downloaded = downloadTile(tile) ?: return@withContext emptyList()
        try {
            File(cacheDir, tile.fileName()).writeText(downloaded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write tile cache for $tile: ${e.message}")
        }
        parseSafely(downloaded)
    }

    private fun cachedJson(tile: TileKey): String? {
        val f = File(cacheDir, tile.fileName())
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > CACHE_TTL_MS) return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    private fun downloadTile(tile: TileKey): String? {
        val url = URL("$CDN_BASE/${tile.tileLat}/${tile.tileLon}.json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 404) {
                Log.i(TAG, "Tile $tile not present on CDN (no ALPRs in this region)")
                ""  // cache the empty result by writing an empty string
            } else if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(TAG, "CDN returned $code for $tile")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $tile: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSafely(json: String): List<AlprPoint> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<AlprPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tags = o.optJSONObject("tags")
                out.add(
                    AlprPoint(
                        id = o.optLong("id", 0L),
                        lat = o.optDouble("lat"),
                        lon = o.optDouble("lon"),
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
            Log.w(TAG, "Failed to parse tile JSON: ${e.message}")
            emptyList()
        }
    }
}
