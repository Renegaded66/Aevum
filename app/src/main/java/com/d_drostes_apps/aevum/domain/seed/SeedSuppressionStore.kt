package com.d_drostes_apps.aevum.domain.seed

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merkt sich, welche Standard-Einträge (Kategorien/Aktivitäten/Tags) der
 * Nutzer NICHT haben will.
 *
 * WARUM ES DIESE DATEI GIBT:
 * [EnsureDefaultDataUseCase] legt bei jedem App-Start fehlende Seeds neu an.
 * Ein einfaches Löschen im Onboarding wäre damit wirkungslos — die gerade
 * entfernte Kategorie stünde beim nächsten Start wieder da (Symptom: „meine
 * Auswahl wird ignoriert"). Deshalb wird eine Entfernung ZUSÄTZLICH hier
 * vermerkt; der Seed-Lauf überspringt diese IDs dauerhaft.
 *
 * Bewusst eine Sperrliste und kein „deleted"-Flag in der Datenbank:
 *  - Die Tabellen bleiben unverändert (keine Migration, keine Schema-Version).
 *  - Es betrifft ausschließlich Seed-IDs; selbst angelegte Einträge sind
 *    nicht betroffen und werden ganz normal gelöscht.
 *  - SharedPreferences genügt: die Information ist klein, lokal und wird
 *    beim Start synchron gebraucht (der Seed-Lauf ist synchron).
 */
@Singleton
class SeedSuppressionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        const val PREFS_NAME = "aevum_seed_suppression"
        const val KEY_IDS = "suppressed_seed_ids"

        /**
         * Namensraum der Sperrliste.
         *
         * Kategorien und Aktivitäten teilen sich IDs („work", „sleep" …).
         * Ohne Präfix würde das Entfernen der Kategorie „Arbeit" auch die
         * Aktivität „Arbeit" dauerhaft unterdrücken — ein Fehler, der erst
         * beim nächsten Start auffällt.
         */
        fun categoryKey(id: String): String = "category:$id"
        fun activityKey(id: String): String = "activity:$id"
        fun tagKey(id: String): String = "tag:$id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** IDs, die beim Seed-Lauf übersprungen werden. */
    fun suppressedIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    fun isSuppressed(id: String): Boolean = id in suppressedIds()

    fun suppress(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // Kopie übergeben: SharedPreferences darf das übergebene Set nicht
        // selbst halten (es wird sonst von außen mutierbar).
        prefs.edit().putStringSet(KEY_IDS, suppressedIds() + ids).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_IDS).apply()
    }
}
