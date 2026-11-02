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
import org.soulstone.overwatch.data.targets.AxonSignatures
import org.soulstone.overwatch.data.targets.BleOuis
import org.soulstone.overwatch.data.targets.HackerTargets
import org.soulstone.overwatch.data.targets.MicTargets
import org.soulstone.overwatch.data.targets.Patterns
import org.soulstone.overwatch.data.targets.RavenUuids
import org.soulstone.overwatch.data.targets.RemoteId
import org.soulstone.overwatch.data.targets.TrackerTargets
import org.soulstone.overwatch.fusion.BleSpamDetector
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.DroneTracker
import org.soulstone.overwatch.fusion.RssiTracker
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.TrackerDwell

/**
 * BLE scanner — ported from AxonCadabra (scan side only; no advertise/fuzz).
 *
 * Strategy:
 *  - Run a low-latency unfiltered scan (cheap on modern Android).
 *  - In the callback, first reject anything that doesn't look like a candidate
 *    (no OUI hit, no name hit, no Raven UUID, no XUNTONG mfg, no Axon tag) — saves CPU.
 *  - The same advert is also checked, when those sources are on, for COMMERCIAL
 *    gear, HACKER gear (Flipper Zero, nyanBOX, AxonCadabra, pop-up spam), TRACKER
 *    tags away from their owner, and drone Remote ID (filed under AIRCRAFT).
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
 *    survives is what can be named precisely: Axon's body-cam tag, service UUID
 *    and company id, drone Remote ID, Raven's service UUIDs and the XUNTONG
 *    manufacturer id, plus the mic-target UUIDs and company ids when that source
 *    is on. HACKER and TRACKER matching waits for the screen.
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
    private val micEnabled: () -> Boolean = { false },
    /** When true, also look for attack and pentest gear (HACKER events). */
    private val hackerEnabled: () -> Boolean = { false },
    /** When true, also look for item trackers away from their owner (TRACKER events). */
    private val trackerEnabled: () -> Boolean = { false },
    /** When true, also decode drone Remote ID (AIRCRAFT events). */
    private val dronesEnabled: () -> Boolean = { false }
) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    private var leScanner: BluetoothLeScanner? = null
    private var running = false
    private var screenReceiverRegistered = false
    /** True while the current scan is the filtered, screen-off variant. */
    @Volatile private var filteredMode = false

    private val spam = BleSpamDetector()
    private val drones = DroneTracker()
    private val trackerDwell = TrackerDwell()

    /**
     * Filters that keep results flowing with the screen off. Only signatures a
     * ScanFilter can actually express — see the class KDoc for what this
     * necessarily leaves out.
     */
    private fun screenOffFilters(): List<ScanFilter> {
        val out = ArrayList<ScanFilter>()
        // Ordered by what most deserves a pocket alert, because the list is trimmed
        // from the end: Axon body cams and police drones first, then Flock's Penguin
        // pack and Raven, then consumer gear.
        // An empty data + empty mask matches any payload for that UUID / company.
        out.add(
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(AxonSignatures.BWC_TAG_UUID), ByteArray(0)).build()
        )
        out.add(
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(AxonSignatures.SVC_FE6B), ByteArray(0)).build()
        )
        out.add(
            ScanFilter.Builder()
                .setManufacturerData(AxonSignatures.TASER_COMPANY_ID, ByteArray(0), ByteArray(0)).build()
        )
        if (dronesEnabled()) {
            out.add(
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(RemoteId.BLE_SERVICE_UUID), byteArrayOf(RemoteId.APP_CODE.toByte()))
                    .build()
            )
        }
        out.add(
            ScanFilter.Builder()
                .setManufacturerData(
                    org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID,
                    ByteArray(0), ByteArray(0)
                ).build()
        )
        // Raven: its five own UUIDs, plus 0x1819 for FW 1.1.x. A filter only decides
        // delivery and FW 1.1.x sends 0x180A + 0x1809 + 0x1819 together, so one of
        // the three wakes the scan for the full set the scorer requires.
        for (uuid in RavenUuids.SPECIFIC) {
            out.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
        }
        out.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(RavenUuids.FW_1_1_FILTER)).build())
        if (micEnabled()) {
            // Precise service UUIDs first (Ray-Ban Meta, HeyCyan glasses, Echo AVS):
            // they are exact signatures, so they outrank the broad company ids that
            // follow and are what should survive if the list is trimmed.
            for (uuid in MicTargets.SERVICE_UUIDS) {
                out.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build())
            }
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
                // Addresses rotated while the screen was off all look new now.
                spam.restartWarmUp()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException switching scan mode", e)
        }
    }

    /**
     * Low-latency, every match. Where the controller can, the scan also reports
     * extended advertisements on every PHY, Coded (long range) included: drones'
     * Bluetooth 5 Remote ID is sent that way, as OpenDroneID's receiver scans for it.
     * Legacy advertisements are still delivered alongside.
     */
    private val scanSettings: ScanSettings by lazy {
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        val adapter = bluetoothAdapter
        if (adapter != null && adapter.isLeExtendedAdvertisingSupported) {
            b.setLegacy(false)
            if (adapter.isLeCodedPhySupported) b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        b.build()
    }

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
            spam.restartWarmUp()
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
        drones.clear()
        trackerDwell.clear()
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

        val serviceUuidList: List<java.util.UUID>? = record?.serviceUuids?.map { it.uuid }
        val serviceData: Map<java.util.UUID, ByteArray>? =
            record?.serviceData?.entries?.associate { it.key.uuid to it.value }
        // A service UUID can ride in the advertised service list OR as a
        // service-data key — HeyCyan-SDK glasses (Nilox etc.) show up both
        // ways (per Nearby Glasses' scanner), so merge the two before matching.
        val advertisedUuids: List<java.util.UUID>? =
            (serviceUuidList.orEmpty() + serviceData?.keys.orEmpty()).distinct().ifEmpty { null }
        val mfgSpecific = record?.manufacturerSpecificData
        val manufacturerData: Map<Int, ByteArray>? = mfgSpecific?.let { arr ->
            if (arr.size() == 0) null else (0 until arr.size()).associate { arr.keyAt(it) to arr.valueAt(it) }
        }
        // Only one company id rides in the observation. Rank them: XUNTONG (the
        // Penguin pack) first, then any COMMERCIAL company id, then whichever came
        // first, so we still surface *some* mfg signal. Without the middle rank a
        // glasses or Echo id behind a lower-numbered entry was never seen
        // (SparseArray iterates in key order).
        var companyId: Int? = null
        var payload: ByteArray? = null
        if (mfgSpecific != null && mfgSpecific.size() > 0) {
            var bestRank = -1
            for (i in 0 until mfgSpecific.size()) {
                val cid = mfgSpecific.keyAt(i)
                val rank = when (cid) {
                    org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID -> 2
                    in MicTargets.COMPANY_IDS -> 1
                    else -> 0
                }
                if (rank > bestRank) {
                    bestRank = rank
                    companyId = cid
                    payload = mfgSpecific.valueAt(i)
                }
                if (rank == 2) break
            }
        }
        val raw = record?.bytes

        // AxonCadabra replays a captured Axon trigger, from 00:25:DF on the Flipper
        // ports. That is a spoof to report, never an Axon device to alarm on.
        val axonSpoof = HackerTargets.isAxonCadabra(serviceData)

        // Cheap pre-filter — drop devices that have zero target signals.
        val isSurveillance = !axonSpoof && (
            BleOuis.matches(mac) ||
                Patterns.bleNameMatch(name) ||
                Patterns.isPenguinNumeric(name) ||
                RavenUuids.countMatches(advertisedUuids) > 0 ||
                companyId == org.soulstone.overwatch.data.targets.Manufacturers.XUNTONG_COMPANY_ID ||
                AxonSignatures.match(raw, name, serviceUuidList, serviceData, manufacturerData) != null
            )
        val isMic = micEnabled() &&
            MicTargets.couldBeMicBle(mac, name, advertisedUuids, companyId)

        var tracked = false
        fun stationary(): Boolean {
            if (!tracked) { rssi.update(mac, result.rssi); tracked = true }
            return rssi.isStationary(mac)
        }

        if (isSurveillance) {
            val obs = ConfidenceEngine.BleObservation(
                mac = mac,
                rssi = result.rssi,
                deviceName = name,
                advertisedUuids = advertisedUuids,
                manufacturerCompanyId = companyId,
                manufacturerPayload = payload,
                isStationary = stationary(),
                serviceUuidList = serviceUuidList,
                serviceData = serviceData,
                manufacturerData = manufacturerData,
                rawBytes = raw
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
                isStationary = stationary()
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
        if (hackerEnabled()) {
            checkHacker(mac, name, result.rssi, advertisedUuids, serviceData, manufacturerData, axonSpoof)
        }
        if (trackerEnabled()) {
            checkTracker(mac, result.rssi, serviceUuidList, serviceData, manufacturerData)
        }
        if (dronesEnabled()) {
            checkDrone(mac, result.rssi, serviceData)
        }
    }

    /** Flipper Zero, nyanBOX, AxonCadabra and BLE pop-up spam. */
    private fun checkHacker(
        mac: String,
        name: String?,
        rssi: Int,
        uuids: List<java.util.UUID>?,
        serviceData: Map<java.util.UUID, ByteArray>?,
        manufacturerData: Map<Int, ByteArray>?,
        axonSpoof: Boolean
    ) {
        val hit: Triple<String, String, Int>? = when {
            axonSpoof -> Triple(
                "AxonCadabra — spoofed Axon Signal trigger ($mac)", "axoncadabra",
                ConfidenceEngine.scoreHacker(ConfidenceEngine.W_HACK_AXONCADABRA, 0, rssi)
            )
            HackerTargets.isFlipperHid(mac, name, uuids) -> Triple(
                "Flipper Zero in BLE remote / BadUSB mode — $name ($mac)", "flipper_hid",
                ConfidenceEngine.scoreHacker(ConfidenceEngine.W_HACK_FLIPPER_HID, 0, rssi)
            )
            HackerTargets.isFlipperUuid(uuids) -> {
                var extra = 0
                val methods = StringBuilder("flipper_uuid")
                if (HackerTargets.isFlipperName(name)) {
                    extra += ConfidenceEngine.B_HACK_FLIPPER_NAME; methods.append(" flipper_name")
                }
                if (HackerTargets.isFlipperAddress(mac)) {
                    extra += ConfidenceEngine.B_HACK_FLIPPER_ADDR; methods.append(" flipper_addr")
                }
                val who = if (!name.isNullOrBlank()) " — $name" else ""
                Triple(
                    "Flipper Zero$who ($mac)", methods.toString(),
                    ConfidenceEngine.scoreHacker(ConfidenceEngine.W_HACK_FLIPPER, extra, rssi)
                )
            }
            HackerTargets.isNyanBox(name, uuids) -> Triple(
                "nyanBOX${if (!name.isNullOrBlank()) " — $name" else ""} ($mac)", "nyanbox",
                ConfidenceEngine.scoreHacker(ConfidenceEngine.W_HACK_NYANBOX, 0, rssi)
            )
            else -> null
        }
        if (hit != null) {
            submitHacker("hack:$mac", hit.first, hit.second, hit.third, rssi)
        }
        val apple = manufacturerData?.get(TrackerTargets.APPLE_COMPANY_ID)
        spam.observe(mac, apple, manufacturerData, serviceData)?.let { f ->
            submitHacker(f.key, f.label, f.method, ConfidenceEngine.W_HACK_BLE_SPAM, null)
        }
    }

    private fun submitHacker(key: String, label: String, methods: String, score: Int, rssi: Int?) {
        if (score < ALARM_THRESHOLD) return
        store.submit(
            DetectionEvent(
                source = DetectionSource.HACKER,
                key = key,
                label = label,
                score = score,
                matchedMethods = methods,
                rssi = rssi
            )
        )
    }

    /** Item trackers that say they are away from their owner, or that stay. */
    private fun checkTracker(
        mac: String,
        rssi: Int,
        serviceUuidList: List<java.util.UUID>?,
        serviceData: Map<java.util.UUID, ByteArray>?,
        manufacturerData: Map<Int, ByteArray>?
    ) {
        val m = TrackerTargets.classify(mac, serviceUuidList, serviceData, manufacturerData) ?: return
        val dwellMs = trackerDwell.seen(mac)
        val scored = ConfidenceEngine.scoreTracker(m.separated, dwellMs)
        if (scored.score < ALARM_THRESHOLD) return
        val minutes = dwellMs / 60_000L
        val state = when {
            m.separated == true && minutes >= 10 -> "away from its owner, near you $minutes min"
            m.separated == true -> "away from its owner"
            else -> "near you $minutes min"
        }
        store.submit(
            DetectionEvent(
                source = DetectionSource.TRACKER,
                key = "trk:$mac",
                label = "${m.kind.label} $state ($mac)",
                score = scored.score,
                matchedMethods = scored.methods,
                rssi = rssi
            )
        )
    }

    /** Drone Remote ID over Bluetooth (legacy and long-range). */
    private fun checkDrone(mac: String, rssi: Int, serviceData: Map<java.util.UUID, ByteArray>?) {
        val msgs = RemoteId.messagesFromBle(serviceData?.get(RemoteId.BLE_SERVICE_UUID))
        if (msgs.isEmpty()) return
        val drone = drones.update(mac, msgs)
        submitDrone(store, drone, "ble:$mac", rssi)
    }

    companion object {
        private const val TAG = "BleScanner"
        private const val ALARM_THRESHOLD = 40
        /** Conservative ceiling on hardware scan-filter slots. */
        private const val MAX_SCAN_FILTERS = 16

        /**
         * One drone, from whichever radio heard it. Keyed by its serial when it has
         * sent one, so the same drone heard over Bluetooth and Wi-Fi is one row.
         * The position is the drone's own broadcast; nothing about it is logged.
         */
        fun submitDrone(store: DetectionStore, drone: RemoteId.Drone, transmitter: String, rssi: Int?) {
            val c = RemoteId.classify(drone.serial)
            val score = ConfidenceEngine.scoreDrone(c.tier)
            val id = drone.id
            val alt = drone.altitudeM?.let { " · ${it.toInt()} m up" } ?: ""
            val label = if (id != null) "${c.name} ($id)$alt" else "${c.name} (Remote ID)$alt"
            store.submit(
                DetectionEvent(
                    source = DetectionSource.AIRCRAFT,
                    key = "rid:${id ?: transmitter}",
                    label = label,
                    score = score,
                    matchedMethods = "remote_id ${c.tier.name.lowercase()}",
                    rssi = rssi,
                    lat = drone.latitude,
                    lon = drone.longitude
                )
            )
        }
    }
}
