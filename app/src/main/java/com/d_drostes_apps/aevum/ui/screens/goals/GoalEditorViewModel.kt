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
 * M20: Editor der Ziel-Architektur.
 *
 * Aufbau des Formulars:
 *  - Schnellstart-Vorlagen ([GoalPreset]) für die typischen Fälle
 *    („Digital unter 2h/Tag", „Lernen 2h Mo–Fr", „4× Aufgaben Mo–Fr" …),
 *  - Erfassung: AUTOMATISCH (Quelle: Aktivität · Kategorie · Bildschirmzeit
 *    · erledigte Aufgaben) oder MANUELL (Abhaken/Zählen),
 *  - Zeitraum: täglich · wöchentlich · monatlich,
 *  - Zieltyp: mindestens · höchstens · Anzahl,
 *  - Wochentage (an welchen Tagen ist das Ziel fällig),
 *  - Farbe (Akzent aus der Aevum-Palette) + Live-Vorschau.
 */
@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _form = MutableStateFlow(GoalFormState())
    val form: StateFlow<GoalFormState> = _form

    // M18.59-FIX (User: „Beim Bearbeiten eines Ziels kommt man in die
    // Ansicht, ein NEUES zu erstellen, wo nichts ausgefüllt ist"): Das
    // ViewModel hatte KEINE loadGoal-Funktion — der Editor zeigte immer
    // das leere Formular und saveGoal() erzeugte immer ein NEUES Goal
    // (neue UUID). Jetzt: loadGoal(goalId) befüllt das Formular, und
    // saveGoal() UPDATET das bestehende Goal, wenn eine ID geladen ist.
    private var editingGoalId: String? = null
    private val _isEditing = MutableStateFlow(false)

    fun loadGoal(goalId: String) {
        editingGoalId = goalId
        _isEditing.value = true
        viewModelScope.launch {
            val goal = goalRepository.getById(goalId).firstOrNull() ?: return@launch
            _form.value = GoalFormState(
                title = goal.title,
                trackingMode = if (GoalProgressAnalytics.isAuto(goal)) {
                    GoalProgressAnalytics.MODE_AUTO
                } else {
                    GoalProgressAnalytics.MODE_MANUAL
                },
                goalType = goal.type,
                period = goal.period,
                targetValue = if (goal.targetValue > 0f) trimNumber(goal.targetValue) else "",
                targetUnit = goal.targetUnit,
                scheduleDays = GoalProgressAnalytics.scheduleMask(goal),
                sourceKind = goal.sourceKind ?: GoalProgressAnalytics.SOURCE_ACTIVITY,
                activityTypeId = goal.activityTypeId,
                categoryId = goal.categoryId,
                accent = goal.accent
            )
        }
    }

    val uiState: StateFlow<GoalEditorUiState> = combine(
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        _form,
        _isEditing
    ) { types, categories, form, isEditing ->
        GoalEditorUiState(
            activityTypes = types,
            categories = categories,
            form = form,
            saved = form.saved,
            isEditing = isEditing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalEditorUiState())

    // ── Formular-Setter ──────────────────────────────────────────────────

    fun setTitle(title: String) = _form.update { it.copy(title = title) }

    fun setTrackingMode(mode: String) = _form.update { f ->
        if (mode == GoalProgressAnalytics.MODE_MANUAL) {
            // Manuell = abhaken/zählen: Anzahl-Ziel mit Einheit „x".
            f.copy(trackingMode = mode, goalType = GoalProgressAnalytics.TYPE_COUNT, targetUnit = GoalProgressAnalytics.UNIT_COUNT)
        } else {
            // Automatisch = aus Aufzeichnungen: Zeit-Ziel als Default
            // (Aufgaben-Quelle setzt gleich wieder COUNT).
            f.copy(
                trackingMode = mode,
                targetUnit = if (f.targetUnit == GoalProgressAnalytics.UNIT_COUNT) "MINUTES" else f.targetUnit
            )
        }
    }

    fun setGoalType(goalType: String) = _form.update { f ->
        f.copy(
            goalType = goalType,
            targetUnit = if (goalType == GoalProgressAnalytics.TYPE_COUNT) {
                GoalProgressAnalytics.UNIT_COUNT
            } else if (f.targetUnit == GoalProgressAnalytics.UNIT_COUNT) "MINUTES" else f.targetUnit
        )
    }

    fun setPeriod(period: String) = _form.update { it.copy(period = period) }

    fun setTargetValue(value: String) = _form.update { it.copy(targetValue = value) }

    fun setTargetUnit(unit: String) = _form.update { it.copy(targetUnit = unit) }

    fun setSourceKind(kind: String) = _form.update { f ->
        when (kind) {
            GoalProgressAnalytics.SOURCE_TODOS -> f.copy(
                sourceKind = kind,
                goalType = GoalProgressAnalytics.TYPE_COUNT,
                targetUnit = GoalProgressAnalytics.UNIT_COUNT
            )
            GoalProgressAnalytics.SOURCE_DIGITAL -> f.copy(
                sourceKind = kind,
                goalType = if (f.goalType == GoalProgressAnalytics.TYPE_COUNT) GoalProgressAnalytics.TYPE_AT_MOST else f.goalType,
                targetUnit = if (f.targetUnit == GoalProgressAnalytics.UNIT_COUNT) "MINUTES" else f.targetUnit
            )
            else -> f.copy(sourceKind = kind)
        }
    }

    fun setActivityType(activityTypeId: String) =
        _form.update { it.copy(activityTypeId = activityTypeId) }

    fun setCategory(categoryId: String) =
        _form.update { it.copy(categoryId = categoryId) }

    fun setScheduleDays(days: Int) =
        _form.update { it.copy(scheduleDays = days and GoalProgressAnalytics.ALL_DAYS) }

    fun toggleWeekday(isoDay: Int) = _form.update { f ->
        f.copy(scheduleDays = f.scheduleDays xor (1 shl (isoDay - 1)))
    }

    fun setAccent(accent: String?) = _form.update { it.copy(accent = accent) }

    fun setShowActivityTypeMenu(show: Boolean) = _form.update { it.copy(showActivityTypeMenu = show) }

    fun setShowCategoryMenu(show: Boolean) = _form.update { it.copy(showCategoryMenu = show) }

    fun setShowUnitMenu(show: Boolean) = _form.update { it.copy(showUnitMenu = show) }

    fun clearError() = _form.update { it.copy(errorRes = null) }

    /** Schnellstart-Vorlagen — die Nutzer-Beispiele als Ein-Tap-Start. */
    fun applyPreset(preset: GoalPreset) = _form.update { f ->
        when (preset) {
            GoalPreset.DIGITAL_DAILY -> f.copy(
                trackingMode = GoalProgressAnalytics.MODE_AUTO,
                sourceKind = GoalProgressAnalytics.SOURCE_DIGITAL,
                goalType = GoalProgressAnalytics.TYPE_AT_MOST,
                period = "DAILY",
                targetValue = "2",
                targetUnit = "HOURS",
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
            GoalPreset.DIGITAL_WEEKLY -> f.copy(
                trackingMode = GoalProgressAnalytics.MODE_AUTO,
                sourceKind = GoalProgressAnalytics.SOURCE_DIGITAL,
                goalType = GoalProgressAnalytics.TYPE_AT_MOST,
                period = "WEEKLY",
                targetValue = "10",
                targetUnit = "HOURS",
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
            GoalPreset.LEARNING_WEEKDAYS -> f.copy(
                trackingMode = GoalProgressAnalytics.MODE_AUTO,
                sourceKind = GoalProgressAnalytics.SOURCE_ACTIVITY,
                goalType = GoalProgressAnalytics.TYPE_AT_LEAST,
                period = "DAILY",
                targetValue = "2",
                targetUnit = "HOURS",
                scheduleDays = WEEKDAYS
            )
            GoalPreset.TASK_COUNT_WEEKDAYS -> f.copy(
                trackingMode = GoalProgressAnalytics.MODE_MANUAL,
                goalType = GoalProgressAnalytics.TYPE_COUNT,
                period = "WEEKLY",
                targetValue = "4",
                targetUnit = GoalProgressAnalytics.UNIT_COUNT,
                scheduleDays = WEEKDAYS
            )
            GoalPreset.DAILY_HABIT -> f.copy(
                trackingMode = GoalProgressAnalytics.MODE_MANUAL,
                goalType = GoalProgressAnalytics.TYPE_COUNT,
                period = "DAILY",
                targetValue = "1",
                targetUnit = GoalProgressAnalytics.UNIT_COUNT,
                scheduleDays = GoalProgressAnalytics.ALL_DAYS
            )
        }.copy(errorRes = null)
    }

    // ── Speichern/Löschen ────────────────────────────────────────────────

    fun saveGoal() {
        val form = _form.value
        val error = validate(form)
        if (error != null) {
            _form.update { it.copy(errorRes = error) }
            return
        }

        val now = System.currentTimeMillis()
        val isManual = form.trackingMode == GoalProgressAnalytics.MODE_MANUAL
        val isTodos = !isManual && form.sourceKind == GoalProgressAnalytics.SOURCE_TODOS
        val goalType = if (isManual || isTodos) GoalProgressAnalytics.TYPE_COUNT else form.goalType
        val unit = if (goalType == GoalProgressAnalytics.TYPE_COUNT) {
            GoalProgressAnalytics.UNIT_COUNT
        } else {
            form.targetUnit
        }
        val value = form.targetValue.replace(',', '.').toFloatOrNull() ?: 0f
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
                            activityTypeId = if (isManual) existing.activityTypeId else form.activityTypeId,
                            categoryId = if (isManual) existing.categoryId else form.categoryId,
                            type = goalType,
                            period = form.period,
                            targetValue = value,
                            targetUnit = unit,
                            trackingMode = form.trackingMode,
                            scheduleDays = form.scheduleDays and GoalProgressAnalytics.ALL_DAYS,
                            sourceKind = form.sourceKind,
                            accent = form.accent,
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
                categoryId = form.categoryId,
                type = goalType,
                period = form.period,
                targetValue = value,
                targetUnit = unit,
                trackingMode = form.trackingMode,
                scheduleDays = form.scheduleDays,
                sourceKind = form.sourceKind,
                accent = form.accent,
                status = "ACTIVE",
                startAt = now
            )
            goalRepository.insert(goal)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun validate(form: GoalFormState): Int? {
        if (form.title.isBlank()) return R.string.goal_error_title_required
        if (form.trackingMode == GoalProgressAnalytics.MODE_AUTO) {
            when (form.sourceKind) {
                GoalProgressAnalytics.SOURCE_ACTIVITY ->
                    if (form.activityTypeId == null) return R.string.goal_error_type_required
                GoalProgressAnalytics.SOURCE_CATEGORY ->
                    if (form.categoryId == null) return R.string.goal_error_category_required
            }
        }
        if (form.scheduleDays and GoalProgressAnalytics.ALL_DAYS == 0) {
            return R.string.goal_error_schedule_required
        }
        if (form.targetValue.isBlank()) return R.string.goal_error_value_required
        val value = form.targetValue.replace(',', '.').toFloatOrNull()
            ?: return R.string.goal_error_value_required
        if (value <= 0f) return R.string.goal_error_value_positive
        return null
    }

    // M11.2: Goal löschen
    fun deleteGoal(goalId: String) {
        viewModelScope.launch {
            goalRepository.delete(goalId)
            _form.update { it.copy(saved = true) }
        }
    }

    private fun trimNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()

    companion object {
        /** Mo–Fr (Bitmaske: Mo=1 … Fr=16). */
        const val WEEKDAYS = 0b00011111

        /** Sa+So. */
        const val WEEKEND = 0b01100000
    }
}

/** M20: Ein-Tap-Vorlagen aus den Nutzer-Beispielen. */
enum class GoalPreset { DIGITAL_DAILY, DIGITAL_WEEKLY, LEARNING_WEEKDAYS, TASK_COUNT_WEEKDAYS, DAILY_HABIT }

data class GoalFormState(
    val title: String = "",
    val trackingMode: String = GoalProgressAnalytics.MODE_AUTO,
    val goalType: String = GoalProgressAnalytics.TYPE_AT_LEAST,
    val period: String = "DAILY",
    val targetValue: String = "",
    val targetUnit: String = "MINUTES",
    val scheduleDays: Int = GoalProgressAnalytics.ALL_DAYS,
    val sourceKind: String = GoalProgressAnalytics.SOURCE_ACTIVITY,
    val activityTypeId: String? = null,
    val categoryId: String? = null,
    val accent: String? = null,
    val showActivityTypeMenu: Boolean = false,
    val showCategoryMenu: Boolean = false,
    val showUnitMenu: Boolean = false,
    val errorRes: Int? = null,
    val saved: Boolean = false
)

data class GoalEditorUiState(
    val form: GoalFormState = GoalFormState(),
    val activityTypes: List<ActivityType> = emptyList(),
    val categories: List<Category> = emptyList(),
    val saved: Boolean = false,
    val isEditing: Boolean = false
)
