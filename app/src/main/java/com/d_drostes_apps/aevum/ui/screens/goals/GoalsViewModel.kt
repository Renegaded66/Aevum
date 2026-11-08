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
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * M36: Ziele sind jetzt ein Habit-Tracker.
 *
 * Drei Arten, mehr nicht:
 *  - [Goal.KIND_DONE]  — nur abhaken.
 *  - [Goal.KIND_COUNT] — Anzahl sammeln (Zielwert = Anzahl).
 *  - [Goal.KIND_TIME]  — Zeit eintragen; jede Zeit > 0 erfüllt den Tag.
 *
 * Der Screen kann durch Tage swipen. Alle Aktionen bekommen deshalb ein
 * explizites [LocalDate] — so lassen sich die letzten Tage nachträglich
 * abhaken und mit Zeit füllen.
 *
 * Alt-Ziele (AUTO/MANUAL aus M20/M35) bleiben als „Weitere Ziele" sichtbar
 * und werden unverändert ausgewertet; der Editor wandelt sie beim Speichern
 * in eines der drei Habit-Formate um.
 */
@HiltViewModel
class GoalsViewModel @Inject constructor(
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

    /** Langer Verlauf für die Alt-Ziel-Auswertung (Streaks/Diagramme). */
    private val historyBalance = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    private val _celebration = MutableStateFlow<GoalCelebrationEvent?>(null)
    val celebration: StateFlow<GoalCelebrationEvent?> = _celebration

    private val _timePrompt = MutableStateFlow<GoalTimePrompt?>(null)
    val timePrompt: StateFlow<GoalTimePrompt?> = _timePrompt

    private val _showArchived = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                historyBalance.value = balanceSource.dailyTotalsInRange(
                    startDate = today.minusDays((HISTORY_FETCH_DAYS - 1).toLong()),
                    endDateExclusive = today.plusDays(1)
                ).first()
            } catch (_: Exception) {
                // Kein Bildschirmzeit-Zugriff — Alt-Ziele zeigen dann nur
                // ihren gespeicherten Stand. Habits sind davon unberührt.
            }
        }
    }

    val uiState: StateFlow<GoalsUiState> = combine(
        goalRepository.getByStatus("ACTIVE"),
        goalRepository.getByStatus("ARCHIVED"),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        balanceSource.dailyTotals(LOOKBACK_DAYS),
        historyBalance,
        goalRepository.getCheckinsSince(checkinLookbackStartMs()),
        todoRepository.getAllCompletions(),
        _celebration,
        _showArchived,
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
        val showArchived = values[10] as Boolean

        buildState(
            activeGoals = activeGoals,
            archivedGoals = archivedGoals,
            sessions = sessions,
            types = types,
            categories = categories,
            balance = balanceHistory + balance32,
            checkins = checkins,
            completions = completions,
            celebration = celebration,
            showArchived = showArchived
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
        showArchived: Boolean
    ): GoalsUiState {
        val typeMap = types.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val checkinsByGoal = checkins.groupBy { it.goalId }

        fun oldProgress(goal: Goal): GoalWithProgress {
            val result = GoalProgressAnalytics.evaluateGoal(
                goal = goal,
                sessions = sessions,
                anchorDate = today,
                zoneId = zoneId,
                typeMap = typeMap,
                balanceMsPerDay = balance,
                checkins = checkinsByGoal[goal.id] ?: emptyList(),
                todoCompletions = completions
            )
            return result.toGoalWithProgress(
                typeMap = typeMap,
                categoryMap = categoryMap,
                todayCheckins = (checkinsByGoal[goal.id] ?: emptyList())
                    .filter { it.dateKey == today.toString() }
                    .size,
                todayCreditMs = (checkinsByGoal[goal.id] ?: emptyList())
                    .filter { it.dateKey == today.toString() }
                    .sumOf { it.durationMs }
            )
        }

        val habitGoals = activeGoals
            .filter { GoalProgressAnalytics.isHabit(it) }
            .map { goal -> habitUi(goal, checkinsByGoal[goal.id] ?: emptyList(), typeMap, categoryMap) }
            .sortedWith(
                compareByDescending<HabitGoalUi> { it.isDueOn(today) && !it.isDoneOn(today) }
                    .thenByDescending { it.isDueOn(today) }
                    .thenByDescending { it.streak }
                    .thenBy { it.goal.title.lowercase() }
            )

        val legacyGoals = activeGoals
            .filterNot { GoalProgressAnalytics.isHabit(it) }
            .map(::oldProgress)
            .sortedWith(progressComparator())

        val archivedHabits = archivedGoals
            .filter { GoalProgressAnalytics.isHabit(it) }
            .map { habitUi(it, checkinsByGoal[it.id] ?: emptyList(), typeMap, categoryMap) }
        val archivedLegacy = archivedGoals
            .filterNot { GoalProgressAnalytics.isHabit(it) }
            .map(::oldProgress)

        // Der perfekte Tag hat Vorrang: Er ist der größere Dopamin-Moment.
        maybeEmitPerfectDay(habitGoals)
        maybeEmitCelebration(habitGoals, legacyGoals)

        val dueHabits = habitGoals.filter { it.isDueOn(today) }
        val doneHabits = dueHabits.count { it.isDoneOn(today) }
        // Der Hero zählt nur die neuen Habits. Alt-Ziele bleiben in ihrer
        // eigenen Sektion und machen den „perfekten Tag" nicht kaputt.
        val due = dueHabits.size
        val met = doneHabits

        return GoalsUiState(
            loading = false,
            habitGoals = habitGoals,
            legacyGoals = legacyGoals,
            archivedHabitGoals = archivedHabits,
            archivedLegacyGoals = archivedLegacy,
            showArchived = showArchived,
            summary = GoalsSummary(
                dueToday = due,
                metDue = met,
                bestStreak = (habitGoals.maxOfOrNull { it.streak } ?: 0)
                    .coerceAtLeast(legacyGoals.maxOfOrNull { it.streakCurrent } ?: 0),
                totalActive = activeGoals.size,
                perfectToday = due > 0 && met >= due,
                perfectStreak = perfectStreak(habitGoals)
            ),
            celebration = celebration
        )
    }

    private fun habitUi(
        goal: Goal,
        checkins: List<GoalCheckin>,
        typeMap: Map<String, ActivityType>,
        categoryMap: Map<String, Category>
    ): HabitGoalUi {
        val result = GoalProgressAnalytics.evaluateGoal(
            goal = goal,
            sessions = emptyList(),
            anchorDate = today,
            zoneId = zoneId,
            typeMap = typeMap,
            checkins = checkins
        ).toGoalWithProgress(
            typeMap = typeMap,
            categoryMap = categoryMap,
            todayCheckins = checkins.count { it.dateKey == today.toString() },
            todayCreditMs = checkins.filter { it.dateKey == today.toString() }.sumOf { it.durationMs }
        )
        return HabitGoalUi(
            goal = goal,
            kind = GoalProgressAnalytics.kindOf(goal),
            icon = goal.icon?.takeIf { it.isNotBlank() } ?: defaultIcon(GoalProgressAnalytics.kindOf(goal)),
            target = if (GoalProgressAnalytics.kindOf(goal) == Goal.KIND_COUNT) {
                goal.targetValue.toInt().coerceAtLeast(1)
            } else 1,
            progress = result,
            checkins = checkins,
            colorLong = goal.activityTypeId?.let { typeMap[it]?.color },
            categoryName = goal.categoryId?.let { categoryMap[it]?.name }
        )
    }

    /** Ein Ziel pro Tag+Periode — der Store verhindert Wiederholungen. */
    private fun maybeEmitCelebration(habits: List<HabitGoalUi>, legacy: List<GoalWithProgress>) {
        if (_celebration.value != null) return
        val candidates = habits.map { it.progress } + legacy
        val candidate = candidates.firstNotNullOfOrNull { g ->
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

    /** „Perfekter Tag" = alle heute fälligen Habits erledigt. */
    private fun maybeEmitPerfectDay(habits: List<HabitGoalUi>) {
        if (_celebration.value != null) return
        val due = habits.filter { it.isDueOn(today) }
        if (due.isEmpty() || due.any { !it.isDoneOn(today) }) return
        val key = today.toString()
        if (!celebrationStore.shouldCelebrate(PERFECT_DAY_ID, key)) return
        celebrationStore.markCelebrated(PERFECT_DAY_ID, key)
        _celebration.value = GoalCelebrationEvent(
            goalId = PERFECT_DAY_ID,
            goalTitle = "",
            streak = perfectStreak(habits),
            periodLabel = "",
            accent = "gold",
            perfectDay = true
        )
    }

    /** Anzahl kompletter Tage in Folge (heute zählt nur, wenn schon fertig). */
    private fun perfectStreak(habits: List<HabitGoalUi>): Int {
        if (habits.isEmpty()) return 0
        var day = today
        fun perfect(date: LocalDate): Boolean {
            val due = habits.filter { it.isDueOn(date) }
            return due.isNotEmpty() && due.all { it.isDoneOn(date) }
        }
        if (!perfect(day)) day = day.minusDays(1)
        var streak = 0
        while (perfect(day)) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    private fun progressComparator(): Comparator<GoalWithProgress> =
        compareByDescending<GoalWithProgress> { it.isDueToday && !it.isCurrentAchieved }
            .thenByDescending { it.isDueToday }
            .thenByDescending { it.progress.coerceAtMost(1f) }
            .thenBy { it.goal.title.lowercase() }

    // ── Aktionen (immer mit explizitem Tag) ──────────────────────────────

    fun checkIn(goalId: String, date: LocalDate = today) {
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).first() ?: return@launch
            val kind = GoalProgressAnalytics.kindOf(goal)
            val existing = goalRepository.getCheckinsForGoal(goalId).first()
                .filter { it.dateKey == date.toString() }

            if (kind == Goal.KIND_TIME) {
                openTimePrompt(goal, date, existing.firstOrNull { it.durationMs > 0L })
                return@launch
            }
            if (kind == Goal.KIND_DONE && existing.isNotEmpty()) {
                return@launch
            }
            goalRepository.addCheckin(
                GoalCheckin(
                    id = UUID.randomUUID().toString(),
                    goalId = goalId,
                    timestampMs = timestampFor(date),
                    dateKey = date.toString(),
                    count = 1,
                    activityTypeId = null,
                    durationMs = 0L
                )
            )
        }
    }

    fun undo(goalId: String, date: LocalDate = today) {
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).first() ?: return@launch
            val kind = GoalProgressAnalytics.kindOf(goal)
            val existing = goalRepository.getCheckinsForGoal(goalId).first()
                .filter { it.dateKey == date.toString() }
            if (existing.isEmpty()) return@launch
            if (kind == Goal.KIND_TIME) {
                existing.forEach { goalRepository.removeCheckin(it.id) }
            } else {
                existing.maxByOrNull { it.timestampMs }?.let { goalRepository.removeCheckin(it.id) }
            }
            celebrationStore.forget(goalId)
        }
    }

    fun openTimePrompt(goalId: String, date: LocalDate = today) {
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).first() ?: return@launch
            val existing = goalRepository.getCheckinsForGoal(goalId).first()
                .filter { it.dateKey == date.toString() && it.durationMs > 0L }
                .maxByOrNull { it.durationMs }
            openTimePrompt(goal, date, existing)
        }
    }

    private fun openTimePrompt(goal: Goal, date: LocalDate, checkin: GoalCheckin?) {
        _timePrompt.value = GoalTimePrompt(
            goalId = goal.id,
            checkinId = checkin?.id,
            goalTitle = goal.title,
            dateKey = date.toString(),
            initialSeconds = (checkin?.durationMs ?: 0L) / 1000L
        )
    }

    fun confirmTimePrompt(seconds: Int) {
        val prompt = _timePrompt.value ?: return
        viewModelScope.launch {
            val goal = goalRepository.getById(prompt.goalId).first() ?: return@launch
            val date = runCatching { LocalDate.parse(prompt.dateKey) }.getOrDefault(today)
            if (seconds <= 0) {
                goalRepository.getCheckinsForGoal(goal.id).first()
                    .filter { it.dateKey == prompt.dateKey }
                    .forEach { goalRepository.removeCheckin(it.id) }
            } else {
                val existing = goalRepository.getCheckinsForGoal(goal.id).first()
                    .filter { it.dateKey == prompt.dateKey }
                val target = prompt.checkinId?.let { id -> existing.firstOrNull { it.id == id } }
                if (target != null) {
                    goalRepository.setCheckinDuration(target.id, seconds * 1000L)
                    // Etwaige weitere Zeit-Zeilen desselben Tages aufräumen.
                    existing.filter { it.id != target.id }
                        .forEach { goalRepository.removeCheckin(it.id) }
                } else {
                    existing.forEach { goalRepository.removeCheckin(it.id) }
                    goalRepository.addCheckin(
                        GoalCheckin(
                            id = UUID.randomUUID().toString(),
                            goalId = goal.id,
                            timestampMs = timestampFor(date),
                            dateKey = prompt.dateKey,
                            count = 1,
                            activityTypeId = null,
                            durationMs = seconds * 1000L
                        )
                    )
                }
            }
            _timePrompt.value = null
            celebrationStore.forget(goal.id)
        }
    }

    fun removeTimeFromPrompt() {
        val prompt = _timePrompt.value ?: return
        viewModelScope.launch {
            goalRepository.getCheckinsForGoal(prompt.goalId).first()
                .filter { it.dateKey == prompt.dateKey }
                .forEach { goalRepository.removeCheckin(it.id) }
            _timePrompt.value = null
            celebrationStore.forget(prompt.goalId)
        }
    }

    fun dismissTimePrompt() {
        _timePrompt.value = null
    }

    fun consumeCelebration() {
        _celebration.value = null
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

    fun setShowArchived(show: Boolean) {
        _showArchived.value = show
    }

    private fun timestampFor(date: LocalDate): Long {
        return date.atTime(LocalTime.now()).atZone(zoneId).toInstant().toEpochMilli()
    }

    private fun checkinLookbackStartMs(): Long =
        today.minusDays((HISTORY_FETCH_DAYS - 1).toLong())
            .atStartOfDay(zoneId).toInstant().toEpochMilli()

    companion object {
        const val LOOKBACK_DAYS = 32
        const val HISTORY_FETCH_DAYS = 192

        /** Pseudo-Ziel-ID für die „Perfekter Tag"-Feier (Dedupe im Store). */
        const val PERFECT_DAY_ID = "__perfect_day__"
        const val PERFECT_DAY_KEY = "perfect-day"

        fun defaultIcon(kind: String): String = when (kind) {
            Goal.KIND_COUNT -> "🔢"
            Goal.KIND_TIME -> "⏱️"
            else -> "🎯"
        }
    }
}

