package com.d_drostes_apps.aevum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * M19.2 — feste Eisblau/Feuerrot-Identität statt Standard-Look (Nutzerwunsch).
 *
 * Der frühere Lila/Teal-Look war ein generisches Material-Schema und machte
 * die App austauschbar. Jetzt gilt: Dunkel ist tiefes Nachtblau, primary ist
 * glasklares Eisblau, secondary ist Feuerrot; Gold bleibt (wie im PM)
 * Auszeichnung, kein Grundton. Hell wickelt dieselbe Identität in Tagesform:
 * gedecktes Eisblau auf fast weißem Papier, Feuerrot als kräftiger Akzent.
 *
 * M19.3-Nachschärfung (Nutzer: „die rote theme Farbe soll noch etwas röter.
 * So feuerrot."): secondary ist von den weichen Salbei-Tönen (#F87171/#DC2626)
 * auf sattes Feuerrot (#FF3B2E dunkel / #D32614 hell) gezogen — mehr
 * Sättigung, tiefere Glut, deutlich als „Feuer" lesbar.
 *
 * `internal` bewusst statt `private`: AevumThemeTest pinnt die Kernfarben
 * in der reinen JVM-Suite (Compose.Color ist eine Value-Klasse, kein Android
 * nötig) — die Identität lässt sich so nicht unbemerkt verschieben.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),        // Eisblau — Hauptfarbe
    onPrimary = Color(0xFF06283D),
    primaryContainer = Color(0xFF0D3D5C),
    onPrimaryContainer = Color(0xFFCDEAFF),
    secondary = Color(0xFFFF3B2E),      // Feuerrot, nachgeschärft (M19.3)
    onSecondary = Color(0xFF4A0B0B),
    secondaryContainer = Color(0xFF6E140B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFFBBF24),       // Gold — Auszeichnung, kein Grundton
    onTertiary = Color(0xFF3D2A00),
    tertiaryContainer = Color(0xFF5A430B),
    onTertiaryContainer = Color(0xFFFFE2A6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF080D17),
    onBackground = Color(0xFFE6EEF8),
    surface = Color(0xFF101726),
    onSurface = Color(0xFFE6EEF8),
    surfaceVariant = Color(0xFF1A2436),
    onSurfaceVariant = Color(0xFFB7C4D6),
    outline = Color(0xFF8496AB),
    outlineVariant = Color(0xFF334056),
    scrim = Color.Black,
    inverseSurface = Color(0xFFE6EEF8),
    inverseOnSurface = Color(0xFF1B2433),
    inversePrimary = Color(0xFF0369A1),
    surfaceTint = Color(0xFF7DD3FC)
)

/** Das helle Gegenstück: dasselbe Eis in Tagesform, dasselbe Feuer. */
internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0369A1),        // Eisblau (gedeckt, lesbar)
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EDFF),
    onPrimaryContainer = Color(0xFF00344D),
    secondary = Color(0xFFD32614),      // Feuerrot, nachgeschärft (M19.3)
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1DD),
    onSecondaryContainer = Color(0xFF5C120C),
    tertiary = Color(0xFFB87500),       // Gold
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8B2),
    onTertiaryContainer = Color(0xFF3D2A00),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5F9FF),
    onBackground = Color(0xFF0C1B2A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C1B2A),
    surfaceVariant = Color(0xFFE3EDF8),
    onSurfaceVariant = Color(0xFF44566B),
    outline = Color(0xFF74869B),
    outlineVariant = Color(0xFFC6D3E1),
    scrim = Color.Black,
    inverseSurface = Color(0xFF2C3138),
    inverseOnSurface = Color(0xFFEEF1F6),
    inversePrimary = Color(0xFF7DD3FC),
    surfaceTint = Color(0xFF0369A1)
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

/**
 * M18.106: Theme-aus-Settings-Wrapper. Liest das persistierte Theme
 * (dark/light/system, Default dark) und reicht es an [AevumTheme] weiter.
 * Ruft der User nichts auf, bleibt alles beim alten Verhalten — die
 * Previews nutzen direkt AevumTheme(darkTheme = true) weiter.
 *
 * Der synchron gespiegelte Wert (SharedPreferences) entscheidet sofort
 * beim ersten Frame — kein Theme-Flacker beim App-Start.
 */
@Composable
fun AevumAppTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    // Synchroner Read im ersten Frame (KEIN State): Das Theme darf beim
    // Starten nicht asynchron nachziehen, sonst blitzt das falsche Theme.
    // Änderungen in den Settings lösen recreate() aus (gleiche Methode wie
    // beim Sprachwechsel) — der Read hier läuft danach mit dem neuen Wert.
    val theme = rememberThemePreference(appContext)
    val darkTheme = when (theme) {
        com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_LIGHT -> false
        com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_SYSTEM ->
            androidx.compose.foundation.isSystemInDarkTheme()
        else -> true // THEME_DARK (Default)
    }
    AevumTheme(darkTheme = darkTheme, content = content)
}

/** Liest das synchron gepiegelte Theme. recreate() (nach Theme-/Sprach-
 *  Wechsel) baut die Komposition komplett neu auf — der Read läuft
 *  danach automatisch mit dem frischen Spiegel erneut. */
@Composable
private fun rememberThemePreference(appContext: android.content.Context): String {
    return androidx.compose.runtime.remember {
        try {
            appContext.getSharedPreferences(
                com.d_drostes_apps.aevum.data.repository.ThemeRepository.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ).getString(
                com.d_drostes_apps.aevum.data.repository.ThemeRepository.PREFS_KEY,
                com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DEFAULT
            ) ?: com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DEFAULT
        } catch (e: Exception) {
            com.d_drostes_apps.aevum.data.repository.ThemeRepository.THEME_DEFAULT
        }
    }
}