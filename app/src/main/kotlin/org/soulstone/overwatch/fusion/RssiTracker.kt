package org.soulstone.overwatch.fusion

/**
 * Tracks RSSI samples per device to detect a stationary signature.
 *
 * Ported from flock-detection (rssi_track_update / rssi_track_is_stationary):
 * a fixed-installation camera produces a rise → peak → fall pattern as the
 * observer walks past it. A handheld emitter (phone, etc.) does not.
 *
 * Algorithm:
 *  - Keep up to [windowSize] recent samples per key.
 *  - Find peak index. Stationary if peak is NOT at the edge AND
 *    range (peak - min(first, last)) >= [minRangeDb].
 */
class RssiTracker(
    private val windowSize: Int = 15,
    private val minRangeDb: Int = 6
) {
    private val samples: MutableMap<String, ArrayDeque<Int>> = mutableMapOf()

    @Synchronized
    fun update(key: String, rssi: Int) {
        val deque = samples.getOrPut(key) { ArrayDeque() }
        deque.addLast(rssi)
        while (deque.size > windowSize) deque.removeFirst()
    }

    @Synchronized
    fun isStationary(key: String): Boolean {
        val s = samples[key] ?: return false
        if (s.size < 3) return false
        val list = s.toList()
        val peakIdx = list.indices.maxByOrNull { list[it] } ?: return false
        if (peakIdx == 0 || peakIdx == list.lastIndex) return false
        val edgeMin = minOf(list.first(), list.last())
        return (list[peakIdx] - edgeMin) >= minRangeDb
    }

    @Synchronized
    fun clear() = samples.clear()
}
