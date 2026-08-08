package com.d_drostes_apps.aevum.domain.analytics

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Pure Kotlin analytics for Goals and Habits progress.
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

    data class HabitProgressResult(
        val habit: Habit,
        val streak: Int,
        val successRate: Int,
        val activeDays: Int,
        val totalDays: Int,
        val heatmap: List<HeatmapDay>,
        val frequencyLabel: String,
        val activityTypeName: String?
    )

    data class HeatmapDay(
        val date: Long,
        val completed: Boolean,
        val intensity: Float = 0.6f
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

    fun evaluateHabit(
        habit: Habit,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        typeMap: Map<String, ActivityType>
    ): HabitProgressResult {
        val frequency = parseFrequencyRule(habit.frequencyRuleJson)
        val frequencyLabel = frequencyLabel(frequency)

        val heatmap = buildHeatmap(habit, sessions, anchorDate, zoneId, 28)
        val streak = calculateStreak(habit, sessions, anchorDate, zoneId, frequency)
        val (activeDays, successRate) = calculateSuccessRate(habit, sessions, anchorDate, frequency)
        val activityTypeName = typeMap[habit.activityTypeId]?.name

        return HabitProgressResult(
            habit = habit,
            streak = streak,
            successRate = successRate,
            activeDays = activeDays,
            totalDays = 28,
            heatmap = heatmap,
            frequencyLabel = frequencyLabel,
            activityTypeName = activityTypeName
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

    private data class FrequencyRule(
        val type: String,
        val count: Int = 1
    )

    private fun parseFrequencyRule(json: String): FrequencyRule {
        return try {
            val type = extractJsonString(json, "type") ?: "daily"
            val count = extractJsonInt(json, "count") ?: 1
            FrequencyRule(type = type, count = count)
        } catch (e: Exception) {
            FrequencyRule("daily", 1)
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun frequencyLabel(rule: FrequencyRule): String = when (rule.type) {
        "daily" -> "Täglich"
        "weekly" -> "${rule.count}× pro Woche"
        "monthly" -> "${rule.count}× pro Monat"
        else -> rule.type
    }

    private fun buildHeatmap(
        habit: Habit,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        days: Int
    ): List<HeatmapDay> {
        val logsMap = mutableMapOf<String, Boolean>()

        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt),
                    zoneId
                )
                logsMap[date.toString()] = true
            }

        return (0 until days).map { i ->
            val date = anchorDate.minusDays(i.toLong())
            val dateStr = date.toString()
            val completed = logsMap[dateStr] == true
            HeatmapDay(
                date = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                completed = completed,
                intensity = if (completed) 0.8f else 0.2f
            )
        }.reversed()
    }

    private fun calculateStreak(
        habit: Habit,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        frequency: FrequencyRule
    ): Int {
        val logsMap = mutableMapOf<String, Boolean>()

        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt),
                    zoneId
                )
                logsMap[date.toString()] = true
            }

        var streak = 0
        var checkDate = anchorDate.minusDays(1)

        while (true) {
            val dateStr = checkDate.toString()
            val completed = logsMap[dateStr] == true

            if (completed) {
                streak++
                checkDate = when (frequency.type) {
                    "daily" -> checkDate.minusDays(1)
                    "weekly" -> checkDate.minusWeeks(1)
                    "monthly" -> checkDate.minusMonths(1)
                    else -> checkDate.minusDays(1)
                }
            } else {
                if (frequency.type == "daily") break
                checkDate = when (frequency.type) {
                    "weekly" -> checkDate.minusWeeks(1)
                    "monthly" -> checkDate.minusMonths(1)
                    else -> checkDate.minusDays(1)
                }
                if (checkDate.isBefore(anchorDate.minusDays(365))) break
            }
        }

        return streak
    }

    private fun calculateSuccessRate(
        habit: Habit,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        frequency: FrequencyRule
    ): Pair<Int, Int> {
        val logsMap = mutableMapOf<String, Boolean>()

        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt),
                    java.time.ZoneId.systemDefault()
                )
                logsMap[date.toString()] = true
            }

        val days = 28
        var activeDays = 0
        var completedDays = 0

        (0 until days).forEach { i ->
            val date = anchorDate.minusDays(i.toLong())
            val dateStr = date.toString()
            val expected = when (frequency.type) {
                "daily" -> true
                "weekly" -> date.dayOfWeek == DayOfWeek.MONDAY
                "monthly" -> date.dayOfMonth == 1
                else -> true
            }

            if (expected) {
                activeDays++
                if (logsMap[dateStr] == true) completedDays++
            }
        }

        val successRate = if (activeDays > 0) (completedDays * 100 / activeDays) else 0
        return activeDays to successRate
    }
}