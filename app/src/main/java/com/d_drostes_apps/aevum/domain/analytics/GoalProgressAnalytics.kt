package com.d_drostes_apps.aevum.domain.analytics

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Pure Kotlin analytics for Goals progress.
 * Testable without Android/Hilt dependencies.
 */
object GoalProgressAnalytics {

    data class GoalProgressResult(
        val goal: Goal,
        val currentValue: Float,
        val targetValue: Float,
        val progress: Float, // 0.0 .. 1.0 (can exceed 1.0 for "at most" goals)
        val periodLabel: String,
        val activityTypeName: String?,
        val isMet: Boolean,
        val progressText: String
    )

    fun evaluateGoal(
        goal: Goal,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        typeMap: Map<String, ActivityType>
    ): GoalProgressResult {
        val (periodStart, periodEnd) = getPeriodWindow(goal.period, anchorDate, zoneId)
        val unitMultiplier = getUnitMultiplier(goal.targetUnit)

        val relevantSessions = sessions.filter { session ->
            session.deletedAt == null &&
                session.activityTypeId == goal.activityTypeId &&
                session.endAt != null &&
                session.startAt < periodEnd &&
                session.endAt!! > periodStart
        }

        var totalMs = 0L
        relevantSessions.forEach { session ->
            val clippedStart = session.startAt.coerceAtLeast(periodStart)
            val clippedEnd = session.endAt!!.coerceAtMost(periodEnd)
            totalMs += (clippedEnd - clippedStart).coerceAtLeast(0L)
        }

        val currentValue = totalMs.toFloat() / unitMultiplier.toFloat()
        val targetValue = goal.targetValue
        val progress = if (targetValue > 0f) currentValue / targetValue else 0f

        val isAtMost = goal.type == "AT_MOST"
        val isMet = if (isAtMost) currentValue <= targetValue else currentValue >= targetValue

        val progressText = buildProgressText(currentValue, targetValue, goal.targetUnit, isAtMost)

        return GoalProgressResult(
            goal = goal,
            currentValue = currentValue,
            targetValue = targetValue,
            progress = progress,
            periodLabel = periodLabel(goal.period),
            activityTypeName = typeMap[goal.activityTypeId]?.name,
            isMet = isMet,
            progressText = progressText
        )
    }

    private fun buildProgressText(currentValue: Float, targetValue: Float, unit: String, isAtMost: Boolean): String {
        val formatted = formatValue(currentValue, unit)
        val targetFormatted = "${targetValue.toInt()} ${unit.lowercase()}"
        return if (isAtMost) {
            "$formatted von maximal $targetFormatted"
        } else {
            "$formatted von $targetFormatted"
        }
    }

    private fun formatValue(value: Float, unit: String): String {
        return when (unit.uppercase()) {
            "HOURS", "HOUR", "H" -> {
                val hours = value.toInt()
                val minutes = ((value - hours) * 60).toInt()
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            }
            "MINUTES", "MINUTE", "MIN", "M" -> "${value.toInt()}m"
            "SESSIONS", "SESSIONS", "S" -> "${value.toInt()}×"
            "DAYS", "DAY", "D" -> "${value.toInt()} Tage"
            "PERCENT", "%" -> "${value.toInt()}%"
            else -> "${"%.1f".format(value)} $unit"
        }
    }

    private fun getPeriodWindow(period: String, anchorDate: LocalDate, zoneId: ZoneId): Pair<Long, Long> {
        val startDate = when (period) {
            "DAILY" -> anchorDate
            "WEEKLY" -> anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "MONTHLY" -> anchorDate.withDayOfMonth(1)
            else -> anchorDate
        }
        val endDateExclusive = when (period) {
            "DAILY" -> startDate.plusDays(1)
            "WEEKLY" -> startDate.plusWeeks(1)
            "MONTHLY" -> startDate.plusMonths(1)
            else -> startDate.plusDays(1)
        }
        val start = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = endDateExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        return start to end
    }

    private fun periodLabel(period: String): String = when (period) {
        "DAILY" -> "Heute"
        "WEEKLY" -> "Diese Woche"
        "MONTHLY" -> "Diesen Monat"
        else -> period
    }

    private fun getUnitMultiplier(unit: String): Long = when (unit.uppercase()) {
        "HOURS", "HOUR", "H" -> 60 * 60 * 1000L
        "MINUTES", "MINUTE", "MIN", "M" -> 60 * 1000L
        "SECONDS", "SECOND", "SEC", "S" -> 1000L
        else -> 60 * 60 * 1000L
    }
}
