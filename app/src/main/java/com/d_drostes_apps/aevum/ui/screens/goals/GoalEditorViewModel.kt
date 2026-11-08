package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M36: Editor für den Habit-Tracker — drei Arten, sonst nichts.
 *
 * Bewusst entfernt: Automatisch/Manuell, Quellen (Aktivität, Kategorie,
 * Bildschirmzeit, Aufgaben), Zeitraum (täglich/wöchentlich/monatlich),
 * Zieltyp (mindestens/maximal), Einheiten-Dropdown und die „optionale"
 * Ziel-Aktivität samt Zeitgutschrift. Devon: „Es gibt Ziele, wo man nur
 * angibt erledigt, … wo man eine Anzahl festlegen kann, … wo man eine Zeit
 * eintragen kann. Fertig."
 *
 * Alt-Ziele lassen sich weiterhin öffnen; sie werden beim Speichern in die
 * gewählte Habit-Art überführt (mit ehrlichem Hinweis im Formular).
 */
@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository
) : ViewModel() {

    private val _form = MutableStateFlow(GoalFormState())
    val form: StateFlow<GoalFormState> = _form

    private var editingGoalId: String? = null
    private val _isEditing = MutableStateFlow(false)

    fun loadGoal(goalId: String) {
        editingGoalId = goalId
        _isEditing.value = true
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).firstOrNull() ?: return@launch
            val kind = when (GoalProgressAnalytics.kindOf(goal)) {
                Goal.KIND_DONE, Goal.KIND_COUNT, Goal.KIND_TIME ->
                    GoalProgressAnalytics.kindOf(goal)
                else -> guessKindForLegacy(goal)
            }
            _form.value = GoalFormState(
                title = goal.title,
                kind = kind,
                icon = goal.icon?.takeIf { it.isNotBlank() }
                    ?: GoalsViewModel.defaultIcon(kind),
                targetCount = if (goal.targetValue >= 1f) goal.targetValue.toInt().toString() else "3",
                scheduleDays = GoalProgressAnalytics.scheduleMask(goal),
                accent = goal.accent,
                legacy = GoalProgressAnalytics.kindOf(goal) == Goal.KIND_LEGACY
            )
        }
    }

    val uiState: StateFlow<GoalEditorUiState> = combine(_form, _isEditing) { form, editing ->
        GoalEditorUiState(form = form, saved = form.saved, isEditing = editing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalEditorUiState())

    // ── Formular ─────────────────────────────────────────────────────────

    fun setTitle(title: String) = _form.update { it.copy(title = title) }

    fun setKind(kind: String) = _form.update { f ->
        f.copy(
            kind = kind,
            icon = if (f.icon.isBlank() || f.icon == GoalsViewModel.defaultIcon(f.kind)) {
                GoalsViewModel.defaultIcon(kind)
            } else f.icon
        )
    }

    fun setIcon(icon: String) = _form.update { it.copy(icon = icon) }

    fun setTargetCount(value: String) = _form.update {
        it.copy(targetCount = value.filter { c -> c.isDigit() }.take(3))
    }

    fun stepTargetCount(delta: Int) = _form.update { f ->
        val current = f.targetCount.toIntOrNull() ?: 1
        f.copy(targetCount = (current + delta).coerceIn(1, 99).toString())
    }

    fun setScheduleDays(days: Int) =
        _form.update { it.copy(scheduleDays = days and GoalProgressAnalytics.ALL_DAYS) }

    fun toggleWeekday(isoDay: Int) = _form.update { f ->
        f.copy(scheduleDays = f.scheduleDays xor (1 shl (isoDay - 1)))
    }

    fun setAccent(accent: String?) = _form.update { it.copy(accent = accent) }

    fun applyPreset(preset: HabitPreset, title: String) = _form.update { f ->
        when (preset) {
            HabitPreset.WATER -> f.copy(
                title = f.title.ifBlank { title },
                kind = Goal.KIND_COUNT,
                icon = "💧",
                targetCount = "8",
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
            HabitPreset.READING -> f.copy(
                title = f.title.ifBlank { title },
                kind = Goal.KIND_TIME,
                icon = "📚",
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
            HabitPreset.MEDITATION -> f.copy(
                title = f.title.ifBlank { title },
                kind = Goal.KIND_DONE,
                icon = "🧘",
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
        }.copy(errorRes = null)
    }

    fun clearError() = _form.update { it.copy(errorRes = null) }

    // ── Speichern/Löschen ────────────────────────────────────────────────

    fun saveGoal() {
        val form = _form.value
        val error = validate(form)
        if (error != null) {
            _form.update { it.copy(errorRes = error) }
            return
        }

        val now = System.currentTimeMillis()
        val count = form.targetCount.toIntOrNull() ?: 1
        val targetValue = when (form.kind) {
            Goal.KIND_COUNT -> count.toFloat()
            Goal.KIND_TIME -> 0f
            else -> 1f
        }
        val targetUnit = when (form.kind) {
            Goal.KIND_TIME -> "SECONDS"
            else -> GoalProgressAnalytics.UNIT_COUNT
        }
        val type = when (form.kind) {
            Goal.KIND_COUNT -> GoalProgressAnalytics.TYPE_COUNT
            Goal.KIND_TIME -> GoalProgressAnalytics.TYPE_TIME
            else -> GoalProgressAnalytics.TYPE_AT_LEAST
        }

        viewModelScope.launch {
            val existingId = editingGoalId
            val existing = existingId?.let { goalRepository.getById(it).firstOrNull() }
            val goal = if (existing != null) {
                existing.copy(
                    title = form.title.trim(),
                    kind = form.kind,
                    icon = form.icon,
                    type = type,
                    period = "DAILY",
                    targetValue = targetValue,
                    targetUnit = targetUnit,
                    trackingMode = GoalProgressAnalytics.MODE_MANUAL,
                    scheduleDays = form.scheduleDays and GoalProgressAnalytics.ALL_DAYS,
                    sourceKind = null,
                    categoryId = null,
                    activityTypeId = null,
                    accent = form.accent,
                    updatedAt = now
                )
            } else {
                Goal(
                    id = UUID.randomUUID().toString(),
                    title = form.title.trim(),
                    kind = form.kind,
                    icon = form.icon,
                    type = type,
                    period = "DAILY",
                    targetValue = targetValue,
                    targetUnit = targetUnit,
                    trackingMode = GoalProgressAnalytics.MODE_MANUAL,
                    scheduleDays = form.scheduleDays and GoalProgressAnalytics.ALL_DAYS,
                    sourceKind = null,
                    accent = form.accent,
                    status = "ACTIVE",
                    startAt = now
                )
            }
            if (existing != null) goalRepository.update(goal) else goalRepository.insert(goal)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun validate(form: GoalFormState): Int? {
        if (form.title.isBlank()) return R.string.goal_error_title_required
        if (form.scheduleDays and GoalProgressAnalytics.ALL_DAYS == 0) {
            return R.string.goal_error_schedule_required
        }
        if (form.kind == Goal.KIND_COUNT) {
            val count = form.targetCount.toIntOrNull()
                ?: return R.string.goal_error_value_required
            if (count <= 0) return R.string.goal_error_value_positive
        }
        return null
    }

    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            goalRepository.delete(goalId)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun guessKindForLegacy(goal: Goal): String = when {
        goal.targetUnit == GoalProgressAnalytics.UNIT_COUNT ||
            goal.type == GoalProgressAnalytics.TYPE_COUNT ->
            if (goal.targetValue <= 1f) Goal.KIND_DONE else Goal.KIND_COUNT
        else -> Goal.KIND_TIME
    }

    companion object {
        /** Mo–Fr (Bitmaske: Mo=1 … Fr=16). */
        const val WEEKDAYS = 0b00011111

        /** Sa+So. */
        const val WEEKEND = 0b01100000
    }
}

/** M36: Ein-Tap-Vorlagen für die häufigsten Habits. */
enum class HabitPreset { WATER, READING, MEDITATION }

data class GoalFormState(
    val title: String = "",
    val kind: String = Goal.KIND_DONE,
    val icon: String = "🎯",
    val targetCount: String = "3",
    val scheduleDays: Int = GoalProgressAnalytics.ALL_DAYS,
    val accent: String? = null,
    val legacy: Boolean = false,
    val errorRes: Int? = null,
    val saved: Boolean = false
)

data class GoalEditorUiState(
    val form: GoalFormState = GoalFormState(),
    val saved: Boolean = false,
    val isEditing: Boolean = false
)
