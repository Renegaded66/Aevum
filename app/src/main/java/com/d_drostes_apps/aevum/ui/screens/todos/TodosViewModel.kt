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
    activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    private val zoneId = ZoneId.systemDefault()

    val uiState: StateFlow<TodosUiState> = combine(
        todoRepo.getAll(),
        todoRepo.getAllCompletions(),
        activityRepository.getAll(),
        activityTypeRepository.getAll()
    ) { todos, completions, sessions, types ->
        buildState(todos, completions, sessions, types)
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
        types: List<ActivityType>
    ): TodosUiState {
        val today = LocalDate.now()
        val typeMap = types.associateBy { it.id }
        val completedToday = allCompletions.filter { it.date == today.toString() }.associateBy { it.todoId }

        // Dauer pro Aktivitätstyp HEUTE (inkl. laufender Session)
        val dayStart = TimeFormatting.startOfDayMillis(today, zoneId)
        val dayEnd = TimeFormatting.endOfDayMillis(today, zoneId)
        val durationByType = mutableMapOf<String, Long>()
        sessions.filter { it.deletedAt == null && it.startAt < dayEnd && (it.endAt == null || it.endAt > dayStart) }
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

            TodoUi(
                todo = todo,
                done = done,
                autoDone = autoDone,
                progress = progress,
                progressMs = progressMs,
                type = typeMap[todo.activityTypeId],
                streak = streak,
                bestStreak = bestStreak
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
    val bestStreak: Int = 0
) {
    val isDuration: Boolean get() = todo.targetMinutes > 0
    val recurrenceLabel: String get() = RecurrenceEngine.labelFor(todo.recurrenceType)
    val streakLabel: String get() = StreakEngine.streakLabel(todo, streak)
}
