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
     *
     * L10N-RUNTIME-FIX: Wird der Helper mit einem Activity-Context
     * aufgerufen, wird der Application-Kontext mitgesynchronisiert.
     * ViewModel-Strings (application.getString(...)) überleben ein
     * Activity-recreate() — ohne dieses Update würden sie in der
     * Sprache des Kaltstarts einfrieren (Dashboard-Narrative, Insights-/
     * Weekly-Texte, Timeline-Labels blieben Deutsch trotz "English").
     * `applicationContext !== context` bricht die Rekursion ab: Beim
     * Aufruf MIT dem Application-Kontext (AevumApplication.onCreate)
     * ist die Bedingung false — kein zweiter Update.
     */
    fun applyLocale(context: Context, languageCode: String) {
        val locale = localeFor(languageCode)
        // L10N-RUNTIME-FIX: App-weites Locale für JVM-Formatter
        // (Wochentagsnamen, Zahlen) mitsynchronisieren — "system" folgt
        // der Systemsprache.
        com.d_drostes_apps.aevum.util.AppLocale.update(locale)
        val resolved = locale ?: return
        val config = Configuration(context.resources.configuration)
        config.setLocale(resolved)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        val app = context.applicationContext
        if (app != null && app !== context) {
            val appConfig = Configuration(app.resources.configuration)
            appConfig.setLocale(resolved)
            app.resources.updateConfiguration(appConfig, app.resources.displayMetrics)
        }
    }

    /** Wendet die Sprache auf den Application-Context an (für onCreate). */
    fun applyToApplication(application: android.app.Application, languageCode: String) {
        applyLocale(application, languageCode)
    }
}