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
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * DeFlock orchestrator.
 *
 * Subscribes to [LocationProvider]; when the user has moved more than
 * [REFETCH_THRESHOLD_M] from the last fetch center (or there is no last
 * center), runs an Overpass query via [DeflockClient] for the surrounding
 * 5-km bbox. For each cached ALPR within [proximityMeters], submits a
 * detection event.
 */
class DeflockScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: DeflockClient,
    private val proximityMeters: () -> Float = { 200f }
) {

    companion object {
        private const val TAG = "DeflockScanner"
        private const val REFETCH_THRESHOLD_M = 1500f
    }

    private var job: Job? = null
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null
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
        lastFetchLat = null
        lastFetchLon = null
        cachedPoints = emptyList()
        Log.i(TAG, "DeflockScanner stopped")
    }

    private suspend fun handleFix(fix: Location) {
        if (shouldRefetch(fix)) {
            when (val result = client.fetchAround(fix.latitude, fix.longitude)) {
                is DeflockClient.FetchResult.Success -> {
                    cachedPoints = result.points
                    lastFetchLat = fix.latitude
                    lastFetchLon = fix.longitude
                    SourceHealth.record(DetectionSource.DEFLOCK, ok = true)
                    Log.i(
                        TAG,
                        "Loaded ${cachedPoints.size} ALPRs around " +
                            "(${fix.latitude}, ${fix.longitude})"
                    )
                }
                is DeflockClient.FetchResult.Failed -> {
                    SourceHealth.record(
                        DetectionSource.DEFLOCK,
                        ok = false,
                        message = "Overpass unreachable: ${result.reason}"
                    )
                    Log.w(TAG, "Overpass fetch failed: ${result.reason}")
                    // Keep using cachedPoints (may be empty on first failure).
                }
            }
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

    private fun shouldRefetch(fix: Location): Boolean {
        val lat = lastFetchLat ?: return true
        val lon = lastFetchLon ?: return true
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, fix.latitude, fix.longitude, out)
        return out[0] > REFETCH_THRESHOLD_M
    }
}
