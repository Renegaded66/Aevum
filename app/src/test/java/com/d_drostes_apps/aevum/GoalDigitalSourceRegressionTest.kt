package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression: Ein Ziel mit Quelle „Bildschirmzeit" muss exakt den
 * Digital-Balance-Wert zeigen. Andere Sessions mit der Aktivität „Digital"
 * (App-Aufzeichnung, PC, manuell) sind Aufzeichnungen und dürfen die
 * gemessene Bildschirmzeit nicht ein zweites Mal addieren.
 *
 * Devons Fall: Balance 3 h 07 min, Ziel 6 h 08 min = 3 h 07 min + 3 h 01 min.
 */
class GoalDigitalSourceRegressionTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 10, 23)
    private val typeMap = mapOf(
        "digital" to ActivityType("digital", "Digital", "digital"),
        "work" to ActivityType("work", "Arbeit", "work")
    )

    @Test
    fun `digital goal shows exactly the balance time despite extra digital recording`() {
        val goal = digitalGoal(targetHours = 3f)
        val recording = session(
            id = "tracked-app",
            typeId = "digital",
            startHour = 10,
            durationMinutes = 181, // 3 h 01 min
            sourceType = "APP_TRACKING"
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = listOf(recording),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = mapOf(anchor to minutes(187)) // 3 h 07 min
        )

        // 3 h 07 min — nicht 6 h 08 min.
        assertThat(result.currentValue).isWithin(0.001f).of(187f / 60f)
        assertThat(result.progressText).isEqualTo("3h 7m von maximal 3 hours")
    }

    @Test
    fun `digital goal ignores the screen recording twice as before`() {
        val goal = digitalGoal(targetHours = 3f)
        val recording = session(
            id = "screen-recording",
            typeId = "digital",
            startHour = 10,
            durationMinutes = 181,
            sourceType = "SCREEN_AUTO"
        )

        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = listOf(recording),
            anchorDate = anchor,
            zoneId = zone,
            typeMap = typeMap,
            balanceMsPerDay = mapOf(anchor to minutes(187))
        )

        assertThat(result.currentValue).isWithin(0.001f).of(187f / 60f)
    }

    @Test
    fun `digital history bar for today uses the balance value only`() {
        val goal = digitalGoal(targetHours = 3f)
        val recording = session(
            id = "pc-digital",
            typeId = "digital",
            startHour = 10,
            durationMinutes = 181,
            sourceType = "PC_REMOTE"
        )

        val history = GoalProgressAnalytics.buildHistory(
            goal = goal,
            sessions = listOf(recording),
            anchorDate = anchor,
            zoneId = zone,
            balanceMsPerDay = mapOf(anchor to minutes(187))
        )

        assertThat(history.last().value).isWithin(0.001f).of(187f / 60f)
    }

    private fun digitalGoal(targetHours: Float) = Goal(
        id = "digital-goal",
        title = "Bildschirmzeit unter 3h",
        type = GoalProgressAnalytics.TYPE_AT_MOST,
        period = "DAILY",
        targetValue = targetHours,
        targetUnit = "HOURS",
        trackingMode = GoalProgressAnalytics.MODE_AUTO,
        sourceKind = GoalProgressAnalytics.SOURCE_DIGITAL
    )

    private fun session(
        id: String,
        typeId: String,
        startHour: Int,
        durationMinutes: Int,
        sourceType: String
    ): ActivitySession {
        val start = anchor.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id,
            title = "Digital",
            categoryId = "digital",
            activityTypeId = typeId,
            startAt = start,
            endAt = start + minutes(durationMinutes),
            timezoneId = zone.id,
            sourceType = sourceType
        )
    }

    private fun minutes(value: Int): Long = value * 60_000L
}
