package com.d_drostes_apps.aevum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.d_drostes_apps.aevum.data.repository.ThemeRepository
import com.d_drostes_apps.aevum.util.LocaleHelper

/**
 * Basisklasse für alle Activities: wendet die gespeicherte App-Sprache
 * VOR [super.onCreate] an, damit Compose/Ressourcen von Anfang an in
 * der richtigen Sprache aufgebaut werden.
 *
 * WICHTIG (Crash-Fix): Hier darf KEIN Hilt-@Inject-Feld stehen. Hilt
 * injiziert über einen OnContextAvailableListener, der erst in
 * [ComponentActivity.onCreate] (dispatchOnContextAvailable) gefeuert
 * wird — also NACH diesem onCreate. Ein Zugriff auf ein injiziertes
 * Feld vor super.onCreate() wirft UninitializedPropertyAccessException.
 * Deshalb: Sprache synchron aus dem SharedPreferences-Spiegel lesen
 * (derselbe Spiegel, den LanguageRepository bei jeder Änderung
 * mitschreibt).
 *
 * L10N-RUNTIME-FIX: applyLocale() hält zusätzlich den Application-Kontext
 * auf derselben Sprache (ViewModel-Strings überleben recreate() — Details
 * siehe LocaleHelper.applyLocale).
 */
