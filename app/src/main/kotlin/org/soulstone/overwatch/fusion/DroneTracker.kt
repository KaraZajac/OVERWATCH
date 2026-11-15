package org.soulstone.overwatch.fusion

import org.soulstone.overwatch.data.targets.RemoteId

/**
 * Collects Remote ID messages per transmitter. A legacy Bluetooth advert carries one
 * message type at a time, so the serial and the position arrive in different adverts
 * from the same address; this joins them. Memory only, and forgotten a minute after
 * a transmitter goes quiet.
 */
class DroneTracker(private val nowMs: () -> Long = System::currentTimeMillis) {

    private class Entry(val drone: RemoteId.Drone, val firstSeen: Long, var lastSeen: Long)

    /** A transmitter's drone, and how long it has been heard for. */
    class Heard(val drone: RemoteId.Drone, val forMs: Long)

    private val byKey = HashMap<String, Entry>()

    /** Fold [messages] into the drone heard as [key] (a MAC or BSSID) and return it. */
    @Synchronized
    fun update(key: String, messages: List<ByteArray>): Heard {
        val now = nowMs()
        byKey.entries.removeAll { now - it.value.lastSeen > FORGET_MS }
        val e = byKey.getOrPut(key) { Entry(RemoteId.Drone(), now, now) }
        e.lastSeen = now
        for (m in messages) RemoteId.apply(e.drone, m)
        return Heard(e.drone, now - e.firstSeen)
    }

    @Synchronized
    fun clear() = byKey.clear()

    private companion object {
        const val FORGET_MS = 60_000L
    }
}
