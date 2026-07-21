package de.devondroste.aevum.ui.screens.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.Habit
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.HabitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HabitsViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val _showCompleted = MutableStateFlow(false)

    val uiState: Flow<HabitsUiState> = combine(
        habitRepository.getActive(),
        habitRepository.getAll(),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        _showCompleted
    ) { activeHabits, allHabits, sessions, activityTypes, showCompleted ->
        val inactiveHabits = allHabits.filter { it.active == false }
        buildState(activeHabits, inactiveHabits, sessions, activityTypes, showCompleted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState())

    fun toggleShowCompleted() {
        _showCompleted.update { !it }
    }

    private fun buildState(
        activeHabits: List<Habit>,
        inactiveHabits: List<Habit>,
        sessions: List<ActivitySession>,
        activityTypes: List<de.devondroste.aevum.data.model.ActivityType>,
        showCompleted: Boolean
    ): HabitsUiState {
        val typeMap = activityTypes.associateBy { it.id }
        
        val active = activeHabits.map { habit ->
            calculateProgress(habit, sessions, typeMap)
        }.sortedByDescending { it.streak }

        val inactive = if (showCompleted) {
            inactiveHabits.map { habit ->
                calculateProgress(habit, sessions, typeMap)
            }.sortedByDescending { it.streak }
        } else emptyList()

        return HabitsUiState(
            activeHabits = active,
            inactiveHabits = inactive,
            showCompleted = showCompleted
        )
    }

    private fun calculateProgress(
        habit: Habit,
        sessions: List<ActivitySession>,
        typeMap: Map<String, de.devondroste.aevum.data.model.ActivityType>
    ): HabitWithProgress {
        val frequency = parseFrequencyRule(habit.frequencyRuleJson)
        val frequencyLabel = frequencyLabel(frequency)
        
        val heatmap = buildHeatmap(habit, sessions, 28)
        val streak = calculateStreak(habit, sessions)
        val (activeDays, successRate) = calculateSuccessRate(habit, sessions, frequency)
        val activityTypeName = typeMap[habit.activityTypeId]?.name

        return HabitWithProgress(
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

    private fun buildHeatmap(habit: Habit, sessions: List<ActivitySession>, days: Int): List<HeatmapDay> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val logsMap = mutableMapOf<String, Boolean>()
        
        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt), 
                    zoneId
                )
                val dateStr = date.format(formatter)
                logsMap[dateStr] = true
            }

        return (0 until days).map { i ->
            val date = today.minusDays(i.toLong())
            val dateStr = date.format(formatter)
            val completed = logsMap[dateStr] == true
            HeatmapDay(
                date = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                completed = completed,
                intensity = if (completed) 0.8f else 0.2f
            )
        }.reversed()
    }

    private fun calculateStreak(habit: Habit, sessions: List<ActivitySession>): Int {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val logsMap = mutableMapOf<String, Boolean>()
        
        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt), 
                    zoneId
                )
                val dateStr = date.format(formatter)
                logsMap[dateStr] = true
            }

        var streak = 0
        var checkDate = today.minusDays(1)
        val frequency = parseFrequencyRule(habit.frequencyRuleJson)
        
        while (true) {
            val dateStr = checkDate.format(formatter)
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
                if (checkDate.isBefore(today.minusDays(365))) break
            }
        }
        
        return streak
    }

    private fun calculateSuccessRate(habit: Habit, sessions: List<ActivitySession>, frequency: FrequencyRule): Pair<Int, Int> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val logsMap = mutableMapOf<String, Boolean>()
        
        sessions.filter { it.activityTypeId == habit.activityTypeId && it.deletedAt == null && it.endAt != null }
            .forEach { session ->
                val date = LocalDate.ofInstant(
                    java.time.Instant.ofEpochMilli(session.startAt), 
                    zoneId
                )
                val dateStr = date.format(formatter)
                logsMap[dateStr] = true
            }

        val days = 28
        var activeDays = 0
        var completedDays = 0
        
        (0 until days).forEach { i ->
            val date = today.minusDays(i.toLong())
            val dateStr = date.format(formatter)
            val expected = when (frequency.type) {
                "daily" -> true
                "weekly" -> date.dayOfWeek == java.time.DayOfWeek.MONDAY
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