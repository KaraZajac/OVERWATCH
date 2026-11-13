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
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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
import org.soulstone.overwatch.fusion.DetectionKind
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.DroneTracker
import org.soulstone.overwatch.fusion.RssiTracker
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.TrackerDwell
import kotlin.math.roundToInt

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
 * The gap is real and deliberate. Nobody can read the BLE row with the screen
 * off, so it carries a note only when the reduced scan outlasts the screen
 * coming back on, which happens when [ScanStartBudget] holds the change. Flock
 * ALPR cameras stay covered in that window by the map source, which is
 * unaffected.
 *
 * **Staying up.** Once started, the scanner keeps a scan going until it is
 * stopped. Bluetooth switched off takes the scan with it, and the stack throws
 * when asked to stop or start one then, so the scanner follows the adapter's
 * state: it says so on the BLE row and starts again when Bluetooth is back. A
 * start that fails is retried, a little later each time. Every start, whatever
 * caused it, goes through [ScanStartBudget].
 *
 * **Starting over.** The stack does not leave a scan alone for ever. One that has
 * run for the stack's time limit (10 minutes from Android 14, 30 before) is set
 * aside if it has no filter: it becomes "opportunistic" and hears only while some
 * other app is scanning. From Android 14 a filtered one is turned down to low
 * power instead. The app is told neither, and a foreground service is not exempt.
 * A scan that is stopped and started gets its full time again, so [tendScan] does
 * that every [REFRESH_MS]. It also starts the full scan over when that has
 * delivered nothing for [QUIET_MS], which is what a start the stack refused, or a
 * scan it set aside sooner than expected, looks like from here. Read in AOSP,
 * 2026-11-13: ScanManager.regularScanTimeout in android-8.1.0_r1, 11.0.0_r1,
 * 13.0.0_r1, 14.0.0_r1 and main, and the 10 minutes also in 15.0.0_r1. The
 * limit itself was never provoked on a device.
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
    /** The service has asked for a scan and not stopped it. */
    private var wanted = false
    /** A scan is registered with the Bluetooth stack right now. */
    @Volatile private var scanning = false
    private var receiversRegistered = false
    /** True while the current scan is the filtered, screen-off variant. */
    @Volatile private var filteredMode = false
    /** Scan failures in a row; sets how long the next retry waits. */
    private var failures = 0
    /** Set once the stack has refused the long-range scan settings. */
    private var plainSettings = false
    /** When the running scan was started, and when it last delivered anything.
     *  On the clock that keeps counting while the phone sleeps. */
    private var startedAtMs = 0L
    private var lastResultAtMs = 0L

    // Everything below runs on the main thread: the service's calls, the
    // broadcasts, the scan callbacks and these delayed steps.
    private val handler = Handler(Looper.getMainLooper())
    /** A start that had to wait: a retry after a failure, or one held back by the
     *  budget. In the second case the screen may have changed back while it
     *  waited, and the scan that kept running is then already the right one. */
    private val launch = Runnable {
        if (!scanning || wrongMode() || stale()) launchScan() else inStep()
    }
    /** The screen has stayed on for a moment: switch to the full scan. */
    private val settle = Runnable {
        if (wanted && scanning) {
            if (wrongMode()) launchScan() else inStep()
        }
    }
    /** A retried scan has run a few seconds with no failure reported. */
    private val confirm = Runnable {
        if (wanted && scanning) { failures = 0; recordRunning() }
    }
    /** Looks in on the running scan every [TEND_MS]. */
    private val tend = Runnable { tendScan() }

    private val spam = BleSpamDetector()
    private val drones = DroneTracker()
    private val trackerDwell = TrackerDwell(nowMs = { SystemClock.elapsedRealtime() })

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

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                // The full scan delivers nothing from this moment, and the phone
                // may be asleep before a delayed step gets to run. So this switch
                // is made now, while the broadcast keeps the phone awake.
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(settle)
                    if (wanted && scanning && !filteredMode) launchScan()
                }
                // Back on, the filtered scan still delivers and the phone is
                // awake, so there is time to see that the screen stays on. Each
                // change of mode is a scan start and the stack rations those (see
                // ScanStartBudget): a screen that only lights up for a
                // notification costs no start at all this way.
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(settle)
                    handler.postDelayed(settle, MODE_SETTLE_MS)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> bluetoothWentOff()
                        BluetoothAdapter.STATE_ON -> { failures = 0; launchScan() }
                    }
                // Only registered before Android 12, where results depend on it.
                LocationManager.MODE_CHANGED_ACTION -> launchScan()
            }
        }
    }

    private fun screenOn(): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    } catch (e: Exception) { true }

    /** The scan that is running is the one for the other screen state. */
    private fun wrongMode(): Boolean = scanning && filteredMode == screenOn()

    /** The running scan should be started over: the stack's time limit is near,
     *  or it is the full scan, the screen is on, and nothing has come from it for
     *  [QUIET_MS]. See "Starting over" in the class comment. */
    private fun stale(): Boolean {
        if (!scanning) return false
        val now = SystemClock.elapsedRealtime()
        if (now - startedAtMs >= REFRESH_MS) return true
        return !filteredMode && screenOn() && now - maxOf(startedAtMs, lastResultAtMs) >= QUIET_MS
    }

    /** The scan that is running is the right one for the screen after all: a
     *  switch that was waiting for the budget is no longer needed. Put the BLE
     *  row and the tracker clock back to what is true. */
    private fun inStep() {
        if (filteredMode) trackerDwell.pause() else trackerDwell.resume()
        // With a failure still to live down, [confirm] or the first result says so.
        if (failures == 0) recordRunning()
    }

    /** Every [TEND_MS] while a scan runs: start it over when it is [stale], and
     *  when it is somehow in the wrong mode for the screen. The delay does not
     *  count while the phone sleeps, and neither does the stack's own timer for
     *  its limit (a delayed Handler message, like this one); the ages in [stale]
     *  do count that time, so this can only be early, never late. */
    private fun tendScan() {
        handler.removeCallbacks(tend)
        if (!wanted || !scanning) return
        handler.postDelayed(tend, TEND_MS)
        if (stale() || wrongMode()) launchScan()
    }

    /** Before Android 12 a Bluetooth scan returns nothing while system Location is
     *  off, and a scan started then stays empty after Location comes back. From
     *  Android 12 the scan permission is declared neverForLocation and does not care. */
    private fun locationNeededButOff(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * Low-latency, every match. Where the controller can, the scan also reports
     * extended advertisements on every PHY, Coded (long range) included: drones'
     * Bluetooth 5 Remote ID is sent that way, as OpenDroneID's receiver scans for it.
     * Legacy advertisements are still delivered alongside.
     *
     * Built per start, not once: what the adapter says it supports is only true
     * while Bluetooth is on.
     */
    private fun scanSettings(): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        val adapter = bluetoothAdapter
        if (!plainSettings && adapter != null && adapter.isLeExtendedAdvertisingSupported) {
            b.setLegacy(false)
            if (adapter.isLeCodedPhySupported) b.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
        return b.build()
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

    /**
     * Begin scanning, and keep a scan going until [stop]: through screen changes,
     * through Bluetooth being switched off and on, through a failed start.
     *
     * False only when a scan can never run here (no permission, no Bluetooth
     * radio). Bluetooth being off is not that: the BLE row says so and the scan
     * starts when it is turned on.
     */
    fun start(): Boolean {
        if (wanted) return true
        if (!hasScanPermission()) {
            Log.w(TAG, "BLE scan permission missing")
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission missing")
            return false
        }
        if (bluetoothAdapter == null) {
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "BLE not supported")
            return false
        }
        wanted = true
        failures = 0
        plainSettings = false
        spam.restartWarmUp()
        registerReceivers()
        launchScan()
        return true
    }

    /**
     * Start the scan in the mode the screen calls for, replacing the one that is
     * running. Every path that needs a scan ends here: Start, a screen change,
     * Bluetooth coming back, a retry after a failure, a scan grown [stale].
     *
     * The mode is whatever the screen is doing now, also at Start: the service can
     * be started with the screen already off, and an unfiltered scan there would
     * deliver nothing at all.
     */
    @SuppressLint("MissingPermission")
    private fun launchScan() {
        handler.removeCallbacks(launch)
        if (!wanted) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            bluetoothWentOff()
            return
        }
        if (locationNeededButOff()) {
            handler.removeCallbacks(confirm)
            stopQuietly()
            scanning = false
            trackerDwell.pause()
            SourceHealth.record(
                DetectionSource.BLE, ok = false,
                message = "Location is off — this Android version gives no Bluetooth results without it"
            )
            return
        }
        val wait = budget.waitMs()
        if (wait > 0) {
            // The scan that is running stays as it is until then.
            Log.i(TAG, "holding the scan start ${wait}ms to stay under the stack's limit")
            val secs = (wait + 999) / 1000
            if (!scanning) {
                SourceHealth.record(
                    DetectionSource.BLE, ok = false,
                    message = "Starting in ${secs}s — Android limits how often a scan can start"
                )
            } else if (wrongMode()) {
                // Until then the other mode's scan runs on.
                if (!filteredMode) {
                    // Screen off, full scan, which Android has silenced: nothing
                    // is heard, and the tracker clock must not count it as
                    // listening. Nobody can read the row.
                    trackerDwell.pause()
                } else if (failures == 0) {
                    // Screen on, reduced scan: the row can be read, and for
                    // these seconds prefixes, names, HACKER and TRACKER are
                    // not matched.
                    SourceHealth.record(
                        DetectionSource.BLE, ok = true,
                        message = "Full scan in ${secs}s — Android limits how often a scan can start"
                    )
                }
            }
            handler.postDelayed(launch, wait)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            scanFailed("BLE scanner unavailable")
            return
        }
        leScanner = scanner
        val filtered = !screenOn()
        // The full scan started over has missed nothing; see the warm-up below.
        val fullScanRestarted = scanning && !filteredMode && !filtered
        handler.removeCallbacks(confirm)
        try {
            // Stop first. That is how a running scan changes mode, and it clears a
            // registration left from before Bluetooth was toggled, which would
            // turn the start below into "already started". Stopping a scan that
            // is not running does nothing.
            scanner.stopScan(scanCallback)
            val filters = if (filtered) screenOffFilters() else null
            scanner.startScan(filters, scanSettings(), scanCallback)
            budget.started()
            scanning = true
            filteredMode = filtered
            startedAtMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(tend)
            handler.postDelayed(tend, TEND_MS)
            if (filtered) {
                // No tracker filter in this mode: see TrackerDwell.
                trackerDwell.pause()
                Log.i(TAG, "BLE scan started, filtered (${filters?.size} filters)")
            } else {
                trackerDwell.resume()
                // Addresses rotated while the screen was off all look new now.
                if (!fullScanRestarted) spam.restartWarmUp()
                Log.i(TAG, "BLE scan started, unfiltered")
            }
            // After a failure, wait and see that this one holds before saying so.
            if (failures == 0) recordRunning() else handler.postDelayed(confirm, CONFIRM_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting scan")
            scanning = false
            SourceHealth.record(DetectionSource.BLE, ok = false, message = "Permission revoked")
        } catch (e: IllegalStateException) {
            // Bluetooth went off between the check above and the call: the stack
            // throws rather than refuses.
            bluetoothWentOff()
        }
    }

    /** Through v0.6.0 the screen-off scan carried a note here, "Screen off —
     *  filtered scan…". The row never showed it, since it showed a message only
     *  for a source that had failed, and nobody reads a row with the screen off. */
    private fun recordRunning() {
        SourceHealth.record(DetectionSource.BLE, ok = true)
    }

    /** Bluetooth is off, and took the scan with it. Nothing to stop; say so and
     *  wait for the broadcast that says it is back. */
    private fun bluetoothWentOff() {
        handler.removeCallbacks(launch)
        handler.removeCallbacks(settle)
        handler.removeCallbacks(confirm)
        handler.removeCallbacks(tend)
        scanning = false
        failures = 0
        trackerDwell.pause()
        if (wanted) {
            SourceHealth.record(
                DetectionSource.BLE, ok = false, message = "Bluetooth is off — turn it on to scan"
            )
        }
    }

    /** A start did not take. Say so and try again, a little later each time. */
    private fun scanFailed(reason: String) {
        scanning = false
        trackerDwell.pause()
        failures++
        val delayMs = minOf(RETRY_FIRST_MS shl minOf(failures - 1, 4), RETRY_MAX_MS)
        SourceHealth.record(
            DetectionSource.BLE, ok = false,
            message = "$reason — trying again in ${delayMs / 1000}s"
        )
        handler.removeCallbacks(launch)
        handler.removeCallbacks(confirm)
        handler.postDelayed(launch, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopQuietly() {
        try {
            leScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan")
        } catch (e: IllegalStateException) {
            // Bluetooth is off: the scan is already gone, and the stack throws
            // instead of saying so.
        }
    }

    fun stop() {
        if (!wanted && !receiversRegistered) return
        wanted = false
        handler.removeCallbacksAndMessages(null)
        stopQuietly()
        scanning = false
        filteredMode = false
        unregisterReceivers()
        drones.clear()
        trackerDwell.clear()
        Log.i(TAG, "BLE scan stopped")
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                addAction(LocationManager.MODE_CHANGED_ACTION)
            }
        }
        // These are protected system broadcasts, so this must be an
        // exported-style registration; ContextCompat's NOT_EXPORTED flag would
        // drop them on API 34+. Nothing but the system can send them.
        ContextCompat.registerReceiver(
            context, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try { context.unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) { }
        receiversRegistered = false
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
            if (!wanted) return
            // The one failure a retry alone cannot cure: a controller that says it
            // does long-range scanning and then refuses the settings for it.
            if (errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED) plainSettings = true
            scanFailed("BLE scan failed (code $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        // A result already on its way when the scan was stopped still arrives.
        // Scored then, it would land in a store that has just been cleared and
        // that nothing prunes any more.
        if (!scanning) return
        lastResultAtMs = SystemClock.elapsedRealtime()
        if (failures != 0) {
            // Results are the proof a retried scan took.
            failures = 0
            handler.removeCallbacks(confirm)
            recordRunning()
        }
        val device = result.device
        val mac = device.address ?: return
        val record = result.scanRecord
        // The name this advert carries, when it carries one. device.name is the
        // Bluetooth stack's cache: empty the first time a device is heard, and
        // whatever the address was called last time after that.
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: try { device.name } catch (e: SecurityException) { null }

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
                        rssi = result.rssi,
                        kind = scored.kind
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
                rssi = rssi,
                kind = DetectionKind(m.kind.name, m.kind.label)
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
        /** How long the screen must stay on before the scan follows it. Going off
         *  is followed at once. */
        private const val MODE_SETTLE_MS = 2_000L
        /** How often a running scan is looked in on. */
        private const val TEND_MS = 30_000L
        /** A scan this old is started over: under the stack's 10-minute limit
         *  with room for a late look ([TEND_MS]) and a held start (31 s). */
        private const val REFRESH_MS = 8 * 60_000L
        /** The full scan with the screen on, silent this long, is started over.
         *  Where anyone carries a phone it hears something every second; where
         *  nothing transmits this costs a scan start every two minutes. */
        private const val QUIET_MS = 2 * 60_000L
        private const val RETRY_FIRST_MS = 5_000L
        private const val RETRY_MAX_MS = 60_000L
        /** How long a retried scan runs without a reported failure before the row says OK. */
        private const val CONFIRM_MS = 4_000L

        /** One for the process: the stack counts scan starts per app. */
        private val budget = ScanStartBudget(nowMs = { SystemClock.elapsedRealtime() })

        /**
         * One drone, from whichever radio heard it. Keyed by its serial when it has
         * sent one, so the same drone heard over Bluetooth and Wi-Fi is one row;
         * keyed by [transmitter] until then.
         * The position is the drone's own broadcast; nothing about it is logged.
         */
        fun submitDrone(store: DetectionStore, drone: RemoteId.Drone, transmitter: String, rssi: Int?) {
            val c = RemoteId.classify(drone.serial)
            val score = ConfidenceEngine.scoreDrone(c.tier)
            val id = drone.id
            // "Up" is the height the drone reports over its take-off point or the
            // ground. Its altitude is measured from the ellipsoid, and labelled a
            // drone sitting on a pad in Denver "1609 m up".
            val height = drone.heightM
            val altitude = drone.altitudeM
            val alt = when {
                height != null && height >= 0f -> " · ${height.roundToInt()} m up"
                height == null && altitude != null -> " · alt ${altitude.roundToInt()} m"
                else -> ""
            }
            val label = if (id != null) "${c.name} ($id)$alt" else "${c.name} (Remote ID)$alt"
            // Until its serial arrives a drone is listed under the radio address
            // that carried it. Once it has one, that row is the same drone twice.
            if (id != null) store.remove(DetectionSource.AIRCRAFT, "rid:$transmitter")
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
