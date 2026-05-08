package org.soulstone.overwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val citizen by settings.citizenEnabled.collectAsState()
    val mic by settings.micEnabled.collectAsState()
    val deflockProx by settings.deflockProximityM.collectAsState()
    val citizenProx by settings.citizenProximityM.collectAsState()
    val theme by settings.themeMode.collectAsState()
    val vibrate by settings.vibrateOnAlert.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        SourceToggle("CITIZEN  •  Real-time incident feed", citizen) { settings.setCitizenEnabled(it) }
        SourceToggle("MIC  •  Smart speakers / cams (Echo, Ring, Nest)", mic) { settings.setMicEnabled(it) }
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

        SectionLabel("Proximity thresholds")
        SliderRow(
            label = "DeFlock alert distance",
            persistedValue = deflockProx,
            range = 50f..1600f,
            steps = 30,
            onCommit = { settings.setDeflockProximityM(it) }
        )
        SliderRow(
            label = "Citizen alert distance",
            persistedValue = citizenProx,
            range = 100f..5000f,
            steps = 48,
            onCommit = { settings.setCitizenProximityM(it) }
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("Alerts")
        SourceToggle("Vibrate on threat escalation", vibrate) { settings.setVibrateOnAlert(it) }

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
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
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
