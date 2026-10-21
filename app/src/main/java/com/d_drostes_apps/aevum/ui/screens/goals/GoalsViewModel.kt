package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * M20: Ziel-Architektur — die Ziele-Liste.
 *
 * Lädt neben den Zielen selbst die kompletten Auswertungs-Grundlagen
 * (Sessions, Aktivitätstypen, Kategorien, Bildschirmzeit, Check-ins,
 * erledigte Aufgaben) und berechnet je Ziel Fortschritt, Streaks und die
 * aktuelle Periode in EINEM Durchlauf ([GoalProgressAnalytics.evaluateGoal]).
 *
 * Bildschirmzeit kommt aus zwei Quellen:
 *  - [DigitalBalanceSource.dailyTotals] (32 Tage, 60-s-Takt) für die
 *    AKTUELLEN Werte („heute bisher 37 min"),
 *  - ein EINMALIGER langer Abruf ([historyBalance], ~192 Tage) für
 *    Streaks, Diagramme und Heatmap — der ist zu teuer für den Minutentakt.
 */
@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val categoryRepository: CategoryRepository,
    private val todoRepository: TodoRepository,
    // M18.138: Bildschirmzeit-Quelle (Digital Balance).
    private val balanceSource: DigitalBalanceSource,
    private val celebrationStore: GoalCelebrationStore
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()

    /** Langer Verlauf (einmal je Screen-Öffnung) — überlagert vom 32-Tage-Takt. */
    private val historyBalance = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    private val _celebration = MutableStateFlow<GoalCelebrationEvent?>(null)
    val celebration: StateFlow<GoalCelebrationEvent?> = _celebration

    private val _showCompleted = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                historyBalance.value = balanceSource.dailyTotalsInRange(
                    startDate = today.minusDays((HISTORY_FETCH_DAYS - 1).toLong()),
                    endDateExclusive = today.plusDays(1)
                ).first()
            } catch (_: Exception) {
                // Bildschirmzeit nicht verfügbar (kein Zugriff) — der
                // Rest der Ziele funktioniert trotzdem.
            }
        }
    }

    val uiState: StateFlow<GoalsUiState> = combine(
        goalRepository.getByStatus("ACTIVE"),
        goalRepository.getByStatus("ARCHIVED"),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        // M18.138: Bildschirmzeit (Digital Balance) — Ziele auf „Digital"
        // messen die echte Bildschirmzeit, nicht die Aufzeichnung. 32 Tage
        // deckten den Monatsfall; die 60-Sekunden-Aktualität kommt von hier.
        balanceSource.dailyTotals(LOOKBACK_DAYS),
        historyBalance,
        goalRepository.getCheckinsSince(checkinLookbackStartMs()),
        todoRepository.getAllCompletions(),
        _celebration,
        _showCompleted,
        // M22: laufende Aufzeichnung live mitzählen (10-s-Takt).
        com.d_drostes_apps.aevum.domain.analytics.LiveRefreshTicker.ticker()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val activeGoals = values[0] as List<Goal>
        @Suppress("UNCHECKED_CAST")
        val archivedGoals = values[1] as List<Goal>
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
        val checkins = values[7] as List<GoalCheckin>
        @Suppress("UNCHECKED_CAST")
        val completions = values[8] as List<TodoCompletion>
        val celebration = values[9] as GoalCelebrationEvent?
        val showCompleted = values[10] as Boolean

        buildState(
            activeGoals, archivedGoals, sessions, types, categories,
            balanceHistory + balance32, checkins, completions,
            celebration, showCompleted
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    private fun buildState(
        activeGoals: List<Goal>,
        archivedGoals: List<Goal>,
        sessions: List<ActivitySession>,
        types: List<ActivityType>,
        categories: List<Category>,
        balance: Map<LocalDate, Long>,
        checkins: List<GoalCheckin>,
        completions: List<TodoCompletion>,
        celebration: GoalCelebrationEvent?,
        showCompleted: Boolean
    ): GoalsUiState {
        val typeMap = types.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val checkinsByGoal = checkins.groupBy { it.goalId }
        val todayKey = today.toString()
        val todayCounts = checkins.filter { it.dateKey == todayKey }
            .groupingBy { it.goalId }.eachCount()

        fun evaluate(goal: Goal) = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = sessions,
            anchorDate = today,
            zoneId = zoneId,
            typeMap = typeMap,
            balanceMsPerDay = balance,
            checkins = checkinsByGoal[goal.id] ?: emptyList(),
            todoCompletions = completions
        )

        val active = activeGoals.map(::evaluate)
            .sortedWith(progressComparator())
            .map { it.toGoalWithProgress(typeMap, categoryMap, todayCounts[it.goal.id] ?: 0) }
        val archived = archivedGoals.map(::evaluate)
            .sortedWith(progressComparator())
            .map { it.toGoalWithProgress(typeMap, categoryMap, todayCounts[it.goal.id] ?: 0) }

        maybeEmitCelebration(active)

        val due = active.filter { it.isDueToday }
        return GoalsUiState(
            loading = false,
            activeGoals = active,
            inactiveGoals = archived,
            showCompleted = showCompleted,
            summary = GoalsSummary(
                dueToday = due.size,
                metDue = due.count { it.isCurrentAchieved },
                bestStreak = active.maxOfOrNull { it.streakCurrent } ?: 0,
                totalActive = active.size
            ),
            celebration = celebration
        )
    }

    /**
     * Feier genau einmal je Ziel+Periode. Läuft im Zustands-Aufbau; das
     * Merken passiert SOFORT, damit der erneute Aufbau (z. B. durch die
     * nächste Bildschirmzeit-Aktualisierung) nicht doppelt feuert.
     */
    private fun maybeEmitCelebration(goals: List<GoalWithProgress>) {
        if (_celebration.value != null) return
        val candidate = goals.firstNotNullOfOrNull { g ->
            val celebration = GoalCelebrationPolicy.candidate(g)
            celebration?.takeIf {
                celebrationStore.shouldCelebrate(g.goal.id, it.periodKey)
            }
        } ?: return
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

    /** Fortschritts-Rang: fällig & offen zuerst, dann Fortschritt absteigend. */
    private fun progressComparator(): Comparator<GoalProgressAnalytics.GoalProgressResult> =
        compareByDescending<GoalProgressAnalytics.GoalProgressResult> { it.isDueToday && !it.isCurrentAchieved }
            .thenByDescending { it.isDueToday }
            .thenByDescending { it.progress.coerceAtMost(1f) }
            .thenBy { it.goal.title.lowercase() }

    // ── Aktionen ─────────────────────────────────────────────────────────

    /** Manuelles Abhaken/Zählen: ein Check-in. */
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

    /** Nimmt den letzten Check-in von HEUTE zurück (Historie bleibt unberührt). */
    fun undoLastCheckin(goalId: String) {
        viewModelScope.launch {
            val last = goalRepository.lastCheckin(goalId) ?: return@launch
            if (last.dateKey == LocalDate.now(zoneId).toString()) {
                goalRepository.removeCheckin(last.id)
                celebrationStore.forget(goalId)
            }
        }
    }

    fun setArchived(goalId: String, archived: Boolean) {
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).first() ?: return@launch
            goalRepository.update(
                goal.copy(
                    status = if (archived) "ARCHIVED" else "ACTIVE",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            goalRepository.delete(goalId)
            celebrationStore.forget(goalId)
        }
    }

    fun setShowCompleted(show: Boolean) {
        _showCompleted.value = show
    }

    private fun checkinLookbackStartMs(): Long =
        today.minusDays((HISTORY_FETCH_DAYS - 1).toLong())
            .atStartOfDay(zoneId).toInstant().toEpochMilli()

    companion object {
        /**
         * M18.138: Rückblick-Fenster des Kurz-Takts. Ziele gibt es
         * täglich/wöchentlich/monatlich → 32 Tage decken den Monatsfall.
         * Öffentlich, weil auch die Detail-Ansicht ([GoalDetailViewModel])
         * denselben Takt nutzt.
         */
        const val LOOKBACK_DAYS = 32

        /**
         * M20: Fenster des langen Verlaufs. Muss die Analytics-Fenster
         * abdecken: 91 Tage (täglich), 26 Wochen = 182 Tage (wöchentlich),
         * 6 Monate ≈ 183 Tage (monatlich) → 192 Tage mit Puffer.
         */
        const val HISTORY_FETCH_DAYS = 192
    }
}

// ── Präsentations-Modelle ────────────────────────────────────────────────

fun GoalProgressAnalytics.GoalProgressResult.toGoalWithProgress(
    typeMap: Map<String, ActivityType>,
    categoryMap: Map<String, Category>,
    todayCheckins: Int = 0
) = GoalWithProgress(
    goal = goal,
    currentValue = currentValue,
    progress = progress,
    periodLabel = periodLabel,
    activityTypeName = activityTypeName,
    progressText = progressText,
    isMet = isMet,
    isDueToday = isDueToday,
    streakCurrent = streakCurrent,
    streakBest = streakBest,
    metPeriods = metPeriods,
    duePeriods = duePeriods,
    periodKey = periodKey,
    isCountType = isCountType,
    categoryName = goal.categoryId?.let { categoryMap[it]?.name },
    activityColorLong = goal.activityTypeId?.let { typeMap[it]?.color },
    todayCheckins = todayCheckins,
    isMetFinal = isMetFinal,
    isPendingFinal = isPendingFinal,
    isCurrentAchieved = isCurrentAchieved,
    lastCompletedMet = lastCompletedMet,
    celebrationEligible = celebrationEligible,
    celebrationPeriodKey = celebrationPeriodKey,
    celebrationPeriodLabel = celebrationPeriodLabel,
    celebrationStreak = celebrationStreak
)

data class GoalWithProgress(
    val goal: Goal,
    val currentValue: Float,
    val progress: Float,
    val periodLabel: String,
    val activityTypeName: String?,
    val progressText: String = "",
    val isMet: Boolean = false,
    /** M20: Ist die aktuelle Periode fällig? false = freier Tag / freie Woche. */
    val isDueToday: Boolean = true,
    val streakCurrent: Int = 0,
    val streakBest: Int = 0,
    /** M20: erfüllte / fällige Perioden im Verlaufsfenster (Detail-Statistik). */
    val metPeriods: Int = 0,
    val duePeriods: Int = 0,
    val periodKey: String = "",
    val isCountType: Boolean = false,
    val categoryName: String? = null,
    /** Aktivitätsfarbe (Long-ARGB aus der Aktivität) — Akzent-Fallback. */
    val activityColorLong: Long? = null,
    /** Heutige Check-ins (manuelle Ziele) — steuert Knopf-Zustand/Rückgängig. */
    val todayCheckins: Int = 0,
    // ── M27: verzögerte Wertung ───────────────────────────────────────────
    /** Abgeschlossene Wertung: bei Auto-Zielen die letzte volle Periode. */
    val isMetFinal: Boolean = false,
    /** Auto-Ziel, laufende Periode — noch keine Wertung. */
    val isPendingFinal: Boolean = false,
    /** Für „Heute erfüllt"-Anzeige: live (manuell) bzw. false solange offen. */
    val isCurrentAchieved: Boolean = false,
    /** Letzte abgeschlossene Periode war erfüllt (null = keine Daten). */
    val lastCompletedMet: Boolean? = null,
    val celebrationEligible: Boolean = false,
    val celebrationPeriodKey: String = "",
    val celebrationPeriodLabel: String = "",
    val celebrationStreak: Int = 0
)

data class GoalsSummary(
    val dueToday: Int = 0,
    val metDue: Int = 0,
    val bestStreak: Int = 0,
    val totalActive: Int = 0
)

data class GoalCelebrationEvent(
    val goalId: String,
    val goalTitle: String,
    val streak: Int,
    val periodLabel: String,
    val accent: String?
)

data class GoalsUiState(
    val loading: Boolean = true,
    val activeGoals: List<GoalWithProgress> = emptyList(),
    val inactiveGoals: List<GoalWithProgress> = emptyList(),
    val showCompleted: Boolean = false,
    val summary: GoalsSummary = GoalsSummary(),
    val celebration: GoalCelebrationEvent? = null
)
