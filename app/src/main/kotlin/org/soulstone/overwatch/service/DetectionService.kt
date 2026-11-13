package org.soulstone.overwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.soulstone.overwatch.MainActivity
import org.soulstone.overwatch.R
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.data.cache.LocationTraces
import org.soulstone.overwatch.data.settings.IgnoreList
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.AlertGate
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.ThreatLevel
import org.soulstone.overwatch.scan.AircraftScanner
import org.soulstone.overwatch.scan.BleScanner
import org.soulstone.overwatch.scan.ClassicScanner
import org.soulstone.overwatch.scan.DeflockClient
import org.soulstone.overwatch.scan.DeflockScanner
import org.soulstone.overwatch.scan.DeflockClient.SurveillancePoint
import org.soulstone.overwatch.scan.WazeClient
import org.soulstone.overwatch.scan.WazeScanner
import org.soulstone.overwatch.scan.WazeSource
import org.soulstone.overwatch.scan.wazert.WazeRtClient
import org.soulstone.overwatch.scan.WifiScanner

/**
 * Foreground service that owns the scanners (BLE, WiFi, DeFlock, Waze, Aircraft;
 * COMMERCIAL rides the BLE and WiFi scans) and the [DetectionStore]. UI observes
 * companion-object state flows directly.
 *
 * Responsibilities beyond scanner orchestration:
 *  - Updates the foreground notification when what it says changes, so a
 *    locked-screen user sees escalations.
 *  - Vibrates on upward tier transitions (gated by Settings.vibrateOnAlert and
 *    by [AlertGate], so one device hovering on a boundary buzzes once).
 *  - Resets [SourceHealth] on start/stop, and keeps the rows of the sources
 *    that ride the radio scans in step with those scans.
 *
 * Returns START_NOT_STICKY so a system-killed service does not auto-restart
 * into a zombie state where the notification disappears but `_running` stays
 * stale. The user explicitly starts and stops; auto-restart isn't needed.
 */
class DetectionService : LifecycleService() {

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "overwatch_detection"
        private const val NOTIFICATION_ID = 0xBEEF
        /** At most one notification update in this long. Android drops updates
         *  that come faster than a few a second, and may drop the last one. */
        private const val NOTIFY_MIN_INTERVAL_MS = 1_000L

        const val ACTION_START = "org.soulstone.overwatch.action.START"
        const val ACTION_STOP = "org.soulstone.overwatch.action.STOP"

        /** Single shared store — UI observes this. */
        val store: DetectionStore = DetectionStore()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Latest ALPR cell cache — UI map renders these as pins. Mirrored from
         *  the active DeflockScanner while the service is running; cleared on stop. */
        private val _mapPoints = MutableStateFlow<List<SurveillancePoint>>(emptyList())
        val mapPoints: StateFlow<List<SurveillancePoint>> = _mapPoints.asStateFlow()

        /** Latest fused location fix — UI map centers on this. */
        private val _location = MutableStateFlow<Location?>(null)
        val location: StateFlow<Location?> = _location.asStateFlow()

        /** True while the running scan has asked for location. A scan with only
         *  the radio sources on never does, and the UI must not say it is
         *  waiting for a fix that nothing requested. */
        private val _locating = MutableStateFlow(false)
        val locating: StateFlow<Boolean> = _locating.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, DetectionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DetectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private val overlayNudge = MutableStateFlow(0)

