package de.devondroste.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Goal
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.GoalRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
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
            _form.update { it.copy(error = error) }
            return
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
            startAt = System.currentTimeMillis()
        )

        viewModelScope.launch {
            goalRepository.insert(goal)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun validate(form: GoalFormState): String? {
        if (form.title.isBlank()) return "Titel ist erforderlich"
        if (form.activityTypeId == null) return "Aktivitätstyp wählen"
        if (form.targetValue.isBlank()) return "Zielwert eingeben"
        if (form.targetValue.toFloatOrNull() == null || form.targetValue.toFloatOrNull()!! <= 0) return "Zielwert muss positiv sein"
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
    val error: String? = null,
    val saved: Boolean = false
)

data class GoalEditorUiState(
    val form: GoalFormState = GoalFormState(),
    val activityTypes: List<ActivityType> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false
)
