package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _form = MutableStateFlow(GoalFormState())
    val form: StateFlow<GoalFormState> = _form

    // M18.59-FIX (User: "Beim Bearbeiten eines Ziels kommt man in die
    // Ansicht, ein NEUES zu erstellen, wo nichts ausgefüllt ist"): Das
    // ViewModel hatte KEINE loadGoal-Funktion — der Editor zeigte immer
    // das leere Formular und saveGoal() erzeugte immer ein NEUES Goal
    // (neue UUID). Jetzt: loadGoal(goalId) befüllt das Formular, und
    // saveGoal() UPDATET das bestehende Goal, wenn eine ID geladen ist.
    private var editingGoalId: String? = null

    fun loadGoal(goalId: String) {
        editingGoalId = goalId
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).firstOrNull()
            if (goal != null) {
                _form.value = GoalFormState(
                    title = goal.title,
                    activityTypeId = goal.activityTypeId,
                    selectedActivityTypeName = null, // wird aus der Typ-Liste aufgelöst
                    period = goal.period,
                    goalType = goal.type,
                    targetValue = if (goal.targetValue > 0f) goal.targetValue.toString() else "",
                    targetUnit = goal.targetUnit
                )
            }
        }
    }

    val uiState: StateFlow<GoalEditorUiState> = combine(
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        _form
    ) { types, categories, form ->
        GoalEditorUiState(
            activityTypes = types,
            categories = categories,
            form = form,
            saved = form.saved
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalEditorUiState())

    fun setTitle(title: String) {
        _form.update { it.copy(title = title) }
    }

    fun setActivityType(activityTypeId: String, activityTypeName: String) {
        _form.update { it.copy(activityTypeId = activityTypeId, selectedActivityTypeName = activityTypeName) }
    }

    fun setShowActivityTypeMenu(show: Boolean) {
        _form.update { it.copy(showActivityTypeMenu = show) }
    }

    fun setPeriod(period: String) {
        _form.update { it.copy(period = period) }
    }

    fun setGoalType(goalType: String) {
        _form.update { it.copy(goalType = goalType) }
    }

    fun setTargetValue(value: String) {
        _form.update { it.copy(targetValue = value) }
    }

    fun setTargetUnit(unit: String) {
        _form.update { it.copy(targetUnit = unit) }
    }

    fun setShowUnitMenu(show: Boolean) {
        _form.update { it.copy(showUnitMenu = show) }
    }

    fun saveGoal() {
        val form = _form.value
        val error = validate(form)
        if (error != null) {
            _form.update { it.copy(errorRes = error) }
            return
        }

        val now = System.currentTimeMillis()
        val existingId = editingGoalId
        viewModelScope.launch {
            if (existingId != null) {
                // M18.59-FIX: Bearbeiten → bestehendes Goal UPDATEN
                // (vorher wurde immer ein NEUES Goal mit neuer UUID
                // angelegt — der User sah nach dem Speichern zwei Ziele).
                val existing = goalRepository.getById(existingId).firstOrNull()
                if (existing != null) {
                    goalRepository.update(
                        existing.copy(
                            title = form.title,
                            activityTypeId = form.activityTypeId,
                            type = form.goalType,
                            period = form.period,
                            targetValue = form.targetValue.toFloatOrNull() ?: 0f,
                            targetUnit = form.targetUnit,
                            updatedAt = now
                        )
                    )
                    _form.update { it.copy(saved = true) }
                    return@launch
                }
            }
            val goal = Goal(
                id = UUID.randomUUID().toString(),
                title = form.title,
                activityTypeId = form.activityTypeId,
                type = form.goalType,
                period = form.period,
                targetValue = form.targetValue.toFloatOrNull() ?: 0f,
                targetUnit = form.targetUnit,
                status = "ACTIVE",
                startAt = now
            )
            goalRepository.insert(goal)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun validate(form: GoalFormState): Int? {
        if (form.title.isBlank()) return R.string.goal_error_title_required
        if (form.activityTypeId == null) return R.string.goal_error_type_required
        if (form.targetValue.isBlank()) return R.string.goal_error_value_required
        if (form.targetValue.toFloatOrNull() == null || form.targetValue.toFloatOrNull()!! <= 0) return R.string.goal_error_value_positive
        return null
    }

    // M11.2: Goal löschen
    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            goalRepository.delete(goalId)
            _form.update { it.copy(saved = true) }
        }
    }
}

data class GoalFormState(
    val title: String = "",
    val activityTypeId: String? = null,
    val selectedActivityTypeName: String? = null,
    val period: String = "WEEKLY",
    val goalType: String = "AT_LEAST",
    val targetValue: String = "",
    val targetUnit: String = "HOURS",
    val showActivityTypeMenu: Boolean = false,
    val showUnitMenu: Boolean = false,
    val errorRes: Int? = null,
    val saved: Boolean = false
)

data class GoalEditorUiState(
    val form: GoalFormState = GoalFormState(),
    val activityTypes: List<ActivityType> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false
)
