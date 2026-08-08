package com.d_drostes_apps.aevum.ui.screens.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.HabitRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HabitEditorViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _form = MutableStateFlow(HabitFormState())
    val form: Flow<HabitFormState> = _form

    val uiState: Flow<HabitEditorUiState> = combine(
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        _form
    ) { types, categories, form ->
        HabitEditorUiState(
            activityTypes = types,
            categories = categories,
            form = form
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitEditorUiState())

    fun setTitle(title: String) {
        _form.update { it.copy(title = title) }
    }

    fun setShowActivityTypeMenu(show: Boolean) {
        _form.update { it.copy(showActivityTypeMenu = show) }
    }

    fun setActivityType(activityTypeId: String, name: String) {
        _form.update { it.copy(activityTypeId = activityTypeId, selectedActivityTypeName = name) }
    }

    fun setFrequencyType(frequencyType: String) {
        _form.update { it.copy(frequencyType = frequencyType) }
    }

    fun setFrequencyCount(count: Int) {
        _form.update { it.copy(frequencyCount = count.coerceAtLeast(1)) }
    }

    fun setSuccessRuleType(ruleType: String) {
        _form.update { it.copy(successRuleType = ruleType) }
    }

    fun setSuccessMinDuration(minDuration: Long) {
        _form.update { it.copy(successMinDuration = minDuration) }
    }

    fun saveHabit() {
        _form.update { form ->
            val error = validate(form)
            if (error != null) {
                return@update form.copy(error = error)
            }

            val habit = Habit(
                id = UUID.randomUUID().toString(),
                title = form.title,
                activityTypeId = form.activityTypeId,
                frequencyRuleJson = buildFrequencyRuleJson(form),
                successRuleJson = buildSuccessRuleJson(form),
                active = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            viewModelScope.launch {
                habitRepository.insert(habit)
            }
            form.copy(saved = true)
        }
    }

    private fun buildFrequencyRuleJson(form: HabitFormState): String {
        return """{"type":"${form.frequencyType}","count":${form.frequencyCount}}"""
    }

    private fun buildSuccessRuleJson(form: HabitFormState): String {
        return if (form.successRuleType == "minDuration") {
            """{"type":"minDuration","minDurationMs":${form.successMinDuration}}"""
        } else {
            """{"type":"${form.successRuleType}"}"""
        }
    }

    private fun validate(form: HabitFormState): String? {
        if (form.title.isBlank()) return "Titel ist erforderlich"
        if (form.activityTypeId == null) return "Aktivitätstyp wählen"
        if (form.frequencyCount <= 0) return "Häufigkeit muss positiv sein"
        return null
    }
}

data class HabitFormState(
    val title: String = "",
    val activityTypeId: String? = null,
    val selectedActivityTypeName: String? = null,
    val frequencyType: String = "daily",
    val frequencyCount: Int = 1,
    val successRuleType: String = "minDuration",
    val successMinDuration: Long = 900000,
    val showActivityTypeMenu: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

data class HabitEditorUiState(
    val form: HabitFormState = HabitFormState(),
    val activityTypes: List<ActivityType> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false
)