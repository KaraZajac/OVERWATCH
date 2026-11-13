package org.soulstone.overwatch.fusion

/**
 * Decides when a rise in the alert level is worth a vibration.
 *
 * A rise buzzes, unless the detection behind it already buzzed for this level or
 * a higher one within [quietMs]. One device whose score sits on a boundary
 * crosses it over and over (its signal a few dB either side of the strong-signal
 * line, a camera a few metres either side of a distance band) and each crossing
 * is the same news. A different detection reaching the level, or the same one
 * going higher than it has, is new and always buzzes.
 */
class AlertGate(
    private val quietMs: Long = 60_000L,
    private val nowMs: () -> Long
) {
    private var lastTier = ThreatLevel.GREEN
    private var alertedTier = ThreatLevel.GREEN
    private var alertedId: String? = null
    private var alertedAtMs = 0L

    /**
     * Tell the gate the level now. Returns true when the phone should vibrate.
     *
     * @param top the detection that sets the level, the highest-scored one.
     */
    @Synchronized
    fun onLevel(tier: ThreatLevel, top: DetectionEvent?): Boolean {
        val rose = tier.ordinal > lastTier.ordinal
        lastTier = tier
        if (!rose) return false
        val now = nowMs()
        val id = top?.let { "${it.source.name}|${it.key}" }
        val repeat = id != null && id == alertedId &&
            tier.ordinal <= alertedTier.ordinal && now - alertedAtMs < quietMs
        if (repeat) return false
        alertedTier = tier
        alertedId = id
        alertedAtMs = now
        return true
    }

    @Synchronized
    fun reset() {
        lastTier = ThreatLevel.GREEN
        alertedTier = ThreatLevel.GREEN
        alertedId = null
        alertedAtMs = 0L
    }
}
