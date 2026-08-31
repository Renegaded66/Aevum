package com.d_drostes_apps.aevum

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
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
            .getString(LanguageRepository.PREFS_KEY, LanguageRepository.LANGUAGE_SYSTEM)
            ?: LanguageRepository.LANGUAGE_SYSTEM
        LocaleHelper.applyLocale(this, language)
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
            .getString(LanguageRepository.PREFS_KEY, LanguageRepository.LANGUAGE_SYSTEM)
            ?: LanguageRepository.LANGUAGE_SYSTEM
        if (language != LanguageRepository.LANGUAGE_SYSTEM) {
            LocaleHelper.applyLocale(this, language)
        }
        super.onConfigurationChanged(newConfig)
    }
}