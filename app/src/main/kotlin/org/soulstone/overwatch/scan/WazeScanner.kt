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
 * Polls the OpenWeb Ninja Waze feed for live POLICE alerts around the current
 * location, then submits any inside [proximityMeters] and younger than
 * [MAX_AGE_MS].
 *
 * Poll cadence is deliberately slow — the feed is a metered paid API and lags
 * live Waze by ~20 min anyway, so a 4-min poll loses nothing and keeps request
 * volume (and pay-as-you-go cost, ~$0.005/req) low at ~15 req/active-hour. Kept
 * just under the DetectionStore's 5-min retention so a persistent alert (a
 * standing checkpoint) is re-submitted before it can expire and flicker out. If
 * no API key is configured the loop records the source as unreachable (with a
 * clear reason) and skips the network call rather than hammering a 401.
 *
 * The last fetched alert set is cached so [refresh] can re-evaluate against a
 * moved proximity slider without a network refetch (mirrors CitizenScanner).
 */
class WazeScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: WazeClient = WazeClient(),
    private val proximityMeters: () -> Float = { 500f }
) {

    companion object {
        private const val TAG = "WazeScanner"
        private const val POLL_INTERVAL_MS = 240_000L
        // The hosted feed only lists still-active alerts but lags live Waze, so
        // real police sightings routinely arrive already 20-30 min old. A 10-min
        // cutoff (fine for the old direct-live feed) would drop nearly all of
        // them; 45 min matches what the feed actually serves as "current."
        private const val MAX_AGE_MS = 45L * 60L * 1000L
    }

    private var job: Job? = null
    private var lastAlerts: List<WazeClient.Alert> = emptyList()

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            // Wait for the first fix so the opening poll fires as soon as we have
            // a location instead of after a full interval.
            locationProvider.location.first { it != null }
            while (isActive) {
                val fix = locationProvider.location.value
                if (fix != null) pollOnce(fix)
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "WazeScanner started (interval=${POLL_INTERVAL_MS}ms, configured=${client.isConfigured})")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        lastAlerts = emptyList()
        Log.i(TAG, "WazeScanner stopped")
    }

    private suspend fun pollOnce(fix: Location) {
        if (!client.isConfigured) {
            SourceHealth.record(
                DetectionSource.WAZE,
                ok = false,
                message = "Proxy token not set — add it in Settings"
            )
            return
        }

        when (val result = client.fetchPoliceNear(fix.latitude, fix.longitude, proximityMeters())) {
            is WazeClient.FetchResult.Failed -> {
                SourceHealth.record(
                    DetectionSource.WAZE,
                    ok = false,
                    message = "Waze feed unreachable: ${result.reason}"
                )
            }
            is WazeClient.FetchResult.Success -> {
                SourceHealth.record(DetectionSource.WAZE, ok = true)
                lastAlerts = result.alerts
                // Deliberately no clearSource() here: re-submitting refreshes
                // still-present alerts by key (dedup) and lets vanished ones age
                // out via the store's 5-min TTL. Clearing every poll would briefly
                // drop the tier and re-raise it, double-firing the escalation
                // vibration each cycle. (Mirrors CitizenScanner.)
                emitProximityEvents(fix, result.alerts)
            }
        }
    }

    /**
     * Re-evaluate the last fetched alert set against the current proximity + age
     * thresholds and latest fix, without a network refetch. Used when the user
     * moves the Waze proximity slider.
     */
    fun refresh() {
        val fix = locationProvider.location.value ?: return
        store.clearSource(DetectionSource.WAZE)
        emitProximityEvents(fix, lastAlerts)
    }

    private fun emitProximityEvents(fix: Location, alerts: List<WazeClient.Alert>) {
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
                    lon = a.lon
                )
            )
        }
    }
}
