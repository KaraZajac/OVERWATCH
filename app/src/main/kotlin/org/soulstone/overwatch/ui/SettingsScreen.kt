package org.soulstone.overwatch.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.overwatch.data.settings.Settings
import org.soulstone.overwatch.scan.wazert.WazeRtClient

@Composable
fun SettingsScreen(
    settings: Settings,
    isRunning: Boolean,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
    val ble by settings.bleEnabled.collectAsState()
    val wifi by settings.wifiEnabled.collectAsState()
    val deflock by settings.deflockEnabled.collectAsState()
    val waze by settings.wazeEnabled.collectAsState()
    val aircraft by settings.aircraftEnabled.collectAsState()
    val mic by settings.micEnabled.collectAsState()
    val wazeApiKey by settings.wazeApiKey.collectAsState()
    val wazeBackend by settings.wazeBackend.collectAsState()
    val theme by settings.themeMode.collectAsState()
    val vibrate by settings.vibrateOnAlert.collectAsState()
    val overlay by settings.overlayEnabled.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Inset before the scroll container so content scrolls inside the
            // safe area rather than under the bars or a cutout.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(8.dp))
        SectionLabel("Detection sources")
        SourceToggle("BLE  •  Bluetooth Low Energy", ble) { settings.setBleEnabled(it) }
        SourceToggle("WIFI  •  WiFi BSSID + SSID", wifi) { settings.setWifiEnabled(it) }
        SourceToggle("DEFLOCK  •  ALPR map (Overpass)", deflock) { settings.setDeflockEnabled(it) }
        SourceToggle("WAZE  •  Live police reports", waze) { settings.setWazeEnabled(it) }
        SourceToggle("AIRCRAFT  •  Police / surveillance planes", aircraft) { settings.setAircraftEnabled(it) }
        SourceToggle("COMMERCIAL  •  Nest, Ring, Echo, glasses", mic) { settings.setMicEnabled(it) }
        Spacer(Modifier.height(8.dp))
        if (isRunning) {
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Restart scan to apply",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Text(
                "Source toggles take effect on next Start.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))

        Spacer(Modifier.height(16.dp))
        SectionLabel("Waze police feed")
        RadioRow(
            "OpenWeb Ninja  •  your API key",
            wazeBackend == Settings.WazeBackend.OPENWEB_NINJA
        ) { settings.setWazeBackend(Settings.WazeBackend.OPENWEB_NINJA) }
        RadioRow(
            "Direct from Waze  •  no key",
            wazeBackend == Settings.WazeBackend.DIRECT
        ) { settings.setWazeBackend(Settings.WazeBackend.DIRECT) }

        when (wazeBackend) {
            Settings.WazeBackend.OPENWEB_NINJA -> {
                HelpText(
                    "Bring your own key: sign up at openwebninja.com, subscribe to " +
                        "the Waze API, and paste the key here. Stored encrypted " +
                        "on-device — never in the app package. Pay-as-you-go runs " +
                        "about \$1-3/month. Waze never sees you: the request is made " +
                        "by OpenWeb Ninja, not your phone."
                )
                ApiKeyField(currentKey = wazeApiKey, onSave = { settings.setWazeApiKey(it) })
            }
            Settings.WazeBackend.DIRECT -> {
                HelpText(
                    "Free, live, no key: OVERWATCH speaks the Waze app's own " +
                        "protocol. It registers an anonymous Waze account (stored " +
                        "encrypted on-device) and sends your position — blurred by up " +
                        "to 500 m — with each poll, about once a minute.\n\n" +
                        "Waze is a Google service. Choosing this tells Google roughly " +
                        "where you are, the way running the Waze app would. Pick it " +
                        "only if that trade is worth a live feed to you."
                )
                WazeAccountControls()
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Alerts")
        SourceToggle("Vibrate on threat escalation", vibrate) { settings.setVibrateOnAlert(it) }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Display over other apps")
        SourceToggle("Floating threat circle", overlay) { enabled ->
            settings.setOverlayEnabled(enabled)
            // Special-access perm: can't be granted via runtime prompt. Bounce
            // the user to the system settings page for this app so they can
            // approve. The DetectionService re-checks canDrawOverlays at show()
            // time so a denied/revoked perm just means the bubble silently
            // doesn't appear — no crash.
            if (enabled && !AndroidSettings.canDrawOverlays(context)) {
                val intent = Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
        }
        if (overlay && !AndroidSettings.canDrawOverlays(context)) {
            Text(
                "Permission needed — system page should have opened. If not, grant manually under Apps → OVERWATCH → Display over other apps.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Appearance")
        RadioRow("System default", theme == Settings.ThemeMode.SYSTEM) {
            settings.setThemeMode(Settings.ThemeMode.SYSTEM)
        }
        RadioRow("Dark", theme == Settings.ThemeMode.DARK) {
            settings.setThemeMode(Settings.ThemeMode.DARK)
        }
        RadioRow("Light", theme == Settings.ThemeMode.LIGHT) {
            settings.setThemeMode(Settings.ThemeMode.LIGHT)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SourceToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f) reserves the remaining row width for the label so it
        // wraps on narrow screens instead of clipping under the Switch.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(end = 12.dp)
        )
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/**
 * Slider that commits the value to Settings only on drag-release. The label
 * tracks the live drag position locally to avoid spamming SharedPreferences
 * writes (and downstream StateFlow re-emissions) on every pixel of movement.
 */
@Composable
private fun SliderRow(
    label: String,
    persistedValue: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Int) -> Unit
) {
    var live by remember(persistedValue) { mutableFloatStateOf(persistedValue.toFloat()) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${live.toInt()} m",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live.toInt()) },
            valueRange = range,
            steps = steps
        )
    }
}

/**
 * Masked entry for the user's OpenWeb Ninja API key. Commits on Save (persisted
 * encrypted via Settings/SecureStore), with a show/hide toggle and a set/unset
 * status line.
 */
@Composable
private fun ApiKeyField(currentKey: String, onSave: (String) -> Unit) {
    var text by remember(currentKey) { mutableStateOf(currentKey) }
    var visible by remember { mutableStateOf(false) }
    val isSet = currentKey.isNotBlank()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("OpenWeb Ninja API key", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide API key" else "Show API key"
                    )
                }
            },
            textStyle = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace, fontSize = 13.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isSet) "API key set — Waze feed enabled" else "No API key — Waze feed off",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f, fill = true)
            )
            Button(
                onClick = { onSave(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Small explanatory paragraph under a setting. */
@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/**
 * State of the anonymous Waze account the direct backend mints, plus a way to
 * throw it away. Waze caps how many anonymous accounts a device may register per
 * day, so forgetting one is not free — the button says so rather than inviting
 * the user to tap it repeatedly.
 */
@Composable
private fun WazeAccountControls() {
    val context = LocalContext.current
    // Bumped after a forget, to re-read the store.
    var revision by remember { mutableStateOf(0) }
    val hasAccount = remember(revision) { WazeRtClient(context).hasAccount() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (hasAccount) "Anonymous Waze account stored"
                   else "No account yet — one is created on the first poll",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f, fill = true)
        )
        if (hasAccount) {
            Button(
                onClick = {
                    WazeRtClient(context).forgetAccount()
                    revision++
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Forget", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target, not just the radio circle: these rows
            // choose a data backend and a theme, and hunting a 20 dp dot on a phone
            // in a car is friction for no reason. `selectable` also gives the row
            // the right accessibility semantics for a radio group.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Null: the row above owns the click, so the button must not double-handle it.
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
