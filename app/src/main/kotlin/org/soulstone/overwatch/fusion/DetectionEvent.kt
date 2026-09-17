package org.soulstone.overwatch.fusion

/**
 * One observation from one source at one moment.
 *
 * @param source which scanner produced this
 * @param key stable per-device identifier (MAC for BLE/WiFi, OSM id for DeFlock, alert id for Waze)
 * @param label short human-readable description shown in the drill-down ("Axon body cam", "FS-1A2B")
 * @param score 0-100 confidence assigned by the engine
 * @param matchedMethods space-separated short tags for what triggered ("axon_oui mfg_0x09C8 tn_serial")
 * @param rssi signal strength if applicable (BLE/WiFi); null for map/feed sources
 * @param lat / lon real-world coordinates for events that have them (DEFLOCK, WAZE); null for radio-only sources
 * @param distanceMeters how far away it was when observed; null for radio-only sources, which
 *        have no position. The UI filters what it *shows* on this; it never filters what is
 *        *scored*, so the threat tier cannot move when the view range changes.
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
    val distanceMeters: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val level: ThreatLevel get() = ThreatLevel.fromScore(score)
    val hasGeo: Boolean get() = lat != null && lon != null

    /**
     * Whether this belongs on screen at the given view range.
     *
     * Anything at or above YELLOW is shown **regardless of range**: the range
     * is a view control, and a setting that could hide a live alert — or worse,
     * quietly turn the circle green — would be a trap. Radio sources have no
     * position and are always shown.
     */
    fun visibleAt(viewRangeMeters: Float): Boolean =
        score >= ThreatLevel.YELLOW.minScore ||
            distanceMeters == null ||
            distanceMeters <= viewRangeMeters
}
