package de.devondroste.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Goal
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.GoalRepository
import de.devondroste.aevum.domain.analytics.GoalProgressAnalytics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()

    val uiState: StateFlow<GoalsUiState> = combine(
        goalRepository.getByStatus("ACTIVE"),
        goalRepository.getByStatus("ARCHIVED"),
        activityRepository.getAll(),
        activityTypeRepository.getAll()
    ) { activeGoals, archivedGoals, sessions, types ->
        buildState(activeGoals, archivedGoals, sessions, types.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    private fun buildState(
        activeGoals: List<Goal>,
        archivedGoals: List<Goal>,
        sessions: List<de.devondroste.aevum.data.model.ActivitySession>,
        typeMap: Map<String, ActivityType>
    ): GoalsUiState {
        val active = activeGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, sessions, today, zoneId, typeMap)
        }.sortedByDescending { it.progress }

        val archived = archivedGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, sessions, today, zoneId, typeMap)
        }.sortedByDescending { it.progress }

        return GoalsUiState(
            activeGoals = active.map { it.toGoalWithProgress() },
            inactiveGoals = archived.map { it.toGoalWithProgress() }
        )
    }

    private fun GoalProgressAnalytics.GoalProgressResult.toGoalWithProgress() = GoalWithProgress(
        goal = goal,
        currentValue = currentValue,
        progress = progress,
        periodLabel = periodLabel,
        activityTypeName = activityTypeName,
        progressText = progressText,
        isMet = isMet
    )

    companion object {
        /** For Dashboard: return top N active goals sorted by progress descending */
        fun getTopProgressGoals(
            activeProgress: List<GoalWithProgress>,
            maxCount: Int = 3
        ): List<GoalWithProgress> {
            return activeProgress
                .sortedByDescending { it.progress }
                .take(maxCount)
        }
    }
}

fun GoalProgressAnalytics.GoalProgressResult.toGoalWithProgress() = GoalWithProgress(
    goal = goal,
    currentValue = currentValue,
    progress = progress,
    periodLabel = periodLabel,
    activityTypeName = activityTypeName,
    progressText = progressText,
    isMet = isMet
)

data class GoalWithProgress(
    val goal: Goal,
    val currentValue: Float,
    val progress: Float,
    val periodLabel: String,
    val activityTypeName: String?,
    val progressText: String = "",
    val isMet: Boolean = false
)

data class GoalsUiState(
    val activeGoals: List<GoalWithProgress> = emptyList(),
    val inactiveGoals: List<GoalWithProgress> = emptyList(),
    val showCompleted: Boolean = false
)