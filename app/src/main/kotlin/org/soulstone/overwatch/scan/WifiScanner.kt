package org.soulstone.overwatch.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.targets.HackerTargets
import org.soulstone.overwatch.data.targets.MicTargets
import org.soulstone.overwatch.data.targets.Patterns
import org.soulstone.overwatch.data.targets.RemoteId
import org.soulstone.overwatch.data.targets.WifiOuis
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.DroneTracker
import org.soulstone.overwatch.fusion.RssiTracker
import org.soulstone.overwatch.fusion.SourceHealth

/**
 * WiFi scanner — BSSID OUI + SSID-pattern matching via [WifiManager.getScanResults].
 *
 * Android 9+ throttles foreground apps to 4 scans per 2 minutes. We poll every 35s
 * (≈3.4 scans / 2 min) and rely on the system to deliver SCAN_RESULTS_AVAILABLE_ACTION.
 * If [WifiManager.startScan] returns false (throttled or radio busy) we still consume
 * whatever cached results the next broadcast carries.
 *
 * The flock-you promiscuous-mode addr1 / wildcard-probe trick from the reference repo
 * is **not portable to Android** — userspace can only see results WifiManager surfaces.
 * What it surfaces is enough for more than BSSIDs and names, though: the capabilities
 * string gives away beacon spam, and from API 30 the information elements carry
 * drones' Wi-Fi Remote ID.
 */
