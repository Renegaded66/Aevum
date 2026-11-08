package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M36: Die drei Habit-Arten — Auswertung ausschließlich aus Check-ins.
 *
 * DONE  : ein Check-in erfüllt den Tag.
 * COUNT : Summe >= Zielwert.
 * TIME  : jede eingetragene Zeit > 0 erfüllt den Tag; die Dauer wird angezeigt.
 */
class GoalHabitTrackerTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val monday = LocalDate.of(2026, 11, 9) // Montag

    private fun goal(
        kind: String,
        target: Float = 1f,
        scheduleDays: Int = GoalProgressAnalytics.ALL_DAYS
    ) = Goal(
        id = "g1",
        title = "Habit",
        kind = kind,
        type = when (kind) {
            Goal.KIND_COUNT -> GoalProgressAnalytics.TYPE_COUNT
            Goal.KIND_TIME -> GoalProgressAnalytics.TYPE_TIME
            else -> GoalProgressAnalytics.TYPE_AT_LEAST
        },
        period = "DAILY",
        targetValue = target,
        targetUnit = if (kind == Goal.KIND_TIME) "SECONDS" else GoalProgressAnalytics.UNIT_COUNT,
        trackingMode = GoalProgressAnalytics.MODE_MANUAL,
        scheduleDays = scheduleDays,
        startAt = monday.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
    )

    private fun checkin(
        date: LocalDate,
        count: Int = 1,
        durationMs: Long = 0L
    ) = GoalCheckin(
        id = "c-$date-$count-$durationMs",
        goalId = "g1",
        timestampMs = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        dateKey = date.toString(),
        count = count,
        durationMs = durationMs
    )

    private fun evaluate(
        g: Goal,
        checkins: List<GoalCheckin>,
        anchor: LocalDate = monday
    ) = GoalProgressAnalytics.evaluateGoal(
        goal = g,
        sessions = emptyList(),
        anchorDate = anchor,
        zoneId = zone,
        typeMap = emptyMap(),
        checkins = checkins
    )

    @Test
    fun `kindOf respektiert die drei neuen Arten und Legacy`() {
        assertThat(GoalProgressAnalytics.kindOf(goal(Goal.KIND_DONE))).isEqualTo(Goal.KIND_DONE)
        assertThat(GoalProgressAnalytics.kindOf(goal(Goal.KIND_COUNT))).isEqualTo(Goal.KIND_COUNT)
        assertThat(GoalProgressAnalytics.kindOf(goal(Goal.KIND_TIME))).isEqualTo(Goal.KIND_TIME)
        assertThat(GoalProgressAnalytics.kindOf(goal(Goal.KIND_LEGACY))).isEqualTo(Goal.KIND_LEGACY)
        assertThat(GoalProgressAnalytics.isHabit(goal(Goal.KIND_DONE))).isTrue()
        assertThat(GoalProgressAnalytics.isHabit(goal(Goal.KIND_LEGACY))).isFalse()
    }

    @Test
    fun `DONE ist ohne Check-in offen und mit Check-in erfuellt`() {
        val g = goal(Goal.KIND_DONE)
        val open = evaluate(g, emptyList())
        assertThat(open.isCurrentAchieved).isFalse()
        assertThat(open.progressText).isEqualTo("–")

        val done = evaluate(g, listOf(checkin(monday)))
        assertThat(done.isCurrentAchieved).isTrue()
        assertThat(done.progressText).isEqualTo("✓")
        assertThat(done.streakCurrent).isEqualTo(1)
    }

    @Test
    fun `COUNT sammelt bis zum Zielwert`() {
        val g = goal(Goal.KIND_COUNT, target = 3f)

        val two = evaluate(g, listOf(checkin(monday), checkin(monday)))
        assertThat(two.isCurrentAchieved).isFalse()
        assertThat(two.currentValue).isEqualTo(2f)
        assertThat(two.progress).isWithin(0.001f).of(2f / 3f)
        assertThat(two.progressText).isEqualTo("2 / 3")

        val three = evaluate(g, listOf(checkin(monday), checkin(monday), checkin(monday)))
        assertThat(three.isCurrentAchieved).isTrue()
        assertThat(three.progressText).isEqualTo("3 / 3")
    }

    @Test
    fun `TIME ist mit jeder eingetragenen Zeit erfuellt und zeigt die Dauer`() {
        val g = goal(Goal.KIND_TIME)

        val open = evaluate(g, emptyList())
        assertThat(open.isCurrentAchieved).isFalse()
        assertThat(open.progressText).isEqualTo("0:00")

        val timed = evaluate(g, listOf(checkin(monday, durationMs = 90_000L)))
        assertThat(timed.isCurrentAchieved).isTrue()
        assertThat(timed.currentValue).isEqualTo(90f)
        assertThat(timed.progressText).isEqualTo("1:30")
        assertThat(timed.progress).isEqualTo(1f)
    }

    @Test
    fun `Streak zaehlt aufeinanderfolgende erfuellte Tage`() {
        val g = goal(Goal.KIND_DONE)
        val checkins = listOf(
            checkin(monday),
            checkin(monday.minusDays(1)),
            checkin(monday.minusDays(2))
        )
        val result = evaluate(g, checkins)
        assertThat(result.streakCurrent).isEqualTo(3)
        assertThat(result.streakBest).isEqualTo(3)
    }

    @Test
    fun `nicht faelliger Tag ist frei und bricht die Serie nicht`() {
        // Nur Montag ist fällig.
        val mondayBit = 1 shl 0
        val g = goal(Goal.KIND_DONE, scheduleDays = mondayBit)
        val checkins = listOf(checkin(monday.minusWeeks(1)), checkin(monday.minusWeeks(2)))
        val result = evaluate(g, checkins, anchor = monday.minusDays(1)) // Sonntag = frei
        assertThat(result.isDueToday).isFalse()
        assertThat(result.streakCurrent).isEqualTo(2)
    }

    @Test
    fun `Verlauf enthaelt 91 Tageswerte und markiert nur den Anker als aktuell`() {
        val g = goal(Goal.KIND_COUNT, target = 2f)
        val history = GoalProgressAnalytics.buildHistory(
            goal = g,
            sessions = emptyList(),
            anchorDate = monday,
            zoneId = zone,
            checkins = listOf(checkin(monday), checkin(monday))
        )
        assertThat(history).hasSize(GoalProgressAnalytics.HISTORY_DAYS)
        assertThat(history.last().isCurrent).isTrue()
        assertThat(history.last().met).isTrue()
        assertThat(history.count { it.isCurrent }).isEqualTo(1)
    }

    @Test
    fun `vor dem Erstellungsdatum ist ein Habit nicht faellig`() {
        val g = goal(Goal.KIND_DONE).copy(
            startAt = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertThat(
            GoalProgressAnalytics.isHabitScheduledOn(g, monday.minusDays(1), zone)
        ).isFalse()
        assertThat(
            GoalProgressAnalytics.isHabitScheduledOn(g, monday, zone)
        ).isTrue()
    }

    @Test
    fun `formatClock formatiert Sekunden und Stunden`() {
        assertThat(GoalProgressAnalytics.formatClock(0)).isEqualTo("0:00")
        assertThat(GoalProgressAnalytics.formatClock(5)).isEqualTo("0:05")
        assertThat(GoalProgressAnalytics.formatClock(90)).isEqualTo("1:30")
        assertThat(GoalProgressAnalytics.formatClock(3661)).isEqualTo("1:01:01")
    }
}
