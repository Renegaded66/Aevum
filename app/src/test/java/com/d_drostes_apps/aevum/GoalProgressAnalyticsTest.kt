package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GoalProgressAnalyticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 7, 21)
    private val monday = LocalDate.of(2026, 7, 20) // 2026-07-20 is a Monday
    private val typeMap = mapOf(
        "sleep" to ActivityType("sleep", "Schlaf", "sleep"),
        "fitness" to ActivityType("fitness", "Fitness", "sport"),
        "digital" to ActivityType("digital", "Digital", "digital"),
        "learning" to ActivityType("learning", "Lernen", "learning"),
        "meditation" to ActivityType("meditation", "Meditation", "health")
    )

    @Test
    fun `evaluateGoal daily period clips to today`() {
        val goal = Goal(
            id = "g1", title = "8h Schlaf", activityTypeId = "sleep",
            type = "AT_LEAST", period = "DAILY", targetValue = 8f, targetUnit = "HOURS"
        )
        val session = session("s1", "sleep", "sleep", anchor, 22, 6, plusDays = true) // 22:00-06:00 next day = 8h
        // Actually: 22:00 to 06:00 next day, but clipped to today
        val result = GoalProgressAnalytics.evaluateGoal(goal, listOf(session), anchor, zone, typeMap)

        assertThat(result.currentValue).isGreaterThan(0f)
        assertThat(result.periodLabel).isEqualTo("Heute")
        assertThat(result.activityTypeName).isEqualTo("Schlaf")
    }

    @Test
    fun `evaluateGoal weekly period clips to current week`() {
        val goal = Goal(
            id = "g2", title = "3h Sport", activityTypeId = "fitness",
            type = "AT_LEAST", period = "WEEKLY", targetValue = 3f, targetUnit = "HOURS"
        )
        // Monday session: 2h
        val session1 = session("s1", "fitness", "sport", monday, 18, 20)
        // Tuesday session: 1h
        val session2 = session("s2", "fitness", "sport", monday.plusDays(1), 18, 19)

        val result = GoalProgressAnalytics.evaluateGoal(goal, listOf(session1, session2), anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(3f)
        assertThat(result.progress).isEqualTo(1f)
        assertThat(result.isMet).isTrue()
        assertThat(result.activityTypeName).isEqualTo("Fitness")
    }

    @Test
    fun `evaluateGoal atMost type inverts criteria`() {
        val goal = Goal(
            id = "g3", title = "Max 2h Digital", activityTypeId = "digital",
            type = "AT_MOST", period = "DAILY", targetValue = 2f, targetUnit = "HOURS"
        )
        // 1h of digital time
        val session = session("s1", "digital", "digital", anchor, 14, 15)

        val result = GoalProgressAnalytics.evaluateGoal(goal, listOf(session), anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(1f)
        assertThat(result.progress).isEqualTo(0.5f)
        assertThat(result.isMet).isTrue()
        assertThat(result.progressText).contains("maximal")
    }

    @Test
    fun `evaluateGoal atMost exceeded shows not met`() {
        val goal = Goal(
            id = "g4", title = "Max 2h Digital", activityTypeId = "digital",
            type = "AT_MOST", period = "DAILY", targetValue = 2f, targetUnit = "HOURS"
        )
        // 3h of digital time
        val session = session("s1", "digital", "digital", anchor, 14, 17)

        val result = GoalProgressAnalytics.evaluateGoal(goal, listOf(session), anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(3f)
        assertThat(result.progress).isEqualTo(1.5f)
        assertThat(result.isMet).isFalse()
    }

    @Test
    fun `evaluateGoal with no matching sessions has zero progress`() {
        val goal = Goal(
            id = "g5", title = "10h Lernen", activityTypeId = "learning",
            type = "AT_LEAST", period = "WEEKLY", targetValue = 10f, targetUnit = "HOURS"
        )

        val result = GoalProgressAnalytics.evaluateGoal(goal, emptyList(), anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(0f)
        assertThat(result.progress).isEqualTo(0f)
        assertThat(result.isMet).isFalse()
    }

    @Test
    fun `evaluateHabit builds heatmap and streak for daily habit`() {
        val habit = Habit(
            id = "h1", title = "Täglich lesen", activityTypeId = "reading",
            frequencyRuleJson = """{"type":"daily"}""",
            successRuleJson = """{"type":"minDuration","minDurationMs":900000}"""
        )
        // 3 consecutive days of reading sessions
        val sessions = (0..2).map { i ->
            session("s$i", "reading", "leisure", anchor.minusDays(i.toLong()), 20, 21)
        }

        val result = GoalProgressAnalytics.evaluateHabit(habit, sessions, anchor, zone, typeMap)

        assertThat(result.streak).isAtLeast(1)
        assertThat(result.heatmap).hasSize(28)
        assertThat(result.heatmap.any { it.completed }).isTrue()
        assertThat(result.successRate).isGreaterThan(0)
        assertThat(result.frequencyLabel).isEqualTo("Täglich")
    }

    @Test
    fun `evaluateHabit with weekly frequency shows correct label`() {
        val habit = Habit(
            id = "h2", title = "3× Sport pro Woche", activityTypeId = "fitness",
            frequencyRuleJson = "{\"type\":\"weekly\",\"count\":3}",
            successRuleJson = """{"type":"minDuration","minDurationMs":1200000}"""
        )

        val result = GoalProgressAnalytics.evaluateHabit(habit, emptyList(), anchor, zone, typeMap)

        assertThat(result.frequencyLabel).isEqualTo("3× pro Woche")
        assertThat(result.streak).isEqualTo(0)
        assertThat(result.activityTypeName).isEqualTo("Fitness")
    }

    @Test
    fun `evaluateHabit empty state has zero streak and rate`() {
        val habit = Habit(
            id = "h3", title = "Meditation", activityTypeId = "meditation",
            frequencyRuleJson = """{"type":"daily"}""",
            successRuleJson = """{"type":"minDuration","minDurationMs":600000}"""
        )

        val result = GoalProgressAnalytics.evaluateHabit(habit, emptyList(), anchor, zone, typeMap)

        assertThat(result.streak).isEqualTo(0)
        assertThat(result.successRate).isEqualTo(0)
        assertThat(result.activeDays).isEqualTo(28) // daily habit: all 28 days are expected
        assertThat(result.heatmap.none { it.completed }).isTrue()
    }

    private fun session(
        id: String,
        typeId: String,
        categoryId: String,
        date: LocalDate,
        startHour: Int,
        endHour: Int,
        plusDays: Boolean = true
    ): ActivitySession {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = if (plusDays && endHour <= startHour) {
            date.plusDays(1).atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        } else {
            date.atTime(endHour, 0).atZone(zone).toInstant().toEpochMilli()
        }
        return ActivitySession(
            id = id, title = id, activityTypeId = typeId, categoryId = categoryId,
            startAt = start, endAt = end
        )
    }
}