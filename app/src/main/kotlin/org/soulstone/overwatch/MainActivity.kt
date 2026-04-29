package org.soulstone.overwatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.service.DetectionService
import org.soulstone.overwatch.ui.MainScreen
import org.soulstone.overwatch.ui.SettingsScreen
import org.soulstone.overwatch.ui.theme.OverwatchTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            // Location is needed pre-S for BLE, pre-T for WiFi scan results,
            // and for Phase 3 DeFlock proximity.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.all { it.value }
        permissionsGranted.value = allGranted
        permanentlyDenied.value = !allGranted && !anyMissingCanStillAsk()
        if (allGranted) {
            // First-run path: user just granted everything, kick off scanning
            // immediately so they don't have to tap START a second time.
            DetectionService.start(this)
        }
    }

    private val permissionsGranted = mutableStateOf(false)
    /** True when at least one required permission is denied AND the system says
     *  we can no longer prompt for it (user picked "don't ask again"). The UI
     *  swaps the START button's call-to-action for an "Open app settings" link. */
    private val permanentlyDenied = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted.value = checkAllPermissions()
        permanentlyDenied.value = false  // reset on activity create
        val settings = Settings.get(this)

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            OverwatchTheme(mode = themeMode) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }

                when (screen) {
                    Screen.MAIN -> {
                        val running by DetectionService.running.collectAsState()
                        val events by DetectionService.store.events.collectAsState()
                        val threat by DetectionService.store.threatLevel.collectAsState()
                        val maxScore by DetectionService.store.maxScore.collectAsState()
                        val granted by permissionsGranted
                        val denied by permanentlyDenied

                        val message = when {
                            granted -> null
                            denied -> "Permissions permanently denied — open app settings to grant"
                            else -> "Tap START to grant Bluetooth, WiFi + location permissions"
                        }

                        MainScreen(
                            running = running,
                            threat = threat,
                            score = maxScore,
                            events = events,
                            canStart = true,
                            permissionMessage = message,
                            showOpenAppSettings = denied && !granted,
                            onOpenAppSettings = { openAppSettings() },
                            onStartStop = {
                                if (running) {
                                    DetectionService.stop(this)
                                } else {
                                    if (granted) {
                                        DetectionService.start(this)
                                    } else if (denied) {
                                        openAppSettings()
                                    } else {
                                        permissionLauncher.launch(requiredPermissions)
                                    }
                                }
                            },
                            onOpenSettings = { screen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS -> {
                        val running by DetectionService.running.collectAsState()
                        SettingsScreen(
                            settings = settings,
                            isRunning = running,
                            onRestart = {
                                DetectionService.stop(this)
                                DetectionService.start(this)
                            },
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have granted permissions in app settings while we were paused.
        val nowGranted = checkAllPermissions()
        permissionsGranted.value = nowGranted
        if (nowGranted) permanentlyDenied.value = false
    }

    private fun checkAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** True if at least one missing permission is still askable via the system
     *  prompt. False means everything missing was denied with "don't ask again". */
    private fun anyMissingCanStillAsk(): Boolean {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        return missing.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
    }

    private fun openAppSettings() {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private enum class Screen { MAIN, SETTINGS }
}
