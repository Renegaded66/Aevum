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
 */
abstract class LocalizedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val language = getSharedPreferences(LanguageRepository.PREFS_NAME, MODE_PRIVATE)
            .getString(LanguageRepository.PREFS_KEY, LanguageRepository.LANGUAGE_SYSTEM)
            ?: LanguageRepository.LANGUAGE_SYSTEM
        LocaleHelper.applyLocale(this, language)
        super.onCreate(savedInstanceState)
    }
}
