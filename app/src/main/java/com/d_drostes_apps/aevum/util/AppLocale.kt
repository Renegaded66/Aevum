package com.d_drostes_apps.aevum.util

import java.util.Locale

/**
 * L10N-RUNTIME-FIX: App-weites Locale für Nicht-Ressourcen-Formatierung
 * (Wochentagsnamen, Zahl-/Datumsformate in TimeFormatting, Kacheln etc.).
 *
 * Wird von [LocaleHelper.applyLocale] bei JEDEM Sprachwechsel aktualisiert —
 * damit laufen auch JVM-seitige Formatter (ohne Android-Context) in der
 * passenden Sprache statt hartkodiert auf Deutsch. Zusätzlich wird
 * [Locale.setDefault] mitgezogen, damit legacy `Locale.getDefault()`-
 * Stellen (z. B. CalendarScreen-Monatsname, DateTimeFormatter.ofPattern
 * mit Default) dieselbe Sprache verwenden.
 *
 * Default GERMAN = bisheriges Verhalten (Tests + allererste Zugriffe vor
 * dem ersten Locale-Update).
 */
object AppLocale {

    /** System-Sprache zum Prozessstart (bevor irgendwelche Overrides greifen). */
    private val systemAtStartup: Locale = Locale.getDefault()

    @Volatile
    var current: Locale = Locale.GERMAN
        private set

    /**
     * Setzt das effektive App-Locale. [locale] == null → "system" gewählt;
     * dann gilt die System-Sprache vom Prozessstart.
     */
    fun update(locale: Locale?) {
        current = locale ?: systemAtStartup
        Locale.setDefault(current)
    }
}