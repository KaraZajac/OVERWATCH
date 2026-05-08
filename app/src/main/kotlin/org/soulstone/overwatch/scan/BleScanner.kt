package org.soulstone.overwatch.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.soulstone.overwatch.data.targets.BleOuis
import org.soulstone.overwatch.data.targets.MicTargets
import org.soulstone.overwatch.data.targets.Patterns
import org.soulstone.overwatch.data.targets.RavenUuids
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.RssiTracker
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * BLE scanner — ported from AxonCadabra (scan side only; no advertise/fuzz).
 *
 * Strategy:
 *  - Run a low-latency unfiltered scan (cheap on modern Android).
 *  - In the callback, first reject anything that doesn't look like a candidate
 *    (no OUI hit, no name hit, no Raven UUID, no XUNTONG mfg) — saves CPU.
 *  - For candidates, build a [ConfidenceEngine.BleObservation] and score it.
 *  - Push to [DetectionStore] if score crosses ALARM_THRESHOLD (40).
 *
 * Permissions: caller must hold BLUETOOTH_SCAN (API 31+) or BLUETOOTH+LOCATION (legacy).
 */
class BleScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val rssi: RssiTracker = RssiTracker(),
    /** When true, also evaluate each scan against MicTargets and submit MIC events. */
    private val micEnabled: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "BleScanner"
        private const val ALARM_THRESHOLD = 40
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var leScanner: BluetoothLeScanner? = null
    private var running = false

    private val scanSettings: ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    /** True if the device supports BLE and the adapter is on. */
    val isAvailable: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasScanPermission()) {
            Log.w(TAG, "BLE scan permission missing")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission missing")
            return false
        }
        val adapter = bluetoothAdapter ?: run {
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "BLE not supported")
            return false
        }
        if (!adapter.isEnabled) {
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Bluetooth disabled")
            return false
        }
        leScanner = adapter.bluetoothLeScanner ?: run {
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "BLE scanner unavailable")
            return false
        }
        try {
            leScanner?.startScan(null, scanSettings, scanCallback)
            running = true
            SourceHealth.record(DetectionSource.BLE, ok = true)
            Log.i(TAG, "BLE scan started")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan", e)
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission revoked")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        try {
            leScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan", e)
        }
        running = false
        Log.i(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            running = false
            SourceHealth.record(
                DetectionSource.BLE,
                ok = false,
                message = "BLE scan failed (code $errorCode)"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val device = result.device
        val mac = device.address ?: return
        val name = try { device.name } catch (e: SecurityException) { null }
        val record = result.scanRecord

        val advertisedUuids = record?.serviceUuids?.map { it.uuid }
        val mfgSpecific = record?.manufacturerSpecificData
        // Iterate ALL manufacturer-data entries; some devices advertise multiple
        // and XUNTONG might not be the first one. Prefer the XUNTONG match if
        // present, otherwise fall back to the first entry so we still surface
        // *some* mfg signal in the observation.
        var companyId: Int? = null
        var payload: ByteArray? = null
        if (mfgSpecific != null && mfgSpecific.size() > 0) {
            for (i in 0 until mfgSpecific.size()) {
                val cid = mfgSpecific.keyAt(i)
                val data = mfgSpecific.valueAt(i)
                if (cid == org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID) {
                    companyId = cid
                    payload = data
                    break
                }
                if (companyId == null) { companyId = cid; payload = data }
            }
        }

        // Cheap pre-filter — drop devices that have zero target signals.
        val isSurveillance = BleOuis.matches(mac) ||
            Patterns.bleNameMatch(name) ||
            Patterns.isPenguinNumeric(name) ||
            RavenUuids.countMatches(advertisedUuids) > 0 ||
            companyId == org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID
        val isMic = micEnabled() &&
            MicTargets.couldBeMicBle(mac, name, advertisedUuids, companyId)
        if (!isSurveillance && !isMic) return

        rssi.update(mac, result.rssi)
        val stationary = rssi.isStationary(mac)

        if (isSurveillance) {
            val obs = ConfidenceEngine.BleObservation(
                mac = mac,
                rssi = result.rssi,
                deviceName = name,
                advertisedUuids = advertisedUuids,
                manufacturerCompanyId = companyId,
                manufacturerPayload = payload,
                isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreBle(obs)
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.BLE,
                        key = mac,
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = result.rssi
                    )
                )
            }
        }
        if (isMic) {
            val obs = ConfidenceEngine.MicBleObservation(
                mac = mac,
                rssi = result.rssi,
                deviceName = name,
                advertisedUuids = advertisedUuids,
                manufacturerCompanyId = companyId,
                isStationary = stationary
            )
            val scored = ConfidenceEngine.scoreMicBle(obs)
            if (scored.score >= ALARM_THRESHOLD) {
                store.submit(
                    DetectionEvent(
                        source = DetectionSource.MIC,
                        // Disambiguate from any BLE event on the same MAC so the
                        // store's (source, key) dedup doesn't collide.
                        key = "mic:$mac",
                        label = scored.label,
                        score = scored.score,
                        matchedMethods = scored.methods,
                        rssi = result.rssi
                    )
                )
            }
        }
    }
}
