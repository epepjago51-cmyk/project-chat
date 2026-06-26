package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GlowLavender,
    secondary = CelestialViolet,
    tertiary = GlowAccent,
    background = DarkCelestialBg,
    surface = DarkCelestialCard,
    onPrimary = DarkCelestialBg,
    onSecondary = SlateTextLight,
    onTertiary = SlateTextLight,
    onBackground = SlateTextLight,
    onSurface = SlateTextLight
)

private val LightColorScheme = lightColorScheme(
    primary = CosmicIndigo,
    secondary = CelestialViolet,
    tertiary = GlowAccent,
    background = SoftLavenderBg,
    surface = androidx.compose.ui.graphics.Color.White,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = SlateTextDark,
    onBackground = SlateTextDark,
    onSurface = SlateTextDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to enforce our gorgeous custom styling
    content: @Composable () -> Unit,
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
        typography = Typography,
        content = content
    )
}