        /** Ask a running scan to look again at whether it may draw the floating
         *  bubble. Called when the app comes back to the front, which is where
         *  the user lands after granting "display over other apps". */
        fun recheckOverlay() = overlayNudge.update { it + 1 }
    }

    /** What the notification shows. Compared before posting, so a detection
     *  re-heard with nothing new to say does not post again. */
    private data class NotificationContent(val tier: ThreatLevel, val score: Int?, val label: String?)

    private lateinit var settings: Settings
    private lateinit var bleScanner: BleScanner
    private lateinit var wifiScanner: WifiScanner
    private lateinit var classicScanner: ClassicScanner
    private lateinit var locationProvider: LocationProvider
    private lateinit var deflockScanner: DeflockScanner
    /** Built per scan, in [beginScanning]: see the note there. */
    private var wazeScanner: WazeScanner? = null
    private lateinit var aircraftScanner: AircraftScanner
    private lateinit var overlayManager: OverlayManager
    private var pruneJob: Job? = null
    private var observerJob: Job? = null
    private var alertJob: Job? = null
    private var healthJob: Job? = null
    private var mapPointsJob: Job? = null
    private var locationJob: Job? = null
    private var overlayJob: Job? = null
    private var bleStarted = false
    private var wifiStarted = false
    private var deflockStarted = false
    private var wazeStarted = false
    private var aircraftStarted = false
    private var classicStarted = false
    private val alertGate = AlertGate(nowMs = { SystemClock.elapsedRealtime() })

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        // Before any scanner exists, so nothing the user has ignored is ever
        // stored, scored or alerted on. The list outlives this service; a rule
        // added mid-scan reaches the store through MainActivity.
        store.ignored = IgnoreList.get(this)::matches
        // COMMERCIAL, HACKER, TRACKER and drone Remote ID ride the two radio
        // scanners; each is read per advert, so the switch is whatever Settings
        // says when the scan (re)starts.
        bleScanner = BleScanner(
            this, store,
            micEnabled = { settings.micEnabled.value },
            hackerEnabled = { settings.hackerEnabled.value },
            trackerEnabled = { settings.trackerEnabled.value },
            dronesEnabled = { settings.aircraftEnabled.value }
        )
        wifiScanner = WifiScanner(
            this, store,
            micEnabled = { settings.micEnabled.value },
            hackerEnabled = { settings.hackerEnabled.value },
            dronesEnabled = { settings.aircraftEnabled.value }
        )
        classicScanner = ClassicScanner(
            this, store,
            enabled = { settings.hackerEnabled.value && settings.skimmerScanEnabled.value }
        )
        locationProvider = LocationProvider(this)
        // Scanners use their own fixed evaluation radii. The user's
        // "show within" setting is a view control and must never decide what
        // gets scored, or narrowing it would hide a real alert.
        deflockScanner = DeflockScanner(store, locationProvider, DeflockClient(this))
        aircraftScanner = AircraftScanner(this, store, locationProvider)
        overlayManager = OverlayManager(
            context = this,
            // User dragged the bubble onto the X — flip the persisted toggle
            // so the setting and the bubble state stay aligned. The settings
            // collector below will call hide() again, but hide() is idempotent.
            onDismissed = { settings.setOverlayEnabled(false) }
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginScanning()
            ACTION_STOP -> {
                endScanning()
                // stopSelf(startId), not stopSelf(): Settings' "Restart scan to
                // apply" sends STOP and START back to back. The no-arg form stops
                // the service regardless of that START, so its onDestroy() could
                // run endScanning() just after the restart and leave scanning off.
                // With the id, Android only stops the service when no newer start
                // has arrived. So a restart keeps this instance and onCreate() does
                // not run again: anything a setting chooses must be built in
                // beginScanning(), not in onCreate().
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginScanning() {
        if (_running.value) return
        // Remove anything a previous scan left behind if it was killed before
        // its stop could clean up.
        LocationTraces.clearAsync(this, includeTiles = settings.clearTilesOnStop.value)
        SourceHealth.reset()
        // Nothing from an earlier scan may be standing when this one starts. A
        // stop clears the store, but a result that was already on its way then
        // could land after the clear, with nothing left running to prune it.
        store.clear()
        alertGate.reset()
        // Bring up the foreground notification BEFORE any scanner so we don't
        // accidentally call startForeground after work has already begun.
        startInForeground(NotificationContent(ThreatLevel.GREEN, null, null))

        if (settings.bleEnabled.value) {
            bleStarted = bleScanner.start()
            if (!bleStarted) Log.w(TAG, "BleScanner.start() returned false (permission or no radio)")
        }
        if (settings.wifiEnabled.value) {
            wifiStarted = wifiScanner.start(lifecycleScope)
            if (!wifiStarted) Log.w(TAG, "WifiScanner.start() returned false (permission/adapter)")
        }
        if (bleStarted && settings.hackerEnabled.value && settings.skimmerScanEnabled.value) {
            classicStarted = classicScanner.start(lifecycleScope)
        }
        val needsLocation = settings.deflockEnabled.value || settings.wazeEnabled.value ||
            settings.aircraftEnabled.value
        if (needsLocation) {
            val locOk = locationProvider.start()
            if (!locOk) {
                Log.w(TAG, "LocationProvider.start() returned false (permission)")
                // Say so on each row that needed it. Left alone they read as
                // sources that looked and found nothing.
                for ((source, on) in listOf(
                    DetectionSource.DEFLOCK to settings.deflockEnabled.value,
                    DetectionSource.WAZE to settings.wazeEnabled.value,
                    DetectionSource.AIRCRAFT to settings.aircraftEnabled.value
                )) {
                    if (on) SourceHealth.record(source, ok = false, message = "Location permission missing")
                }
            } else {
                _locating.value = true
                if (settings.deflockEnabled.value) {
                    deflockScanner.start(lifecycleScope); deflockStarted = true
                }
                if (settings.wazeEnabled.value) {
                    // The backend is chosen here, per scan, not once in onCreate():
                    // a restart keeps this service, so a backend picked there would
                    // outlive a switch the user restarted to apply. v0.5.18 did
                    // exactly that, and kept sending the grid square to Waze after
                    // the user had switched to OpenWeb Ninja.
                    wazeScanner = WazeScanner(store, locationProvider, source = newWazeSource())
                        .also { it.start(lifecycleScope) }
                    wazeStarted = true
                }
                if (settings.aircraftEnabled.value) {
                    aircraftScanner.start(lifecycleScope); aircraftStarted = true
                }
            }
        }

        val anyStarted = bleStarted || wifiStarted || deflockStarted || wazeStarted ||
            aircraftStarted
        if (!anyStarted) {
            Log.w(TAG, "No scanner started — endScanning + stopSelf")
            endScanning()
            stopSelf()
            return
        }

        // MIC, HACKER and TRACKER piggyback on the BLE/WiFi scanners. Their rows
        // follow those scans for as long as this one runs: a radio switched off
        // halfway through takes them down with it, and back up when it returns.
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            SourceHealth.ble.combine(SourceHealth.wifi) { ble, wifi ->
                (bleStarted && ble.status != SourceHealth.Status.FAILED) to
                    (wifiStarted && wifi.status != SourceHealth.Status.FAILED)
            }.distinctUntilChanged().collect { (bleUp, wifiUp) -> recordRiders(bleUp, wifiUp) }
        }

        _running.value = true
        pruneJob?.cancel()
        pruneJob = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                store.pruneExpired()
            }
        }
        // The level alone decides a vibration. Collected apart from the
        // notification below: that one is slowed down, and a buzz must not be.
        alertJob?.cancel()
        alertJob = lifecycleScope.launch {
            store.threatLevel.collect { tier ->
                val buzz = alertGate.onLevel(tier, store.events.value.firstOrNull())
                if (buzz && settings.vibrateOnAlert.value) vibrateForTier(tier)
            }
        }
        // The notification says the level and the top detection. A radio device
        // is re-heard several times a second and the store emits each time, so
        // post only when the words change, and not more than once a second.
        // conflate() keeps the newest while the last post settles, so what ends
        // up on the lock screen is always the latest.
        //
        // The level is worked out here from the same list the top detection is
        // read from. The store publishes its list and its level one after the
        // other; combined, the two gave a half-way pair at every change of
        // level (the new detection under the old level, "GREEN" over a 90), and
        // the pause below kept it on the lock screen for its full second.
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            store.events.map { events ->
                val top = events.firstOrNull()
                NotificationContent(
                    ThreatLevel.fromScore(events.maxOfOrNull { it.score } ?: 0),
                    top?.score, top?.label
                )
            }.distinctUntilChanged().conflate().collect { content ->
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(content))
                delay(NOTIFY_MIN_INTERVAL_MS)
            }
        }

        // Mirror scanner state to the companion StateFlows the UI observes.
        // These exist so the map widget doesn't need a direct handle on the
        // scanner instances (which are private to this service).
        mapPointsJob?.cancel()
        if (deflockStarted) {
            mapPointsJob = lifecycleScope.launch {
                deflockScanner.cachedPoints.collect { _mapPoints.value = it }
            }
        }
        locationJob?.cancel()
        locationJob = lifecycleScope.launch {
            locationProvider.location.collect { _location.value = it }
        }

        // Live re-eval when the user moves a proximity slider. drop(1) skips
        // the StateFlow's initial replay so we don't redundantly clear+re-emit
        // the events the scanner just produced from its first handleFix call.
        // Nothing to do on a radius change any more: "show within" is a view
        // control, the scanners evaluate at their own fixed radii, and the UI
        // re-filters itself. Scoring is deliberately independent of it.

        // Floating threat-circle overlay — observe the toggle and show/hide
        // accordingly. The OverlayManager re-checks SYSTEM_ALERT_WINDOW each
        // show() so a denied/revoked permission silently no-ops. The nudge
        // re-runs it when the app returns to the front: the switch is turned on
        // before the permission is granted, so the toggle alone never fires
        // again once it has been.
        overlayJob?.cancel()
        overlayJob = lifecycleScope.launch {
            settings.overlayEnabled.combine(overlayNudge) { enabled, _ -> enabled }
                .collect { enabled ->
                    if (enabled) overlayManager.show() else overlayManager.hide()
                }
        }
    }

    /** Health rows for the sources that have no scan of their own. */
    private fun recordRiders(bleUp: Boolean, wifiUp: Boolean) {
        val bleOn = settings.bleEnabled.value
        val wifiOn = settings.wifiEnabled.value
        for ((source, on) in listOf(
            DetectionSource.MIC to settings.micEnabled.value,
            DetectionSource.HACKER to settings.hackerEnabled.value,
            DetectionSource.TRACKER to settings.trackerEnabled.value
        )) {
            if (!on) continue
            if (source == DetectionSource.TRACKER) {
                when {
                    bleUp -> SourceHealth.record(source, ok = true)
                    !bleOn -> SourceHealth.record(source, ok = false, message = "Needs the BLE scanner enabled")
                    else -> SourceHealth.record(source, ok = false, message = "BLE scan is not running — see BLE")
                }
            } else {
                when {
                    bleUp || wifiUp -> SourceHealth.record(source, ok = true)
                    !bleOn && !wifiOn ->
                        SourceHealth.record(source, ok = false, message = "Needs BLE or WiFi scanner enabled")
                    else -> SourceHealth.record(
                        source, ok = false, message = "BLE and WiFi scans are not running — see their rows"
                    )
                }
            }
        }
    }

    /** A fresh client for the backend Settings names now. A new direct client
     *  restores the stored anonymous account, so a Forget also takes effect here. */
    private fun newWazeSource(): WazeSource = when (settings.wazeBackend.value) {
        Settings.WazeBackend.DIRECT -> WazeRtClient(this)
        Settings.WazeBackend.OPENWEB_NINJA -> WazeClient(apiKey = { settings.wazeApiKey.value })
    }

    private fun endScanning() {
        if (!_running.value && !bleStarted && !wifiStarted && !deflockStarted && !wazeStarted &&
            !aircraftStarted && !classicStarted
        ) {
            return
        }
        _running.value = false
        // The watchers go first. Each reacts to what the lines below change, and
        // a notification posted after stopForeground() would stay up for good.
        pruneJob?.cancel(); pruneJob = null
        observerJob?.cancel(); observerJob = null
        alertJob?.cancel(); alertJob = null
        healthJob?.cancel(); healthJob = null
        mapPointsJob?.cancel(); mapPointsJob = null
        locationJob?.cancel(); locationJob = null
        overlayJob?.cancel(); overlayJob = null
        if (classicStarted) { classicScanner.stop(); classicStarted = false }
        if (bleStarted) { bleScanner.stop(); bleStarted = false }
        if (wifiStarted) { wifiScanner.stop(); wifiStarted = false }
        if (deflockStarted) { deflockScanner.stop(); deflockStarted = false }
        if (wazeStarted) { wazeScanner?.stop(); wazeScanner = null; wazeStarted = false }
        if (aircraftStarted) { aircraftScanner.stop(); aircraftStarted = false }
        locationProvider.stop()
        // The camera cache is a list of every ~5.5 km area this scan covered;
        // map tiles too, if the user asked. Delete them now the scan is over.
        LocationTraces.clearAsync(this, includeTiles = settings.clearTilesOnStop.value)
        store.clear()
        SourceHealth.reset()
        overlayManager.hide()
        _mapPoints.value = emptyList()
        _location.value = null
        _locating.value = false
        alertGate.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        endScanning()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun vibrateForTier(tier: ThreatLevel) {
        val v = currentVibrator() ?: return
        val effect = when (tier) {
            ThreatLevel.YELLOW -> VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            ThreatLevel.ORANGE -> VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1)
            ThreatLevel.RED -> VibrationEffect.createWaveform(
                longArrayOf(0, 250, 120, 250, 120, 400), -1
            )
            ThreatLevel.GREEN -> return
        }
        try { v.vibrate(effect) } catch (e: Exception) { Log.w(TAG, "vibrate failed: ${e.message}") }
    }

    private fun currentVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun startInForeground(content: NotificationContent) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the runtime type to cover every capability the
            // service uses. We declare both in the manifest; pass both here so
            // location-using sources (DeFlock, Waze) keep working with the
            // screen off.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(content: NotificationContent): Notification {
        val tier = content.tier
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "OVERWATCH  •  ${tier.name}"
        val text = if (content.score != null && content.label != null) {
            "${content.score}  •  ${content.label}"
        } else {
            getString(R.string.notification_text)
        }
        // Higher importance for ORANGE/RED so the system surfaces it more
        // aggressively (heads-up notification, etc.). The channel was created
        // with LOW; on supported versions this priority is best-effort.
        val priority = when (tier) {
            ThreatLevel.RED -> NotificationCompat.PRIORITY_HIGH
            ThreatLevel.ORANGE -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(priority)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }
}
