package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.goals.GoalCelebrationStore
import com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationPolicy
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.data.repository.TodoRepository
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.domain.digital.DigitalBalanceSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * M20: Detail-Ansicht eines Ziels — Stats, Streaks, Verlauf/Heatmap und
 * (bei manuellen Zielen) Abhaken samt Check-in-Liste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalDetailViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val categoryRepository: CategoryRepository,
    private val todoRepository: TodoRepository,
    private val balanceSource: DigitalBalanceSource,
    private val celebrationStore: GoalCelebrationStore
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()

    private val goalIdFlow = MutableStateFlow<String?>(null)
    private val historyBalance = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())
    private val _celebration = MutableStateFlow<GoalCelebrationEvent?>(null)
    private val _closed = MutableStateFlow(false)

    private val goalFlow = goalIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else goalRepository.getById(id)
    }
    private val checkinsFlow = goalIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else goalRepository.getCheckinsForGoal(id)
    }

    fun load(goalId: String) {
        if (goalIdFlow.value == goalId) return
        goalIdFlow.value = goalId
        viewModelScope.launch {
            try {
                historyBalance.value = balanceSource.dailyTotalsInRange(
                    startDate = today.minusDays((HISTORY_FETCH_DAYS - 1).toLong()),
                    endDateExclusive = today.plusDays(1)
                ).first()
            } catch (_: Exception) {
                // Kein Bildschirmzeit-Zugriff — Rest der Auswertung läuft.
            }
        }
    }

    val uiState: StateFlow<GoalDetailUiState> = combine(
        goalFlow,
        checkinsFlow,
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        balanceSource.dailyTotals(GoalsViewModel.LOOKBACK_DAYS),
        historyBalance,
        todoRepository.getAllCompletions(),
        _celebration,
        _closed,
        // M22: laufende Aufzeichnung live mitzählen (10-s-Takt).
        com.d_drostes_apps.aevum.domain.analytics.LiveRefreshTicker.ticker()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val goal = values[0] as Goal?
        @Suppress("UNCHECKED_CAST")
        val checkins = values[1] as List<GoalCheckin>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[2] as List<ActivitySession>
        @Suppress("UNCHECKED_CAST")
        val types = values[3] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val categories = values[4] as List<Category>
        @Suppress("UNCHECKED_CAST")
        val balance32 = values[5] as Map<LocalDate, Long>
        @Suppress("UNCHECKED_CAST")
        val balanceHistory = values[6] as Map<LocalDate, Long>
        @Suppress("UNCHECKED_CAST")
        val completions = values[7] as List<TodoCompletion>
        val celebration = values[8] as GoalCelebrationEvent?
        val closed = values[9] as Boolean

        if (goal == null) {
            return@combine GoalDetailUiState(loading = false, celebration = celebration, closed = closed)
        }
        val typeMap = types.associateBy { it.id }
        val balance = balanceHistory + balance32

        val result = GoalProgressAnalytics.evaluateGoal(
            goal, sessions, today, zoneId, typeMap, balance, checkins, completions
        )
        val history = GoalProgressAnalytics.buildHistory(
            goal, sessions, today, zoneId, balance, checkins, completions
        )
        val progress = result.toGoalWithProgress(
            typeMap,
            categories.associateBy { it.id },
            checkins.count { it.dateKey == today.toString() }
        )

        maybeEmitCelebration(progress)

        GoalDetailUiState(
            loading = false,
            goal = goal,
            progress = progress,
            history = history,
            checkins = checkins,
            categoryName = goal.categoryId?.let { cid -> categories.firstOrNull { it.id == cid }?.name },
            celebration = celebration,
            closed = closed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDetailUiState())

    private fun maybeEmitCelebration(result: GoalWithProgress) {
        if (_celebration.value != null) return
        val candidate = GoalCelebrationPolicy.candidate(result) ?: return
        if (!celebrationStore.shouldCelebrate(candidate.goalId, candidate.periodKey)) return
        celebrationStore.markCelebrated(candidate.goalId, candidate.periodKey)
        _celebration.value = GoalCelebrationEvent(
            goalId = candidate.goalId,
            goalTitle = candidate.goalTitle,
            streak = candidate.streak,
            periodLabel = candidate.periodLabel,
            accent = candidate.accent
        )
    }

    fun consumeCelebration() {
        _celebration.value = null
    }

    // ── Aktionen ─────────────────────────────────────────────────────────

    fun checkIn() {
        val id = goalIdFlow.value ?: return
        viewModelScope.launch {
            goalRepository.addCheckin(
                GoalCheckin(
                    id = UUID.randomUUID().toString(),
                    goalId = id,
                    timestampMs = System.currentTimeMillis(),
                    dateKey = LocalDate.now(zoneId).toString()
                )
            )
        }
    }

    fun undoLastCheckin() {
        val id = goalIdFlow.value ?: return
        viewModelScope.launch {
            val last = goalRepository.lastCheckin(id) ?: return@launch
            if (last.dateKey == LocalDate.now(zoneId).toString()) {
                goalRepository.removeCheckin(last.id)
                celebrationStore.forget(id)
            }
        }
    }

    /** Löscht einen einzelnen Check-in (Verlaufs-Liste, Checkbox → Papierkorb). */
    fun removeCheckin(checkinId: String) {
        val id = goalIdFlow.value ?: return
        viewModelScope.launch {
            goalRepository.removeCheckin(checkinId)
            celebrationStore.forget(id)
        }
    }

    fun setArchived(archived: Boolean) {
        val id = goalIdFlow.value ?: return
        viewModelScope.launch {
            val goal = goalRepository.getById(id).first() ?: return@launch
            goalRepository.update(
                goal.copy(
                    status = if (archived) "ARCHIVED" else "ACTIVE",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteGoal() {
        val id = goalIdFlow.value ?: return
        viewModelScope.launch {
            goalRepository.delete(id)
            celebrationStore.forget(id)
            _closed.value = true
        }
    }

    companion object {
        private const val HISTORY_FETCH_DAYS = GoalsViewModel.HISTORY_FETCH_DAYS
    }
}

data class GoalDetailUiState(
    val loading: Boolean = true,
    val goal: Goal? = null,
    val progress: GoalWithProgress? = null,
    val history: List<GoalProgressAnalytics.PeriodStatus> = emptyList(),
    val checkins: List<GoalCheckin> = emptyList(),
    val categoryName: String? = null,
    val celebration: GoalCelebrationEvent? = null,
    val closed: Boolean = false
)
