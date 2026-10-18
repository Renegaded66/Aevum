package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.goals.GoalCelebrationStore
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
import com.d_drostes_apps.aevum.ui.screens.goals.GoalCelebrationEvent
import com.d_drostes_apps.aevum.ui.screens.goals.GoalWithProgress
import com.d_drostes_apps.aevum.ui.screens.goals.GoalsViewModel
import com.d_drostes_apps.aevum.ui.screens.goals.toGoalWithProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * M21: Ziele auf dem Dashboard — der „Dopamin-Block" direkt unter dem Hero.
 *
 * Bewusst ein EIGENES ViewModel neben dem (sehr großen) [DashboardViewModel]:
 *  - eigene Datenquellen (Ziele, Check-ins, Bildschirmzeit-Verlauf),
 *  - eigene Aktionen (Abhaken/Rückgängig direkt auf dem Dashboard),
 *  - identische Auswertung wie der Ziele-Screen
 *    ([GoalProgressAnalytics.evaluateGoal] + `toGoalWithProgress`).
 *
 * Es werden nur die Ziele gezeigt, die HEUTE fällig sind oder bereits
 * Fortschritt haben (max. [MAX_CARD_GOALS]) — der Rest wartet im
 * Ziele-Screen, den der „Alle"-Knopf öffnet.
 */
@HiltViewModel
class DashboardGoalsViewModel @Inject constructor(
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

    /** Langer Verlauf für Streaks (einmal je Dashboard-Öffnung). */
    private val historyBalance = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    private val _celebration = MutableStateFlow<GoalCelebrationEvent?>(null)
    val celebration: StateFlow<GoalCelebrationEvent?> = _celebration

    init {
        viewModelScope.launch {
            try {
                historyBalance.value = balanceSource.dailyTotalsInRange(
                    startDate = today.minusDays((HISTORY_FETCH_DAYS - 1).toLong()),
                    endDateExclusive = today.plusDays(1)
                ).first()
            } catch (_: Exception) {
                // Kein Bildschirmzeit-Zugriff — der Rest der Ziele läuft trotzdem.
            }
        }
    }

    val goals: StateFlow<List<GoalWithProgress>> = combine(
        goalRepository.getByStatus("ACTIVE"),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        balanceSource.dailyTotals(LOOKBACK_DAYS),
        historyBalance,
        goalRepository.getCheckinsSince(checkinLookbackStartMs()),
        todoRepository.getAllCompletions(),
        // M22: laufende Aufzeichnung live mitzählen (10-s-Takt).
        com.d_drostes_apps.aevum.domain.analytics.LiveRefreshTicker.ticker()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val activeGoals = values[0] as List<Goal>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[1] as List<ActivitySession>
        @Suppress("UNCHECKED_CAST")
        val types = values[2] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val categories = values[3] as List<Category>
        @Suppress("UNCHECKED_CAST")
        val balance32 = values[4] as Map<LocalDate, Long>
        @Suppress("UNCHECKED_CAST")
        val balanceHistory = values[5] as Map<LocalDate, Long>
        @Suppress("UNCHECKED_CAST")
        val checkins = values[6] as List<GoalCheckin>
        @Suppress("UNCHECKED_CAST")
        val completions = values[7] as List<TodoCompletion>

        val typeMap = types.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val checkinsByGoal = checkins.groupBy { it.goalId }
        val todayKey = today.toString()
        val todayCounts = checkins.filter { it.dateKey == todayKey }
            .groupingBy { it.goalId }.eachCount()

        val all = activeGoals
            .map { goal ->
                GoalProgressAnalytics.evaluateGoal(
                    goal = goal,
                    sessions = sessions,
                    anchorDate = today,
                    zoneId = zoneId,
                    typeMap = typeMap,
                    balanceMsPerDay = balanceHistory + balance32,
                    checkins = checkinsByGoal[goal.id] ?: emptyList(),
                    todoCompletions = completions
                ).toGoalWithProgress(typeMap, categoryMap, todayCounts[goal.id] ?: 0)
            }
            .sortedWith(
                compareByDescending<GoalWithProgress> { it.isDueToday && !it.isMet }
                    .thenByDescending { it.isDueToday }
                    .thenByDescending { it.progress.coerceAtMost(1f) }
                    .thenBy { it.goal.title.lowercase() }
            )

        maybeEmitCelebration(all)
        all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Nur die obersten Ziele — „Alle" öffnet den Ziele-Screen. */
    fun cardGoals(all: List<GoalWithProgress>): List<GoalWithProgress> =
        all.filter { it.isDueToday || it.progress > 0f }.take(MAX_CARD_GOALS)

    fun hiddenCount(all: List<GoalWithProgress>): Int =
        (all.count { it.isDueToday || it.progress > 0f } - MAX_CARD_GOALS).coerceAtLeast(0)

    /** Feier genau einmal je Ziel+Periode (Store dedupliziert global). */
    private fun maybeEmitCelebration(goals: List<GoalWithProgress>) {
        if (_celebration.value != null) return
        val candidate = goals.firstOrNull { g ->
            g.isDueToday && g.isMet &&
                celebrationStore.shouldCelebrate(g.goal.id, g.periodKey)
        } ?: return
        celebrationStore.markCelebrated(candidate.goal.id, candidate.periodKey)
        _celebration.value = GoalCelebrationEvent(
            goalId = candidate.goal.id,
            goalTitle = candidate.goal.title,
            streak = candidate.streakCurrent,
            periodLabel = candidate.periodLabel,
            accent = candidate.goal.accent
        )
    }

    fun consumeCelebration() {
        _celebration.value = null
    }

    fun checkIn(goalId: String) {
        viewModelScope.launch {
            goalRepository.addCheckin(
                GoalCheckin(
                    id = UUID.randomUUID().toString(),
                    goalId = goalId,
                    timestampMs = System.currentTimeMillis(),
                    dateKey = LocalDate.now(zoneId).toString()
                )
            )
        }
    }

    fun undoLastCheckin(goalId: String) {
        viewModelScope.launch {
            val last = goalRepository.lastCheckin(goalId) ?: return@launch
            if (last.dateKey == LocalDate.now(zoneId).toString()) {
                goalRepository.removeCheckin(last.id)
                celebrationStore.forget(goalId)
            }
        }
    }

    private fun checkinLookbackStartMs(): Long =
        today.minusDays((HISTORY_FETCH_DAYS - 1).toLong())
            .atStartOfDay(zoneId).toInstant().toEpochMilli()

    companion object {
        /** So viele Ziele zeigt die Dashboard-Karte (Rest über „Alle"). */
        const val MAX_CARD_GOALS = 4

        /** Muss die Analytics-Fenster decken (26 Wochen = 182 Tage). */
        const val HISTORY_FETCH_DAYS = 192

        /** Kurz-Takt der Bildschirmzeit (Monatsziel). */
        const val LOOKBACK_DAYS = GoalsViewModel.LOOKBACK_DAYS
    }
}
