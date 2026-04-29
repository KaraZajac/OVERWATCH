package org.soulstone.overwatch.fusion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory hub for detection events.
 *
 * - Keeps the most recent event per (source, key) — newer overwrites older.
 * - Drops events older than [retentionMs] (default 5 min, mirrors flock-detection's dedup window).
 * - Exposes [threatLevel] = the worst tier across all live events.
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

    @Synchronized
    fun submit(event: DetectionEvent) {
        val cutoff = nowMs() - retentionMs
        val merged = (_events.value + event)
            .filter { it.timestampMs >= cutoff }
            .groupBy { it.source to it.key }
            .map { (_, list) -> list.maxByOrNull { it.timestampMs }!! }
            .sortedByDescending { it.score }
        _events.value = merged
        recompute(merged)
    }

    @Synchronized
    fun clear() {
        _events.value = emptyList()
        _threatLevel.value = ThreatLevel.GREEN
        _maxScore.value = 0
    }

    @Synchronized
    fun pruneExpired() {
        val cutoff = nowMs() - retentionMs
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
}
