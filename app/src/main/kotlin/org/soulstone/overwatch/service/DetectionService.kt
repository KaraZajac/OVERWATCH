package org.soulstone.overwatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.soulstone.overwatch.MainActivity
import org.soulstone.overwatch.R
import org.soulstone.overwatch.data.location.LocationProvider
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.fusion.DetectionStore
import org.soulstone.overwatch.fusion.SourceHealth
import org.soulstone.overwatch.scan.BleScanner
import org.soulstone.overwatch.scan.CitizenScanner
import org.soulstone.overwatch.scan.DeflockClient
import org.soulstone.overwatch.scan.DeflockScanner
import org.soulstone.overwatch.scan.WazeScanner
import org.soulstone.overwatch.scan.WifiScanner

/**
 * Foreground service that owns all scanners and the [DetectionStore].
 *
 * Phase 1 wires only [BleScanner]; phases 2-4 will register WiFi, DeFlock, Waze.
 *
 * The service is a singleton at runtime — UI binds to it (or observes the
 * companion-object state flows directly, which is what we do here for simplicity).
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
    private lateinit var wazeScanner: WazeScanner
    private lateinit var citizenScanner: CitizenScanner
    private var pruneJob: Job? = null
    private var bleStarted = false
    private var wifiStarted = false
    private var deflockStarted = false
    private var wazeStarted = false
    private var citizenStarted = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings.get(this)
        bleScanner = BleScanner(this, store)
        wifiScanner = WifiScanner(this, store)
        locationProvider = LocationProvider(this)
        deflockScanner = DeflockScanner(
            store, locationProvider, DeflockClient(this),
            proximityMeters = { settings.deflockProximityM.value.toFloat() }
        )
        wazeScanner = WazeScanner(
            store, locationProvider,
            proximityMeters = { settings.wazeProximityM.value.toFloat() }
        )
        citizenScanner = CitizenScanner(
            store, locationProvider,
            proximityMeters = { settings.wazeProximityM.value.toFloat() }
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
        return START_STICKY
    }

    private fun beginScanning() {
        if (_running.value) return
        SourceHealth.reset()
        startInForeground()
        if (settings.bleEnabled.value) {
            bleStarted = bleScanner.start()
            if (!bleStarted) Log.w(TAG, "BleScanner.start() returned false (permission/adapter)")
        }
        if (settings.wifiEnabled.value) {
            wifiStarted = wifiScanner.start(lifecycleScope)
            if (!wifiStarted) Log.w(TAG, "WifiScanner.start() returned false (permission/adapter)")
        }
        val needsLocation = settings.deflockEnabled.value ||
            settings.wazeEnabled.value ||
            settings.citizenEnabled.value
        if (needsLocation) {
            val locOk = locationProvider.start()
            if (!locOk) {
                Log.w(TAG, "LocationProvider.start() returned false (permission)")
            } else {
                if (settings.deflockEnabled.value) {
                    deflockScanner.start(lifecycleScope); deflockStarted = true
                }
                if (settings.wazeEnabled.value) {
                    wazeScanner.start(lifecycleScope); wazeStarted = true
                }
                if (settings.citizenEnabled.value) {
                    citizenScanner.start(lifecycleScope); citizenStarted = true
                }
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
    }

    private fun endScanning() {
        if (!_running.value) return
        if (bleStarted) { bleScanner.stop(); bleStarted = false }
        if (wifiStarted) { wifiScanner.stop(); wifiStarted = false }
        if (deflockStarted) { deflockScanner.stop(); deflockStarted = false }
        if (wazeStarted) { wazeScanner.stop(); wazeStarted = false }
        if (citizenStarted) { citizenScanner.stop(); citizenStarted = false }
        locationProvider.stop()
        store.clear()
        SourceHealth.reset()
        pruneJob?.cancel()
        pruneJob = null
        _running.value = false
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

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires the runtime type to cover every capability
            // the service uses. We declare both in the manifest; pass both here
            // so location-using sources (DeFlock, Waze) keep working with the
            // screen off.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
