package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.screens.goals.toGoalWithProgress
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * M27: Ziele werden bei automatischer Messung erst nach Ablauf ihrer
 * Periode gewertet.
 *
 * Der konkrete Bug (Devon): Ein Tagesziel „Bildschirmzeit unter 3 h" feierte
 * jeden MORGEN, weil der laufende Tag um 8 Uhr natürlich unter 3 h liegt.
 * Abends war das Ziel dann oft überschritten. Jetzt zählt die Feier nur die
 * letzte ABGESCHLOSSENE Periode; die Serie läuft likewise erst morgen weiter.
 */
class GoalDelayedEvaluationTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 10, 21)
    private val digitalType = ActivityType("digital", "Digital", "digital")
    private val learningType = ActivityType("learning", "Lernen", "learning")
    private val typeMap = mapOf("digital" to digitalType, "learning" to learningType)

    private fun dayStart(daysAgo: Long): Long =
        anchor.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun digitalGoal(startDaysAgo: Long = 2) = Goal(
        id = "g-digital",
        title = "Bildschirmzeit unter 3 h",
        type = "AT_MOST",
        period = "DAILY",
        targetValue = 180f,
        targetUnit = "MINUTES",
        sourceKind = "DIGITAL",
        activityTypeId = "digital",
        startAt = dayStart(startDaysAgo),
        createdAt = dayStart(startDaysAgo)
    )

    @Test
    fun `morgens-unter-3h-ist-noch-kein-erreichtes-Tagesziel`() {
        val balance = mapOf(
            anchor.minusDays(1) to 60L * 60_000,
            anchor.minusDays(2) to 60L * 60_000,
            anchor to 60L * 60_000
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = digitalGoal(),
            sessions = emptyList(),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = balance
        )

        // Live ist der Wert unter dem Limit …
        assertThat(result.isMet).isTrue()
        // … aber der Tag läuft noch: keine Wertung, kein „Erreicht".
        assertThat(result.isPendingFinal).isTrue()
        assertThat(result.isCurrentAchieved).isFalse()
        // Die letzte abgeschlossene Periode (gestern) war erfüllt.
        assertThat(result.lastCompletedStart).isEqualTo(anchor.minusDays(1))
        assertThat(result.lastCompletedMet).isTrue()
        assertThat(result.lastCompletedPeriodKey).isEqualTo("2026-10-20")
        assertThat(result.celebrationEligible).isTrue()
        assertThat(result.celebrationPeriodKey).isEqualTo("2026-10-20")
        assertThat(result.celebrationPeriodLabel).isEqualTo("Gestern")
        // Serie zählt nur volle Tage: vorgestern + gestern = 2.
        assertThat(result.streakCurrent).isEqualTo(2)
        assertThat(result.celebrationStreak).isEqualTo(2)

        // Genau diese abgeschlossene Periode ist der Feier-Kandidat —
        // nicht der laufende Tag.
        val progress = result.toGoalWithProgress(emptyMap(), emptyMap())
        val candidate = com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationPolicy
            .candidate(progress)
        assertThat(candidate).isNotNull()
        assertThat(candidate!!.periodKey).isEqualTo("2026-10-20")
        assertThat(candidate.streak).isEqualTo(2)
    }

    @Test
    fun `abends-ueber-dem-Limit-macht-die-Feier-am-naechsten-Morgen-nicht-kaputt`() {
        val balance = mapOf(
            anchor.minusDays(1) to 60L * 60_000,
            anchor.minusDays(2) to 60L * 60_000,
            anchor to 300L * 60_000 // heute schon 5 h
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = digitalGoal(),
            sessions = emptyList(),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = balance
        )

        assertThat(result.isMet).isFalse()
        assertThat(result.isPendingFinal).isTrue()
        assertThat(result.isCurrentAchieved).isFalse()
        // Die abgeschlossenen zwei Tage bleiben die Grundlage der Feier.
        assertThat(result.celebrationEligible).isTrue()
        assertThat(result.celebrationPeriodKey).isEqualTo("2026-10-20")
        assertThat(result.streakCurrent).isEqualTo(2)
    }

    @Test
    fun `frisch-angelegtes-AT-MOST-Ziel-hat-keine-91-Tage-Serie`() {
        val goal = digitalGoal(startDaysAgo = 0)

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = emptyList(),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = mapOf(anchor to 0L)
        )

        // Kein abgeschlossener Tag seit Erstellung → nichts zu feiern.
        assertThat(result.lastCompletedStart).isNull()
        assertThat(result.celebrationEligible).isFalse()
        assertThat(result.streakCurrent).isEqualTo(0)
        assertThat(result.streakBest).isEqualTo(0)
    }

    @Test
    fun `ein-einzelner-erfolgreicher-Tag-loest-noch-kein-Popup-aus`() {
        val balance = mapOf(
            anchor.minusDays(1) to 60L * 60_000,   // gestern geschafft
            anchor.minusDays(2) to 300L * 60_000,  // vorgestern über dem Limit
            anchor to 60L * 60_000
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = digitalGoal(),
            sessions = emptyList(),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = balance
        )

        assertThat(result.lastCompletedMet).isTrue()
        assertThat(result.streakCurrent).isEqualTo(1)
        assertThat(result.celebrationEligible).isFalse()
    }

    @Test
    fun `manuelles-Ziel-bleibt-mit-dem-Check-in-sofort-final`() {
        val goal = Goal(
            id = "g-manual",
            title = "Täglich abhaken",
            type = "COUNT",
            period = "DAILY",
            targetValue = 1f,
            targetUnit = "COUNT",
            trackingMode = "MANUAL",
            startAt = dayStart(10),
            createdAt = dayStart(10)
        )
        val checkin = GoalCheckin(
            id = "c1",
            goalId = goal.id,
            timestampMs = anchor.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(),
            dateKey = anchor.toString()
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = emptyList(),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            checkins = listOf(checkin)
        )

        assertThat(result.isMet).isTrue()
        assertThat(result.isPendingFinal).isFalse()
        assertThat(result.isCurrentAchieved).isTrue()
        assertThat(result.celebrationEligible).isTrue()
        assertThat(result.celebrationPeriodKey).isEqualTo(anchor.toString())
        assertThat(result.streakCurrent).isEqualTo(1)
    }

    @Test
    fun `automatisches-Lernziel-feiert-weiterhin-beim-Erreichen`() {
        val goal = Goal(
            id = "g-learning",
            title = "2 h lernen",
            activityTypeId = "learning",
            type = "AT_LEAST",
            period = "DAILY",
            targetValue = 2f,
            targetUnit = "HOURS",
            startAt = dayStart(3),
            createdAt = dayStart(3)
        )
        val sessions = listOf(
            sessionAt(anchor, 10),
            sessionAt(anchor.minusDays(1), 10),
            sessionAt(anchor.minusDays(2), 10)
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = sessions,
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap
        )

        // AT_LEAST ist mit dem Erreichen final — keine künstliche Verzögerung.
        assertThat(result.isMet).isTrue()
        assertThat(result.isPendingFinal).isFalse()
        assertThat(result.isCurrentAchieved).isTrue()
        assertThat(result.streakCurrent).isEqualTo(3)
        assertThat(result.celebrationEligible).isTrue()
        assertThat(result.celebrationPeriodKey).isEqualTo(anchor.toString())
    }

    private fun sessionAt(date: LocalDate, startHour: Int): ActivitySession {
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = "s-$date-$startHour",
            title = "Lernen",
            activityTypeId = "learning",
            startAt = start,
            endAt = start + 2L * 60 * 60 * 1000
        )
    }
}
