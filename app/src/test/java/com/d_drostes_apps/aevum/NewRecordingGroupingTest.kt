package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.timeline.groupActivitiesByCategory
import org.junit.Test

/**
 * M18.74: Tests für die Kategorie-Gruppierung im New-Recording-Dialog.
 *
 * Die Gruppierungslogik (groupActivitiesByCategory) ist eine reine Funktion —
 * sie bestimmt, welche Aktivitäten der User im Plus-Button-Dialog unter
 * welcher Kategorie sieht.
 */
class NewRecordingGroupingTest {

    private fun category(id: String, name: String, sortOrder: Int = 0) = Category(
        id = id,
        name = name,
        color = "#6366F1",
        icon = "◆",
        isSystem = true,
        sortOrder = sortOrder
    )

    private fun type(id: String, name: String, categoryId: String? = null) = ActivityType(
        id = id,
        name = name,
        defaultCategoryId = categoryId
    )

    @Test
    fun activitiesAreGroupedByTheirCategory() {
        val cats = listOf(category("work", "Arbeit"), category("sport", "Sport"))
        val types = listOf(
            type("t1", "Deep Work", "work"),
            type("t2", "Joggen", "sport"),
            type("t3", "Meeting", "work")
        )

        val groups = groupActivitiesByCategory(cats, types)

        assertThat(groups).hasSize(2)
        val work = groups.first { it.categoryName == "Arbeit" }
        val sport = groups.first { it.categoryName == "Sport" }
        assertThat(work.activities.map { it.id }).containsExactly("t1", "t3")
        assertThat(sport.activities.map { it.id }).containsExactly("t2")
    }

    @Test
    fun activitiesWithoutCategoryLandInOhneKategorieGroup() {
        val cats = listOf(category("work", "Arbeit"))
        val types = listOf(
            type("t1", "Deep Work", "work"),
            type("t2", "Sonstiges")
        )

        val groups = groupActivitiesByCategory(cats, types)

        assertThat(groups).hasSize(2)
        val fallback = groups.first { it.categoryName == "Ohne Kategorie" }
        assertThat(fallback.categoryId).isNull()
        assertThat(fallback.activities.map { it.id }).containsExactly("t2")
    }

    @Test
    fun categoriesWithoutActivitiesAreOmitted() {
        val cats = listOf(category("work", "Arbeit"), category("empty", "Leer"))
        val types = listOf(type("t1", "Deep Work", "work"))

        val groups = groupActivitiesByCategory(cats, types)

        assertThat(groups.map { it.categoryName }).containsExactly("Arbeit")
    }

    @Test
    fun activitiesAreSortedAlphabeticallyInsideGroup() {
        val cats = listOf(category("work", "Arbeit"))
        val types = listOf(
            type("t1", "Meeting"),
            type("t2", "Deep Work"),
            type("t3", "Call")
        )

        val groups = groupActivitiesByCategory(cats, types)

        assertThat(groups.single().activities.map { it.name })
            .containsExactly("Call", "Deep Work", "Meeting")
            .inOrder()
    }

    @Test
    fun categoriesAreSortedBySortOrder() {
        val cats = listOf(
            category("a", "Zweit", sortOrder = 2),
            category("b", "Erst", sortOrder = 1),
            category("c", "Dritt", sortOrder = 3)
        )
        val types = listOf(
            type("t1", "X", "a"),
            type("t2", "Y", "b"),
            type("t3", "Z", "c")
        )

        val groups = groupActivitiesByCategory(cats, types)

        assertThat(groups.map { it.categoryName })
            .containsExactly("Erst", "Zweit", "Dritt")
            .inOrder()
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertThat(groupActivitiesByCategory(emptyList(), emptyList())).isEmpty()
    }
}
