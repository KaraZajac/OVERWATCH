package org.soulstone.overwatch.fusion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-source upstream-health registry.
 *
 * Network sources (DEFLOCK, WAZE) record OK/FAILED so the UI can distinguish
 * "scanned, found nothing" from "couldn't reach the data source." BLE/WIFI
 * are radio-only and don't currently report; they default to UNKNOWN, which
 * the UI treats the same as OK.
 */
object SourceHealth {

    enum class Status { UNKNOWN, OK, FAILED }

    data class Health(
        val status: Status = Status.UNKNOWN,
        val lastFetchMs: Long = 0L,
        /** Short reason shown in the UI when status = FAILED. */
        val message: String? = null
    )

    private val flows: Map<DetectionSource, MutableStateFlow<Health>> =
        DetectionSource.values().associateWith { MutableStateFlow(Health()) }

    // One read-only view per source, so the UI gets the same instance on every
    // recomposition (a fresh wrapper would restart collectAsState each time).
    private val views: Map<DetectionSource, StateFlow<Health>> =
        flows.mapValues { it.value.asStateFlow() }

    val ble: StateFlow<Health> = flowFor(DetectionSource.BLE)
    val wifi: StateFlow<Health> = flowFor(DetectionSource.WIFI)
    val deflock: StateFlow<Health> = flowFor(DetectionSource.DEFLOCK)
    val waze: StateFlow<Health> = flowFor(DetectionSource.WAZE)
    val aircraft: StateFlow<Health> = flowFor(DetectionSource.AIRCRAFT)
    val mic: StateFlow<Health> = flowFor(DetectionSource.MIC)

    fun flowFor(source: DetectionSource): StateFlow<Health> = views.getValue(source)

    fun record(source: DetectionSource, ok: Boolean, message: String? = null) {
        flows.getValue(source).value = Health(
            status = if (ok) Status.OK else Status.FAILED,
            lastFetchMs = System.currentTimeMillis(),
            message = message
        )
    }

    fun reset() {
        flows.values.forEach { it.value = Health() }
    }
}
