package com.esp32wifiadapter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium dark palette — electric teal on deep navy
private val DarkColors = darkColorScheme(
    primary          = Color(0xFF00E5CC),   // electric teal
    onPrimary        = Color(0xFF001A17),
    primaryContainer = Color(0xFF003730),
    secondary        = Color(0xFF4FC3F7),   // sky blue accent
    onSecondary      = Color(0xFF001E2D),
    background       = Color(0xFF060D14),   // deep navy
    onBackground     = Color(0xFFE0F7FA),
    surface          = Color(0xFF0D1B2A),
    onSurface        = Color(0xFFCAE9F5),
    surfaceVariant   = Color(0xFF142436),
    error            = Color(0xFFFF5370),
    onError          = Color(0xFF200000)
)

private val LightColors = lightColorScheme(
    primary          = Color(0xFF006B5E),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF74F8E1),
    secondary        = Color(0xFF0277BD),
    background       = Color(0xFFF0FFFE),
    surface          = Color(0xFFFFFFFF),
    error            = Color(0xFFB00020)
)

@Composable
fun ESP32WifiAdapterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content     = content
    )
}
