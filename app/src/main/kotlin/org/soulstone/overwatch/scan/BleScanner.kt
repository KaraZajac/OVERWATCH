package org.soulstone.overwatch.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
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
 * **Screen-off behaviour.** Since Android 8.1 the Bluetooth stack stops
 * delivering results for scans started with no [ScanFilter] once the screen
 * turns off, and a foreground service does not exempt it — this is a stack
 * rule, not a process-lifetime one. An unfiltered scan therefore goes silent
 * exactly when this app is most useful: in a pocket, screen locked.
 *
 * So the scanner swaps strategies on screen state:
 *  - **screen on** — unfiltered, full coverage (OUI prefixes, name substrings,
 *    service UUIDs, manufacturer data).
 *  - **screen off** — filtered, which keeps delivering. A [ScanFilter] can only
 *    express an exact address, an exact name, a service UUID or manufacturer
 *    data; there is no way to express an **OUI prefix**, so MAC-prefix and
 *    name-substring matching genuinely cannot run with the screen off. What
 *    survives is what can be named precisely: Raven's service UUIDs and the
 *    XUNTONG manufacturer id, plus the mic-target company ids when that source
 *    is on.
 *
 * The gap is real and deliberate rather than hidden — [SourceHealth] says so on
 * the BLE row while the screen is off. Flock ALPR cameras stay covered in that
 * window by the map source, which is unaffected.
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
        /** Conservative ceiling on hardware scan-filter slots. */
        private const val MAX_SCAN_FILTERS = 16
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var leScanner: BluetoothLeScanner? = null
    private var running = false
    private var screenReceiverRegistered = false
    /** True while the current scan is the filtered, screen-off variant. */
    @Volatile private var filteredMode = false

    /**
     * Filters that keep results flowing with the screen off. Only signatures a
     * ScanFilter can actually express — see the class KDoc for what this
     * necessarily leaves out.
     */
    private fun screenOffFilters(): List<ScanFilter> {
        val out = ArrayList<ScanFilter>()
        // Surveillance signatures first: if the list has to be trimmed below,
        // consumer gear is what should fall off the end, not a Raven detector.
        out.add(
            ScanFilter.Builder()
                .setManufacturerData(
                    org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID,
                    // Empty data + empty mask matches any payload for that id.
                    ByteArray(0), ByteArray(0)
                ).build()
        )
        for (uuid in RavenUuids.ALL) {
            out.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
        }
        if (micEnabled()) {
            for (id in MicTargets.COMPANY_IDS) {
                out.add(
                    ScanFilter.Builder()
                        .setManufacturerData(id, ByteArray(0), ByteArray(0)).build()
                )
            }
        }
        // Filter slots are a hardware resource and chipsets differ — a common
        // allocation is 16. Overflow is supposed to fall back to software
        // filtering, but a scan that silently returns nothing is the worst
        // failure this app can have, so cap rather than gamble.
        if (out.size > MAX_SCAN_FILTERS) {
            Log.w(TAG, "trimming ${out.size} filters to $MAX_SCAN_FILTERS")
            return out.subList(0, MAX_SCAN_FILTERS).toList()
        }
        return out
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> applyMode(filtered = true)
                Intent.ACTION_SCREEN_ON -> applyMode(filtered = false)
            }
        }
    }

    /** Restart the scan in the other mode. Screen transitions are rare, so this
     *  stays well clear of the stack's 5-starts-per-30s ceiling. */
    @SuppressLint("MissingPermission")
    private fun applyMode(filtered: Boolean) {
        if (!running || filteredMode == filtered) return
        val scanner = leScanner ?: return
        try {
            scanner.stopScan(scanCallback)
            val filters = if (filtered) screenOffFilters() else null
            scanner.startScan(filters, scanSettings, scanCallback)
            filteredMode = filtered
            if (filtered) {
                Log.i(TAG, "screen off — filtered scan (${filters?.size} filters)")
                SourceHealth.record(
                    DetectionSource.BLE, ok = true,
                    message = "Screen off — filtered scan; OUI/name matching resumes when the screen is on"
                )
            } else {
                Log.i(TAG, "screen on — unfiltered scan")
                SourceHealth.record(DetectionSource.BLE, ok = true)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException switching scan mode", e)
        }
    }

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
            // Start in whichever mode matches the screen right now: the service
            // can be started from a notification action with the screen already
            // off, and an unfiltered scan there would deliver nothing at all.
            val screenOn = try {
                (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.isInteractive != false
            } catch (e: Exception) { true }
            filteredMode = !screenOn
            leScanner?.startScan(
                if (filteredMode) screenOffFilters() else null,
                scanSettings,
                scanCallback
            )
            running = true
            registerScreenReceiver()
            SourceHealth.record(
                DetectionSource.BLE, ok = true,
                message = if (filteredMode)
                    "Screen off — filtered scan; OUI/name matching resumes when the screen is on"
                else null
            )
            Log.i(TAG, "BLE scan started (filtered=$filteredMode)")
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
        filteredMode = false
        unregisterScreenReceiver()
        Log.i(TAG, "BLE scan stopped")
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Screen on/off are protected system broadcasts, so this must be an
        // exported-style registration; ContextCompat's NOT_EXPORTED flag would
        // drop them on API 34+.
        androidx.core.content.ContextCompat.registerReceiver(
            context, screenReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try { context.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
        screenReceiverRegistered = false
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
