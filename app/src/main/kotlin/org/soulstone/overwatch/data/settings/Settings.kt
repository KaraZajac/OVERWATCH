package org.soulstone.overwatch.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide user preferences. Backed by SharedPreferences (no DataStore dep).
 *
 * Each preference is exposed as a [StateFlow] for Compose to observe; mutators
 * write through to disk and update the flow synchronously.
 *
 * Per-source toggles only take effect at the next Start cycle — flipping a
 * source while scanning will NOT live-restart that scanner.
 */
class Settings private constructor(private val prefs: SharedPreferences) {

    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _wifiEnabled = MutableStateFlow(prefs.getBoolean(KEY_WIFI, true))
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _deflockEnabled = MutableStateFlow(prefs.getBoolean(KEY_DEFLOCK, true))
    val deflockEnabled: StateFlow<Boolean> = _deflockEnabled.asStateFlow()

    private val _wazeEnabled = MutableStateFlow(prefs.getBoolean(KEY_WAZE, true))
    val wazeEnabled: StateFlow<Boolean> = _wazeEnabled.asStateFlow()

    private val _deflockProximityM = MutableStateFlow(
        prefs.getInt(KEY_DEFLOCK_PROX, DEFAULT_DEFLOCK_PROX)
    )
    val deflockProximityM: StateFlow<Int> = _deflockProximityM.asStateFlow()

    private val _wazeProximityM = MutableStateFlow(
        prefs.getInt(KEY_WAZE_PROX, DEFAULT_WAZE_PROX)
    )
    val wazeProximityM: StateFlow<Int> = _wazeProximityM.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setBleEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_BLE, v) }; _bleEnabled.value = v }
    fun setWifiEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WIFI, v) }; _wifiEnabled.value = v }
    fun setDeflockEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_DEFLOCK, v) }; _deflockEnabled.value = v }
    fun setWazeEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WAZE, v) }; _wazeEnabled.value = v }

    fun setDeflockProximityM(v: Int) {
        val clamped = v.coerceIn(50, 1600)
        prefs.edit { putInt(KEY_DEFLOCK_PROX, clamped) }
        _deflockProximityM.value = clamped
    }

    fun setWazeProximityM(v: Int) {
        val clamped = v.coerceIn(100, 5000)
        prefs.edit { putInt(KEY_WAZE_PROX, clamped) }
        _wazeProximityM.value = clamped
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    companion object {
        private const val PREFS = "overwatch_settings"
        private const val KEY_BLE = "src_ble"
        private const val KEY_WIFI = "src_wifi"
        private const val KEY_DEFLOCK = "src_deflock"
        private const val KEY_WAZE = "src_waze"
        private const val KEY_DEFLOCK_PROX = "deflock_proximity_m"
        private const val KEY_WAZE_PROX = "waze_proximity_m"
        private const val KEY_THEME = "theme_mode"

        const val DEFAULT_DEFLOCK_PROX = 200
        const val DEFAULT_WAZE_PROX = 500

        @Volatile private var INSTANCE: Settings? = null

        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ).also { INSTANCE = it }
        }
    }
}
