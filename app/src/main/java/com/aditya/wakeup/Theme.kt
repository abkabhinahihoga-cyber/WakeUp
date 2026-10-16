package com.aditya.wakeup

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun WakeUpTheme(mode: String = "System", content: @Composable () -> Unit) {
    val dark = mode == "Dark" || (mode == "System" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFB7F36B), onPrimary = Color(0xFF18250B),
        primaryContainer = Color(0xFF30472C), onPrimaryContainer = Color(0xFFE1FBCF), secondaryContainer = Color(0xFF253A3D),
        background = Color(0xFF101510), surface = Color(0xFF192019), surfaceVariant = Color(0xFF283128),
        secondary = Color(0xFFCCD7BD), onBackground = Color(0xFFF1F5EA), onSurface = Color(0xFFF1F5EA)
    ) else lightColorScheme(
        primary = Color(0xFF456B20), onPrimary = Color.White,
        primaryContainer = Color(0xFFE0F0CA), onPrimaryContainer = Color(0xFF1C3214), secondaryContainer = Color(0xFFD4E8E2),
        background = Color(0xFFF4F6EE), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE3EAD9),
        secondary = Color(0xFF58654B), onBackground = Color(0xFF182117), onSurface = Color(0xFF182117)
    )
    MaterialTheme(colorScheme = colors, content = content)
}
