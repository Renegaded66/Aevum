package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF1A1A2E),
    primaryContainer = Color(0xFF5B4CE6),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF001F1F),
    secondaryContainer = Color(0xFF00504D),
    onSecondaryContainer = Color(0xFFA0F0ED),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF534200),
    onTertiaryContainer = Color(0xFFFFDC6C),
    error = Color(0xFFF87171),
    onError = Color(0xFF4B0000),
    errorContainer = Color(0xFF7B0000),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF080A10),
    onBackground = Color(0xFFE6E6EB),
    surface = Color(0xFF121521),
    onSurface = Color(0xFFE6E6EB),
    surfaceVariant = Color(0xFF1B2030),
    onSurfaceVariant = Color(0xFFC4C6D6),
    outline = Color(0xFF8E90A1),
    outlineVariant = Color(0xFF4A4C5D),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE6E6EB),
    inverseOnSurface = Color(0xFF1A1C26),
    inversePrimary = Color(0xFF4A3BD9),
    surfaceTint = Color(0xFF8B7CFF)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6D5DF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E5FF),
    onPrimaryContainer = Color(0xFF251A7D),
    secondary = Color(0xFF14B8A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF9F6),
    onSecondaryContainer = Color(0xFF00201F),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEFD5),
    onTertiaryContainer = Color(0xFF3D2E00),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF4B0000),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C7CF),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2E3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFB8AEFF),
    surfaceTint = Color(0xFF6D5DF6)
)

@Composable
fun AevumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AevumTypography,
        content = content
    )
}