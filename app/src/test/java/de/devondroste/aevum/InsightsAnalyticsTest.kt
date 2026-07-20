package de.devondroste.aevum

import com.google.common.truth.Truth.assertThat
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.ui.screens.insights.InsightPeriod
import de.devondroste.aevum.ui.screens.insights.InsightsAnalytics
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InsightsAnalyticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 19)
    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("sport", "Sport", "#22C55E", "▲"),
        Category("digital", "Digital", "#64748B", "■"),
        Category("social", "Soziales", "#EC4899", "●")
    )
    private val types = listOf(
        ActivityType("deep_work", "Deep Work", "work"),
        ActivityType("gym", "Fitnessstudio", "sport"),
        ActivityType("phone", "Smartphone", "digital"),
        ActivityType("friends", "Freunde", "social")
    )

    @Test
    fun timeDistributionGroupsByCategoryAndComputesPercent() {
        val result = InsightsAnalytics.build(
            sessions = listOf(
                session("a", "deep_work", "work", 9, 11),
                session("b", "gym", "sport", 18, 19)
            ),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.timeDistribution.map { it.id }).containsExactly("work", "sport").inOrder()
        assertThat(result.timeDistribution.first().durationMs).isEqualTo(2 * HOUR)
        assertThat(result.timeDistribution.first().percent).isEqualTo(67)
    }

    @Test
    fun previousPeriodComparisonOnlyAppearsWithPreviousData() {
        val result = InsightsAnalytics.build(
            sessions = listOf(
                session("today", "deep_work", "work", 9, 12),
                session("yesterday", "deep_work", "work", 9, 10, date = anchor.minusDays(1))
            ),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.changes).hasSize(1)
        assertThat(result.changes.first().label).isEqualTo("Arbeit")
        assertThat(result.changes.first().deltaMs).isEqualTo(2 * HOUR)
    }

    @Test
    fun topActivitiesGroupByActivityType() {
        val result = InsightsAnalytics.build(
            sessions = listOf(
                session("a", "deep_work", "work", 8, 10),
                session("b", "deep_work", "work", 14, 15),
                session("c", "gym", "sport", 18, 19)
            ),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.topActivities.first().label).isEqualTo("Deep Work")
        assertThat(result.topActivities.first().durationMs).isEqualTo(3 * HOUR)
        assertThat(result.topActivities.first().percent).isEqualTo(75)
    }

    @Test
    fun balanceMapsCategoriesToLifeAreasWithoutScoring() {
        val result = InsightsAnalytics.build(
            sessions = listOf(
                session("a", "deep_work", "work", 8, 10),
                session("b", "phone", "digital", 20, 21),
                session("c", "friends", "social", 19, 20)
            ),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Today,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.balance.first { it.area == "Arbeit" }.durationMs).isEqualTo(2 * HOUR)
        assertThat(result.balance.first { it.area == "Digital" }.durationMs).isEqualTo(HOUR)
        assertThat(result.balance.first { it.area == "Soziales" }.durationMs).isEqualTo(HOUR)
    }

    @Test
    fun weekHeatmapContainsCurrentWeekDaysAndDurations() {
        val monday = LocalDate.of(2026, 7, 13)
        val result = InsightsAnalytics.build(
            sessions = listOf(
                session("mon", "deep_work", "work", 9, 11, date = monday),
                session("sun", "gym", "sport", 18, 19, date = anchor)
            ),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Week,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.weekHeatmap.days).hasSize(7)
        assertThat(result.weekHeatmap.days.first().date).isEqualTo(monday)
        assertThat(result.weekHeatmap.days.first().durationMs).isEqualTo(2 * HOUR)
        assertThat(result.weekHeatmap.days.last().durationMs).isEqualTo(HOUR)
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
        return ActivitySession(id = id, title = id, activityTypeId = typeId, categoryId = categoryId, startAt = start, endAt = end)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
