package org.soulstone.overwatch.fusion

/**
 * One observation from one source at one moment.
 *
 * @param source which scanner produced this
 * @param key stable per-device identifier (MAC for BLE/WiFi, OSM id for DeFlock, uuid for Citizen)
 * @param label short human-readable description shown in the drill-down ("Axon body cam", "FS-1A2B")
 * @param score 0-100 confidence assigned by the engine
 * @param matchedMethods space-separated short tags for what triggered ("axon_oui mfg_0x09C8 tn_serial")
 * @param rssi signal strength if applicable (BLE/WiFi); null for map/Citizen sources
 * @param lat / lon real-world coordinates for events that have them (DEFLOCK, CITIZEN); null for radio-only sources
 * @param timestampMs wall-clock millis when this event was produced
 */
data class DetectionEvent(
    val source: DetectionSource,
    val key: String,
    val label: String,
    val score: Int,
    val matchedMethods: String,
    val rssi: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val level: ThreatLevel get() = ThreatLevel.fromScore(score)
    val hasGeo: Boolean get() = lat != null && lon != null
}
