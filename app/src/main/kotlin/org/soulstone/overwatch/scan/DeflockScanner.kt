package org.soulstone.overwatch.scan

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore

/**
 * DeFlock orchestrator.
 *
 * Subscribes to [LocationProvider]; for each new fix, looks up the matching 20° tile
 * (loaded from [DeflockClient] cache or downloaded once / 24h) and submits a
 * detection event for every ALPR within [PROXIMITY_M].
 *
 * Tile-boundary edge case: at lat ≈ tile_lat or lon ≈ tile_lon ±0.002°, ALPRs across
 * the boundary won't be visible until the user crosses it. Acceptable for v0.1 — a
 * 5-tile fetch (current + 4 neighbours) is a polish item.
 */
class DeflockScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: DeflockClient,
    private val proximityMeters: () -> Float = { 200f }
) {

    companion object {
        private const val TAG = "DeflockScanner"
    }

    private var job: Job? = null
    private var lastTile: DeflockClient.TileKey? = null
    private var cachedPoints: List<DeflockClient.AlprPoint> = emptyList()

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            locationProvider.location.collectLatest { fix ->
                if (fix != null) handleFix(fix)
            }
        }
        Log.i(TAG, "DeflockScanner started")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        lastTile = null
        cachedPoints = emptyList()
        Log.i(TAG, "DeflockScanner stopped")
    }

    private suspend fun handleFix(fix: Location) {
        val tile = client.tileFor(fix.latitude, fix.longitude)
        if (tile != lastTile) {
            cachedPoints = client.fetchTile(tile)
            lastTile = tile
            Log.i(TAG, "Loaded tile $tile with ${cachedPoints.size} ALPRs")
        }
        if (cachedPoints.isEmpty()) return

        val limit = proximityMeters()
        val out = FloatArray(1)
        for (p in cachedPoints) {
            Location.distanceBetween(fix.latitude, fix.longitude, p.lat, p.lon, out)
            val dist = out[0]
            if (dist > limit) continue
            val obs = ConfidenceEngine.DeflockObservation(
                osmId = p.id,
                distanceMeters = dist,
                operator = p.operator,
                manufacturer = p.manufacturer
            )
            val scored = ConfidenceEngine.scoreDeflock(obs)
            store.submit(
                DetectionEvent(
                    source = DetectionSource.DEFLOCK,
                    key = "osm:${p.id}",
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = null
                )
            )
        }
    }
}
