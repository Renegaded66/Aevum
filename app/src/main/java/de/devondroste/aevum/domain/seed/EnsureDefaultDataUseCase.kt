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
        ActivityType("work", "Arbeit", "work", true, "{\"overlay\": false}"),
        ActivityType("deep_work", "Deep Work", "work", true, "{\"overlay\": false}"),
        ActivityType("sleep", "Schlaf", "sleep", true, "{\"overlay\": false}"),
        ActivityType("fitness", "Fitness", "sport", true, "{\"overlay\": false}"),
        ActivityType("learning", "Lernen", "learning", true, "{\"overlay\": false}"),
        ActivityType("reading", "Lesen", "leisure", true, "{\"overlay\": false}"),
        ActivityType("meditation", "Meditation", "health", true, "{\"overlay\": false}"),
        ActivityType("eating", "Essen", "leisure", true, "{\"overlay\": false}"),
        ActivityType("social", "Soziales", "relationships", true, "{\"overlay\": false}"),
        ActivityType("household", "Haushalt", "household", true, "{\"overlay\": false}"),
        ActivityType("driving", "Autofahren", "transport", true, "{\"overlay\": true}"),
        ActivityType("transport", "Transport", "transport", true, "{\"overlay\": true}"),
        ActivityType("digital", "Digital", "digital", true, "{\"overlay\": true}"),
        ActivityType("leisure", "Freizeit", "leisure", true, "{\"overlay\": false}"),
        ActivityType("other", "Sonstiges", "unknown", true, "{\"overlay\": false}")
    )

    private val defaultTags = listOf(
        Tag("deep-work", "Deep Work", "#6366F1"),
        Tag("focus", "Fokus", "#2DD4BF"),
        Tag("routine", "Routine", "#F59E0B"),
        Tag("energy", "Energie", "#22C55E"),
        Tag("recovery", "Erholung", "#334155")
    )
}