abstract class LocalizedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val language = getSharedPreferences(LanguageRepository.PREFS_NAME, MODE_PRIVATE)
            .getString(LanguageRepository.PREFS_KEY, LanguageRepository.LANGUAGE_DEFAULT)
            ?: LanguageRepository.LANGUAGE_DEFAULT
        LocaleHelper.applyLocale(this, language)
        // M18.136: Edge-to-Edge VOR super.onCreate() aktivieren (Play-Action
        // "Die randlose Anzeige funktioniert möglicherweise nicht für alle
        // Nutzer").
        //
        // Aevum hat targetSdk 36. Damit erzwingt Android 15+ (API 35+)
        // randlose Anzeige: die App zeichnet HINTER Status- und
        // Navigationsleiste. Auf Android 14 und älter (API < 35) passiert
        // das NICHT automatisch — dort lief die App bisher mit
        // undurchsichtigen Systemleisten (die Themes setzen
        // android:statusBarColor = md_theme_dark_background).
        // enableEdgeToEdge() zieht die Abwärtskompatibilität nach: identisches
        // Verhalten auf allen API-Leveln, Systemleisten transparent und
        // Icon-Farben automatisch passend.
        //
        // Der Aufruf steht in der Basisklasse, damit ALLE Activities
        // (MainActivity, SwitchActivity, BlockActivity) ihn erben — eine
        // einzelne Activity ohne den Aufruf würde auf API < 35 als
        // einzige mit Leisten-Balken erscheinen.
        //
        // WICHTIG (Aevum-Eigenheit): SystemBarStyle.auto() leitet die
        // Icon-Farbe standardmäßig aus dem SYSTEM-Theme ab
        // (resources.configuration.uiMode). Aevum hat aber ein EIGENES,
        // davon unabhängiges Theme (ThemeRepository, Default = Dark).
        // Bei System-Hell + App-Dunkel wären die Icons sonst dunkel auf
        // dunklem Grund (unsichtbar) und umgekehrt. Deshalb wird der Stil
        // hier an Aevums Theme gebunden; nur bei "system" entscheidet
        // tatsächlich das System-Theme.
        val appTheme = getSharedPreferences(ThemeRepository.PREFS_NAME, MODE_PRIVATE)
            .getString(ThemeRepository.PREFS_KEY, ThemeRepository.THEME_DEFAULT)
            ?: ThemeRepository.THEME_DEFAULT
        val aevumIsDark = when (appTheme) {
            ThemeRepository.THEME_LIGHT -> false
            ThemeRepository.THEME_SYSTEM ->
                (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true // THEME_DARK (Default)
        }
        enableEdgeToEdge(
            statusBarStyle = aevumStatusBarStyle(aevumIsDark),
            navigationBarStyle = aevumSystemBarStyle(aevumIsDark)
        )
        super.onCreate(savedInstanceState)
    }

    /**
     * L10N-RUNTIME-FIX: Bei System-Configuration-Events (Dark-Mode-Toggle,
     * Schriftgrößen-/Tastaturwechsel etc.) liefert Android dieser Activity
     * eine NEUE Basiskonfiguration — der in [onCreate] gesetzte Locale-
     * Override wäre damit verloren. Deshalb: vor dem super-Aufruf die
     * gewählte Sprache synchron aus dem SharedPreferences-Spiegel wieder
     * anwenden (mutiert die neue Config zurück auf die gewählte Sprache).
     * Kein Hilt-Zugriff nötig — derselbe Spiegel, den LanguageRepository
     * bei jedem setLanguage mitschreibt. "system" → super unverändert.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val language = getSharedPreferences(LanguageRepository.PREFS_NAME, MODE_PRIVATE)
            .getString(LanguageRepository.PREFS_KEY, LanguageRepository.LANGUAGE_DEFAULT)
            ?: LanguageRepository.LANGUAGE_DEFAULT
        if (language != LanguageRepository.LANGUAGE_SYSTEM) {
            LocaleHelper.applyLocale(this, language)
        }
        super.onConfigurationChanged(newConfig)
    }

    companion object {
        /**
         * M18.136: Scrim-Farben für die Systemleisten auf API ≤ 28.
         *
         * Auf API 29+ bleiben beide Leisten transparent — das System
         * erzwingt dort den Kontrast selbst. Die Scrims greifen nur auf
         * älteren Geräten (dort gibt es nur die 3-/2-Button-Navigation, die
         * ohne Kontrast-Sicherung des Systems einen sichtbaren Hintergrund
         * braucht). Werte entsprechen den Plattform-Vorgaben
         * (DefaultLightScrim 0xE6FFFFFF / DefaultDarkScrim 0x801B1B1B).
         *
         * Die Statusleiste bekommt transparent, weil darunter immer die
         * vom Screen selbst gezeichnete Theme-Fläche liegt
         * (md_theme_dark_background bzw. helles Pendant).
         */
        private const val STATUS_BAR_SCRIM = android.graphics.Color.TRANSPARENT
        private const val NAV_DARK_SCRIM = 0x801B1B1B.toInt()
        private const val NAV_LIGHT_SCRIM = 0xE6FFFFFF.toInt()

        /**
         * M18.136: [SystemBarStyle] an Aevums Theme koppeln.
         *
         * WARUM NICHT [SystemBarStyle.dark]/[SystemBarStyle.light]:
         * Diese setzen `nightMode = MODE_NIGHT_YES/NO`. Die
         * Scrim-Berechnung ([SystemBarStyle.getScrimWithEnforcedContrast])
         * liefert dann auf API 29+ einen SICHTBAREN Scrim statt Transparenz
         * — die Randlosigkeit wäre damit wieder aufgehoben und der
         * Play-Hinweis nicht behoben.
         *
         * WARUM NICHT das parameterlose `enableEdgeToEdge()`:
         * dessen [SystemBarStyle.auto] leitet die Icon-Farbe (hell/dunkel)
         * aus dem SYSTEM-Theme ab (`uiMode`). Aevum hat ein eigenes Theme
         * (ThemeRepository, Default = Dark), das vom System unabhängig ist.
         * Bei System-Hell + App-Dunkel wären die Icons dunkel auf dunklem
         * Grund — also unsichtbar.
         *
         * LÖSUNG: `auto()` mit eigenen Scrims UND eigener
         * `detectDarkMode`-Funktion. Damit bleibt `nightMode` auf
         * MODE_NIGHT_AUTO → transparente Leisten auf API 29+ (System
         * sichert den Kontrast: `isNavigationBarContrastEnforced`), und die
         * Icon-Farbe folgt Aevums Theme statt dem System.
         */
        internal fun aevumSystemBarStyle(isDark: Boolean): SystemBarStyle =
            SystemBarStyle.auto(
                lightScrim = NAV_LIGHT_SCRIM,
                darkScrim = NAV_DARK_SCRIM,
                detectDarkMode = { isDark }
            )

        /**
         * M18.136: wie [aevumSystemBarStyle], aber mit transparentem Scrim
         * für die Statusleiste (siehe [STATUS_BAR_SCRIM]).
         */
        internal fun aevumStatusBarStyle(isDark: Boolean): SystemBarStyle =
            SystemBarStyle.auto(
                lightScrim = STATUS_BAR_SCRIM,
                darkScrim = STATUS_BAR_SCRIM,
                detectDarkMode = { isDark }
            )
    }
}