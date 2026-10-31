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

    /**
     * Liefert das Locale für den gespeicherten Sprach-Code (oder null bei "system").
     *
     * M34: Alle zehn Sprachen laufen über [AppLanguage] — eine Quelle für
     * Code → BCP-47-Tag. Unbekannte Codes (alte Stände, kaputte Pref) fallen
     * auf `null` (= Systemsprache) zurück, statt die App in einen Zustand zu
     * bringen, den keine Übersetzung kennt.
     */
    fun localeFor(languageCode: String): Locale? {
        val tag = com.d_drostes_apps.aevum.util.AppLanguage.byCode(languageCode)?.localeTag
            ?: return null
        return Locale.forLanguageTag(tag)
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

    /**
     * Ressourcen in der gewünschten Sprache.
     *
     * Für Stellen, die einen Sprachwechsel OHNE Activity-Recreate zeigen
     * müssen (Onboarding: Seite 1 wählt die Sprache, die restlichen Seiten
     * sollen sofort in dieser Sprache erscheinen). Compose-Texte laufen dort
     * über diese Ressourcen statt über `stringResource`, weil dessen
     * `LocalContext` erst beim nächsten Recreate die neue Konfiguration sieht.
     */
    fun resourcesFor(context: Context, languageCode: String): android.content.res.Resources =
        applyTo(context, languageCode).resources

    /** Wendet die Sprache auf den Application-Context an (für onCreate). */
    fun applyToApplication(application: android.app.Application, languageCode: String) {
        applyLocale(application, languageCode)
    }
}