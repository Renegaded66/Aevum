package com.d_drostes_apps.aevum.util

import androidx.annotation.StringRes
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.repository.LanguageRepository

/**
 * Zentrale Liste aller wählbaren App-Sprachen.
 *
 * WARUM EINE EIGENE KATALOG-DATEI:
 * Sprache taucht an drei Stellen auf — Onboarding (Seite 1), Einstellungen
 * (Dropdown) und Sprach-Auflösung ([LocaleHelper]). Vorher stand die Liste
 * dreimal separat im Code (nur DE/EN), und jede Erweiterung hätte drei
 * Stellen getroffen. Jetzt gibt es genau eine Quelle: [AppLanguage.entries].
 *
 * [labelRes] ist bewusst ein ENDONYM (die Sprache in ihrer eigenen Sprache,
 * z. B. „Español"). Endonyme werden NICHT übersetzt — ein Nutzer, der nur
 * Spanisch liest, findet sein Land in jeder Oberfläche wieder. Die
 * Ressourcen-Dateien enthalten diese Werte deshalb in allen Sprachen
 * identisch (Ausnahme: [SYSTEM], das übersetzt wird).
 *
 * [localeTag] ist der BCP-47-Tag für [java.util.Locale.forLanguageTag].
 * `null` bedeutet: kein erzwungenes Locale — die App folgt der
 * Systemsprache (Eintrag „System").
 */
enum class AppLanguage(
    val code: String,
    val flag: String,
    @StringRes val labelRes: Int,
    val localeTag: String?
) {
    ENGLISH(LanguageRepository.LANGUAGE_EN, "🇬🇧", R.string.language_en, "en"),
    DANISH(LanguageRepository.LANGUAGE_DA, "🇩🇰", R.string.language_da, "da"),
    GERMAN(LanguageRepository.LANGUAGE_DE, "🇩🇪", R.string.language_de, "de"),
    SPANISH(LanguageRepository.LANGUAGE_ES, "🇪🇸", R.string.language_es, "es"),
    FRENCH(LanguageRepository.LANGUAGE_FR, "🇫🇷", R.string.language_fr, "fr"),
    ITALIAN(LanguageRepository.LANGUAGE_IT, "🇮🇹", R.string.language_it, "it"),
    NORWEGIAN(LanguageRepository.LANGUAGE_NB, "🇳🇴", R.string.language_nb, "nb"),
    POLISH(LanguageRepository.LANGUAGE_PL, "🇵🇱", R.string.language_pl, "pl"),
    PORTUGUESE(LanguageRepository.LANGUAGE_PT, "🇵🇹", R.string.language_pt, "pt"),
    SWEDISH(LanguageRepository.LANGUAGE_SV, "🇸🇪", R.string.language_sv, "sv"),
    TURKISH(LanguageRepository.LANGUAGE_TR, "🇹🇷", R.string.language_tr, "tr"),
    SYSTEM(LanguageRepository.LANGUAGE_SYSTEM, "🌐", R.string.language_system, null);

    companion object {

        /**
         * Sprachen, die im Onboarding zur Auswahl stehen — alle konkreten
         * Sprachen, ohne „System" (dort ist die App-Sprache noch nicht
         * gesetzt; ein „System"-Eintrag würde die erste Seite verwirren).
         *
         * Reihenfolge: Englisch zuerst (Default der App), danach alphabetisch
         * nach Endonym — so findet jeder seine Sprache, ohne zu suchen.
         */
        val selectable: List<AppLanguage> = entries.filter { it != SYSTEM }

        /** Alle Einträge inklusive „System" — für die Einstellungen. */
        val all: List<AppLanguage> = entries.toList()

        fun byCode(code: String): AppLanguage? = entries.firstOrNull { it.code == code }

        /** Gültiger Code? Unbekannte Codes fallen auf den Default zurück. */
        fun isValidCode(code: String): Boolean = byCode(code) != null

        /** Alle konkreten Sprach-Tags (ohne „System") — für Seed-Namen. */
        val localeTags: List<String> = selectable.mapNotNull { it.localeTag }
    }
}