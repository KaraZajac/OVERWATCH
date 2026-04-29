package org.soulstone.overwatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.soulstone.overwatch.data.settings.Settings

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1FAA59),
    onPrimary = Color.White,
    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFF4F6FA),
    surfaceVariant = Color(0xFF1E232C),
    onSurfaceVariant = Color(0xFF9AA3B2)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1FAA59),
    onPrimary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0B0E12),
    surface = Color.White,
    onSurface = Color(0xFF0B0E12)
)

object ThreatColors {
    val Green = Color(0xFF1FAA59)
    val Yellow = Color(0xFFF4C20D)
    val Orange = Color(0xFFF26B0F)
    val Red = Color(0xFFD7263D)
}

@Composable
fun OverwatchTheme(
    mode: Settings.ThemeMode = Settings.ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        Settings.ThemeMode.DARK -> true
        Settings.ThemeMode.LIGHT -> false
        Settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
