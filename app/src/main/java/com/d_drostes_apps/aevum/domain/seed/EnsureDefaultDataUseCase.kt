package com.d_drostes_apps.aevum.domain.seed

import android.content.Context
import android.util.Log
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.TagRepository
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import com.d_drostes_apps.aevum.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Legt die Standard-Kategorien/-Aktivitätstypen/-Tags an — in der AKTUELLEN
 * App-Sprache (Ressourcen strings_seeds.xml).
 *
 * Sprachwechsel: Existiert ein Seed bereits und sein Name ist noch ein
 * unveränderter Seed-Name (DE oder EN), wird er auf die aktuelle Sprache
 * aktualisiert. Hat der User den Namen umbenannt, bleibt er unangetastet.
 */
class EnsureDefaultDataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val tagRepository: TagRepository
) {
    suspend operator fun invoke() {
        val names = SeedNames(context)
        // M18.56: Jeder Seed-Schritt einzeln abgesichert — ein Fehler bei
        // Categories darf die ActivityTypes/Tags nicht blockieren (und
        // umgekehrt). Ohne diese Härtung führt ein einzelner DB-Fehler
        // dazu, dass ALLE Defaults fehlen (Symptom: "Schlaf/Arbeit/Sport
        // nicht vor eingestellt nach Neuinstallation").
        try {
            val existing = categoryRepository.getAll().first().associateBy { it.id }
            defaultCategories(names).forEach { seed ->
                val current = existing[seed.id]
                when {
                    current == null -> categoryRepository.insert(seed)
                    current.name in names.knownNames(seedNameKey(seed.id)) ->
                        categoryRepository.update(current.copy(name = seed.name))
                }
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "Categories-Seed fehlgeschlagen", e)
        }
        try {
            // M18.59: Nicht nur bei leerer Tabelle seeden — auch fehlende
            // System-Typen NACHträglich ergänzen (bestehende Installationen
            // bekommen so die neuen Garmin-Typen joggen/radfahren/spazieren).
            val existing = activityTypeRepository.getAll().first().associateBy { it.id }
            defaultActivityTypes(names).forEach { seed ->
                val current = existing[seed.id]
                when {
                    current == null -> activityTypeRepository.insert(seed)
                    current.name in names.knownNames(seedNameKey(seed.id)) ->
                        activityTypeRepository.update(current.copy(name = seed.name))
                }
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "ActivityTypes-Seed fehlgeschlagen", e)
        }
        try {
            val existing = tagRepository.getAll().first().associateBy { it.id }
            defaultTags(names).forEach { seed ->
                val current = existing[seed.id]
                when {
                    current == null -> tagRepository.insert(seed)
                    current.name in names.knownNames(seedNameKey(seed.id)) ->
                        tagRepository.update(current.copy(name = seed.name))
                }
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "Tags-Seed fehlgeschlagen", e)
        }
    }

    private fun defaultCategories(names: SeedNames) = listOf(
        Category("work", names.currentName(R.string.seed_category_work), "#6366F1", "◼", true, 10),
        Category("sleep", names.currentName(R.string.seed_category_sleep), "#334155", "◒", true, 20),
        Category("sport", names.currentName(R.string.seed_category_sport), "#22C55E", "◆", true, 30),
        Category("learning", names.currentName(R.string.seed_category_learning), "#0EA5E9", "◎", true, 40),
        Category("health", names.currentName(R.string.seed_category_health), "#2DD4BF", "✦", true, 50),
        Category("leisure", names.currentName(R.string.seed_category_leisure), "#F97316", "●", true, 60),
        Category("relationships", names.currentName(R.string.seed_category_relationships), "#EC4899", "♡", true, 70),
        Category("transport", names.currentName(R.string.seed_category_transport), "#F59E0B", "↗", true, 80),
        Category("digital", names.currentName(R.string.seed_category_digital), "#64748B", "▣", true, 90),
        Category("household", names.currentName(R.string.seed_category_household), "#A855F7", "⌂", true, 100),
        Category("unknown", names.currentName(R.string.seed_category_unknown), "#94A3B8", "…", true, 999)
    )

    private fun defaultActivityTypes(names: SeedNames) = listOf(
        // M18: Positivitäts-Scores (0-100). Bewusst gewählte Defaults:
        // Digital/Transport niedrig, Sport/Lernen/Meditation hoch.
        // Der User kann jeden Wert im Activity-Editor anpassen.
        // M18.12: Icons (Emoji) + Farben (ARGB-Int) pro Aktivität.
        ActivityType("work", names.currentName(R.string.seed_activity_work), "work", true, "{\"overlay\": false}", positivityScore = 50, icon = "💼", color = 0xFF5C6BC0.toLong()),
        ActivityType("deep_work", names.currentName(R.string.seed_activity_deep_work), "work", true, "{\"overlay\": false}", positivityScore = 80, icon = "🧠", color = 0xFF7E57C2.toLong()),
        ActivityType("sleep", names.currentName(R.string.seed_activity_sleep), "sleep", true, "{\"overlay\": false}", positivityScore = 70, icon = "🌙", color = 0xFF3949AB.toLong()),
        ActivityType("fitness", names.currentName(R.string.seed_activity_fitness), "sport", true, "{\"overlay\": false}", positivityScore = 85, icon = "🏋️", color = 0xFF43A047.toLong()),
        // M18.59: Garmin-Typen — joggen/radfahren/spazieren sind die
        // Ziel-Typen des Garmin-Imports (running/cycling/walking/hiking).
        // Vorher fiel alles auf "other" (User: "meine joggen Aktivität
        // wurde als sonstiges abgespeichert").
        ActivityType("joggen", names.currentName(R.string.seed_activity_joggen), "sport", true, "{\"overlay\": false}", positivityScore = 90, icon = "🏃", color = 0xFF26A69A.toLong()),
        ActivityType("radfahren", names.currentName(R.string.seed_activity_radfahren), "sport", true, "{\"overlay\": false}", positivityScore = 85, icon = "🚴", color = 0xFF29B6F6.toLong()),
        ActivityType("spazieren", names.currentName(R.string.seed_activity_spazieren), "sport", true, "{\"overlay\": false}", positivityScore = 70, icon = "🚶", color = 0xFF66BB6A.toLong()),
        ActivityType("learning", names.currentName(R.string.seed_activity_learning), "learning", true, "{\"overlay\": false}", positivityScore = 75, icon = "📚", color = 0xFF26A69A.toLong()),
        ActivityType("reading", names.currentName(R.string.seed_activity_reading), "leisure", true, "{\"overlay\": false}", positivityScore = 65, icon = "📖", color = 0xFF8D6E63.toLong()),
        ActivityType("meditation", names.currentName(R.string.seed_activity_meditation), "health", true, "{\"overlay\": false}", positivityScore = 90, icon = "🧘", color = 0xFF66BB6A.toLong()),
        ActivityType("eating", names.currentName(R.string.seed_activity_eating), "leisure", true, "{\"overlay\": false}", positivityScore = 45, icon = "🍽️", color = 0xFFFFA726.toLong()),
        ActivityType("social", names.currentName(R.string.seed_activity_social), "relationships", true, "{\"overlay\": false}", positivityScore = 80, icon = "👥", color = 0xFFEC407A.toLong()),
        ActivityType("household", names.currentName(R.string.seed_activity_household), "household", true, "{\"overlay\": false}", positivityScore = 40, icon = "🧹", color = 0xFF78909C.toLong()),
        ActivityType("driving", names.currentName(R.string.seed_activity_driving), "transport", true, "{\"overlay\": true}", positivityScore = 35, icon = "🚗", color = 0xFFEF5350.toLong()),
        ActivityType("transport", names.currentName(R.string.seed_activity_transport), "transport", true, "{\"overlay\": true}", positivityScore = 30, icon = "🚆", color = 0xFFEF5350.toLong()),
        ActivityType("digital", names.currentName(R.string.seed_activity_digital), "digital", true, "{\"overlay\": true}", positivityScore = 15, icon = "📱", color = 0xFFAB47BC.toLong()),
        ActivityType("leisure", names.currentName(R.string.seed_activity_leisure), "leisure", true, "{\"overlay\": false}", positivityScore = 60, icon = "🎮", color = 0xFF29B6F6.toLong()),
        ActivityType("other", names.currentName(R.string.seed_activity_other), "unknown", true, "{\"overlay\": false}", positivityScore = 50, icon = "✨", color = 0xFF9E9E9E.toLong())
    )

    private fun defaultTags(names: SeedNames) = listOf(
        Tag("deep-work", names.currentName(R.string.seed_tag_deep_work), "#6366F1"),
        Tag("focus", names.currentName(R.string.seed_tag_focus), "#2DD4BF"),
        Tag("routine", names.currentName(R.string.seed_tag_routine), "#F59E0B"),
        Tag("energy", names.currentName(R.string.seed_tag_energy), "#22C55E"),
        Tag("recovery", names.currentName(R.string.seed_tag_recovery), "#334155")
    )

    /** M18.68: String-Key eines Seeds (für die Sprach-Erkennung). */
    private fun seedNameKey(id: String): Int = when (id) {
        "work" -> R.string.seed_category_work
        "sleep" -> R.string.seed_category_sleep
        "sport" -> R.string.seed_category_sport
        "learning" -> R.string.seed_category_learning
        "health" -> R.string.seed_category_health
        "leisure" -> R.string.seed_category_leisure
        "relationships" -> R.string.seed_category_relationships
        "transport" -> R.string.seed_category_transport
        "digital" -> R.string.seed_category_digital
        "household" -> R.string.seed_category_household
        "unknown" -> R.string.seed_category_unknown
        "deep_work" -> R.string.seed_activity_deep_work
        "fitness" -> R.string.seed_activity_fitness
        "joggen" -> R.string.seed_activity_joggen
        "radfahren" -> R.string.seed_activity_radfahren
        "spazieren" -> R.string.seed_activity_spazieren
        "reading" -> R.string.seed_activity_reading
        "meditation" -> R.string.seed_activity_meditation
        "eating" -> R.string.seed_activity_eating
        "social" -> R.string.seed_activity_social
        "household" -> R.string.seed_activity_household
        "driving" -> R.string.seed_activity_driving
        "transport" -> R.string.seed_activity_transport
        "digital" -> R.string.seed_activity_digital
        "leisure" -> R.string.seed_activity_leisure
        "other" -> R.string.seed_activity_other
        "deep-work" -> R.string.seed_tag_deep_work
        "focus" -> R.string.seed_tag_focus
        "routine" -> R.string.seed_tag_routine
        "energy" -> R.string.seed_tag_energy
        "recovery" -> R.string.seed_tag_recovery
        else -> R.string.seed_category_unknown
    }

    /**
     * Liefert Seed-Namen in der aktuellen Sprache sowie die bekannten
     * Namen (DE + EN), um unveränderte Seeds beim Sprachwechsel zu erkennen.
     */
    private class SeedNames(context: Context) {
        private val current = context.resources
        private val de = LocaleHelper.applyTo(context, LanguageRepository.LANGUAGE_DE).resources
        private val en = LocaleHelper.applyTo(context, LanguageRepository.LANGUAGE_EN).resources

        fun currentName(key: Int): String = current.getString(key)
        fun knownNames(key: Int): Set<String> = setOf(de.getString(key), en.getString(key))
    }
}
