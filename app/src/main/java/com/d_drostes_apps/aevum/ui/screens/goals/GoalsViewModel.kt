package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
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
    private val activityTypeRepository: ActivityTypeRepository,
    // M18.138: Bildschirmzeit-Quelle (Digital Balance).
    private val balanceSource: com.d_drostes_apps.aevum.domain.digital.DigitalBalanceSource
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()

    val uiState: StateFlow<GoalsUiState> = combine(
        goalRepository.getByStatus("ACTIVE"),
        goalRepository.getByStatus("ARCHIVED"),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        // M18.138: Bildschirmzeit (Digital Balance) — Ziele auf „Digital"
        // messen die echte Bildschirmzeit, nicht die Aufzeichnung.
        balanceSource.dailyTotals(LOOKBACK_DAYS)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val activeGoals = values[0] as List<Goal>
        @Suppress("UNCHECKED_CAST")
        val archivedGoals = values[1] as List<Goal>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[2] as List<com.d_drostes_apps.aevum.data.model.ActivitySession>
        @Suppress("UNCHECKED_CAST")
        val types = values[3] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val balance = values[4] as Map<LocalDate, Long>
        buildState(activeGoals, archivedGoals, sessions, types.associateBy { it.id }, balance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    private fun buildState(
        activeGoals: List<Goal>,
        archivedGoals: List<Goal>,
        sessions: List<com.d_drostes_apps.aevum.data.model.ActivitySession>,
        typeMap: Map<String, ActivityType>,
        balanceMsPerDay: Map<LocalDate, Long> = emptyMap()
    ): GoalsUiState {
        val active = activeGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, sessions, today, zoneId, typeMap, balanceMsPerDay)
        }.sortedWith(goalProgressComparator())

        val archived = archivedGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, sessions, today, zoneId, typeMap, balanceMsPerDay)
        }.sortedWith(goalProgressComparator())

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

    private fun goalProgressComparator(): Comparator<GoalProgressAnalytics.GoalProgressResult> =
        compareBy {
            if (it.goal.type == "AT_MOST") it.currentValue else -it.progress
        }

    companion object {
        /**
         * M18.138: Rückblick-Fenster für die Bildschirmzeit. Ziele gibt es
         * täglich/wöchentlich/monatlich → 32 Tage decken den Monatsfall.
         */
        private const val LOOKBACK_DAYS = 32

        /** For Dashboard: return top N active goals sorted by progress descending */
        fun getTopProgressGoals(
            activeProgress: List<GoalWithProgress>,
            maxCount: Int = 3
        ): List<GoalWithProgress> {
            return activeProgress
                .sortedWith(compareBy {
                    if (it.goal.type == "AT_MOST") it.currentValue else -it.progress
                })
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