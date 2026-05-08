package org.soulstone.overwatch.scan

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /** Don't retry an Overpass POST within this window after a failure. */
        private const val FAILURE_BACKOFF_MS = 60_000L
    }

    private var job: Job? = null
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null
    private var lastAttemptMs: Long = 0L
    private var lastAttemptOk: Boolean = false
    private val _cachedPoints = MutableStateFlow<List<DeflockClient.AlprPoint>>(emptyList())
    /** All ALPR points in the current cell — exposed so the UI map can render them.
     *  Distinct from the proximity-filtered DetectionEvents on [DetectionStore]. */
    val cachedPoints: StateFlow<List<DeflockClient.AlprPoint>> = _cachedPoints.asStateFlow()

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
        lastAttemptMs = 0L
        lastAttemptOk = false
        _cachedPoints.value = emptyList()
        Log.i(TAG, "DeflockScanner stopped")
    }

    private suspend fun handleFix(fix: Location) {
        if (shouldRefetch(fix)) {
            // Mark the attempt before the network call so a concurrent location
            // tick doesn't trigger a parallel re-fetch of the same area.
            lastFetchLat = fix.latitude
            lastFetchLon = fix.longitude
            lastAttemptMs = System.currentTimeMillis()
            when (val result = client.fetchAround(fix.latitude, fix.longitude)) {
                is DeflockClient.FetchResult.Success -> {
                    _cachedPoints.value = result.points
                    lastAttemptOk = true
                    SourceHealth.record(DetectionSource.DEFLOCK, ok = true)
                    Log.i(
                        TAG,
                        "Loaded ${result.points.size} ALPRs around " +
                            "(${fix.latitude}, ${fix.longitude})"
                    )
                }
                is DeflockClient.FetchResult.Failed -> {
                    lastAttemptOk = false
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
        emitProximityEvents(fix)
    }

    /**
     * Re-evaluate the cached ALPRs against the current proximity threshold and
     * the latest fix, *without* a network refetch. Used when the user moves the
     * proximity slider — the slider changes [proximityMeters], but the scanner
     * is otherwise idle (no new location ticks while stationary), so events
     * outside the new radius would otherwise linger and detections inside the
     * widened radius wouldn't appear until the next handleFix cycle.
     *
     * Clears the DEFLOCK source from the store first so events that fall
     * outside a tightened radius disappear immediately.
     */
    fun refresh() {
        val fix = locationProvider.location.value ?: return
        store.clearSource(DetectionSource.DEFLOCK)
        emitProximityEvents(fix)
    }

    private fun emitProximityEvents(fix: Location) {
        val points = _cachedPoints.value
        if (points.isEmpty()) return

        val limit = proximityMeters()
        val out = FloatArray(1)
        for (p in points) {
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
                    rssi = null,
                    lat = p.lat,
                    lon = p.lon
                )
            )
        }
    }

    private fun shouldRefetch(fix: Location): Boolean {
        val lat = lastFetchLat ?: return true
        val lon = lastFetchLon ?: return true
        // After a failed attempt, hold off for FAILURE_BACKOFF_MS even if the
        // user hasn't moved — avoids hammering Overpass when it's struggling.
        if (!lastAttemptOk &&
            System.currentTimeMillis() - lastAttemptMs < FAILURE_BACKOFF_MS) {
            return false
        }
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, fix.latitude, fix.longitude, out)
        return out[0] > REFETCH_THRESHOLD_M
    }
}
