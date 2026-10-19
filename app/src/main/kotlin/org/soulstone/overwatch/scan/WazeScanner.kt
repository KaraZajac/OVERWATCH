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
 * location, then scores any inside [proximityMeters] and younger than
 * [MAX_AGE_MS], again at every fix until the next poll.
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
        /** How long a poll's alerts stay scored if later polls fail: the
         *  DetectionStore's retention, which used to drop them. */
        private const val ALERTS_KEPT_MS = 5 * 60 * 1000L
    }

    private var job: Job? = null
    private var rescoreJob: Job? = null
    /** The last successful poll's alerts, and when it returned. */
    private var alerts: List<WazeSource.Alert> = emptyList()
    private var alertsAtMs = 0L

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
        // Between polls, re-score the last poll's alerts at every fix, as the
        // camera map does: a report doesn't move, so its distance from the latest
        // fix is always known. Until v0.5.20 a report kept the distance and score
        // of the poll that found it until the store dropped it 5 minutes later:
        // "@ 180m" on a phone that had since moved kilometres, or whose first fix
        // at START was a stale cached position.
        rescoreJob = scope.launch {
            locationProvider.location.collect { fix ->
                if (fix != null) emitProximityEvents(fix)
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
        rescoreJob?.cancel()
        rescoreJob = null
        alerts = emptyList()
        alertsAtMs = 0L
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
                alerts = result.alerts
                alertsAtMs = System.currentTimeMillis()
                // The fetch can take seconds: score from the latest fix.
                emitProximityEvents(locationProvider.location.value ?: fix)
            }
        }
    }


    private fun emitProximityEvents(fix: Location) {
        val now = System.currentTimeMillis()
        // After failed polls, keep the last alerts only as long as the store keeps
        // an event, so an outage still ends with the reports gone.
        val current = if (now - alertsAtMs > ALERTS_KEPT_MS) emptyList() else alerts
        val limit = proximityMeters()
        val out = FloatArray(1)

        val events = current.mapNotNull { a ->
            val age = now - a.pubMillis
            if (age > MAX_AGE_MS) return@mapNotNull null
            Location.distanceBetween(fix.latitude, fix.longitude, a.lat, a.lon, out)
            val dist = out[0]
            if (dist > limit) return@mapNotNull null

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
        }
        // One replacement, never a clear and re-add: a clear would drop the tier
        // for a moment, and the re-add would fire the escalation vibration again.
        store.replaceSource(DetectionSource.WAZE, events)
    }
}
