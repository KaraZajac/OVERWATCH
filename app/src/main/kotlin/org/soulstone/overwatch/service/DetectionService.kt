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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.soulstone.overwatch.MainActivity
import org.soulstone.overwatch.R
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.fusion.ThreatLevel
import org.soulstone.overwatch.scan.BleScanner
import org.soulstone.overwatch.scan.CitizenScanner
import org.soulstone.overwatch.scan.DeflockClient
import org.soulstone.overwatch.scan.DeflockScanner
import org.soulstone.overwatch.scan.DeflockClient.AlprPoint
import org.soulstone.overwatch.scan.WifiScanner

/**
 * Foreground service that owns all four scanners (BLE, WiFi, DeFlock, Citizen)
 * and the [DetectionStore]. UI observes companion-object state flows directly.
 *
 * Responsibilities beyond scanner orchestration:
 *  - Updates the foreground notification on every threat-tier change so a
 *    locked-screen user sees escalations.
 *  - Vibrates on upward tier transitions (gated by Settings.vibrateOnAlert).
 *  - Resets [SourceHealth] on start/stop.
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

        const val ACTION_START = "org.soulstone.overwatch.action.START"
        const val ACTION_STOP = "org.soulstone.overwatch.action.STOP"

        /** Single shared store — UI observes this. */
        val store: DetectionStore = DetectionStore()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Latest ALPR cell cache — UI map renders these as pins. Mirrored from
         *  the active DeflockScanner while the service is running; cleared on stop. */
        private val _mapPoints = MutableStateFlow<List<AlprPoint>>(emptyList())
        val mapPoints: StateFlow<List<AlprPoint>> = _mapPoints.asStateFlow()

        /** Latest fused location fix — UI map centers on this. */
        private val _location = MutableStateFlow<Location?>(null)
        val location: StateFlow<Location?> = _location.asStateFlow()

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
    }

    private lateinit var settings: Settings
    private lateinit var bleScanner: BleScanner
    private lateinit var wifiScanner: WifiScanner
    private lateinit var locationProvider: LocationProvider
    private lateinit var deflockScanner: DeflockScanner
    private lateinit var citizenScanner: CitizenScanner
    private var pruneJob: Job? = null
    private var observerJob: Job? = null
    private var mapPointsJob: Job? = null
    private var locationJob: Job? = null
    private var deflockProxJob: Job? = null
    private var citizenProxJob: Job? = null
    private var bleStarted = false
    private var wifiStarted = false
    private var deflockStarted = false
    private var citizenStarted = false
    /** Last threat tier the notification displayed; tracks upward transitions for vibration. */
    private var lastNotifiedTier: ThreatLevel = ThreatLevel.GREEN

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        bleScanner = BleScanner(this, store, micEnabled = { settings.micEnabled.value })
        wifiScanner = WifiScanner(this, store, micEnabled = { settings.micEnabled.value })
        locationProvider = LocationProvider(this)
        deflockScanner = DeflockScanner(
            store, locationProvider, DeflockClient(this),
            proximityMeters = { settings.deflockProximityM.value.toFloat() }
        )
        citizenScanner = CitizenScanner(
            store, locationProvider,
            proximityMeters = { settings.citizenProximityM.value.toFloat() }
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> beginScanning()
            ACTION_STOP -> {
                endScanning()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginScanning() {
        if (_running.value) return
        SourceHealth.reset()
        lastNotifiedTier = ThreatLevel.GREEN
        // Bring up the foreground notification BEFORE any scanner so we don't
        // accidentally call startForeground after work has already begun.
        startInForeground(ThreatLevel.GREEN, topEvent = null)

        if (settings.bleEnabled.value) {
            bleStarted = bleScanner.start()
            if (!bleStarted) Log.w(TAG, "BleScanner.start() returned false (permission/adapter)")
        }
        if (settings.wifiEnabled.value) {
            wifiStarted = wifiScanner.start(lifecycleScope)
            if (!wifiStarted) Log.w(TAG, "WifiScanner.start() returned false (permission/adapter)")
        }
        val needsLocation = settings.deflockEnabled.value || settings.citizenEnabled.value
        if (needsLocation) {
            val locOk = locationProvider.start()
            if (!locOk) {
                Log.w(TAG, "LocationProvider.start() returned false (permission)")
            } else {
                if (settings.deflockEnabled.value) {
                    deflockScanner.start(lifecycleScope); deflockStarted = true
                }
                if (settings.citizenEnabled.value) {
                    citizenScanner.start(lifecycleScope); citizenStarted = true
                }
            }
        }

        val anyStarted = bleStarted || wifiStarted || deflockStarted || citizenStarted
        if (!anyStarted) {
            Log.w(TAG, "No scanner started — endScanning + stopSelf")
            endScanning()
            stopSelf()
            return
        }

        // MIC piggybacks on the BLE/WiFi scanners. Surface its health so the
        // user sees an explicit status row rather than a silent UNKNOWN.
        if (settings.micEnabled.value) {
            if (bleStarted || wifiStarted) {
                SourceHealth.record(DetectionSource.MIC, ok = true)
            } else {
                SourceHealth.record(
                    DetectionSource.MIC,
                    ok = false,
                    message = "Needs BLE or WiFi scanner enabled"
                )
            }
        }

        _running.value = true
        pruneJob?.cancel()
        pruneJob = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                store.pruneExpired()
            }
        }
        observerJob?.cancel()
        observerJob = lifecycleScope.launch {
            // Watch threat tier + the top event together; rebuild the notification
            // on either change. Vibrate only when the tier ratchets upward.
            store.threatLevel.combine(store.events) { tier, events ->
                tier to events.firstOrNull()
            }.collect { (tier, top) ->
                onTierChanged(tier, top)
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
        deflockProxJob?.cancel()
        if (deflockStarted) {
            deflockProxJob = lifecycleScope.launch {
                settings.deflockProximityM.drop(1).collect { deflockScanner.refresh() }
            }
        }
        citizenProxJob?.cancel()
        if (citizenStarted) {
            citizenProxJob = lifecycleScope.launch {
                settings.citizenProximityM.drop(1).collect { citizenScanner.refresh() }
            }
        }
    }

    private fun endScanning() {
        if (!_running.value && !bleStarted && !wifiStarted && !deflockStarted && !citizenStarted) {
            return
        }
        _running.value = false
        if (bleStarted) { bleScanner.stop(); bleStarted = false }
        if (wifiStarted) { wifiScanner.stop(); wifiStarted = false }
        if (deflockStarted) { deflockScanner.stop(); deflockStarted = false }
        if (citizenStarted) { citizenScanner.stop(); citizenStarted = false }
        locationProvider.stop()
        store.clear()
        SourceHealth.reset()
        pruneJob?.cancel(); pruneJob = null
        observerJob?.cancel(); observerJob = null
        mapPointsJob?.cancel(); mapPointsJob = null
        locationJob?.cancel(); locationJob = null
        deflockProxJob?.cancel(); deflockProxJob = null
        citizenProxJob?.cancel(); citizenProxJob = null
        _mapPoints.value = emptyList()
        _location.value = null
        lastNotifiedTier = ThreatLevel.GREEN
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

    private fun onTierChanged(tier: ThreatLevel, top: DetectionEvent?) {
        // Re-issue the foreground notification with the current tier + top event
        // so a locked-screen user sees the escalation even without opening the app.
        val notification = buildNotification(tier, top)
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, notification)

        if (tier.ordinal > lastNotifiedTier.ordinal && settings.vibrateOnAlert.value) {
            vibrateForTier(tier)
        }
        lastNotifiedTier = tier
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

    private fun startInForeground(tier: ThreatLevel, topEvent: DetectionEvent?) {
        val notification = buildNotification(tier, topEvent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the runtime type to cover every capability the
            // service uses. We declare both in the manifest; pass both here so
            // location-using sources (DeFlock, Citizen) keep working with the
            // screen off.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(tier: ThreatLevel, topEvent: DetectionEvent?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = "OVERWATCH  •  ${tier.name}"
        val text = topEvent?.let { "${it.score}  •  ${it.label}" }
            ?: getString(R.string.notification_text)
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
            .setSmallIcon(android.R.drawable.ic_menu_view)
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
