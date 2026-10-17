package com.aditya.wakeup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class ThemeOption(
    val id: String,
    val name: String,
    val primaryColor: Color,
    val backgroundColor: Color,
    val surfaceColor: Color
)

val WakeUpThemeOptions = listOf(
    ThemeOption("System", "System Default", Color(0xFF456B20), Color(0xFF101510), Color(0xFF192019)),
    ThemeOption("Light", "Light", Color(0xFF386A20), Color(0xFFF8FAF0), Color(0xFFFFFFFF)),
    ThemeOption("Dark", "Dark", Color(0xFFB7F36B), Color(0xFF101510), Color(0xFF192019)),
    ThemeOption("Midnight", "Midnight", Color(0xFF748FFC), Color(0xFF0B0F19), Color(0xFF121826)),
    ThemeOption("Forest", "Forest", Color(0xFF7CA982), Color(0xFF101712), Color(0xFF162118)),
    ThemeOption("Ocean", "Ocean", Color(0xFF4EA8DE), Color(0xFF0A131C), Color(0xFF111E2E)),
    ThemeOption("Warm", "Warm", Color(0xFFE08E45), Color(0xFF181412), Color(0xFF231D1A))
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF386A20), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F397), onPrimaryContainer = Color(0xFF042100),
    secondary = Color(0xFF55624C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7CB), onSecondaryContainer = Color(0xFF131F0D),
    background = Color(0xFFF8FAF0), onBackground = Color(0xFF191D17),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDFE4D6), onSurfaceVariant = Color(0xFF43483E),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7F36B), onPrimary = Color(0xFF18250B),
    primaryContainer = Color(0xFF30472C), onPrimaryContainer = Color(0xFFE1FBCF),
    secondary = Color(0xFFCCD7BD), onSecondary = Color(0xFF1B2317),
    secondaryContainer = Color(0xFF253A3D), onSecondaryContainer = Color(0xFFD2E8E4),
    background = Color(0xFF101510), onBackground = Color(0xFFF1F5EA),
    surface = Color(0xFF192019), onSurface = Color(0xFFF1F5EA),
    surfaceVariant = Color(0xFF283128), onSurfaceVariant = Color(0xFFC3CCC0),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

val MidnightColorScheme = darkColorScheme(
    primary = Color(0xFF748FFC), onPrimary = Color(0xFF0B102B),
    primaryContainer = Color(0xFF202A54), onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFF4DABF7), onSecondary = Color(0xFF00223D),
    secondaryContainer = Color(0xFF182C4A), onSecondaryContainer = Color(0xFFC5E4FF),
    background = Color(0xFF0B0F19), onBackground = Color(0xFFE6EDF8),
    surface = Color(0xFF121826), onSurface = Color(0xFFE6EDF8),
    surfaceVariant = Color(0xFF1C253B), onSurfaceVariant = Color(0xFFAEBACF),
    error = Color(0xFFFF6B6B), onError = Color(0xFF49000B),
    errorContainer = Color(0xFF5C101B), onErrorContainer = Color(0xFFFFD8D8)
)

val ForestColorScheme = darkColorScheme(
    primary = Color(0xFF7CA982), onPrimary = Color(0xFF0E2213),
    primaryContainer = Color(0xFF243B2B), onPrimaryContainer = Color(0xFFD5E8D8),
    secondary = Color(0xFFA3B899), onSecondary = Color(0xFF1A2614),
    secondaryContainer = Color(0xFF223126), onSecondaryContainer = Color(0xFFD0E0CE),
    background = Color(0xFF101712), onBackground = Color(0xFFE8EFE8),
    surface = Color(0xFF162118), onSurface = Color(0xFFE8EFE8),
    surfaceVariant = Color(0xFF213025), onSurfaceVariant = Color(0xFFB5C4B5),
    error = Color(0xFFE57373), onError = Color(0xFF4C0F0F),
    errorContainer = Color(0xFF5A1E1E), onErrorContainer = Color(0xFFFFCDD2)
)

val OceanColorScheme = darkColorScheme(
    primary = Color(0xFF4EA8DE), onPrimary = Color(0xFF00223A),
    primaryContainer = Color(0xFF143755), onPrimaryContainer = Color(0xFFCBE7FF),
    secondary = Color(0xFF64DFDF), onSecondary = Color(0xFF002F31),
    secondaryContainer = Color(0xFF123440), onSecondaryContainer = Color(0xFFBDEFF2),
    background = Color(0xFF0A131C), onBackground = Color(0xFFE2EEF8),
    surface = Color(0xFF111E2E), onSurface = Color(0xFFE2EEF8),
    surfaceVariant = Color(0xFF1A2E44), onSurfaceVariant = Color(0xFFA8C4DF),
    error = Color(0xFFFF6B6B), onError = Color(0xFF49000B),
    errorContainer = Color(0xFF5C101B), onErrorContainer = Color(0xFFFFD8D8)
)

val WarmColorScheme = darkColorScheme(
    primary = Color(0xFFE08E45), onPrimary = Color(0xFF351A00),
    primaryContainer = Color(0xFF543015), onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFD4A373), onSecondary = Color(0xFF332010),
    secondaryContainer = Color(0xFF3A2E27), onSecondaryContainer = Color(0xFFEBD2BE),
    background = Color(0xFF181412), onBackground = Color(0xFFF7EFE9),
    surface = Color(0xFF231D1A), onSurface = Color(0xFFF7EFE9),
    surfaceVariant = Color(0xFF332924), onSurfaceVariant = Color(0xFFCFC0B7),
    error = Color(0xFFE57373), onError = Color(0xFF4C0F0F),
    errorContainer = Color(0xFF5A1E1E), onErrorContainer = Color(0xFFFFCDD2)
)

fun getColorSchemeForTheme(mode: String, systemIsDark: Boolean): ColorScheme = when (mode) {
    "Light" -> LightColorScheme
    "Dark" -> DarkColorScheme
    "Midnight" -> MidnightColorScheme
    "Forest" -> ForestColorScheme
    "Ocean" -> OceanColorScheme
    "Warm" -> WarmColorScheme
    else -> if (systemIsDark) DarkColorScheme else LightColorScheme
}

@Composable
fun animateColorScheme(target: ColorScheme): ColorScheme {
    val context = LocalContext.current
    val animScale = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
    }

    val spec = if (animScale == 0f) snap() else tween<Color>(durationMillis = 240)

    val primary by animateColorAsState(target.primary, spec, label = "th_primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "th_onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "th_primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "th_onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "th_secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "th_onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "th_secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "th_onSecondaryContainer")
    val background by animateColorAsState(target.background, spec, label = "th_background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "th_onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "th_surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "th_onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "th_surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "th_onSurfaceVariant")
    val error by animateColorAsState(target.error, spec, label = "th_error")
    val onError by animateColorAsState(target.onError, spec, label = "th_onError")
    val errorContainer by animateColorAsState(target.errorContainer, spec, label = "th_errorContainer")
    val onErrorContainer by animateColorAsState(target.onErrorContainer, spec, label = "th_onErrorContainer")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer
    )
}

@Composable
fun WakeUpTheme(mode: String = "System", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val targetColors = getColorSchemeForTheme(mode, systemDark)
    val animatedColors = animateColorScheme(targetColors)
    MaterialTheme(colorScheme = animatedColors, content = content)
}