// ── Präsentations-Modelle ────────────────────────────────────────────────

/**
 * Ein Habit mit allem, was die Tages-Karten brauchen. [checkins] ist die
 * vollständige Liste im Verlaufsfenster — daraus berechnet die UI den
 * Status für JEDEN Tag, ohne weitere Datenbank-Abfragen.
 */
data class HabitGoalUi(
    val goal: Goal,
    val kind: String,
    val icon: String,
    val target: Int,
    val progress: GoalWithProgress,
    val checkins: List<GoalCheckin>,
    val colorLong: Long?,
    val categoryName: String?
) {
    fun checkinsOn(date: LocalDate): List<GoalCheckin> =
        checkins.filter { it.dateKey == date.toString() }

    fun countOn(date: LocalDate): Int =
        checkinsOn(date).sumOf { it.count }

    fun msOn(date: LocalDate): Long =
        checkinsOn(date).sumOf { it.durationMs }

    /** Vor dem Anlegen des Habits gibt es nichts anzuzeigen. */
    fun isVisibleOn(date: LocalDate): Boolean =
        !date.isBefore(GoalProgressAnalytics.habitCreatedDate(goal))

    fun isDueOn(date: LocalDate): Boolean =
        isVisibleOn(date) && GoalProgressAnalytics.isScheduledOn(goal, date)

    fun isDoneOn(date: LocalDate): Boolean = when (kind) {
        Goal.KIND_DONE -> countOn(date) > 0
        Goal.KIND_COUNT -> countOn(date) >= target
        else -> msOn(date) > 0L
    }

    fun progressOn(date: LocalDate): Float = when (kind) {
        Goal.KIND_DONE -> if (isDoneOn(date)) 1f else 0f
        Goal.KIND_COUNT -> (countOn(date).toFloat() / target.coerceAtLeast(1)).coerceAtMost(1f)
        else -> if (msOn(date) > 0L) 1f else 0f
    }

    fun valueOn(date: LocalDate): String = when (kind) {
        Goal.KIND_DONE -> if (isDoneOn(date)) "✓" else "–"
        Goal.KIND_COUNT -> "${countOn(date)} / $target"
        else -> GoalProgressAnalytics.formatClock(msOn(date) / 1000L)
    }

    val streak: Int get() = progress.streakCurrent
    val bestStreak: Int get() = progress.streakBest
}