class WifiScanner(
    private val context: Context,
    private val store: DetectionStore,
    private val rssi: RssiTracker = RssiTracker(),
    /** When true, also evaluate each scan against MicTargets and submit MIC events. */
    private val micEnabled: () -> Boolean = { false },
    /** When true, also look for pentest access points and beacon spam (HACKER events). */
    private val hackerEnabled: () -> Boolean = { false },
    /** When true, also decode drone Remote ID beacons (AIRCRAFT events). */
    private val dronesEnabled: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "WifiScanner"
        private const val ALARM_THRESHOLD = 40
        private const val SCAN_INTERVAL_MS = 35_000L
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var running = false
    private var scanJob: Job? = null
    private var receiverRegistered = false
    private val drones = DroneTracker()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            if (!updated) return
            handleResults()
        }
    }

    val isAvailable: Boolean
        get() = wifiManager?.isWifiEnabled == true

    fun hasScanPermission(): Boolean {
        val locOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyOk = ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            return nearbyOk || locOk
        }
        return locOk
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (running) return true
        if (!hasScanPermission()) {
            Log.w(TAG, "WiFi scan permission missing")
            SourceHealth.record(DetectionSource.WIFI, ok = false, message = "Permission missing")
            return false
        }
        val mgr = wifiManager ?: run {
            Log.w(TAG, "WifiManager unavailable")
            SourceHealth.record(DetectionSource.WIFI, ok = false, message = "WifiManager unavailable")
            return false
        }
        if (!mgr.isWifiEnabled) {
            Log.w(TAG, "WiFi disabled — scanner won't return results")
            SourceHealth.record(
                DetectionSource.WIFI, ok = false,
                message = "WiFi disabled — enable in system settings"
            )
            // We still register the receiver so results arrive when the user enables WiFi.
        } else {
            SourceHealth.record(DetectionSource.WIFI, ok = true)
        }
        registerReceiver()
        running = true
        scanJob = scope.launch {
            while (isActive) {
                try {
                    @Suppress("DEPRECATION")
                    val ok = mgr.startScan()
                    if (!ok) Log.d(TAG, "startScan returned false (throttled or radio busy)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException starting WiFi scan", e)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
        Log.i(TAG, "WiFi scan started (interval=${SCAN_INTERVAL_MS}ms)")
        return true
    }

    fun stop() {
        if (!running) return
        scanJob?.cancel()
        scanJob = null
        unregisterReceiver()
        running = false
        drones.clear()
        Log.i(TAG, "WiFi scan stopped")
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already gone
        }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun handleResults() {
        val mgr = wifiManager ?: return
        val results: List<ScanResult> = try {
            mgr.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reading scanResults", e)
            return
        }
        val hacker = hackerEnabled()
        val dronesOn = dronesEnabled()

        for (r in results) {
            val bssid = r.BSSID ?: continue
            val ssid = readSsid(r)

            // ESP32 Marauder's wardrive decoy is a fake "Flock" network: report the
            // tool, never the camera it imitates.
            val marauderDecoy = HackerTargets.isMarauderFlockDecoy(ssid, bssid)
            val isSurveillance = !marauderDecoy && (
                WifiOuis.matches(bssid) ||
                    Patterns.ssidGenericMatch(ssid) ||
                    Patterns.ssidFlockFormat(ssid) ||
                    Patterns.ssidFlockHotspot(ssid)
                )
            val isMic = micEnabled() && MicTargets.couldBeMicWifi(bssid, ssid)

            if (isSurveillance || isMic) {
                rssi.update(bssid, r.level)
                val stationary = rssi.isStationary(bssid)

                if (isSurveillance) {
                    val obs = ConfidenceEngine.WifiObservation(
                        bssid = bssid, ssid = ssid, rssi = r.level, isStationary = stationary
                    )
                    val scored = ConfidenceEngine.scoreWifi(obs)
                    if (scored.score >= ALARM_THRESHOLD) {
                        store.submit(
                            DetectionEvent(
                                source = DetectionSource.WIFI,
                                key = bssid,
                                label = scored.label,
                                score = scored.score,
                                matchedMethods = scored.methods,
                                rssi = r.level
                            )
                        )
                    }
                }
                if (isMic) {
                    val obs = ConfidenceEngine.MicWifiObservation(
                        bssid = bssid, ssid = ssid, rssi = r.level, isStationary = stationary
                    )
                    val scored = ConfidenceEngine.scoreMicWifi(obs)
                    if (scored.score >= ALARM_THRESHOLD) {
                        store.submit(
                            DetectionEvent(
                                source = DetectionSource.MIC,
                                key = "mic:$bssid",
                                label = scored.label,
                                score = scored.score,
                                matchedMethods = scored.methods,
                                rssi = r.level
                            )
                        )
                    }
                }
            }
            if (hacker) checkHacker(bssid, ssid, r.level, marauderDecoy)
            if (dronesOn) checkDrone(r, bssid)
        }
        if (hacker) checkBeaconSpam(results)
    }

    /** Pentest access points by their default names and rogue-AP frameworks by
     *  their default addresses. */
    private fun checkHacker(bssid: String, ssid: String?, level: Int, marauderDecoy: Boolean) {
        val m = when {
            marauderDecoy -> HackerTargets.Match(
                HackerTargets.Kind.MARAUDER_DECOY,
                "ESP32 Marauder wardrive decoy — fake \"Flock\" network", "marauder_decoy"
            )
            else -> HackerTargets.matchRogueBssid(bssid) ?: HackerTargets.matchSsid(ssid)
        } ?: return
        val score = ConfidenceEngine.scoreHacker(ConfidenceEngine.hackerWeight(m.kind), 0, level)
        submitHacker("hack:$bssid", "${m.label} ($bssid)", m.method, score, level)
    }

    /** Whole-scan checks: beacon floods and one BSSID wearing many names. */
    private fun checkBeaconSpam(results: List<ScanResult>) {
        val aps = results.mapNotNull { r ->
            val b = r.BSSID ?: return@mapNotNull null
            HackerTargets.ApBrief(b, readSsid(r), r.capabilities)
        }
        HackerTargets.beaconSpam(aps)?.let { f ->
            submitHacker("hack:beacon_spam", f.label, f.method, ConfidenceEngine.W_HACK_BEACON_SPAM, null)
        }
        HackerTargets.manyNamesOneBssid(aps)?.let { (bssid, n) ->
            submitHacker(
                "hack:names:$bssid", "Rogue AP broadcasting $n network names ($bssid)", "many_names",
                ConfidenceEngine.W_HACK_MANY_NAMES, null
            )
        }
    }

    private fun submitHacker(key: String, label: String, methods: String, score: Int, level: Int?) {
        if (score < ALARM_THRESHOLD) return
        store.submit(
            DetectionEvent(
                source = DetectionSource.HACKER,
                key = key,
                label = label,
                score = score,
                matchedMethods = methods,
                rssi = level
            )
        )
    }

    /** Drone Remote ID in a beacon's vendor-specific element (API 30+). */
    private fun checkDrone(r: ScanResult, bssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val elements = try { r.informationElements } catch (e: Exception) { return }
        for (ie in elements) {
            if (ie.id != 221) continue
            val buf = ie.bytes
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            val msgs = RemoteId.messagesFromWifiIe(bytes)
            if (msgs.isEmpty()) continue
            val drone = drones.update(bssid, msgs)
            BleScanner.submitDrone(store, drone, "wifi:$bssid", r.level)
            return
        }
    }

    private fun readSsid(r: ScanResult): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val raw = r.wifiSsid?.toString() ?: return null
            return raw.trim('"').ifBlank { null }
        }
        @Suppress("DEPRECATION")
        val raw = r.SSID ?: return null
        return raw.trim('"').ifBlank { null }
    }
}
