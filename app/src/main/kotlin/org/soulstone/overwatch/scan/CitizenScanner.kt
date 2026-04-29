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
 * Polls Citizen.com for nearby active incidents, filters out pure fire/medical
 * (no police presence implied), and submits a detection event for each
 * remaining incident inside [proximityMeters] and younger than [MAX_AGE_MS].
 *
 * Detail responses are cached in-memory by incident id for the life of the
 * scanner — Citizen incidents don't mutate after creation, so we only need to
 * fetch each id once per session.
 */
class CitizenScanner(
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: CitizenClient = CitizenClient(),
    private val proximityMeters: () -> Float = { 500f }
) {

    companion object {
        private const val TAG = "CitizenScanner"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val MAX_AGE_MS = 30L * 60L * 1000L

        /** Skip incidents whose title is purely fire/medical with no police implication. */
        private val FIRE_MEDICAL_RX = Regex(
            "\\b(fire|smoke|gas\\s+(odor|leak)|medical|cardiac|ambulance|" +
                "ems|injury|alarm|odor)\\b",
            RegexOption.IGNORE_CASE
        )

        /** Title contains an explicit police-action keyword → score bump. */
        private val POLICE_TITLE_RX = Regex(
            "\\b(police|officer|patrol|arrest|swat|tactical|raid|pursuit|" +
                "stop|search\\s+warrant)\\b",
            RegexOption.IGNORE_CASE
        )
    }

    private var job: Job? = null
    /** Detail cache for the lifetime of one start/stop cycle. */
    private val incidentCache = mutableMapOf<String, CitizenClient.Incident>()

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            while (isActive) {
                val fix = locationProvider.location.value
                if (fix != null) pollOnce(fix)
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "CitizenScanner started (interval=${POLL_INTERVAL_MS}ms)")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        incidentCache.clear()
        Log.i(TAG, "CitizenScanner stopped")
    }

    private suspend fun pollOnce(fix: Location) {
        when (val trending = client.trendingNear(fix.latitude, fix.longitude)) {
            is CitizenClient.TrendingResult.Failed -> {
                SourceHealth.record(
                    DetectionSource.CITIZEN,
                    ok = false,
                    message = "Citizen unreachable: ${trending.reason}"
                )
                return
            }
            is CitizenClient.TrendingResult.Success -> {
                SourceHealth.record(DetectionSource.CITIZEN, ok = true)
                handleIds(fix, trending.ids)
            }
        }
    }

    private suspend fun handleIds(fix: Location, ids: List<String>) {
        // Drop cache entries that no longer appear in the trending list (resolved).
        incidentCache.keys.retainAll(ids.toSet())

        val now = System.currentTimeMillis()
        val limit = proximityMeters()
        val out = FloatArray(1)

        for (id in ids) {
            val incident = incidentCache[id] ?: client.fetchIncident(id)?.also {
                incidentCache[id] = it
            } ?: continue

            // Title-based pre-filter: drop pure fire/medical events.
            if (FIRE_MEDICAL_RX.containsMatchIn(incident.title) &&
                !POLICE_TITLE_RX.containsMatchIn(incident.title)) {
                continue
            }

            val age = now - incident.pubMillis
            if (age > MAX_AGE_MS) continue
            Location.distanceBetween(
                fix.latitude, fix.longitude,
                incident.lat, incident.lon,
                out
            )
            val dist = out[0]
            if (dist > limit) continue

            val obs = ConfidenceEngine.CitizenObservation(
                incidentId = incident.id,
                distanceMeters = dist,
                ageMs = age,
                level = incident.level,
                title = incident.title,
                isPoliceTitled = POLICE_TITLE_RX.containsMatchIn(incident.title),
                precinct = incident.precinct
            )
            val scored = ConfidenceEngine.scoreCitizen(obs)
            store.submit(
                DetectionEvent(
                    source = DetectionSource.CITIZEN,
                    key = "citizen:${incident.id}",
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = null
                )
            )
        }
    }
}
