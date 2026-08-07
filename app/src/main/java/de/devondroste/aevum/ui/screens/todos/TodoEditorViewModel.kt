package de.devondroste.aevum.ui.screens.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Todo
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.TodoRepository
import de.devondroste.aevum.domain.todo.RecurrenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TodoEditorViewModel @Inject constructor(
    private val todoRepo: TodoRepository,
    activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    // M18.30: Form-State ist MutableStateFlow — die Setter schreiben direkt.
    // M18.36-FIX (Root Cause): Vorher war uiState ein `map` auf den
    // ActivityTypes-Flow — der emittiert NUR bei ActivityType-Aenderungen.
    // Die Setter schrieben zwar formState.value, aber die UI las uiState,
    // der sich NIE aktualisierte -> kein Text eingebbar, keine Klick-
    // Reaktion (Dauer-Ziel, Wiederholung, Faellig). Jetzt: combine() aus
    // formState + Types — jede Setter-Aenderung emittiert sofort.
    private val formState = MutableStateFlow(TodoEditorUiState())

    // M18.38: Edit-Modus — die zu bearbeitende Todo-ID (null = neu).
    private val editingId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TodoEditorUiState> = combine(
        formState,
        activityTypeRepository.getAll()
    ) { form, types ->
        form.copy(activityTypes = types)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TodoEditorUiState())

    /** M18.38: Bestehendes Todo laden — Form-State mit den Werten fuellen. */
    fun loadTodo(todoId: String) {
        viewModelScope.launch {
            val todo = todoRepo.getById(todoId) ?: return@launch
            editingId.value = todoId
            val recurrence = runCatching { JSONObject(todo.recurrenceJson) }.getOrNull()
            formState.value = TodoEditorUiState(
                title = todo.title,
                isDuration = todo.targetMinutes > 0,
                targetMinutes = if (todo.targetMinutes > 0) todo.targetMinutes else 60,
                activityTypeId = todo.activityTypeId,
                recurrenceType = todo.recurrenceType,
                selectedWeekdays = recurrence
                    ?.optInt(RecurrenceEngine.KEY_WEEKDAYS, 0)
                    ?.let { RecurrenceEngine.weekdaysFromBitmask(it) }
                    ?.toSet()
                    ?: setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                intervalDays = recurrence?.optInt(RecurrenceEngine.KEY_INTERVAL_DAYS, 2) ?: 2,
                countPerPeriod = recurrence?.optInt(RecurrenceEngine.KEY_COUNT_PER_PERIOD, 3) ?: 3,
                dueInDays = 0
            )
        }
    }

    fun setTitle(title: String) {
        formState.value = formState.value.copy(title = title)
    }

    fun setDuration(isDuration: Boolean) {
        formState.value = formState.value.copy(isDuration = isDuration)
    }

    fun setTargetMinutes(minutes: Int) {
        formState.value = formState.value.copy(targetMinutes = minutes.coerceIn(5, 480))
    }

    fun setActivityType(typeId: String?) {
        formState.value = formState.value.copy(activityTypeId = typeId)
    }

    fun setRecurrenceType(type: String) {
        formState.value = formState.value.copy(recurrenceType = type)
    }

    fun toggleWeekday(day: DayOfWeek) {
        val current = formState.value.selectedWeekdays
        val updated = if (day in current) current - day else current + day
        formState.value = formState.value.copy(selectedWeekdays = updated)
    }

    fun setIntervalDays(days: Int) {
        formState.value = formState.value.copy(intervalDays = days.coerceIn(1, 30))
    }

    fun setCountPerPeriod(count: Int) {
        formState.value = formState.value.copy(countPerPeriod = count.coerceIn(1, 14))
    }

    fun setDueInDays(days: Int) {
        formState.value = formState.value.copy(dueInDays = days)
    }

    fun save() {
        val s = formState.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val recurrenceJson = JSONObject().apply {
                when (s.recurrenceType) {
                    RecurrenceEngine.TYPE_WEEKLY_ON ->
                        put(RecurrenceEngine.KEY_WEEKDAYS, RecurrenceEngine.weekdayBitmask(s.selectedWeekdays.toList()))
                    RecurrenceEngine.TYPE_EVERY_N_DAYS ->
                        put(RecurrenceEngine.KEY_INTERVAL_DAYS, s.intervalDays)
                    RecurrenceEngine.TYPE_N_PER_WEEK, RecurrenceEngine.TYPE_N_PER_MONTH ->
                        put(RecurrenceEngine.KEY_COUNT_PER_PERIOD, s.countPerPeriod)
                }
            }.toString()

            val dueDate = if (s.recurrenceType == RecurrenceEngine.TYPE_ONCE && s.dueInDays > 0) {
                today.plusDays(s.dueInDays.toLong()).toString()
            } else null

            // M18.38: Edit-Modus — bestehendes Todo aktualisieren statt neu anlegen.
            val existingId = editingId.value
            if (existingId != null) {
                val existing = todoRepo.getById(existingId)
                if (existing != null) {
                    todoRepo.insert(
                        existing.copy(
                            title = s.title.trim(),
                            activityTypeId = s.activityTypeId,
                            targetMinutes = if (s.isDuration) s.targetMinutes else 0,
                            recurrenceType = s.recurrenceType,
                            recurrenceJson = recurrenceJson,
                            dueDate = dueDate,
                            updatedAt = now
                        )
                    )
                    return@launch
                }
            }

            todoRepo.insert(
                Todo(
                    id = UUID.randomUUID().toString(),
                    title = s.title.trim(),
                    activityTypeId = s.activityTypeId,
                    targetMinutes = if (s.isDuration) s.targetMinutes else 0,
                    recurrenceType = s.recurrenceType,
                    recurrenceJson = recurrenceJson,
                    startDate = today.toString(),
                    dueDate = dueDate,
                    active = true,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }
}

data class TodoEditorUiState(
    val title: String = "",
    val isDuration: Boolean = false,
    val targetMinutes: Int = 60,
    val activityTypeId: String? = null,
    val recurrenceType: String = RecurrenceEngine.TYPE_ONCE,
    val selectedWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val intervalDays: Int = 2,
    val countPerPeriod: Int = 3,
    val dueInDays: Int = 0,
    val activityTypes: List<ActivityType> = emptyList()
)
