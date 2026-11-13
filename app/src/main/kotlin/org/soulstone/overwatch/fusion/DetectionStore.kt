package org.soulstone.overwatch.fusion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory hub for detection events.
 *
 * - Keeps the most recent event per (source, key) — newer overwrites older.
 * - Holds a device's highest score for [PEAK_HOLD_MS] after it was last reached,
 *   so one weaker advert does not move the tier. A radio device is re-scored
 *   several times a second, and a signal a few dB either side of the
 *   strong-signal line took the circle from RED to ORANGE and back at that rate.
 * - Drops events older than [retentionMs] (default 5 min, mirrors flock-detection's dedup window).
 * - Exposes [threatLevel] = the worst tier across all live events.
 * - Drops anything [ignored] says to, before it can count: an ignored device never
 *   reaches the tier, the vibration, the notification, the overlay or a list.
 * - No persistence: per the user's spec, no detection-history DB.
 */
class DetectionStore(
    private val retentionMs: Long = 5 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val _events = MutableStateFlow<List<DetectionEvent>>(emptyList())
    val events: StateFlow<List<DetectionEvent>> = _events.asStateFlow()

    private val _threatLevel = MutableStateFlow(ThreatLevel.GREEN)
    val threatLevel: StateFlow<ThreatLevel> = _threatLevel.asStateFlow()

    private val _maxScore = MutableStateFlow(0)
    val maxScore: StateFlow<Int> = _maxScore.asStateFlow()

    /** The user's ignore list, asked about every event on its way in. It is a
     *  function so the store stays free of Android and of storage; whoever sets
     *  it after events exist follows with [dropIgnored]. */
    @Volatile
    var ignored: (DetectionEvent) -> Boolean = { false }

    private class Peak(val score: Int, val methods: String, val atMs: Long)
    private val peaks = HashMap<Pair<DetectionSource, String>, Peak>()

    @Synchronized
    fun submit(event: DetectionEvent) {
        if (ignored(event)) return
        val now = nowMs()
        val id = event.source to event.key
        val peak = peaks[id]
        // The row keeps this advert's label and signal strength either way; only
        // the score, and the reasons that explain it, are the held ones.
        val shown = if (peak != null && event.score < peak.score && now - peak.atMs < PEAK_HOLD_MS) {
            event.copy(score = peak.score, matchedMethods = peak.methods)
        } else {
            peaks[id] = Peak(event.score, event.matchedMethods, now)
            event
        }
        val cutoff = now - retentionMs
        val merged = (_events.value + shown)
            .filter { it.timestampMs >= cutoff }
            .groupBy { it.source to it.key }
            .map { (_, list) -> list.maxByOrNull { it.timestampMs }!! }
            .sortedByDescending { it.score }
        _events.value = merged
        recompute(merged)
    }

    /** Replace every event from [source] with [events], for a scanner that
     *  re-scores its whole set at each fix: whatever it leaves out is gone now,
     *  not after [retentionMs]. One update, so the tier never dips in between
     *  (a dip and rise would fire the escalation vibration; see WazeScanner). */
    @Synchronized
    fun replaceSource(source: DetectionSource, events: List<DetectionEvent>) =
        replaceKeyed(source, keyPrefix = "", events = events)

    /** [replaceSource] for a source that two scanners feed: only the events whose
     *  key starts with [keyPrefix] are replaced, the source's others stay.
     *  AIRCRAFT holds ADS-B contacts ("air:") from a poll loop and drones' Remote
     *  ID ("rid:") from the radio scanners, and a poll must not clear a drone. */
    @Synchronized
    fun replaceKeyed(source: DetectionSource, keyPrefix: String, events: List<DetectionEvent>) {
        val cutoff = nowMs() - retentionMs
        val kept = _events.value.filter {
            it.timestampMs >= cutoff && !(it.source == source && it.key.startsWith(keyPrefix))
        }
        val merged = (kept + events.filterNot(ignored).distinctBy { it.key })
            .sortedByDescending { it.score }
        _events.value = merged
        recompute(merged)
    }

    /** Drop one event, if it is held. For a row that has been superseded: a drone
     *  listed under its transmitter until its serial arrived. */
    @Synchronized
    fun remove(source: DetectionSource, key: String) {
        val remaining = _events.value.filterNot { it.source == source && it.key == key }
        if (remaining.size == _events.value.size) return
        peaks.remove(source to key)
        _events.value = remaining
        recompute(remaining)
    }

    @Synchronized
    fun clear() {
        peaks.clear()
        _events.value = emptyList()
        _threatLevel.value = ThreatLevel.GREEN
        _maxScore.value = 0
    }

    /** Drop every event from a single source, for a scanner that needs to re-emit
     *  a fresh slate. Nothing calls it at present: range changes used to, but since
     *  v0.5.12 the range only filters the view and the scanners use fixed radii. */
    @Synchronized
    fun clearSource(source: DetectionSource) {
        val remaining = _events.value.filter { it.source != source }
        if (remaining.size == _events.value.size) return
        _events.value = remaining
        recompute(remaining)
    }

    /** Apply [ignored] to what is already held, for a rule added while its device
     *  is on screen: the row goes and the tier is worked out again without it. A
     *  rule that is removed needs nothing here; the device is back at its next
     *  sighting. */
    @Synchronized
    fun dropIgnored() {
        val live = _events.value.filterNot(ignored)
        if (live.size != _events.value.size) {
            _events.value = live
            recompute(live)
        }
    }

    @Synchronized
    fun pruneExpired() {
        val cutoff = nowMs() - retentionMs
        peaks.entries.removeAll { it.value.atMs < cutoff }
        val live = _events.value.filter { it.timestampMs >= cutoff }
        if (live.size != _events.value.size) {
            _events.value = live
            recompute(live)
        }
    }

    private fun recompute(live: List<DetectionEvent>) {
        val max = live.maxOfOrNull { it.score } ?: 0
        _maxScore.value = max
        _threatLevel.value = ThreatLevel.fromScore(max)
    }

    companion object {
        /** How long a device's highest score outlives the advert that earned it. */
        const val PEAK_HOLD_MS = 10_000L
    }
}
