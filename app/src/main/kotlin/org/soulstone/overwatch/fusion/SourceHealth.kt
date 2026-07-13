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

    private val _ble = MutableStateFlow(Health())
    private val _wifi = MutableStateFlow(Health())
    private val _deflock = MutableStateFlow(Health())
    private val _citizen = MutableStateFlow(Health())
    private val _waze = MutableStateFlow(Health())
    private val _mic = MutableStateFlow(Health())

    val ble: StateFlow<Health> = _ble.asStateFlow()
    val wifi: StateFlow<Health> = _wifi.asStateFlow()
    val deflock: StateFlow<Health> = _deflock.asStateFlow()
    val citizen: StateFlow<Health> = _citizen.asStateFlow()
    val waze: StateFlow<Health> = _waze.asStateFlow()
    val mic: StateFlow<Health> = _mic.asStateFlow()

    fun flowFor(source: DetectionSource): StateFlow<Health> = when (source) {
        DetectionSource.BLE -> ble
        DetectionSource.WIFI -> wifi
        DetectionSource.DEFLOCK -> deflock
        DetectionSource.CITIZEN -> citizen
        DetectionSource.WAZE -> waze
        DetectionSource.MIC -> mic
    }

    fun record(source: DetectionSource, ok: Boolean, message: String? = null) {
        val target = when (source) {
            DetectionSource.BLE -> _ble
            DetectionSource.WIFI -> _wifi
            DetectionSource.DEFLOCK -> _deflock
            DetectionSource.CITIZEN -> _citizen
            DetectionSource.WAZE -> _waze
            DetectionSource.MIC -> _mic
        }
        target.value = Health(
            status = if (ok) Status.OK else Status.FAILED,
            lastFetchMs = System.currentTimeMillis(),
            message = message
        )
    }

    fun reset() {
        _ble.value = Health()
        _wifi.value = Health()
        _deflock.value = Health()
        _citizen.value = Health()
        _waze.value = Health()
        _mic.value = Health()
    }
}
