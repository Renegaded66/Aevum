package com.d_drostes_apps.aevum.util

import android.content.Context
import android.content.res.Configuration
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import java.util.Locale

/**
 * Wendet die gespeicherte App-Sprache auf einen Context an.
 *
 * "system" → unverändert (folgt der Systemsprache).
 * "de"/"en" → erzwingt die Sprache, unabhängig von der Systemsprache.
 */
object LocaleHelper {

    /** Liefert das Locale für den gespeicherten Sprach-Code (oder null bei "system"). */
    fun localeFor(languageCode: String): Locale? = when (languageCode) {
        LanguageRepository.LANGUAGE_DE -> Locale.GERMAN
        LanguageRepository.LANGUAGE_EN -> Locale.ENGLISH
        else -> null
    }

    /**
     * Erzeugt einen Context mit der gewünschten Sprache.
     * Muss VOR dem ersten Zugriff auf Ressourcen/Compose aufgerufen werden.
     */
    fun applyTo(context: Context, languageCode: String): Context {
        val locale = localeFor(languageCode) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Wendet die Sprache direkt auf den Context an (mutierend, via
     * [Configuration.setLocale] + [updateConfiguration]). Anders als
     * [applyTo] wird hier KEIN neuer Context erzeugt — das ist für
     * Activities der richtige Weg, damit ALLE Ressourcen (auch bereits
     * gehaltene Referenzen auf this) sofort die neue Sprache liefern.
     */
    fun applyLocale(context: Context, languageCode: String) {
        val locale = localeFor(languageCode) ?: return
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /** Wendet die Sprache auf den Application-Context an (für onCreate). */
    fun applyToApplication(application: android.app.Application, languageCode: String) {
        applyLocale(application, languageCode)
    }
}
