package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.weekly.WeeklyReviewAnalytics
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyReviewAnalyticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 19)
    private val monday = LocalDate.of(2026, 7, 13)
    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("sport", "Bewegung", "#22C55E", "▲"),
        Category("digital", "Digital", "#64748B", "■"),
        Category("leisure", "Freizeit", "#F59E0B", "○"),
        Category("sleep", "Schlaf", "#38BDF8", "☾")
    )
    private val types = listOf(
        ActivityType("deep_work", "Deep Work", "work"),
        ActivityType("run", "Laufen", "sport"),
        ActivityType("phone", "Smartphone", "digital"),
        ActivityType("reading", "Lesen", "leisure"),
        ActivityType("sleep", "Schlaf", "sleep")
    )

    @Test
    fun weeklyReviewBuildsNarrativeTimelineDistributionAndOpenTime() {
        val result = WeeklyReviewAnalytics.build(
            sessions = listOf(
                session("work1", "Deep Work", "deep_work", "work", monday, 9, 13),
                session("sport1", "Laufen", "run", "sport", monday.plusDays(1), 18, 19),
                session("read1", "Lesen", "reading", "leisure", monday.plusDays(2), 20, 22)
            ),
            candidates = emptyList(),
            categories = categories,
            activityTypes = types,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.hasData).isTrue()
        assertThat(result.heroTitle).isEqualTo("Deine Woche")
        assertThat(result.days).hasSize(7)
        assertThat(result.days.first().date).isEqualTo(monday)
        assertThat(result.days.first().topCategoryLabel).isEqualTo("Arbeit")
        assertThat(result.timeDistribution.map { it.id }).containsAtLeast("work", "sport", "leisure")
        assertThat(result.openTimeMs).isGreaterThan(0L)
        assertThat(result.closingText).isNotEmpty()
    }

    @Test
    fun weeklyChangesCompareAgainstPreviousWeekOnlyWhenPreviousDataExists() {
        val result = WeeklyReviewAnalytics.build(
            sessions = listOf(
                session("currentSport", "Laufen", "run", "sport", monday, 18, 20),
                session("prevSport", "Laufen", "run", "sport", monday.minusWeeks(1), 18, 19),
                session("prevDigital", "Smartphone", "phone", "digital", monday.minusWeeks(1), 20, 22)
            ),
            candidates = emptyList(),
            categories = categories,
            activityTypes = types,
            anchorDate = anchor,
            zoneId = zone
        )

        val sport = result.changes.first { it.id == "sport" }
        val digital = result.changes.first { it.id == "digital" }
        assertThat(sport.deltaMs).isEqualTo(HOUR)
        assertThat(digital.deltaMs).isEqualTo(-2 * HOUR)
    }

    @Test
    fun weeklyHighlightsFindLongestActivityAndMostActiveDay() {
        val result = WeeklyReviewAnalytics.build(
            sessions = listOf(
                session("short", "Laufen", "run", "sport", monday, 18, 19),
                session("long", "Deep Work", "deep_work", "work", monday.plusDays(1), 8, 13),
                session("extra", "Lesen", "reading", "leisure", monday.plusDays(1), 20, 22)
            ),
            candidates = emptyList(),
            categories = categories,
            activityTypes = types,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.highlights.map { it.title }).contains("Längste Aktivität")
        assertThat(result.highlights.first { it.title == "Längste Aktivität" }.value).contains("Deep Work")
        assertThat(result.highlights.map { it.title }).contains("Aktivster Tag")
    }

    @Test
    fun pendingCandidatesAreCountedForReviewInboxIntegration() {
        val result = WeeklyReviewAnalytics.build(
            sessions = listOf(session("work", "Deep Work", "deep_work", "work", monday, 9, 11)),
            candidates = listOf(
                candidate("c1", monday, 8, 9, status = "PENDING"),
                candidate("c2", monday.plusDays(1), 8, 9, status = "PENDING"),
                candidate("c3", monday.plusDays(2), 8, 9, status = "DISMISSED")
            ),
            categories = categories,
            activityTypes = types,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.pendingReviewCount).isEqualTo(2)
    }

    @Test
    fun emptyWeeklyReviewHasPremiumEmptyStateAndNoArtificialChanges() {
        val result = WeeklyReviewAnalytics.build(
            sessions = emptyList(),
            candidates = emptyList(),
            categories = categories,
            activityTypes = types,
            anchorDate = anchor,
            zoneId = zone
        )

        assertThat(result.hasData).isFalse()
        assertThat(result.timeDistribution).isEmpty()
        assertThat(result.changes).isEmpty()
        assertThat(result.emptyTitle).contains("Noch")
    }

    private fun session(
        id: String,
        title: String,
        typeId: String,
        categoryId: String,
        date: LocalDate,
        startHour: Int,
        endHour: Int
    ): ActivitySession {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(id = id, title = title, activityTypeId = typeId, categoryId = categoryId, startAt = start, endAt = end)
    }

    private fun candidate(id: String, date: LocalDate, startHour: Int, endHour: Int, status: String): ActivityCandidate {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivityCandidate(id = id, suggestedTitle = id, startAt = start, endAt = end, status = status)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