data class GoalsSummary(
    val dueToday: Int = 0,
    val metDue: Int = 0,
    val bestStreak: Int = 0,
    val totalActive: Int = 0,
    val perfectToday: Boolean = false,
    val perfectStreak: Int = 0
)

data class GoalCelebrationEvent(
    val goalId: String,
    val goalTitle: String,
    val streak: Int,
    val periodLabel: String,
    val accent: String?,
    /** M36: „Perfekter Tag" nutzt denselben Konfetti-Overlay mit anderem Titel. */
    val perfectDay: Boolean = false
)

data class GoalsUiState(
    val loading: Boolean = true,
    val habitGoals: List<HabitGoalUi> = emptyList(),
    val legacyGoals: List<GoalWithProgress> = emptyList(),
    val archivedHabitGoals: List<HabitGoalUi> = emptyList(),
    val archivedLegacyGoals: List<GoalWithProgress> = emptyList(),
    val showArchived: Boolean = false,
    val summary: GoalsSummary = GoalsSummary(),
    val celebration: GoalCelebrationEvent? = null
)

/**
 * Gemeinsames Präsentationsmodell für Alt-Ziele, Dashboard und Detail.
 * (M20; bleibt unverändert, damit die bestehende Verdrahtung weiterläuft.)
 */
fun GoalProgressAnalytics.GoalProgressResult.toGoalWithProgress(
    typeMap: Map<String, ActivityType>,
    categoryMap: Map<String, Category>,
    todayCheckins: Int = 0,
    todayCreditMs: Long = 0L
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
    todayCreditMs = todayCreditMs,
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
    val isDueToday: Boolean = true,
    val streakCurrent: Int = 0,
    val streakBest: Int = 0,
    val metPeriods: Int = 0,
    val duePeriods: Int = 0,
    val periodKey: String = "",
    val isCountType: Boolean = false,
    val categoryName: String? = null,
    val activityColorLong: Long? = null,
    val todayCheckins: Int = 0,
    val todayCreditMs: Long = 0L,
    val isMetFinal: Boolean = false,
    val isPendingFinal: Boolean = false,
    val isCurrentAchieved: Boolean = false,
    val lastCompletedMet: Boolean? = null,
    val celebrationEligible: Boolean = false,
    val celebrationPeriodKey: String = "",
    val celebrationPeriodLabel: String = "",
    val celebrationStreak: Int = 0
)
