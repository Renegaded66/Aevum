package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M20: Editor der Ziel-Architektur — Vorlagen, Erfassung (automatisch/
 * manuell), Zeitraum, Zieltyp, Wochentage, Farbe, Live-Vorschau.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    goalId: String? = null,
    viewModel: GoalEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    val form = state.form

    // M18.59-FIX: Beim Bearbeiten das bestehende Ziel laden — vorher
    // zeigte der Editor immer das leere Neu-Formular.
    LaunchedEffect(goalId) {
        if (goalId != null) viewModel.loadGoal(goalId)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    val isManual = form.trackingMode == GoalProgressAnalytics.MODE_MANUAL
    val isCount = form.goalType == GoalProgressAnalytics.TYPE_COUNT
    val selectedActivityName = form.activityTypeId?.let { id ->
        state.activityTypes.firstOrNull { it.id == id }?.let { "${it.icon} ${it.name}" }
    }
    val selectedCategoryName = form.categoryId?.let { id ->
        state.categories.firstOrNull { it.id == id }?.name
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Header
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), fontSize = 14.sp) }
                        Text(
                            if (goalId == null) stringResource(R.string.goal_create) else stringResource(R.string.goal_edit),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Schnellstart-Vorlagen
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_presets), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.goal_presets_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            val presetDigitalDailyTitle = stringResource(R.string.goal_preset_digital_daily)
                            val presetDigitalWeeklyTitle = stringResource(R.string.goal_preset_digital_weekly)
                            val presetLearningTitle = stringResource(R.string.goal_preset_learning)
                            val presetTasksTitle = stringResource(R.string.goal_preset_tasks)
                            val presetHabitTitle = stringResource(R.string.goal_preset_habit)
                            PresetChip(GoalPreset.DIGITAL_DAILY, R.string.goal_preset_digital_daily) {
                                if (form.title.isBlank()) viewModel.setTitle(presetDigitalDailyTitle)
                                viewModel.applyPreset(GoalPreset.DIGITAL_DAILY)
                            }
                            PresetChip(GoalPreset.DIGITAL_WEEKLY, R.string.goal_preset_digital_weekly) {
                                if (form.title.isBlank()) viewModel.setTitle(presetDigitalWeeklyTitle)
                                viewModel.applyPreset(GoalPreset.DIGITAL_WEEKLY)
                            }
                            PresetChip(GoalPreset.LEARNING_WEEKDAYS, R.string.goal_preset_learning) {
                                if (form.title.isBlank()) viewModel.setTitle(presetLearningTitle)
                                viewModel.applyPreset(GoalPreset.LEARNING_WEEKDAYS)
                            }
                            PresetChip(GoalPreset.TASK_COUNT_WEEKDAYS, R.string.goal_preset_tasks) {
                                if (form.title.isBlank()) viewModel.setTitle(presetTasksTitle)
                                viewModel.applyPreset(GoalPreset.TASK_COUNT_WEEKDAYS)
                            }
                            PresetChip(GoalPreset.DAILY_HABIT, R.string.goal_preset_habit) {
                                if (form.title.isBlank()) viewModel.setTitle(presetHabitTitle)
                                viewModel.applyPreset(GoalPreset.DAILY_HABIT)
                            }
                        }
                    }
                }
            }

            // Titel
            item {
                AevumCard {
                    OutlinedTextField(
                        value = form.title,
                        onValueChange = viewModel::setTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.goal_title_label)) },
                        placeholder = { Text(stringResource(R.string.goal_title_placeholder)) },
                        singleLine = true
                    )
                }
            }

            // Erfassung: automatisch oder manuell
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_tracking), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            FilterChip(
                                selected = !isManual,
                                onClick = { viewModel.setTrackingMode(GoalProgressAnalytics.MODE_AUTO) },
                                label = { Text(stringResource(R.string.goal_tracking_auto)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isManual,
                                onClick = { viewModel.setTrackingMode(GoalProgressAnalytics.MODE_MANUAL) },
                                label = { Text(stringResource(R.string.goal_tracking_manual)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            stringResource(
                                if (isManual) R.string.goal_tracking_manual_desc
                                else R.string.goal_tracking_auto_desc
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quelle (nur automatisch)
            if (!isManual) {
                item {
                    AevumCard {
                        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            Text(stringResource(R.string.goal_source), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                SourceChip(GoalProgressAnalytics.SOURCE_ACTIVITY, R.string.goal_source_activity, form, viewModel)
                                SourceChip(GoalProgressAnalytics.SOURCE_CATEGORY, R.string.goal_source_category, form, viewModel)
                                SourceChip(GoalProgressAnalytics.SOURCE_DIGITAL, R.string.goal_source_digital, form, viewModel)
                                SourceChip(GoalProgressAnalytics.SOURCE_TODOS, R.string.goal_source_todos, form, viewModel)
                            }

                            when (form.sourceKind) {
                                GoalProgressAnalytics.SOURCE_ACTIVITY -> {
                                    Box {
                                        OutlinedButton(
                                            onClick = { viewModel.setShowActivityTypeMenu(true) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(selectedActivityName ?: stringResource(R.string.goal_activity_type_select))
                                                Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = form.showActivityTypeMenu,
                                            onDismissRequest = { viewModel.setShowActivityTypeMenu(false) }
                                        ) {
                                            state.activityTypes.forEach { type ->
                                                DropdownMenuItem(
                                                    onClick = {
                                                        viewModel.setActivityType(type.id)
                                                        viewModel.setShowActivityTypeMenu(false)
                                                    },
                                                    text = { Text("${type.icon} ${type.name}") }
                                                )
                                            }
                                        }
                                    }
                                }
                                GoalProgressAnalytics.SOURCE_CATEGORY -> {
                                    Box {
                                        OutlinedButton(
                                            onClick = { viewModel.setShowCategoryMenu(true) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(selectedCategoryName ?: stringResource(R.string.goal_category_select))
                                                Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = form.showCategoryMenu,
                                            onDismissRequest = { viewModel.setShowCategoryMenu(false) }
                                        ) {
                                            state.categories.forEach { category ->
                                                DropdownMenuItem(
                                                    onClick = {
                                                        viewModel.setCategory(category.id)
                                                        viewModel.setShowCategoryMenu(false)
                                                    },
                                                    text = { Text(category.name) }
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        stringResource(R.string.goal_source_category_hint),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                GoalProgressAnalytics.SOURCE_DIGITAL -> {
                                    Text(
                                        stringResource(R.string.goal_source_digital_hint),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                GoalProgressAnalytics.SOURCE_TODOS -> {
                                    Text(
                                        stringResource(R.string.goal_source_todos_hint),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Zeitraum
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_period), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf(
                                "DAILY" to stringResource(R.string.common_daily),
                                "WEEKLY" to stringResource(R.string.goal_weekly),
                                "MONTHLY" to stringResource(R.string.goal_monthly)
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = form.period == value,
                                    onClick = { viewModel.setPeriod(value) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Zieltyp
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_type), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf(
                                GoalProgressAnalytics.TYPE_AT_LEAST to stringResource(R.string.goal_at_least),
                                GoalProgressAnalytics.TYPE_AT_MOST to stringResource(R.string.goal_at_most),
                                GoalProgressAnalytics.TYPE_COUNT to stringResource(R.string.goal_goaltype_count)
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = form.goalType == value,
                                    onClick = { viewModel.setGoalType(value) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.goal_type_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Zielwert
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(if (isCount) R.string.goal_value_count_label else R.string.goal_value_unit),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isCount) {
                            OutlinedTextField(
                                value = form.targetValue,
                                onValueChange = viewModel::setTargetValue,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.goal_value_count_label)) },
                                placeholder = { Text(stringResource(R.string.goal_value_count_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                OutlinedTextField(
                                    value = form.targetValue,
                                    onValueChange = viewModel::setTargetValue,
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.goal_value)) },
                                    placeholder = { Text(stringResource(R.string.goal_value_placeholder)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { viewModel.setShowUnitMenu(true) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                if (form.targetUnit == "HOURS") stringResource(R.string.common_hours)
                                                else stringResource(R.string.common_minutes)
                                            )
                                            Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = form.showUnitMenu,
                                        onDismissRequest = { viewModel.setShowUnitMenu(false) }
                                    ) {
                                        listOf(
                                            "HOURS" to stringResource(R.string.common_hours),
                                            "MINUTES" to stringResource(R.string.common_minutes)
                                        ).forEach { (value, label) ->
                                            DropdownMenuItem(
                                                onClick = {
                                                    viewModel.setTargetUnit(value)
                                                    viewModel.setShowUnitMenu(false)
                                                },
                                                text = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Wochentage
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_schedule), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            FilterChip(
                                selected = form.scheduleDays and GoalProgressAnalytics.ALL_DAYS == GoalProgressAnalytics.ALL_DAYS,
                                onClick = { viewModel.setScheduleDays(GoalProgressAnalytics.ALL_DAYS) },
                                label = { Text(stringResource(R.string.goal_schedule_all)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = form.scheduleDays == GoalEditorViewModel.WEEKDAYS,
                                onClick = { viewModel.setScheduleDays(GoalEditorViewModel.WEEKDAYS) },
                                label = { Text(stringResource(R.string.goal_schedule_weekdays)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = form.scheduleDays == GoalEditorViewModel.WEEKEND,
                                onClick = { viewModel.setScheduleDays(GoalEditorViewModel.WEEKEND) },
                                label = { Text(stringResource(R.string.goal_schedule_weekend)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        GoalWeekdayPills(
                            selectedMask = form.scheduleDays,
                            onToggle = viewModel::toggleWeekday
                        )
                        Text(
                            stringResource(R.string.goal_schedule_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Farbe
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_color), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        GoalAccentPicker(selected = form.accent, onSelect = viewModel::setAccent)
                    }
                }
            }

            // Vorschau
            item {
                AevumCard(variant = CardVariant.Filled) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_preview), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            GoalAccents.color(form.accent)?.let { c ->
                                Box(
                                    modifier = Modifier
                                        .height(14.dp)
                                        .width(14.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(c)
                                )
                            }
                            GoalPill(
                                text = stringResource(
                                    when {
                                        isManual -> R.string.goal_tracking_manual
                                        form.sourceKind == GoalProgressAnalytics.SOURCE_DIGITAL -> R.string.goal_source_digital
                                        form.sourceKind == GoalProgressAnalytics.SOURCE_TODOS -> R.string.goal_source_todos
                                        form.sourceKind == GoalProgressAnalytics.SOURCE_CATEGORY -> R.string.goal_source_category
                                        else -> R.string.goal_source_activity
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            GoalPill(
                                text = stringResource(
                                    when (form.period) {
                                        "WEEKLY" -> R.string.goal_weekly
                                        "MONTHLY" -> R.string.goal_monthly
                                        else -> R.string.common_daily
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            form.title.ifBlank { stringResource(R.string.goal_title_placeholder) },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatPreviewValue(form) + " · " + scheduleText(form.scheduleDays),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Fehler
            form.errorRes?.let { errorRes ->
                item {
                    AevumCard(variant = CardVariant.Filled) {
                        Text(stringResource(errorRes), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            // Speichern
            item {
                Button(
                    onClick = viewModel::saveGoal,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.common_save), fontSize = 16.sp)
                }
            }

            // M11.2: Löschen — nur im Edit-Modus
            if (goalId != null) {
                item {
                    var showDeleteDialog by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.goal_delete), fontSize = 14.sp)
                    }
                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text(stringResource(R.string.goal_delete_title)) },
                            text = { Text(stringResource(R.string.goal_delete_message), fontSize = 14.sp) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDeleteDialog = false
                                        viewModel.deleteGoal(goalId)
                                    }
                                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun PresetChip(preset: GoalPreset, labelRes: Int, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(stringResource(labelRes)) }
    )
}

@Composable
private fun SourceChip(
    kind: String,
    labelRes: Int,
    form: GoalFormState,
    viewModel: GoalEditorViewModel
) {
    FilterChip(
        selected = form.sourceKind == kind,
        onClick = { viewModel.setSourceKind(kind) },
        label = { Text(stringResource(labelRes)) }
    )
}

private fun formatPreviewValue(form: GoalFormState): String {
    val value = form.targetValue.ifBlank { "?" }
    return when {
        form.goalType == GoalProgressAnalytics.TYPE_COUNT -> "${value}×"
        form.targetUnit == "HOURS" -> "${value} h"
        else -> "${value} min"
    }
}
