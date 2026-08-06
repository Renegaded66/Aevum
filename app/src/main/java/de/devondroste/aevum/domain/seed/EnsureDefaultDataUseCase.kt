package de.devondroste.aevum.domain.seed

import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Tag
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.TagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class EnsureDefaultDataUseCase @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val tagRepository: TagRepository
) {
    suspend operator fun invoke() {
        if (categoryRepository.getAll().first().isEmpty()) {
            categoryRepository.insertAll(defaultCategories)
        }
        if (activityTypeRepository.getAll().first().isEmpty()) {
            activityTypeRepository.insertAll(defaultActivityTypes)
        }
        if (tagRepository.getAll().first().isEmpty()) {
            tagRepository.insertAll(defaultTags)
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
        ActivityType("work", "Arbeit", "work", true, "{\"overlay\": false}", positivityScore = 50),
        ActivityType("deep_work", "Deep Work", "work", true, "{\"overlay\": false}", positivityScore = 80),
        ActivityType("sleep", "Schlaf", "sleep", true, "{\"overlay\": false}", positivityScore = 70),
        ActivityType("fitness", "Fitness", "sport", true, "{\"overlay\": false}", positivityScore = 85),
        ActivityType("learning", "Lernen", "learning", true, "{\"overlay\": false}", positivityScore = 75),
        ActivityType("reading", "Lesen", "leisure", true, "{\"overlay\": false}", positivityScore = 65),
        ActivityType("meditation", "Meditation", "health", true, "{\"overlay\": false}", positivityScore = 90),
        ActivityType("eating", "Essen", "leisure", true, "{\"overlay\": false}", positivityScore = 45),
        ActivityType("social", "Soziales", "relationships", true, "{\"overlay\": false}", positivityScore = 80),
        ActivityType("household", "Haushalt", "household", true, "{\"overlay\": false}", positivityScore = 40),
        ActivityType("driving", "Autofahren", "transport", true, "{\"overlay\": true}", positivityScore = 35),
        ActivityType("transport", "Transport", "transport", true, "{\"overlay\": true}", positivityScore = 30),
        ActivityType("digital", "Digital", "digital", true, "{\"overlay\": true}", positivityScore = 15),
        ActivityType("leisure", "Freizeit", "leisure", true, "{\"overlay\": false}", positivityScore = 60),
        ActivityType("other", "Sonstiges", "unknown", true, "{\"overlay\": false}", positivityScore = 50)
    )

    private val defaultTags = listOf(
        Tag("deep-work", "Deep Work", "#6366F1"),
        Tag("focus", "Fokus", "#2DD4BF"),
        Tag("routine", "Routine", "#F59E0B"),
        Tag("energy", "Energie", "#22C55E"),
        Tag("recovery", "Erholung", "#334155")
    )
}
