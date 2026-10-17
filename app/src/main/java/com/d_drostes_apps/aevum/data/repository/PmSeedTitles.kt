package com.d_drostes_apps.aevum.data.repository

import android.content.Context
import com.d_drostes_apps.aevum.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * M19.2: Lokalisierte Titel der Beispielstruktur (Vorlage).
 *
 * WARUM ALS EIGENE SCHNITTSTELLE: [com.d_drostes_apps.aevum.domain.projects.PmSeed]
 * bleibt REIN (kein Android, JVM-testbar) und bekommt die Titel als Funktion
 * herein gereicht. Im echten Betrieb liefert diese Implementierung die zur
 * Gerätesprache passende Fassung aus den String-Ressourcen (Deutsch in
 * `values-de/`, Englisch als Standard in `values/`) — eine Vorlage mit
 * deutschen Titeln auf einem englischen Telefon wäre im Play Store peinlich.
 *
 * Tests können die Schnittstelle mit einem reinen Lambda bedienen
 * ([PmSeedTitles] ist ein `fun interface`).
 */
fun interface PmSeedTitles {
    fun titleOf(key: String): String
}

/** Android-Umsetzung: Schlüssel → String-Ressource. */
class AndroidPmSeedTitles @Inject constructor(
    @ApplicationContext private val context: Context
) : PmSeedTitles {

    override fun titleOf(key: String): String {
        val res = when (key) {
            // Haushalt & Alltag
            "alltag" -> R.string.pm_seed_alltag
            "wohnung" -> R.string.pm_seed_wohnung
            "einkauf" -> R.string.pm_seed_einkauf
            "aufraeumen" -> R.string.pm_seed_aufraeumen
            "waesche" -> R.string.pm_seed_waesche
            "papiere" -> R.string.pm_seed_papiere
            "termine" -> R.string.pm_seed_termine
            // Arbeit & Ausbildung
            "arbeit" -> R.string.pm_seed_arbeit
            "projekt" -> R.string.pm_seed_projekt
            "schritt" -> R.string.pm_seed_schritt
            "wochenziele" -> R.string.pm_seed_wochenziele
            "offenepunkte" -> R.string.pm_seed_offenepunkte
            "ergebnis" -> R.string.pm_seed_ergebnis
            "lernen" -> R.string.pm_seed_lernen
            "lernziel" -> R.string.pm_seed_lernziel
            "lektion" -> R.string.pm_seed_lektion
            // Gesundheit
            "gesundheit" -> R.string.pm_seed_gesundheit
            "sport" -> R.string.pm_seed_sport
            "spaziergang" -> R.string.pm_seed_spaziergang
            "vorsorge" -> R.string.pm_seed_vorsorge
            // Finanzen
            "finanzen" -> R.string.pm_seed_finanzen
            "budget" -> R.string.pm_seed_budget
            "rechnungen" -> R.string.pm_seed_rechnungen
            "vertraege" -> R.string.pm_seed_vertraege
            else -> return key
        }
        return context.getString(res)
    }
}
