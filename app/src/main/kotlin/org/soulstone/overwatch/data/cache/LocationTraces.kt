package org.soulstone.overwatch.data.cache

import android.content.Context
import android.util.Log
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter

/**
 * The two on-disk caches that, left alone, record roughly where OVERWATCH has been
 * used, and the code that removes them.
 *
 * - **Camera cache** (`cache/deflock/`): one Overpass response per ~5.5 km area
 *   scanned, written by DeflockClient. Within a scan it saves re-querying an area
 *   you drive back through. It is deleted every time scanning stops, and again
 *   when scanning next starts in case the app was killed mid-scan.
 * - **Map tiles** (osmdroid's SQLite database in `cache/osmdroid-tiles/`): kept by
 *   default, because OpenStreetMap's tile policy asks apps to cache tiles rather
 *   than re-download them. Deleted on the same schedule only when the user turns
 *   on "Delete map tiles when scanning stops".
 *
 * Tiles live in one database that every MapView shares through osmdroid's static
 * connection, and a MapView can still be attached at the moment scanning stops. So
 * the file is never deleted out from under it: tiles are purged through osmdroid's
 * own connection, then `VACUUM` rewrites the file, so the deleted rows don't linger
 * in free pages for anyone reading the raw database.
 *
 * Everything here is best effort and never throws: failing to clear a cache must
 * not take the scanner down.
 */
object LocationTraces {

    private const val TAG = "LocationTraces"

    /** Directory under the app cache dir that DeflockClient writes to. */
    const val CAMERA_CACHE_DIR = "deflock"

    /** Directory under the app cache dir that osmdroid keeps its tile database in. */
    const val TILE_CACHE_DIR = "osmdroid-tiles"

    /**
     * Point osmdroid at the app's own cache directory. MainActivity calls this
     * before any MapView exists; [clearMapTiles] calls it too, so a purge can never
     * land on osmdroid's default location instead.
     */
    fun configureOsmdroid(context: Context) {
        val cache = context.applicationContext.cacheDir
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = cache
            osmdroidTileCache = File(cache, TILE_CACHE_DIR).apply { mkdirs() }
        }
    }

    /**
     * Delete every cached Overpass response, keeping the directory itself so a scan
     * that is still starting up can write to it. Returns how many files were removed.
     */
    fun clearCameraCache(cacheRoot: File): Int {
        val files = File(cacheRoot, CAMERA_CACHE_DIR).listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.isFile && f.delete()) removed++
        }
        return removed
    }

    /** Purge every cached tile and compact the database. Returns false on failure. */
    fun clearMapTiles(context: Context): Boolean = try {
        configureOsmdroid(context)
        TileWiper().wipe()
    } catch (e: Exception) {
        Log.w(TAG, "Map tile cache not cleared: ${e.message}")
        false
    }

    /**
     * Clear the camera cache, and the map tiles too when [includeTiles], on a
     * background thread. Not tied to the service's lifecycle scope on purpose: a stop
     * is followed by stopSelf(), which would cancel that scope mid-clear.
     */
    fun clearAsync(context: Context, includeTiles: Boolean) {
        val app = context.applicationContext
        Thread({
            val files = try { clearCameraCache(app.cacheDir) } catch (e: Exception) {
                Log.w(TAG, "Camera cache not cleared: ${e.message}"); 0
            }
            val tiles = if (includeTiles) clearMapTiles(app) else null
            Log.i(TAG, "Cleared $files camera-cache file(s)" +
                if (tiles == null) "; map tiles kept" else "; map tiles purged=$tiles")
        }, "location-traces").start()
    }

    /**
     * Subclass only to reach osmdroid's shared connection ([SqlTileWriter.mDb]), so
     * the purge and the VACUUM go through the same connection the MapViews use.
     * The connection is left open: closing it could break a MapView still using it.
     */
    private class TileWiper : SqlTileWriter() {
        fun wipe(): Boolean {
            val purged = purgeCache()
            mDb?.execSQL("VACUUM")
            return purged
        }
    }
}
