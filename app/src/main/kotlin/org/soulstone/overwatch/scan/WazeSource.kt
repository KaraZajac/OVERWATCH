package org.soulstone.overwatch.scan

/**
 * A backend the WAZE detection source can read live police reports from.
 *
 * Two exist, and they are a genuine trade-off rather than one superseding the
 * other:
 *
 * - [WazeClient] reads OpenWeb Ninja's hosted Waze feed with the user's own API
 *   key. Costs money per request, lags live Waze by ~20 min, and tells Waze
 *   nothing about the user — the request comes from OpenWeb Ninja's servers.
 * - [org.soulstone.overwatch.scan.wazert.WazeRtClient] speaks the Waze app's own
 *   protocol directly. Free, live, no key — but it registers an anonymous Waze
 *   account and sends a (jittered) position with every poll, which is a real
 *   disclosure to a Google service. Off by default; the user opts in.
 *
 * [WazeScanner] is written against this interface so neither backend leaks into
 * the scoring or UI layers, and so a backend can set its own poll cadence: the
 * metered one polls slowly because requests cost money, the direct one polls
 * fast enough to keep its session alive.
 */
interface WazeSource {

    /** Short backend name, for status rows and logs. */
    val backendName: String

    /** False when the backend needs setup the user has not done yet. */
    val isConfigured: Boolean

    /** Shown to the user when [isConfigured] is false. */
    val unconfiguredReason: String

    /** How often [WazeScanner] should poll this backend. */
    val pollIntervalMs: Long

    /** Police reports within [radiusMeters] of the point, or a failure reason. */
    suspend fun fetchPoliceNear(lat: Double, lon: Double, radiusMeters: Float): FetchResult

    /**
     * One police report, normalised across backends.
     *
     * [confidence] (0-5) and [reliability] (0-10) are Waze's own crowd-trust
     * numbers. A backend that cannot supply one reports 0 rather than inventing
     * a value — ConfidenceEngine treats them as bonuses over a distance floor,
     * so a missing signal costs a few points and never fabricates a detection.
     */
    data class Alert(
        val uuid: String,
        val subtype: String?,
        val lat: Double,
        val lon: Double,
        val pubMillis: Long,
        val confidence: Int,
        val reliability: Int
    )

    /** Distinguishes "no police reports in this area" from "couldn't reach the feed." */
    sealed class FetchResult {
        data class Success(val alerts: List<Alert>) : FetchResult()
        data class Failed(val reason: String) : FetchResult()
    }
}
