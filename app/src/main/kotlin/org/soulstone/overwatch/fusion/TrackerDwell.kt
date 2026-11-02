package org.soulstone.overwatch.fusion

/**
 * How long each tracker has stayed in range without a break. A tag heard again
 * within [gapMs] continues its stay; a longer silence starts a new one. Memory
 * only, like the rest of the store. A tag that rotates its address starts over
 * at each rotation, which can only delay an alert, never invent one.
 */
class TrackerDwell(
    private val gapMs: Long = 2 * 60 * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val first = HashMap<String, Long>()
    private val last = HashMap<String, Long>()

    /** Record a sighting of [key] and return how long it has been in range, in ms. */
    @Synchronized
    fun seen(key: String): Long {
        val now = nowMs()
        val prev = last[key]
        if (prev == null || now - prev > gapMs) first[key] = now
        last[key] = now
        if (last.size > 512) {
            val stale = last.filterValues { now - it > gapMs }.keys
            stale.forEach { last.remove(it); first.remove(it) }
        }
        return now - (first[key] ?: now)
    }

    @Synchronized
    fun clear() {
        first.clear()
        last.clear()
    }
}
