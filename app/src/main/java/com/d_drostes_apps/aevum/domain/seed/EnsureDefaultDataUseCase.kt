package com.d_drostes_apps.aevum.domain.seed

import android.util.Log
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.TagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class EnsureDefaultDataUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val tagRepository: TagRepository
) {
    suspend operator fun invoke() {
        // M18.56: Jeder Seed-Schritt einzeln abgesichert — ein Fehler bei
        // Categories darf die ActivityTypes/Tags nicht blockieren (und
        // umgekehrt). Ohne diese Härtung führt ein einzelner DB-Fehler
        // dazu, dass ALLE Defaults fehlen (Symptom: "Schlaf/Arbeit/Sport
        // nicht vor eingestellt nach Neuinstallation").
        try {
            if (categoryRepository.getAll().first().isEmpty()) {
                categoryRepository.insertAll(defaultCategories)
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "Categories-Seed fehlgeschlagen", e)
        }
        try {
            if (activityTypeRepository.getAll().first().isEmpty()) {
                activityTypeRepository.insertAll(defaultActivityTypes)
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "ActivityTypes-Seed fehlgeschlagen", e)
        }
        try {
            if (tagRepository.getAll().first().isEmpty()) {
                tagRepository.insertAll(defaultTags)
            }
        } catch (e: Exception) {
            Log.e("EnsureDefaultData", "Tags-Seed fehlgeschlagen", e)
        }
    }

    private val defaultCategories = listOf(
        Category("work", "Arbeit", "#6366F1", "◼", true, 10),
        Category("sleep", "Schlaf", "#334155", "◒", true, 20),
        Category("sport", "Sport", "#22C55E", "◆", true, 30),
        Category("learning", "Lernen", "#0EA5E9", "◎", true, 40),
        Category("health", "Gesundheit", "#2DD4BF", "✦", true, 50),
        Category("leisure", "Freizeit", "#F97316", "●", true, 60),
        Category("relationships", "Beziehungen", "#EC4899", "♡", true, 70),
        Category("transport", "Transport", "#F59E0B", "↗", true, 80),
        Category("digital", "Digital", "#64748B", "▣", true, 90),
        Category("household", "Haushalt", "#A855F7", "⌂", true, 100),
        Category("unknown", "Sonstiges", "#94A3B8", "…", true, 999)
    )

    private val defaultActivityTypes = listOf(
        // M18: Positivitäts-Scores (0-100). Bewusst gewählte Defaults:
        // Digital/Transport niedrig, Sport/Lernen/Meditation hoch.
        // Der User kann jeden Wert im Activity-Editor anpassen.
        // M18.12: Icons (Emoji) + Farben (ARGB-Int) pro Aktivität.
        ActivityType("work", "Arbeit", "work", true, "{\"overlay\": false}", positivityScore = 50, icon = "💼", color = 0xFF5C6BC0.toLong()),
        ActivityType("deep_work", "Deep Work", "work", true, "{\"overlay\": false}", positivityScore = 80, icon = "🧠", color = 0xFF7E57C2.toLong()),
        ActivityType("sleep", "Schlaf", "sleep", true, "{\"overlay\": false}", positivityScore = 70, icon = "🌙", color = 0xFF3949AB.toLong()),
        ActivityType("fitness", "Fitness", "sport", true, "{\"overlay\": false}", positivityScore = 85, icon = "🏋️", color = 0xFF43A047.toLong()),
        ActivityType("learning", "Lernen", "learning", true, "{\"overlay\": false}", positivityScore = 75, icon = "📚", color = 0xFF26A69A.toLong()),
        ActivityType("reading", "Lesen", "leisure", true, "{\"overlay\": false}", positivityScore = 65, icon = "📖", color = 0xFF8D6E63.toLong()),
        ActivityType("meditation", "Meditation", "health", true, "{\"overlay\": false}", positivityScore = 90, icon = "🧘", color = 0xFF66BB6A.toLong()),
        ActivityType("eating", "Essen", "leisure", true, "{\"overlay\": false}", positivityScore = 45, icon = "🍽️", color = 0xFFFFA726.toLong()),
        ActivityType("social", "Soziales", "relationships", true, "{\"overlay\": false}", positivityScore = 80, icon = "👥", color = 0xFFEC407A.toLong()),
        ActivityType("household", "Haushalt", "household", true, "{\"overlay\": false}", positivityScore = 40, icon = "🧹", color = 0xFF78909C.toLong()),
        ActivityType("driving", "Autofahren", "transport", true, "{\"overlay\": true}", positivityScore = 35, icon = "🚗", color = 0xFFEF5350.toLong()),
        ActivityType("transport", "Transport", "transport", true, "{\"overlay\": true}", positivityScore = 30, icon = "🚆", color = 0xFFEF5350.toLong()),
        ActivityType("digital", "Digital", "digital", true, "{\"overlay\": true}", positivityScore = 15, icon = "📱", color = 0xFFAB47BC.toLong()),
        ActivityType("leisure", "Freizeit", "leisure", true, "{\"overlay\": false}", positivityScore = 60, icon = "🎮", color = 0xFF29B6F6.toLong()),
        ActivityType("other", "Sonstiges", "unknown", true, "{\"overlay\": false}", positivityScore = 50, icon = "✨", color = 0xFF9E9E9E.toLong())
    )

    private val defaultTags = listOf(
        Tag("deep-work", "Deep Work", "#6366F1"),
        Tag("focus", "Fokus", "#2DD4BF"),
        Tag("routine", "Routine", "#F59E0B"),
        Tag("energy", "Energie", "#22C55E"),
        Tag("recovery", "Erholung", "#334155")
    )
}
