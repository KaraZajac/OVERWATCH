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
class Settings private constructor(
    private val prefs: SharedPreferences,
    private val appContext: Context
) {

    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    /**
     * Which backend the WAZE source reads from.
     *
     * [OPENWEB_NINJA] is the default and stays the default: it costs money but
     * tells Waze nothing about the user, because the request is made by OpenWeb
     * Ninja's servers rather than the phone. [DIRECT] is free and live but speaks
     * the Waze app's own protocol, which means registering an anonymous Waze
     * account and sending a (jittered) position on every poll. That is a real
     * disclosure to a Google service, so it is never selected on the user's
     * behalf — they choose it in Settings after reading what it does.
     */
    enum class WazeBackend { OPENWEB_NINJA, DIRECT }

    private val _bleEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLE, true))
    val bleEnabled: StateFlow<Boolean> = _bleEnabled.asStateFlow()

    private val _wifiEnabled = MutableStateFlow(prefs.getBoolean(KEY_WIFI, true))
    val wifiEnabled: StateFlow<Boolean> = _wifiEnabled.asStateFlow()

    private val _deflockEnabled = MutableStateFlow(prefs.getBoolean(KEY_DEFLOCK, true))
    val deflockEnabled: StateFlow<Boolean> = _deflockEnabled.asStateFlow()

    private val _wazeEnabled = MutableStateFlow(prefs.getBoolean(KEY_WAZE, true))
    val wazeEnabled: StateFlow<Boolean> = _wazeEnabled.asStateFlow()

    private val _aircraftEnabled = MutableStateFlow(prefs.getBoolean(KEY_AIRCRAFT, true))
    val aircraftEnabled: StateFlow<Boolean> = _aircraftEnabled.asStateFlow()

    private val _micEnabled = MutableStateFlow(prefs.getBoolean(KEY_MIC, true))
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    // One radius for every location-driven source (DeFlock + Waze). Two
    // separate sliders meant the map had to visualise the larger of them, so
    // the circle rarely matched what either source was actually using.
    private val _detectionRadiusM = MutableStateFlow(
        prefs.getInt(KEY_DETECTION_RADIUS, DEFAULT_DETECTION_RADIUS)
    )
    val detectionRadiusM: StateFlow<Int> = _detectionRadiusM.asStateFlow()

    // The user's own OpenWeb Ninja API key for the Waze feed. Stored encrypted
    // (Keystore), never baked into the APK, so a published build carries no
    // credential and every install authenticates as its own owner.
    private val _wazeApiKey = MutableStateFlow(SecureStore.get(appContext, KEY_WAZE_API_KEY) ?: "")
    val wazeApiKey: StateFlow<String> = _wazeApiKey.asStateFlow()

    private val _wazeBackend = MutableStateFlow(
        runCatching { WazeBackend.valueOf(prefs.getString(KEY_WAZE_BACKEND, null) ?: "") }
            .getOrDefault(WazeBackend.OPENWEB_NINJA)
    )
    val wazeBackend: StateFlow<WazeBackend> = _wazeBackend.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _vibrateOnAlert = MutableStateFlow(prefs.getBoolean(KEY_VIBRATE, true))
    val vibrateOnAlert: StateFlow<Boolean> = _vibrateOnAlert.asStateFlow()

    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY, false))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    fun setBleEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_BLE, v) }; _bleEnabled.value = v }
    fun setWifiEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WIFI, v) }; _wifiEnabled.value = v }
    fun setDeflockEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_DEFLOCK, v) }; _deflockEnabled.value = v }
    fun setWazeEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_WAZE, v) }; _wazeEnabled.value = v }
    fun setAircraftEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_AIRCRAFT, v) }; _aircraftEnabled.value = v }
    fun setMicEnabled(v: Boolean) { prefs.edit { putBoolean(KEY_MIC, v) }; _micEnabled.value = v }

    fun setDetectionRadiusM(v: Int) {
        val clamped = v.coerceIn(RADIUS_MIN, RADIUS_MAX)
        prefs.edit { putInt(KEY_DETECTION_RADIUS, clamped) }
        _detectionRadiusM.value = clamped
    }

    fun setWazeApiKey(v: String) {
        val t = v.trim()
        SecureStore.put(appContext, KEY_WAZE_API_KEY, t)
        _wazeApiKey.value = t
    }

    init {
        // One-time cleanup: v0.5.5 and earlier stored a token for the retired
        // api.blackflagintel.com proxy. It authenticates nothing now, so purge it
        // rather than leave a dead secret sitting in the store. (Empty value =
        // remove, per SecureStore.put.)
        SecureStore.put(appContext, KEY_LEGACY_WAZE_PROXY_TOKEN, "")
    }

    fun setWazeBackend(v: WazeBackend) {
        prefs.edit { putString(KEY_WAZE_BACKEND, v.name) }
        _wazeBackend.value = v
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    fun setVibrateOnAlert(v: Boolean) {
        prefs.edit { putBoolean(KEY_VIBRATE, v) }
        _vibrateOnAlert.value = v
    }

    fun setOverlayEnabled(v: Boolean) {
        prefs.edit { putBoolean(KEY_OVERLAY, v) }
        _overlayEnabled.value = v
    }

    companion object {
        private const val PREFS = "overwatch_settings"
        private const val KEY_BLE = "src_ble"
        private const val KEY_WIFI = "src_wifi"
        private const val KEY_DEFLOCK = "src_deflock"
        private const val KEY_WAZE = "src_waze"
        private const val KEY_AIRCRAFT = "src_aircraft"
        private const val KEY_MIC = "src_mic"
        private const val KEY_DETECTION_RADIUS = "detection_radius_m"
        private const val KEY_WAZE_API_KEY = "waze_api_key"
        private const val KEY_WAZE_BACKEND = "waze_backend"
        private const val KEY_LEGACY_WAZE_PROXY_TOKEN = "waze_proxy_token"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_VIBRATE = "vibrate_on_alert"
        private const val KEY_OVERLAY = "overlay_enabled"

        const val DEFAULT_DETECTION_RADIUS = 500
        const val RADIUS_MIN = 100
        const val RADIUS_MAX = 5000

        @Volatile private var INSTANCE: Settings? = null

        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                context.applicationContext
            ).also { INSTANCE = it }
        }
    }
}
