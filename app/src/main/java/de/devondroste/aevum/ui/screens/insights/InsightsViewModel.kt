package de.devondroste.aevum.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.GoalRepository
import de.devondroste.aevum.data.repository.HabitRepository
import de.devondroste.aevum.domain.analytics.GoalProgressAnalytics
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.screens.goals.toGoalWithProgress
import de.devondroste.aevum.ui.screens.habits.HabitWithProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val goalRepository: GoalRepository,
    private val habitRepository: HabitRepository
) : ViewModel() {
    private val zoneId = java.time.ZoneId.systemDefault()
    private val anchorDate = java.time.LocalDate.now()
    private val _selectedPeriod = MutableStateFlow(InsightPeriod.Week)
    private val _selectedHeatmapDate = MutableStateFlow<java.time.LocalDate?>(null)

    // Data layer: sessions + categories + types + goals + habits
    private val dataFlow = combine(
        activityRepository.getAll(),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        goalRepository.getByStatus("ACTIVE"),
        habitRepository.getActive()
    ) { sessions, categories, types, goals, habits ->
        DataLayer(sessions, categories, types, goals, habits)
    }

    val uiState: StateFlow<InsightsUiState> = combine(
        dataFlow,
        _selectedPeriod,
        _selectedHeatmapDate
    ) { data, period, heatmapDate ->
        val typeMap = data.types.associateBy { it.id }
        val goalProgress = data.goals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, data.sessions, anchorDate, zoneId, typeMap)
        }.sortedByDescending { it.progress }.map { it.toGoalWithProgress() }
        val habitProgress = data.habits.map { habit ->
            GoalProgressAnalytics.evaluateHabit(habit, data.sessions, anchorDate, zoneId, typeMap)
        }.map { result ->
            HabitWithProgress(
                habit = result.habit,
                streak = result.streak,
                successRate = result.successRate,
                activeDays = result.activeDays,
                totalDays = result.totalDays,
                heatmap = result.heatmap.map { day ->
                    de.devondroste.aevum.ui.screens.habits.HeatmapDay(
                        date = day.date,
                        completed = day.completed,
                        intensity = day.intensity
                    )
                },
                frequencyLabel = result.frequencyLabel,
                activityTypeName = result.activityTypeName
            )
        }
        InsightsAnalytics.build(
            sessions = data.sessions,
            categories = data.categories,
            activityTypes = data.types,
            selectedPeriod = period,
            anchorDate = anchorDate,
            zoneId = zoneId,
            goalProgress = goalProgress,
            habitProgress = habitProgress
        ).copy(selectedHeatmapDate = heatmapDate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun selectPeriod(period: InsightPeriod) {
        _selectedPeriod.value = period
        _selectedHeatmapDate.value = null
    }

    fun selectHeatmapDay(date: java.time.LocalDate) {
        _selectedHeatmapDate.value = date
    }

    private data class DataLayer(
        val sessions: List<ActivitySession>,
        val categories: List<Category>,
        val types: List<ActivityType>,
        val goals: List<de.devondroste.aevum.data.model.Goal>,
        val habits: List<de.devondroste.aevum.data.model.Habit>
    )
}
