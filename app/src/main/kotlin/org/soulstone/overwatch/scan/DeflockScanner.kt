package org.soulstone.overwatch.scan

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Subscribes to [LocationProvider] and keeps one grid cell's box of mapped
 * cameras loaded (see [DeflockClient]). On each fix it scores every camera within
 * [proximityMeters]. It asks the client for the fix's own cell only when that
 * circle reaches past the loaded box, and the cell is a different one: below about
 * 64° latitude a cell's box holds the circle of every fix inside the cell, so
 * every fix is scored against a box that covers it. A cell already fetched this
 * scan comes back from the cache without a request.
 *
 * Until v0.5.19 it refetched only after 1.5 km of movement, so for a while after
 * entering a new cell fixes were checked against the old box, and part of the
 * circle could lie outside it (0.64% of fixes at 38°N and 11.7% at 60°N on
 * simulated drives; none with this rule, see SOURCES.md §2.1).
 */
class DeflockScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: DeflockClient,
    private val proximityMeters: () -> Float = { EVAL_RADIUS_M }
) {

    companion object {
        private const val TAG = "DeflockScanner"
        /**
         * Fixed evaluation radius — deliberately NOT the user's view slider.
         * Everything inside this is scored and can drive the threat tier; the
         * slider only decides how much of it is drawn. An ALPR scores below
         * YELLOW past ~500 m, so this carries margin while keeping the store small.
         */
        const val EVAL_RADIUS_M = 1200f
        /** After a failed fetch, wait this long before asking again, moving or not. */
        private const val FAILURE_BACKOFF_MS = 60_000L
    }

    private var job: Job? = null
    /** The cell whose box [cachedPoints] holds; null until a fetch succeeds. */
    private var loadedCell: DeflockClient.Cell? = null
    /** When the last fetch failed; 0 once one succeeds. */
    private var lastFailureMs: Long = 0L
    private val _cachedPoints = MutableStateFlow<List<DeflockClient.SurveillancePoint>>(emptyList())
    /** Every mapped camera in the loaded cell's box — exposed so the UI map can render them.
     *  Distinct from the proximity-filtered DetectionEvents on [DetectionStore]. */
    val cachedPoints: StateFlow<List<DeflockClient.SurveillancePoint>> = _cachedPoints.asStateFlow()

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            // collect, not collectLatest: a newer fix must not cancel a fetch in
            // flight. Cancelling discards the response, and fixes arrive every
            // 5-15 s, often sooner at start, when the phone's cached position and
            // its first live fix land within a second. Until v0.5.19 a discarded
            // first fetch could leave the map empty until the user had moved 1.5 km.
            // While a fetch runs, newer fixes wait; the latest is scored after it.
            locationProvider.location.collect { fix ->
                if (fix != null) handleFix(fix)
            }
        }
        Log.i(TAG, "DeflockScanner started")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        loadedCell = null
        lastFailureMs = 0L
        _cachedPoints.value = emptyList()
        Log.i(TAG, "DeflockScanner stopped")
    }

    private suspend fun handleFix(fix: Location) {
        if (needsFetch(fix)) {
            when (val result = client.fetchAround(fix.latitude, fix.longitude)) {
                is DeflockClient.FetchResult.Success -> {
                    _cachedPoints.value = result.points
                    loadedCell = DeflockClient.Cell.of(fix.latitude, fix.longitude)
                    lastFailureMs = 0L
                    SourceHealth.record(DetectionSource.DEFLOCK, ok = true)
                    // Never a coordinate here: logcat outlives the scan and turns
                    // up in bug reports, so a position would be a location trail
                    // the app can't clear (v0.5.17 logged the exact fix).
                    Log.i(TAG, "Loaded ${result.points.size} surveillance nodes")
                }
                is DeflockClient.FetchResult.Failed -> {
                    lastFailureMs = System.currentTimeMillis()
                    SourceHealth.record(
                        DetectionSource.DEFLOCK,
                        ok = false,
                        message = "Overpass unreachable: ${result.reason}"
                    )
                    Log.w(TAG, "Overpass fetch failed: ${result.reason}")
                    // Keep scoring against the loaded box, if any: it still covers
                    // part of the circle.
                }
            }
        }
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
                kind = p.kind,
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
                    lon = p.lon,
                    distanceMeters = dist
                )
            )
        }
    }

    private fun needsFetch(fix: Location): Boolean {
        // After a failure, wait out the backoff, then ask again whether or not the
        // user has moved. (Until v0.5.19 a retry also needed 1.5 km of movement,
        // so one failure while parked emptied the map until you drove off.)
        if (lastFailureMs != 0L &&
            System.currentTimeMillis() - lastFailureMs < FAILURE_BACKOFF_MS) {
            return false
        }
        val loaded = loadedCell ?: return true
        if (loaded.holds(fix.latitude, fix.longitude, proximityMeters().toDouble())) return false
        // Past ~64° latitude even a fix's own cell can't hold the whole circle, and
        // asking for it again would only re-read the cache.
        return DeflockClient.Cell.of(fix.latitude, fix.longitude) != loaded
    }
}
