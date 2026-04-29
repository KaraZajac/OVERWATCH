package org.soulstone.overwatch.scan

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * Polls Waze every 60s for live POLICE alerts in a small bounding box around the
 * current location, then submits any inside [PROXIMITY_M] and younger than [MAX_AGE_MS].
 *
 * Skips the poll cycle if location is not yet known. Network-only — no on-disk cache
 * (data is real-time by definition).
 */
class WazeScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: WazeClient = WazeClient(),
    private val proximityMeters: () -> Float = { 500f }
) {

    companion object {
        private const val TAG = "WazeScanner"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val MAX_AGE_MS = 10L * 60L * 1000L
    }

    private var job: Job? = null

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            while (isActive) {
                val fix = locationProvider.location.value
                if (fix != null) {
                    pollOnce(fix)
                } else {
                    Log.d(TAG, "Skip poll — no location yet")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "WazeScanner started (interval=${POLL_INTERVAL_MS}ms)")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "WazeScanner stopped")
    }

    private suspend fun pollOnce(fix: Location) {
        val result = client.fetchPoliceNear(fix.latitude, fix.longitude)
        val alerts = when (result) {
            is WazeClient.FetchResult.Success -> {
                SourceHealth.record(DetectionSource.WAZE, ok = true)
                result.alerts
            }
            is WazeClient.FetchResult.Failed -> {
                SourceHealth.record(
                    DetectionSource.WAZE,
                    ok = false,
                    message = "Waze unreachable: ${result.reason}"
                )
                return
            }
        }
        if (alerts.isEmpty()) return
        val now = System.currentTimeMillis()
        val limit = proximityMeters()
        val out = FloatArray(1)

        for (a in alerts) {
            val age = now - a.pubMillis
            if (age > MAX_AGE_MS) continue
            Location.distanceBetween(fix.latitude, fix.longitude, a.lat, a.lon, out)
            val dist = out[0]
            if (dist > limit) continue

            val obs = ConfidenceEngine.WazeObservation(
                uuid = a.uuid,
                distanceMeters = dist,
                ageMs = age,
                confidence = a.confidence,
                reliability = a.reliability,
                subtype = a.subtype
            )
            val scored = ConfidenceEngine.scoreWaze(obs)
            store.submit(
                DetectionEvent(
                    source = DetectionSource.WAZE,
                    key = a.uuid,
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = null
                )
            )
        }
    }
}
