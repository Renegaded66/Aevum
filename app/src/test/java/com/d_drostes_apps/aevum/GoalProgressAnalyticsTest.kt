package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.d_drostes_apps.aevum.data.model.TodoCompletion
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
    // ── M20: Ziel-Architektur ────────────────────────────────────────────

    private fun checkin(id: String, goalId: String, date: LocalDate): GoalCheckin {
        val ts = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return GoalCheckin(id = id, goalId = goalId, timestampMs = ts, dateKey = date.toString())
    }

    private fun completion(todoId: String, date: LocalDate) =
        TodoCompletion(todoId = todoId, date = date.toString())

    @Test
    fun `MANUAL Ziel zaehlt Check-ins der Periode - Wochenziel`() {
        val goal = Goal(
            id = "m1", title = "4x Aufgaben", type = "COUNT", period = "WEEKLY",
            targetValue = 4f, targetUnit = "COUNT", trackingMode = "MANUAL"
        )
        val checkins = listOf(
            checkin("c1", "m1", anchor),
            checkin("c2", "m1", anchor.minusDays(1)),
            checkin("c3", "m1", anchor.minusWeeks(1)) // Vorwoche zählt nicht
        )
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), anchor, zone, typeMap, checkins = checkins
        )

        assertThat(result.currentValue).isEqualTo(2f)
        assertThat(result.isMet).isFalse()
        assertThat(result.progressText).isEqualTo("2 von 4")
        assertThat(result.periodKey).isEqualTo("W2026-30")
        assertThat(result.isCountType).isTrue()
    }

    @Test
    fun `Serie - laufende Periode bricht nicht und freie Tage unterbrechen nicht`() {
        // Mo(20.)-Mi(22.) abgehakt, heute (Do 23.) noch nicht → Serie 3.
        val goal = Goal(
            id = "h1", title = "Taeglich abhaken", type = "COUNT", period = "DAILY",
            targetValue = 1f, targetUnit = "COUNT", trackingMode = "MANUAL"
        )
        val thursday = LocalDate.of(2026, 7, 23)
        val checkins = listOf(
            checkin("k1", "h1", thursday.minusDays(3)),
            checkin("k2", "h1", thursday.minusDays(2)),
            checkin("k3", "h1", thursday.minusDays(1))
        )
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), thursday, zone, typeMap, checkins = checkins
        )

        assertThat(result.streakCurrent).isEqualTo(3)
        assertThat(result.streakBest).isEqualTo(3)
        assertThat(result.isMet).isFalse() // heute noch offen
    }

    @Test
    fun `Wochenende unterbricht die Serie eines Werktags-Ziels nicht`() {
        // Mo–Fr (13.–17.07.) erfüllt, Sa+So frei, heute (Mo 20.) noch offen.
        val goal = Goal(
            id = "h2", title = "Werktags", type = "COUNT", period = "DAILY",
            targetValue = 1f, targetUnit = "COUNT", trackingMode = "MANUAL",
            scheduleDays = 0b00011111
        )
        val lastMonday = LocalDate.of(2026, 7, 13)
        val checkins = (0..4).map { checkin("w$it", "h2", lastMonday.plusDays(it.toLong())) }
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), LocalDate.of(2026, 7, 20), zone, typeMap, checkins = checkins
        )

        assertThat(result.streakCurrent).isEqualTo(5)
        assertThat(result.streakBest).isEqualTo(5)
    }

    @Test
    fun `Wochentags-Maske - Samstag ist nicht faellig`() {
        val weekdayGoal = Goal(
            id = "w1", title = "Lernen Mo-Fr", activityTypeId = "learning",
            type = "AT_LEAST", period = "DAILY", targetValue = 2f, targetUnit = "HOURS",
            scheduleDays = 0b00011111
        )
        val saturday = LocalDate.of(2026, 7, 25)
        val saturdayResult = GoalProgressAnalytics.evaluateGoal(
            weekdayGoal, emptyList(), saturday, zone, typeMap
        )
        assertThat(saturdayResult.isDueToday).isFalse()

        val allDaysGoal = Goal(
            id = "w2", title = "Schlaf", activityTypeId = "sleep",
            type = "AT_LEAST", period = "DAILY", targetValue = 8f, targetUnit = "HOURS"
        )
        val open = GoalProgressAnalytics.evaluateGoal(allDaysGoal, emptyList(), saturday, zone, typeMap)
        assertThat(open.isDueToday).isTrue()
    }

    @Test
    fun `Wochenziel mit Mo-Fr-Maske ignoriert Check-ins am Wochenende`() {
        val goal = Goal(
            id = "w3", title = "4x Aufgaben", type = "COUNT", period = "WEEKLY",
            targetValue = 4f, targetUnit = "COUNT", trackingMode = "MANUAL",
            scheduleDays = 0b00011111
        )
        val checkins = listOf(
            checkin("x1", "w3", anchor),                 // Di — zählt
            checkin("x2", "w3", anchor.minusDays(1)),    // Mo — zählt
            checkin("x3", "w3", LocalDate.of(2026, 7, 25)) // Sa — zählt nicht
        )
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), anchor, zone, typeMap, checkins = checkins
        )

        assertThat(result.currentValue).isEqualTo(2f)
    }

    @Test
    fun `AUTO mit Quelle TODOS zaehlt abgehakte Aufgaben der Periode`() {
        val goal = Goal(
            id = "t1", title = "4 Aufgaben", type = "COUNT", period = "WEEKLY",
            targetValue = 4f, targetUnit = "COUNT", sourceKind = "TODOS"
        )
        val completions = listOf(
            completion("td1", anchor),
            completion("td1", anchor.minusDays(1)),
            completion("td2", anchor.minusWeeks(1)) // Vorwoche
        )
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), anchor, zone, typeMap, todoCompletions = completions
        )

        assertThat(result.currentValue).isEqualTo(2f)
    }

    @Test
    fun `AUTO mit Quelle DIGITAL nutzt die Bildschirmzeit des Tages`() {
        val goal = Goal(
            id = "d1", title = "Digital unter 2h", type = "AT_MOST", period = "DAILY",
            targetValue = 120f, targetUnit = "MINUTES", sourceKind = "DIGITAL"
        )
        val result = GoalProgressAnalytics.evaluateGoal(
            goal, emptyList(), anchor, zone, typeMap,
            balanceMsPerDay = mapOf(anchor to 90L * 60 * 1000)
        )

        assertThat(result.currentValue).isEqualTo(90f)
        assertThat(result.isMet).isTrue()
    }

    @Test
    fun `AUTO mit Quelle KATEGORIE summiert alle Aktivitaeten der Kategorie`() {
        val goal = Goal(
            id = "c1", title = "Sport", type = "AT_LEAST", period = "DAILY",
            targetValue = 1f, targetUnit = "HOURS", sourceKind = "CATEGORY",
            categoryId = "sport"
        )
        val sessions = listOf(
            session("s1", "fitness", "sport", anchor, 10, 11),
            session("s2", "yoga", "sport", anchor, 12, 13),
            session("s3", "sleep", "sleep", anchor, 14, 15)
        )
        val result = GoalProgressAnalytics.evaluateGoal(goal, sessions, anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(2f)
        assertThat(result.isMet).isTrue()
    }

    @Test
    fun `AUTO-Anzahl zaehlt Sessions der Periode`() {
        val goal = Goal(
            id = "a1", title = "4x Lernen", type = "COUNT", period = "WEEKLY",
            targetValue = 4f, targetUnit = "COUNT", activityTypeId = "learning"
        )
        val sessions = listOf(
            session("s1", "learning", "learning", anchor, 10, 11),
            session("s2", "learning", "learning", anchor, 12, 13),
            session("s3", "learning", "learning", anchor.minusDays(1), 18, 19)
        )
        val result = GoalProgressAnalytics.evaluateGoal(goal, sessions, anchor, zone, typeMap)

        assertThat(result.currentValue).isEqualTo(3f)
        assertThat(result.isMet).isFalse()
    }

    @Test
    fun `Verlauf - taegliche Ziele liefern 91 Perioden bis einschliesslich heute`() {
        val goal = Goal(
            id = "v1", title = "X", type = "COUNT", period = "DAILY",
            targetValue = 1f, targetUnit = "COUNT", trackingMode = "MANUAL"
        )
        val history = GoalProgressAnalytics.buildHistory(goal, emptyList(), anchor, zone)

        assertThat(history).hasSize(91)
        assertThat(history.first().startDate).isEqualTo(anchor.minusDays(90))
        assertThat(history.last().startDate).isEqualTo(anchor)
        assertThat(history.last().isCurrent).isTrue()
    }

    @Test
    fun `periodKey - taeglich und monatlich`() {
        val daily = Goal(id = "p1", title = "X", period = "DAILY", targetValue = 1f)
        val monthly = Goal(id = "p2", title = "X", period = "MONTHLY", targetValue = 1f)
        assertThat(GoalProgressAnalytics.periodKey(daily, anchor)).isEqualTo("2026-07-21")
        assertThat(GoalProgressAnalytics.periodKey(monthly, anchor)).isEqualTo("2026-07")
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