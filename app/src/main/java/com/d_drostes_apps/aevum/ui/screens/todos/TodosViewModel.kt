package com.d_drostes_apps.aevum.ui.screens.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Todo
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.TodoRepository
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.domain.todo.RecurrenceEngine
import com.d_drostes_apps.aevum.domain.todo.StreakEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TodosViewModel @Inject constructor(
    private val todoRepo: TodoRepository,
    private val activityRepository: ActivityRepository,
    activityTypeRepository: ActivityTypeRepository,
    // M18.138: Bildschirmzeit-Quelle (Digital Balance).
    private val balanceSource: com.d_drostes_apps.aevum.domain.digital.DigitalBalanceSource
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()

    private companion object {
        /**
         * M18.138: Rückblick-Fenster für die Bildschirmzeit. Todos werden
         * für HEUTE ausgewertet; die kurze Historie deckt Mitternachts-
         * Randfälle und den Tageswechsel ab.
         */
        const val LOOKBACK_DAYS = 3
    }

    val uiState: StateFlow<TodosUiState> = combine(
        todoRepo.getAll(),
        todoRepo.getAllCompletions(),
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        // M18.138: Bildschirmzeit (Digital Balance) — ersetzt die
        // Aufzeichnungen in den Dauer-Fortschritten (z. B. Digital-Todos).
        balanceSource.dailyTotals(LOOKBACK_DAYS)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val todos = values[0] as List<Todo>
        @Suppress("UNCHECKED_CAST")
        val completions = values[1] as List<TodoCompletion>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[2] as List<ActivitySession>
        @Suppress("UNCHECKED_CAST")
        val types = values[3] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val balance = values[4] as Map<LocalDate, Long>
        buildState(todos, completions, sessions, types, balance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodosUiState())

    fun toggle(todoId: String, completed: Boolean) {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            if (completed) {
                todoRepo.insertCompletion(TodoCompletion(todoId = todoId, date = today, source = "MANUAL"))
                // ONCE ohne dueDate: nach Erledigung archivieren — aber NICHT
                // bei Check-in-only-Todos ("heute dabei" ist kein Abschluss).
                val todo = todoRepo.getById(todoId)
                if (todo != null && todo.recurrenceType == RecurrenceEngine.TYPE_ONCE && todo.dueDate == null && !todo.checkInOnly) {
                    todoRepo.setActive(todoId, false)
                }
            } else {
                todoRepo.deleteCompletion(todoId, today)
            }
        }
    }

    fun archive(todoId: String) {
        viewModelScope.launch { todoRepo.setActive(todoId, false) }
    }

    fun delete(todoId: String) {
        viewModelScope.launch { todoRepo.delete(todoId) }
    }

    private fun buildState(
        todos: List<Todo>,
        allCompletions: List<TodoCompletion>,
        sessions: List<ActivitySession>,
        types: List<ActivityType>,
        balanceMsPerDay: Map<LocalDate, Long> = emptyMap()
    ): TodosUiState {
        val today = LocalDate.now()
        val typeMap = types.associateBy { it.id }
        val completedToday = allCompletions.filter { it.date == today.toString() }.associateBy { it.todoId }

        // Dauer pro Aktivitätstyp HEUTE (inkl. laufender Session)
        val dayStart = TimeFormatting.startOfDayMillis(today, zoneId)
        val dayEnd = TimeFormatting.endOfDayMillis(today, zoneId)
        val durationByType = mutableMapOf<String, Long>()
        // M18.138: Bildschirm-Aufzeichnungen (SCREEN_AUTO) zählen nicht in
        // die Statistik — stattdessen die echte Bildschirmzeit (Digital
        // Balance) dieses Tages. Ein „Digital"-Dauer-Todo erreicht sein
        // Ziel damit über die gemessene Zeit, nicht über die Aufzeichnung.
        com.d_drostes_apps.aevum.domain.digital.StatisticsSessionSource
            .mergeForDay(
                sessions = sessions.filter { it.deletedAt == null },
                date = today,
                balanceMs = balanceMsPerDay[today] ?: 0L,
                zoneId = zoneId
            )
            .filter { it.startAt < dayEnd && (it.endAt == null || it.endAt > dayStart) }
            .forEach { session ->
                val typeId = session.activityTypeId ?: return@forEach
                val clipStart = maxOf(session.startAt, dayStart)
                val clipEnd = minOf(session.endAt ?: System.currentTimeMillis(), dayEnd)
                durationByType[typeId] = (durationByType[typeId] ?: 0L) + (clipEnd - clipStart).coerceAtLeast(0L)
            }

        val visible = todos
            .filter { it.active && RecurrenceEngine.isRelevantOn(it, today) }
            .sortedBy { it.targetMinutes == 0 } // Checkboxen zuerst? Nein: nach Relevanz

        val items = visible.map { todo ->
            val isDuration = todo.targetMinutes > 0
            val autoDone = isDuration && (durationByType[todo.activityTypeId] ?: 0L) >= todo.targetMinutes * 60_000L
            val done = (completedToday[todo.id] != null) || autoDone
            val progress = if (isDuration) {
                val targetMs = todo.targetMinutes * 60_000L
                ((durationByType[todo.activityTypeId] ?: 0L).toFloat() / targetMs).coerceIn(0f, 1f)
            } else 0f
            val progressMs = if (isDuration) durationByType[todo.activityTypeId] ?: 0L else 0L
            // M18.60: Streaks — perioden-basiert (Woche/Monat/Tag je
            // Recurrence-Typ). Ersichtlich als 🔥-Badge auf der Todo-Karte.
            val streak = StreakEngine.currentStreak(todo, allCompletions, today)
            val bestStreak = StreakEngine.bestStreak(todo, allCompletions, today)
            // M18.60: Ziel-Fortschritt in der AKTUELLEN Periode (z.B. 3/5
            // diese Woche) — Todos erfüllen die Anforderungen eines Ziels.
            val periodKey = RecurrenceEngine.periodKey(todo, today)
            val periodCount = allCompletions.count { it.todoId == todo.id && RecurrenceEngine.periodKey(todo, LocalDate.parse(it.date)) == periodKey }
            val periodRequired = RecurrenceEngine.requiredCompletionsInPeriod(todo, today)

            TodoUi(
                todo = todo,
                done = done,
                autoDone = autoDone,
                progress = progress,
                progressMs = progressMs,
                type = typeMap[todo.activityTypeId],
                streak = streak,
                bestStreak = bestStreak,
                periodCount = periodCount,
                periodRequired = periodRequired
            )
        }

        // Sortierung: offene zuerst, dann erledigte
        val sorted = items.sortedWith(compareBy<TodoUi> { it.done }.thenBy { it.todo.title.lowercase() })

        val archived = todos.filter { !it.active }

        return TodosUiState(
            activeTodos = sorted,
            archivedTodos = archived,
            today = today,
            weekDayLabels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        )
    }
}

data class TodosUiState(
    val activeTodos: List<TodoUi> = emptyList(),
    val archivedTodos: List<Todo> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val weekDayLabels: List<String> = emptyList(),
    val isLoading: Boolean = false
)

data class TodoUi(
    val todo: Todo,
    val done: Boolean,
    val autoDone: Boolean,
    val progress: Float,
    val progressMs: Long,
    val type: ActivityType?,
    // M18.60: Streaks
    val streak: Int = 0,
    val bestStreak: Int = 0,
    // M18.60: Ziel-Fortschritt in der aktuellen Periode (X/Y)
    val periodCount: Int = 0,
    val periodRequired: Int = 1
) {
    val isDuration: Boolean get() = todo.targetMinutes > 0
    val recurrenceLabel: String get() = RecurrenceEngine.labelFor(todo.recurrenceType)
    val streakLabel: String get() = StreakEngine.streakLabel(todo, streak)
    val periodLabel: String get() = "$periodCount/$periodRequired"
}
