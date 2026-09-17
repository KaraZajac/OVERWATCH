package org.soulstone.overwatch.scan

import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.data.targets.LeAircraft
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * Watches overhead air traffic for surveillance aircraft.
 *
 * Two independent signals, because either alone misses real cases:
 *
 *  1. **Registry match** — the contact's ICAO address is in [LeAircraft].
 *     Authoritative when it fires, but the bundled list only covers aircraft
 *     whose registered owner names them: measured 2026-09-17, a 250 nm sweep of
 *     Washington DC returned 394 aircraft and matched none of 2,760 community
 *     police/government hexes, while 15 helicopters were aloft.
 *
 *  2. **Loiter/orbit behaviour** — surveillance aircraft circle; airliners,
 *     and medevac flights heading somewhere, do not. An aircraft that stays
 *     inside [LOITER_RADIUS_M] of its own track centroid across several
 *     minutes, low and slow, is behaving like a surveillance asset whatever
 *     the registry says. This is what catches the unlisted ones.
 *
 * A registry hit is reported wherever it is; an unregistered aircraft is only
 * reported when it is both loitering *and* close, so ordinary traffic passing
 * overhead never raises an alert.
 */
class AircraftScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val locationProvider: LocationProvider,
    private val client: AircraftClient = AircraftClient()
) {

    companion object {
        private const val TAG = "AircraftScanner"
        /** Aircraft move fast, but the feed is a courtesy — don't hammer it. */
        private const val POLL_INTERVAL_MS = 60_000L

        /** Ignore anything further out than this; the query itself is wider. */
        private const val MAX_RANGE_M = 15_000f

        /** An unregistered aircraft has to be at least this close to report. */
        private const val UNKNOWN_MAX_RANGE_M = 8_000f

        /** Above this, it's transiting airspace, not watching anything. */
        private const val LOW_ALTITUDE_FT = 12_000

        /** Surveillance orbits are slow; airliners are not. */
        private const val SLOW_GROUND_SPEED_KT = 200.0

        /** Track history older than this is dropped. */
        private const val HISTORY_TTL_MS = 15L * 60L * 1000L

        /** Loiter test: samples must span at least this long… */
        private const val LOITER_MIN_SPAN_MS = 4L * 60L * 1000L

        /** …and stay within this radius of their own centroid. */
        private const val LOITER_RADIUS_M = 5_000f

        private const val LOITER_MIN_SAMPLES = 3
    }

    private data class Sample(val lat: Double, val lon: Double, val atMs: Long)

    private var job: Job? = null
    /** Per-aircraft recent track, for the loiter test. Cleared on stop. */
    private val history = mutableMapOf<String, MutableList<Sample>>()

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        job = scope.launch {
            locationProvider.location.first { it != null }
            while (isActive) {
                locationProvider.location.value?.let { pollOnce(it) }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "AircraftScanner started (interval=${POLL_INTERVAL_MS}ms)")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        history.clear()
        Log.i(TAG, "AircraftScanner stopped")
    }

    private suspend fun pollOnce(fix: Location) {
        when (val result = client.fetchAround(fix.latitude, fix.longitude)) {
            is AircraftClient.FetchResult.Failed -> SourceHealth.record(
                DetectionSource.AIRCRAFT, ok = false,
                message = "ADS-B feed unreachable: ${result.reason}"
            )
            is AircraftClient.FetchResult.Success -> {
                SourceHealth.record(DetectionSource.AIRCRAFT, ok = true)
                handle(fix, result.contacts)
            }
        }
    }

    private fun handle(fix: Location, contacts: List<AircraftClient.Contact>) {
        val now = System.currentTimeMillis()
        recordHistory(contacts, now)

        val out = FloatArray(1)
        for (c in contacts) {
            Location.distanceBetween(fix.latitude, fix.longitude, c.lat, c.lon, out)
            val ground = out[0]
            if (ground > MAX_RANGE_M) continue

            val known = LeAircraft.lookup(context, c.icaoHex)
            val low = (c.altitudeFt ?: Int.MAX_VALUE) <= LOW_ALTITUDE_FT
            val slow = (c.groundSpeedKt ?: Double.MAX_VALUE) <= SLOW_GROUND_SPEED_KT
            val loitering = low && slow && isLoitering(c.icaoHex, now)

            // An unknown aircraft is only interesting if it is behaving like a
            // surveillance asset AND is overhead; otherwise it's just traffic.
            if (known == null && !(loitering && ground <= UNKNOWN_MAX_RANGE_M)) continue

            val obs = ConfidenceEngine.AircraftObservation(
                icaoHex = c.icaoHex,
                distanceMeters = ground,
                altitudeFt = c.altitudeFt,
                isKnownLawEnforcement = known != null,
                isLoitering = loitering,
                isLadd = known?.ladd == true,
                owner = known?.owner,
                registration = c.registration,
                aircraftType = c.type,
                callsign = c.callsign
            )
            val scored = ConfidenceEngine.scoreAircraft(obs)
            store.submit(
                DetectionEvent(
                    source = DetectionSource.AIRCRAFT,
                    key = "air:${c.icaoHex}",
                    label = scored.label,
                    score = scored.score,
                    matchedMethods = scored.methods,
                    rssi = null,
                    lat = c.lat,
                    lon = c.lon
                )
            )
        }
    }

    private fun recordHistory(contacts: List<AircraftClient.Contact>, now: Long) {
        for (c in contacts) {
            history.getOrPut(c.icaoHex) { mutableListOf() }
                .add(Sample(c.lat, c.lon, now))
        }
        val cutoff = now - HISTORY_TTL_MS
        val it = history.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.removeAll { s -> s.atMs < cutoff }
            if (e.value.isEmpty()) it.remove()
        }
    }

    /**
     * True when the aircraft's recent track stays bunched around its own
     * centroid — i.e. it is going round rather than going somewhere.
     */
    private fun isLoitering(hex: String, now: Long): Boolean {
        val samples = history[hex] ?: return false
        if (samples.size < LOITER_MIN_SAMPLES) return false
        val span = now - samples.first().atMs
        if (span < LOITER_MIN_SPAN_MS) return false
        val cLat = samples.sumOf { it.lat } / samples.size
        val cLon = samples.sumOf { it.lon } / samples.size
        val out = FloatArray(1)
        for (s in samples) {
            Location.distanceBetween(cLat, cLon, s.lat, s.lon, out)
            if (out[0] > LOITER_RADIUS_M) return false
        }
        return true
    }
}
