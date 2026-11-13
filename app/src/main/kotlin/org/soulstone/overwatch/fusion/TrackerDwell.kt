package org.soulstone.overwatch.fusion

/**
 * How long each tracker has stayed in range without a break. A tag heard again
 * within [gapMs] continues its stay; a longer silence starts a new one. Memory
 * only, like the rest of the store. A tag that rotates its address starts over
 * at each rotation, which can only delay an alert, never invent one.
 *
 * Silence only counts while the scanner could have heard the tag. The screen-off
 * scan carries no tracker filter, so between [pause] and [resume] nothing is
 * heard whether the tag is there or not, and that time is left out of the gap. A
 * tag heard before the phone went into a pocket and again when it came out has,
 * as far as anyone can tell, been there throughout, and its stay is counted
 * that way. Without this a phone that spent more than [gapMs] in a pocket
 * started every count again, and the ten-minute alert never came.
 */
class TrackerDwell(
    private val gapMs: Long = 2 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    /** When each stay began, on the real clock. */
    private val first = HashMap<String, Long>()
    /** Each tag's last sighting, on the listening clock: time with the deaf spells taken out. */
    private val last = HashMap<String, Long>()
    private var deafSinceMs: Long? = null
    private var deafTotalMs = 0L

    /** The scanner cannot hear trackers from now. */
    @Synchronized
    fun pause() {
        if (deafSinceMs == null) deafSinceMs = nowMs()
    }

    /** The scanner can hear trackers again. */
    @Synchronized
    fun resume() {
        deafSinceMs?.let { deafTotalMs += nowMs() - it }
        deafSinceMs = null
    }

    /** Record a sighting of [key] and return how long it has been in range, in ms. */
    @Synchronized
    fun seen(key: String): Long {
        val now = nowMs()
        val heard = (deafSinceMs ?: now) - deafTotalMs
        val prev = last[key]
        if (prev == null || heard - prev > gapMs) first[key] = now
        last[key] = heard
        if (last.size > 512) {
            val stale = last.filterValues { heard - it > gapMs }.keys
            stale.forEach { last.remove(it); first.remove(it) }
        }
        return now - (first[key] ?: now)
    }

    @Synchronized
    fun clear() {
        first.clear()
        last.clear()
        deafSinceMs = null
        deafTotalMs = 0L
    }
}
