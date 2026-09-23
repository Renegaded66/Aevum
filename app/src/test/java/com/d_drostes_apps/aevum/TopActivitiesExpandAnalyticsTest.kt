package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.insights.BreakdownMode
import com.d_drostes_apps.aevum.ui.screens.insights.InsightPeriod
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.d_drostes_apps.aevum.ui.screens.insights.TOP_ACTIVITIES_COLLAPSED_COUNT
import com.d_drostes_apps.aevum.ui.screens.insights.topActivitiesToggleAvailable
import com.d_drostes_apps.aevum.ui.screens.insights.topActivitiesVisibleCount
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.137 (Kanban t_70a06809): Die Top-Aktivitäten-Liste ist jetzt
 * aufklappbar — zugeklappt die ersten 5, aufgeklappt ALLE Aktivitäten.
 *
 * Der Nutzer-Bug war strukturell: [InsightsAnalytics.build] kappte die Liste
 * hart auf 5 (`.take(5)`), BEVOR die UI sie überhaupt sah. Damit hätte das
 * Ausklappen nie mehr als 5 Zeilen zeigen können — egal wie die UI aussieht.
 * Diese Suite nagelt die Datenseite fest: die Liste muss VOLLSTÄNDIG und
 * sortiert herauskommen, die 5er-Grenze ist reine Darstellung.
 *
 * Die UI-Seite (Icon-Toggle, Animation, Semantik) ist in
 * TopActivitiesToggleTest abgedeckt.
 */
class TopActivitiesExpandAnalyticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 19)

    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("sport", "Sport", "#22C55E", "▲"),
        Category("digital", "Digital", "#64748B", "■")
    )

    /** 7 Aktivitätstypen — mehr als die 5, die zugeklappt sichtbar sind. */
    private val sevenTypes = (1..7).map { i ->
        ActivityType("type$i", "Aktivität $i", categoryFor(i))
    }

    /** Verteilt die Typen auf die drei Kategorien (für die Kategorie-Ansicht). */
    private fun categoryFor(i: Int): String = when (i) {
        in 1..3 -> "work"
        in 4..5 -> "sport"
        else -> "digital"
    }

    private fun sevenSessions(): List<ActivitySession> =
        (1..7).map { i ->
            // Absteigende Dauer: type1 am längsten, type7 am kürzesten.
            session(
                id = "s$i",
                typeId = "type$i",
                categoryId = categoryFor(i),
                startHour = 8,
                endHour = 8 + (8 - i)
            )
        }

    private fun build(
        sessions: List<ActivitySession>,
        mode: BreakdownMode = BreakdownMode.Activity
    ) = InsightsAnalytics.build(
        sessions = sessions,
        categories = categories,
        activityTypes = sevenTypes,
        selectedPeriod = InsightPeriod.Today,
        anchorDate = anchor,
        zoneId = zone,
        breakdownMode = mode
    )

    /**
     * KERN-BEWEIS gegen den Nutzer-Bug: die Analytik liefert alle 7
     * Aktivitäten, nicht nur 5. Vor dem Fix lieferte sie exakt 5.
     */
    @Test
    fun topBreakdownContainsAllActivitiesNotOnlyFive() {
        val result = build(sevenSessions())

        assertThat(result.topBreakdown).hasSize(7)
        assertThat(result.topBreakdown.map { it.label })
            .containsExactly(
                "Aktivität 1", "Aktivität 2", "Aktivität 3", "Aktivität 4",
                "Aktivität 5", "Aktivität 6", "Aktivität 7"
            ).inOrder()
    }

    /** Sortierung bleibt absteigend nach Dauer — auch jenseits der Top 5. */
    @Test
    fun topBreakdownStaysSortedByDurationBeyondTheTopFive() {
        val result = build(sevenSessions())

        val durations = result.topBreakdown.map { it.durationMs }
        assertThat(durations).isEqualTo(durations.sortedDescending())
        // Die letzten beiden Einträge sind kürzer als der fünfte — sie
        // existieren also NUR, weil nicht mehr gekappt wird.
        assertThat(result.topBreakdown[6].durationMs)
            .isLessThan(result.topBreakdown[4].durationMs)
    }

    /** Zugeklappt genau 5 Zeilen, aufgeklappt alle. */
    @Test
    fun collapsedShowsFiveAndExpandedShowsAll() {
        val total = 7

        assertThat(
            topActivitiesVisibleCount(total, BreakdownMode.Activity, expanded = false)
        ).isEqualTo(TOP_ACTIVITIES_COLLAPSED_COUNT)
        assertThat(
            topActivitiesVisibleCount(total, BreakdownMode.Activity, expanded = true)
        ).isEqualTo(total)
    }

    /** Weniger als 5 Einträge: keine leeren Zeilen, kein Overflow. */
    @Test
    fun fewerThanFiveActivitiesAreShownCompletelyWhenCollapsed() {
        assertThat(topActivitiesVisibleCount(3, BreakdownMode.Activity, expanded = false)).isEqualTo(3)
        assertThat(topActivitiesVisibleCount(0, BreakdownMode.Activity, expanded = false)).isEqualTo(0)
    }

    /** Genau 5: es gibt nichts zu verbergen → kein Toggle. */
    @Test
    fun fiveActivitiesNeedNoToggle() {
        assertThat(topActivitiesToggleAvailable(5, BreakdownMode.Activity)).isFalse()
        assertThat(topActivitiesToggleAvailable(6, BreakdownMode.Activity)).isTrue()
        assertThat(topActivitiesToggleAvailable(7, BreakdownMode.Activity)).isTrue()
    }

    /**
     * Kategorie-Ansicht: bleibt ungekürzt (M18.66-FIX17) und bekommt
     * bewusst KEINEN Toggle — dort ist schon alles sichtbar.
     */
    @Test
    fun categoryModeStaysUncappedAndHasNoToggle() {
        val result = build(sevenSessions(), mode = BreakdownMode.Category)

        assertThat(result.topBreakdown).hasSize(3) // 3 Kategorien
        assertThat(
            topActivitiesVisibleCount(9, BreakdownMode.Category, expanded = false)
        ).isEqualTo(9)
        assertThat(
            topActivitiesVisibleCount(9, BreakdownMode.Category, expanded = true)
        ).isEqualTo(9)
        assertThat(topActivitiesToggleAvailable(9, BreakdownMode.Category)).isFalse()
    }

    /** Default ist zugeklappt — der Screen startet mit der kurzen Liste. */
    @Test
    fun defaultStateIsCollapsed() {
        assertThat(build(sevenSessions()).topActivitiesExpanded).isFalse()
    }

    private fun session(
        id: String,
        typeId: String,
        categoryId: String,
        startHour: Int,
        endHour: Int,
        date: LocalDate = anchor
    ): ActivitySession {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id, title = id, activityTypeId = typeId,
            categoryId = categoryId, startAt = start, endAt = end
        )
    }
}
