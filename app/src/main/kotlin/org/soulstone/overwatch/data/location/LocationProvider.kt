package org.soulstone.overwatch.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [FusedLocationProviderClient] and exposes the latest fix as a [StateFlow].
 *
 * Update cadence: 15s desired, 5s minimum, BALANCED_POWER_ACCURACY (≈100m precision —
 * good enough for ALPR proximity alerts ≤200m, easy on battery).
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val INTERVAL_MS = 15_000L
        private const val MIN_INTERVAL_MS = 5_000L
    }

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val request: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        INTERVAL_MS
    )
        .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!running) return
            val fix = result.lastLocation ?: return
            _location.value = fix
        }
    }

    @Volatile private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted")
            return false
        }
        try {
            running = true
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            // Seed with the cached lastLocation only if (a) we haven't already
            // received a fresh fix from requestLocationUpdates and (b) we're
            // still running by the time the listener fires. Otherwise the
            // listener can race and either overwrite a fresh fix with a stale
            // one or fire after stop().
            client.lastLocation.addOnSuccessListener { last ->
                if (running && last != null && _location.value == null) {
                    _location.value = last
                }
            }
            Log.i(TAG, "Location updates started")
            return true
        } catch (e: SecurityException) {
            running = false
            Log.e(TAG, "SecurityException starting location updates", e)
            return false
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
        _location.value = null
        Log.i(TAG, "Location updates stopped")
    }
}
