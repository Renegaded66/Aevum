package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.insights.BalanceMode
import com.d_drostes_apps.aevum.ui.screens.insights.InsightPeriod
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M32: Das Balance-Radar basiert auf fünf frei gewählten Aktivitäten oder
 * Kategorien. Diese Suite sichert die drei Zusagen:
 *
 *  1. Ohne gespeicherte Auswahl sind es die fünf häufigsten Einträge.
 *  2. Eine gespeicherte Auswahl wird in ihrer Reihenfolge respektiert — auch
 *     dann, wenn eine gewählte Aktivität im gezeigten Zeitraum 0 Zeit hat.
 *  3. Ungültige IDs (gelöschte Einträge) fallen raus; bleibt nichts übrig,
 *     greift wieder der Default.
 */
class InsightsBalanceSelectionTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 19)

    private val types = (1..6).map { index ->
        ActivityType("t$index", "Typ $index", "work")
    }
    private val workOnly = listOf(Category("work", "Arbeit", "#6366F1", "□"))

    /** Sechs Sessions von 0 bis 21 Uhr: 6h, 5h, 4h, 3h, 2h, 1h. */
    private fun sixSessions(
        categoryOf: (Int) -> String? = { null }
    ): List<ActivitySession> {
        val minutes = listOf(360, 300, 240, 180, 120, 60)
        var cursor = anchor.atTime(0, 0).atZone(zone)
        return minutes.mapIndexed { index, duration ->
            val start = cursor
            val end = start.plusMinutes(duration.toLong())
            cursor = end
            ActivitySession(
                id = "s$index",
                title = "Typ ${index + 1}",
                activityTypeId = "t${index + 1}",
                categoryId = categoryOf(index + 1),
                startAt = start.toInstant().toEpochMilli(),
                endAt = end.toInstant().toEpochMilli()
            )
        }
    }

    @Test
    fun defaultIsTheFiveMostFrequentActivities() {
        val result = InsightsAnalytics.build(
            sessions = sixSessions(),
            categories = workOnly,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.balanceMode).isEqualTo(BalanceMode.Activity)
        assertThat(result.balanceOptions).hasSize(6)
        assertThat(result.balanceSelectedIds)
            .containsExactly("t1", "t2", "t3", "t4", "t5")
            .inOrder()
        assertThat(result.balanceAxes.map { it.area })
            .containsExactly("Typ 1", "Typ 2", "Typ 3", "Typ 4", "Typ 5")
            .inOrder()
        // Prozentwerte beziehen sich auf die Summe der AUSGWÄHLTEN Achsen:
        // 6+5+4+3+2 = 20 h → 30, 25, 20, 15, 10 %.
        assertThat(result.balanceAxes.map { it.percent })
            .containsExactly(30, 25, 20, 15, 10)
            .inOrder()
    }

    @Test
    fun savedSelectionKeepsOrderAndAZeroTimeActivity() {
        val result = InsightsAnalytics.build(
            // Nur t1 (6h) und t3 (4h) haben Zeit; t6 hat in diesem Zeitraum 0.
            sessions = sixSessions().filter { it.activityTypeId == "t1" || it.activityTypeId == "t3" },
            categories = workOnly,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone,
            balanceMode = BalanceMode.Activity,
            balanceSelectedIds = listOf("t3", "t6", "t1")
        )

        assertThat(result.balanceSelectedIds).containsExactly("t3", "t6", "t1").inOrder()
        assertThat(result.balanceAxes.map { it.area })
            .containsExactly("Typ 3", "Typ 6", "Typ 1")
            .inOrder()
        assertThat(result.balanceAxes.map { it.durationMs })
            .containsExactly(HOUR * 4, 0L, HOUR * 6)
            .inOrder()
        // 4h von 10h = 40 %, 0 %, 6h von 10h = 60 %.
        assertThat(result.balanceAxes.map { it.percent }).containsExactly(40, 0, 60).inOrder()
    }

    @Test
    fun savedSelectionWithFewerThanFiveAxesIsNotFilledUp() {
        val result = InsightsAnalytics.build(
            sessions = sixSessions(),
            categories = workOnly,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone,
            balanceSelectedIds = listOf("t2", "t4")
        )

        assertThat(result.balanceSelectedIds).containsExactly("t2", "t4").inOrder()
        assertThat(result.balanceAxes).hasSize(2)
    }

    @Test
    fun deletedSavedIdsFallBackToTheDefault() {
        val result = InsightsAnalytics.build(
            sessions = sixSessions(),
            categories = workOnly,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone,
            balanceSelectedIds = listOf("deleted_type", "another_deleted_type")
        )

        assertThat(result.balanceSelectedIds)
            .containsExactly("t1", "t2", "t3", "t4", "t5")
            .inOrder()
    }

    @Test
    fun categoryModeUsesTheFiveMostFrequentCategories() {
        val categories = (1..6).map { index ->
            Category("c$index", "Kat $index", "#6366F1", "•")
        }
        val result = InsightsAnalytics.build(
            sessions = sixSessions { index -> "c$index" },
            categories = categories,
            activityTypes = emptyList(),
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone,
            balanceMode = BalanceMode.Category
        )

        assertThat(result.balanceMode).isEqualTo(BalanceMode.Category)
        assertThat(result.balanceOptions).hasSize(6)
        assertThat(result.balanceSelectedIds)
            .containsExactly("c1", "c2", "c3", "c4", "c5")
            .inOrder()
        assertThat(result.balanceAxes.map { it.area })
            .containsExactly("Kat 1", "Kat 2", "Kat 3", "Kat 4", "Kat 5")
            .inOrder()
        assertThat(result.balanceAxes.map { it.percent })
            .containsExactly(30, 25, 20, 15, 10)
            .inOrder()
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
