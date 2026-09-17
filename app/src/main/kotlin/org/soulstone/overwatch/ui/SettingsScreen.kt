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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.overwatch.data.settings.Settings

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
        Text(
            "Bring your own key: sign up at openwebninja.com, subscribe to the " +
                "Waze API, and paste the key here. Stored encrypted on-device — " +
                "never in the app package. Pay-as-you-go runs about \$1-3/month.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        ApiKeyField(currentKey = wazeApiKey, onSave = { settings.setWazeApiKey(it) })

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
        ThemeRadio("System default", theme == Settings.ThemeMode.SYSTEM) {
            settings.setThemeMode(Settings.ThemeMode.SYSTEM)
        }
        ThemeRadio("Dark", theme == Settings.ThemeMode.DARK) {
            settings.setThemeMode(Settings.ThemeMode.DARK)
        }
        ThemeRadio("Light", theme == Settings.ThemeMode.LIGHT) {
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

@Composable
private fun ThemeRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
