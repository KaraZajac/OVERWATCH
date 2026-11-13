package org.soulstone.overwatch.fusion

import android.os.SystemClock

/**
 * Spots the signal shape of passing a fixed transmitter: its RSSI rises, peaks
 * and falls again as you go by. A device you carry, or one you are standing
 * next to, does not do that.
 *
 * The idea is flock-detection's (rssi_track_update / rssi_track_is_stationary).
 * Its rule, ported unchanged and used through v0.6.0, looked at the last 15 raw samples and
 * said yes when the strongest was not the first or last and stood 6 dB over the
 * weaker of those two. Android delivers every advert, several a second, so those
 * 15 samples covered a few seconds, and ordinary RSSI jitter of 2 to 4 dB passed
 * the test on 28 to 74 % of checks with nobody moving. The +15 it earns then
 * lifted a bare module prefix (25) over the display floor on noise alone.
 *
 * What is measured now:
 *  - Samples are averaged into [SLOT_MS] slots, which takes most of the jitter
 *    out of a Bluetooth signal. Bluetooth only: a Wi-Fi scan comes every 35 s,
 *    too seldom to show a pass, so WifiScanner does not use this class.
 *  - The last [MAX_SLOTS] slots are cut into thirds. It is a pass when the middle
 *    third averages at least [minRangeDb] over the first third AND over the last:
 *    a rise and a fall, not one or the other.
 *  - The step is 8 dB, not flock-detection's 6, and the window a minute, not a
 *    few seconds. Both come from simulating the rule (log-distance path loss,
 *    3 dB jitter, slow fading of 3 to 5 dB): standing still for ten minutes reads
 *    as a pass in under 10 % of runs at the worst, walking past within 5 m in
 *    all of them, at 10 m in about three in four, driving past at 13 m/s within
 *    15 m in over nine in ten. A signal that really does swell and fade by 8 dB
 *    while nobody moves (a body turning between phone and device) still reads
 *    as a pass; signal strength alone cannot tell those apart.
 *  - Under [MIN_SLOTS] slots, or with a third holding fewer than
 *    [MIN_SAMPLES_PER_THIRD] samples, there is no answer yet.
 *  - A device not heard for [GAP_MS] starts again from nothing, so two meetings
 *    an hour apart are never read as one shape.
 */
class RssiTracker(
    private val minRangeDb: Int = 8,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private class Slot(val startMs: Long, var sum: Int, var count: Int)

    private val slots: MutableMap<String, ArrayDeque<Slot>> = mutableMapOf()
    private var lastPruneMs = 0L

    @Synchronized
    fun update(key: String, rssi: Int) {
        val now = nowMs()
        if (now - lastPruneMs > PRUNE_EVERY_MS) prune(now)
        val q = slots.getOrPut(key) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && now - last.startMs > GAP_MS) q.clear()
        val open = q.lastOrNull()
        if (open != null && now - open.startMs < SLOT_MS) {
            open.sum += rssi
            open.count++
        } else {
            q.addLast(Slot(now, rssi, 1))
            while (q.size > MAX_SLOTS) q.removeFirst()
        }
    }

    @Synchronized
    fun isStationary(key: String): Boolean {
        val q = slots[key] ?: return false
        val n = q.size
        if (n < MIN_SLOTS) return false
        val third = n / 3
        val first = part(q, 0, third) ?: return false
        val middle = part(q, third, n - third) ?: return false
        val last = part(q, n - third, n) ?: return false
        return middle - first >= minRangeDb && middle - last >= minRangeDb
    }

    /** Mean of the slot means in [from, to): each second weighs the same however
     *  many adverts arrived in it. Null when there are too few samples to trust. */
    private fun part(q: ArrayDeque<Slot>, from: Int, to: Int): Double? {
        var total = 0.0
        var samples = 0
        for (i in from until to) {
            val s = q[i]
            total += s.sum.toDouble() / s.count
            samples += s.count
        }
        return if (samples < MIN_SAMPLES_PER_THIRD) null else total / (to - from)
    }

    /** Drop devices that have gone quiet; their next sample would start over anyway. */
    private fun prune(now: Long) {
        lastPruneMs = now
        slots.values.removeAll { q -> q.isEmpty() || now - q.last().startMs > GAP_MS }
    }

    @Synchronized
    fun clear() = slots.clear()

    companion object {
        private const val SLOT_MS = 1_000L
        private const val MAX_SLOTS = 60
        private const val MIN_SLOTS = 6
        private const val MIN_SAMPLES_PER_THIRD = 3
        private const val GAP_MS = 90_000L
        private const val PRUNE_EVERY_MS = 60_000L
    }
}
