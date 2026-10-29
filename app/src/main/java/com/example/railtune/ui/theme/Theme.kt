package com.example.railtune.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF65CFFF),
    onPrimary = Color(0xFF082136),
    primaryContainer = Color(0xFF17283D),
    onPrimaryContainer = Color(0xFFF1F6FF),
    secondary = Color(0xFF55B9E8),
    onSecondary = Color(0xFF071B28),
    secondaryContainer = Color(0xFF172B3D),
    onSecondaryContainer = Color(0xFFD8EAF7),
    tertiary = Color(0xFFFFAB70),
    onTertiary = Color(0xFF2A1608),
    tertiaryContainer = Color(0xFF482B1D),
    onTertiaryContainer = Color(0xFFFFE4CF),
    error = Color(0xFFFF716C),
    onError = Color(0xFF2B0B0A),
    errorContainer = Color(0xFF4A1B22),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFEAF1FC),
    surface = Color(0xFF101827),
    onSurface = Color(0xFFEAF1FC),
    surfaceVariant = Color(0xFF121C2C),
    onSurfaceVariant = Color(0xFF9AAAC1),
    outline = Color(0xFF52647F),
    outlineVariant = Color(0xFF293A53),
    inverseSurface = Color(0xFFEAF1FC),
    inverseOnSurface = Color(0xFF192231),
    inversePrimary = Color(0xFF17618A),
    scrim = Color(0xFF000000)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00897B),
    secondary = Color(0xFF0288D1),
    tertiary = Color(0xFFF57C00),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun RailTuneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
