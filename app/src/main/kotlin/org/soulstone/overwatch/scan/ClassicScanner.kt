package org.soulstone.overwatch.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.soulstone.overwatch.data.targets.HackerTargets
import org.soulstone.overwatch.fusion.ConfidenceEngine
import org.soulstone.overwatch.fusion.DetectionEvent
import org.soulstone.overwatch.fusion.DetectionSource
import org.soulstone.overwatch.fusion.DetectionStore

/**
 * Card-skimmer check: a Bluetooth **Classic** discovery every few minutes, looking for
 * the default names of the HC-05-family serial modules gas-pump skimmers are built
 * from. Those modules have no BLE, so the BLE scanner never sees them; SquachWatch,
 * which has no Classic inquiry, matches only BLE names for the same reason.
 *
 * Off by default and opt-in in Settings, because it is the one radio action in this
 * app that is not passive listening: it is the same discovery Android's own Bluetooth
 * settings run, and a module that answers without its name gets a name request,
 * which pages it and shows it this phone's Bluetooth address. Nothing is ever paired
 * or connected.
 *
 * The switch is read before every discovery, not only at Start: turning the skimmer
 * check or HACKER off stops this from transmitting again, and a discovery it already
 * has running is cancelled when the scan stops. Bluetooth being off only skips a
 * round; the check resumes when it is back.
 */
class ClassicScanner(
    private val context: Context,
    private val store: DetectionStore,
    /** Read before each discovery and for each result, so switching the check or
     *  HACKER off stops both the asking and the listening at once. */
    private val enabled: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "ClassicScanner"
        private const val INTERVAL_MS = 180_000L
        private const val ALARM_THRESHOLD = 40
        /** An inquiry runs about 12 seconds. A discovery still going later than
         *  this was started by something else, the system's own Bluetooth page say. */
        private const val OWN_DISCOVERY_MS = 15_000L
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var job: Job? = null
    private var registered = false
    /** When this scanner last started a discovery (elapsed-realtime ms), or 0. */
    @Volatile private var discoveryStartedMs = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> handle(intent)
            }
        }
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        val a = adapter ?: return false
        if (!hasPermission()) {
            Log.w(TAG, "Classic discovery unavailable (permission missing)")
            return false
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        // System broadcasts, so an exported-style registration, as BleScanner does
        // for screen on/off.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        job = scope.launch {
            while (isActive) {
                try {
                    if (enabled() && a.isEnabled && !a.isDiscovering && a.startDiscovery()) {
                        discoveryStartedMs = SystemClock.elapsedRealtime()
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException starting discovery")
                }
                delay(INTERVAL_MS)
            }
        }
        Log.i(TAG, "Classic discovery every ${INTERVAL_MS / 1000}s")
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        job?.cancel()
        job = null
        // Cancel a discovery only if it is ours: one started from the system's
        // Bluetooth page is somebody pairing headphones.
        val started = discoveryStartedMs
        discoveryStartedMs = 0L
        val ours = started != 0L && SystemClock.elapsedRealtime() - started < OWN_DISCOVERY_MS
        try {
            if (ours && adapter?.isDiscovering == true) adapter?.cancelDiscovery()
        } catch (_: SecurityException) { }
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
            registered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handle(intent: Intent) {
        if (!enabled()) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val mac = device?.address ?: return
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?: try { device.name } catch (e: SecurityException) { null }
        val m = HackerTargets.skimmerName(name) ?: return
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
            .takeIf { it != Short.MIN_VALUE.toInt() }
        val score = ConfidenceEngine.scoreHacker(ConfidenceEngine.hackerWeight(m.kind), 0, rssi)
        if (score < ALARM_THRESHOLD) return
        store.submit(
            DetectionEvent(
                source = DetectionSource.HACKER,
                key = "skim:$mac",
                label = "${m.label} ($mac)",
                score = score,
                matchedMethods = m.method,
                rssi = rssi
            )
        )
    }
}
