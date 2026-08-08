package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA99CFF),
    onPrimary = Color(0xFF171329),
    primaryContainer = Color(0xFF3A326B),
    onPrimaryContainer = Color(0xFFE8E4FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0xFF134D48),
    onSecondaryContainer = Color(0xFFA7F3E9),
    tertiary = Color(0xFFFFC85C),
    onTertiary = Color(0xFF2E2100),
    tertiaryContainer = Color(0xFF5A430B),
    onTertiaryContainer = Color(0xFFFFE2A6),
    error = Color(0xFFF87171),
    onError = Color(0xFF4B0000),
    errorContainer = Color(0xFF7B0000),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF090B16),
    onBackground = Color(0xFFF0EFF8),
    surface = Color(0xFF121526),
    onSurface = Color(0xFFF0EFF8),
    surfaceVariant = Color(0xFF1D2136),
    onSurfaceVariant = Color(0xFFCAC8D8),
    outline = Color(0xFF9491A5),
    outlineVariant = Color(0xFF3B4057),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE6E6EB),
    inverseOnSurface = Color(0xFF1A1C26),
    inversePrimary = Color(0xFF5446D5),
    surfaceTint = Color(0xFFA99CFF)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6756E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E6FF),
    onPrimaryContainer = Color(0xFF251A7D),
    secondary = Color(0xFF0F9F91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9F7F0),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFFB87500),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8B2),
    onTertiaryContainer = Color(0xFF3D2A00),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF4B0000),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1B27),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B27),
    surfaceVariant = Color(0xFFE8E6F0),
    onSurfaceVariant = Color(0xFF474653),
    outline = Color(0xFF777482),
    outlineVariant = Color(0xFFC9C6D3),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2E3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFC3BBFF),
    surfaceTint = Color(0xFF6756E8)
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