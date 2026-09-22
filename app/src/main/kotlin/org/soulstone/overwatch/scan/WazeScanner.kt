package org.soulstone.overwatch.scan

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * Polls a [WazeSource] backend for live POLICE alerts around the current
 * location, then submits any inside [proximityMeters] and younger than
 * [MAX_AGE_MS].
 *
 * The backend supplies its own poll cadence, because the two differ by an order
 * of magnitude for good reasons: the metered hosted feed polls every 4 min to
 * keep cost down, the direct protocol every 60 s to keep its session alive. If
 * the backend is not configured (no API key, say) the loop records the source as
 * unreachable with that backend's own reason and skips the network call rather
 * than hammering a 401.
 *
 */
class WazeScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val source: WazeSource,
    private val proximityMeters: () -> Float = { EVAL_RADIUS_M }
) {

    companion object {
        private const val TAG = "WazeScanner"
        /** Fixed evaluation radius — see DeflockScanner.EVAL_RADIUS_M. Wider
         *  than the camera one because police move and a report stays above
         *  GREEN further out. */
        const val EVAL_RADIUS_M = 2000f
        // Hosted-feed alerts routinely arrive already 20-30 min old (it lags live
        // Waze), so a 10-min cutoff would drop nearly all of them. 45 min matches
        // what that feed serves as "current" and is harmless for the live backend.
        /** Shared with ConfidenceEngine, which decays the score across this window. */
        private const val MAX_AGE_MS = ConfidenceEngine.WAZE_MAX_AGE_MS
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            // Wait for the first fix so the opening poll fires as soon as we have
            // a location instead of after a full interval.
            locationProvider.location.first { it != null }
            while (isActive) {
                val fix = locationProvider.location.value
                if (fix != null) pollOnce(fix)
                delay(source.pollIntervalMs)
            }
        }
        Log.i(
            TAG,
            "WazeScanner started (backend=${source.backendName}, " +
                "interval=${source.pollIntervalMs}ms, configured=${source.isConfigured})"
        )
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "WazeScanner stopped")
    }

    private suspend fun pollOnce(fix: Location) {
        if (!source.isConfigured) {
            SourceHealth.record(DetectionSource.WAZE, ok = false, message = source.unconfiguredReason)
            return
        }

        when (val result = source.fetchPoliceNear(fix.latitude, fix.longitude, proximityMeters())) {
            is WazeSource.FetchResult.Failed -> {
                SourceHealth.record(
                    DetectionSource.WAZE,
                    ok = false,
                    message = "${source.backendName} unreachable: ${result.reason}"
                )
            }
            is WazeSource.FetchResult.Success -> {
                SourceHealth.record(DetectionSource.WAZE, ok = true)
                // Deliberately no clearSource() here: re-submitting refreshes
                // still-present alerts by key (dedup) and lets vanished ones age
                // out via the store's 5-min TTL. Clearing every poll would briefly
                // drop the tier and re-raise it, double-firing the escalation
                // vibration each cycle.
                emitProximityEvents(fix, result.alerts)
            }
        }
    }


    private fun emitProximityEvents(fix: Location, alerts: List<WazeSource.Alert>) {
        val now = System.currentTimeMillis()
        val limit = proximityMeters()
        val out = FloatArray(1)

        for (a in alerts) {
            val age = now - a.pubMillis
            if (age > MAX_AGE_MS) continue
            Location.distanceBetween(fix.latitude, fix.longitude, a.lat, a.lon, out)
            val dist = out[0]
            if (dist > limit) continue

            val scored = ConfidenceEngine.scoreWaze(
                ConfidenceEngine.WazeObservation(
                    uuid = a.uuid,
                    distanceMeters = dist,
                    ageMs = age,
                    confidence = a.confidence,
                    reliability = a.reliability,
                    subtype = a.subtype
                )
            )
            store.submit(
                DetectionEvent(
                    source = DetectionSource.WAZE,
                    key = "waze:${a.uuid}",
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = null,
                    lat = a.lat,
                    lon = a.lon,
                    distanceMeters = dist
                )
            )
        }
    }
}
