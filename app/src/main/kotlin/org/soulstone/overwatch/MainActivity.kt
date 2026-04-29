package org.soulstone.overwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        if (allGranted) {
            // First-run path: user just granted everything, kick off scanning
            // immediately so they don't have to tap START a second time.
            DetectionService.start(this)
        }
    }

    private val permissionsGranted = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted.value = checkAllPermissions()
        val settings = Settings.get(this)

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            OverwatchTheme(mode = themeMode) {
                var screen by remember { mutableStateOf(Screen.MAIN) }

                when (screen) {
                    Screen.MAIN -> {
                        val running by DetectionService.running.collectAsState()
                        val events by DetectionService.store.events.collectAsState()
                        val threat by DetectionService.store.threatLevel.collectAsState()
                        val maxScore by DetectionService.store.maxScore.collectAsState()
                        val granted by permissionsGranted

                        MainScreen(
                            running = running,
                            threat = threat,
                            score = maxScore,
                            events = events,
                            canStart = true,
                            permissionMessage = if (!granted) "Tap START to grant Bluetooth, WiFi + location permissions" else null,
                            onStartStop = {
                                if (running) {
                                    DetectionService.stop(this)
                                } else {
                                    if (granted) {
                                        DetectionService.start(this)
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
        permissionsGranted.value = checkAllPermissions()
    }

    private fun checkAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private enum class Screen { MAIN, SETTINGS }
}
