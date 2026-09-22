package org.soulstone.overwatch.scan.wazert

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.soulstone.overwatch.scan.WazeSource

/**
 * The WAZE source read straight from Waze, with no hosted middleman and no API
 * key: [WazeRtFetcher] speaks the protocol the Waze app itself speaks, over an
 * anonymous account it registers on first use.
 *
 * Why this exists: the public `waze.com/live-map/api/georss` endpoint every
 * scraper used is dead — it is fronted by Google's edge, which 403s automated
 * clients outright. The app protocol is a different host and a different path,
 * and it answers. The cost is that OVERWATCH is now a Waze client: it holds a
 * Waze account and sends a position (jittered up to 500 m by the protocol layer)
 * on every poll. That is why this backend is opt-in and the Settings copy says
 * so before the user turns it on.
 *
 * Poll cadence is 60 s, deliberately under the protocol's ~100 s session idle
 * timeout: a live session receives only deltas (~8 KB) while a re-login re-sends
 * the whole viewport (~200 KB), so polling faster here uses *less* data than
 * polling slowly. There is no per-request cost to weigh against it.
 */
class WazeRtClient(
    context: Context,
    region: String = DEFAULT_REGION
) : WazeSource {

    companion object {
        private const val TAG = "WazeRtClient"
        /** "na" covers North America; the fetcher maps it to the regional RT host. */
        const val DEFAULT_REGION = "na"
        private const val POLL_INTERVAL_MS = 60_000L
    }

    private val fetcher = WazeRtFetcher(context, region)

    override val backendName: String = "Waze direct"
    /** Nothing to configure — the account mints itself on first poll. */
    override val isConfigured: Boolean = true
    override val unconfiguredReason: String = ""
    override val pollIntervalMs: Long = POLL_INTERVAL_MS

    override suspend fun fetchPoliceNear(
        lat: Double,
        lon: Double,
        radiusMeters: Float
    ): WazeSource.FetchResult = withContext(Dispatchers.IO) {
        try {
            val alerts = fetcher.fetchPoliceNear(lat, lon, radiusMeters.toDouble())
            WazeSource.FetchResult.Success(
                alerts.map { a ->
                    WazeSource.Alert(
                        uuid = a.uuid,
                        subtype = a.subtype.ifBlank { null },
                        lat = a.lat,
                        lon = a.lon,
                        pubMillis = a.pubMillis,
                        // The RT fetch path carries no confidence/reliability — those
                        // live in the alert-details sub-tree the vendored proto omits —
                        // but it does carry the thumbs-up count, which is the same crowd
                        // corroboration expressed differently. Map it onto the 0-10
                        // reliability scale the engine already reads, and leave
                        // confidence at 0 rather than invent a number.
                        confidence = 0,
                        reliability = a.thumbsUp.coerceIn(0, 10)
                    )
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Waze RT fetch failed: ${e.message}")
            WazeSource.FetchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Drop the stored anonymous account; the next poll registers a fresh one. */
    fun forgetAccount() = fetcher.forgetAccount()

    /** True once an anonymous account has been minted and persisted. */
    fun hasAccount(): Boolean = fetcher.hasAccount()
}
