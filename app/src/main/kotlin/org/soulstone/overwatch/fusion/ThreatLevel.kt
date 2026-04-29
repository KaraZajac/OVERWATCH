package org.soulstone.overwatch.fusion

/**
 * 4-tier threat classification. Maps directly to the green/yellow/orange/red UI circle.
 * Thresholds ported from flock-detection's CONFIDENCE_* constants.
 */
enum class ThreatLevel(val minScore: Int) {
    GREEN(0),     // < 40 — nothing credible
    YELLOW(40),   // 40-69 — single weak indicator
    ORANGE(70),   // 70-84 — high confidence
    RED(85);      // 85+ — certain

    companion object {
        fun fromScore(score: Int): ThreatLevel = when {
            score >= RED.minScore -> RED
            score >= ORANGE.minScore -> ORANGE
            score >= YELLOW.minScore -> YELLOW
            else -> GREEN
        }
    }
}

/** Logical signal channel — used in the drill-down UI. */
enum class DetectionSource { BLE, WIFI, DEFLOCK, CITIZEN }
