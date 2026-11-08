package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M36: Habit-Editor — drei Arten, ein Formular.
 *
 * Keine Automatik-Quellen, keine Zeiträume, keine Einheiten, nichts
 * „Optionales". Nur: Was ist es (abhaken / Anzahl / Zeit), wie heißt es,
 * wie oft, an welchen Tagen, welche Farbe.
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
    // Ressourcen außerhalb der onClick-Lambdas auflösen (Composable-Kontext).
    val presetWater = stringResource(R.string.goal_tracker_preset_water)
    val presetReading = stringResource(R.string.goal_tracker_preset_reading)
    val presetMeditation = stringResource(R.string.goal_tracker_preset_meditation)

    LaunchedEffect(goalId) {
        if (goalId != null) viewModel.loadGoal(goalId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item {
                AevumCard(variant = CardVariant.Gradient) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                    ) {
                        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                            Text("‹ " + stringResource(R.string.common_back), fontSize = 13.sp)
                        }
                        Text(
                            if (goalId == null) stringResource(R.string.goal_create)
                            else stringResource(R.string.goal_edit),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.goal_tracker_editor_subtitle),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (form.legacy) {
                item {
                    AevumCard(variant = CardVariant.Filled) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📦", fontSize = 22.sp)
                            Spacer(Modifier.width(AevumSpacing.sm))
                            Text(
                                stringResource(R.string.goal_tracker_legacy_notice),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_tracker_presets),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            PresetChip("💧 " + presetWater) {
                                viewModel.applyPreset(HabitPreset.WATER, presetWater)
                            }
                            PresetChip("📚 " + presetReading) {
                                viewModel.applyPreset(HabitPreset.READING, presetReading)
                            }
                            PresetChip("🧘 " + presetMeditation) {
                                viewModel.applyPreset(HabitPreset.MEDITATION, presetMeditation)
                            }
                        }
                    }
                }
            }

            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_title_label),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(form.icon, fontSize = 24.sp)
                            }
                            Spacer(Modifier.width(AevumSpacing.sm))
                            OutlinedTextField(
                                value = form.title,
                                onValueChange = viewModel::setTitle,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.goal_title_placeholder)) },
                                singleLine = true
                            )
                        }

                        Text(
                            stringResource(R.string.goal_tracker_icon),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HABIT_ICONS.forEach { icon ->
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (form.icon == icon) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        )
                                        .border(
                                            1.dp,
                                            if (form.icon == icon) MaterialTheme.colorScheme.primary
                                            else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable { viewModel.setIcon(icon) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(icon, fontSize = 19.sp)
                                }
                            }
                        }
                    }
                }
            }

            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_tracker_kind_section),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        KindOption(
                            kind = Goal.KIND_DONE,
                            selected = form.kind == Goal.KIND_DONE,
                            icon = "✅",
                            title = stringResource(R.string.goal_kind_done_title),
                            description = stringResource(R.string.goal_kind_done_desc),
                            onClick = { viewModel.setKind(Goal.KIND_DONE) }
                        )
                        KindOption(
                            kind = Goal.KIND_COUNT,
                            selected = form.kind == Goal.KIND_COUNT,
                            icon = "🔢",
                            title = stringResource(R.string.goal_kind_count_title),
                            description = stringResource(R.string.goal_kind_count_desc),
                            onClick = { viewModel.setKind(Goal.KIND_COUNT) }
                        )
                        KindOption(
                            kind = Goal.KIND_TIME,
                            selected = form.kind == Goal.KIND_TIME,
                            icon = "⏱️",
                            title = stringResource(R.string.goal_kind_time_title),
                            description = stringResource(R.string.goal_kind_time_desc),
                            onClick = { viewModel.setKind(Goal.KIND_TIME) }
                        )
                    }
                }
            }

            if (form.kind == Goal.KIND_COUNT) {
                item {
                    AevumCard {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                        ) {
                            Text(
                                stringResource(R.string.goal_tracker_count_label),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
                            ) {
                                StepButton(Icons.Filled.Remove) { viewModel.stepTargetCount(-1) }
                                Text(
                                    form.targetCount.ifBlank { "1" },
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(70.dp)
                                )
                                StepButton(Icons.Filled.Add) { viewModel.stepTargetCount(1) }
                            }
                            Text(
                                stringResource(R.string.goal_tracker_count_hint),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_schedule),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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
                            stringResource(R.string.goal_tracker_schedule_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_color),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        GoalAccentPicker(selected = form.accent, onSelect = viewModel::setAccent)
                    }
                }
            }

            item {
                AevumCard(variant = CardVariant.Filled) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(
                            stringResource(R.string.goal_tracker_preview),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HabitPreview(form)
                    }
                }
            }

            form.errorRes?.let { errorRes ->
                item {
                    AevumCard(variant = CardVariant.Filled) {
                        Text(
                            stringResource(errorRes),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(AevumSpacing.sm)
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = viewModel::saveGoal,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.common_save), fontSize = 16.sp)
                }
            }

            if (goalId != null) {
                item {
                    var showDelete by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showDelete = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(stringResource(R.string.goal_delete), fontSize = 14.sp) }

                    if (showDelete) {
                        AlertDialog(
                            onDismissRequest = { showDelete = false },
                            title = { Text(stringResource(R.string.goal_delete_title)) },
                            text = { Text(stringResource(R.string.goal_delete_message), fontSize = 14.sp) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDelete = false
                                    viewModel.deleteGoal(goalId)
                                }) {
                                    Text(
                                        stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDelete = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
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
private fun PresetChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label, fontSize = 12.sp) })
}

@Composable
private fun KindOption(
    kind: String,
    selected: Boolean,
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(AevumRadius.lg),
        color = if (selected) colors.primary.copy(alpha = 0.10f) else colors.surfaceVariant.copy(alpha = 0.25f),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(Modifier.width(AevumSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HabitPreview(form: GoalFormState) {
    val kind = form.kind
    val accent = GoalAccents.color(form.accent) ?: MaterialTheme.colorScheme.primary
    val value = when (kind) {
        Goal.KIND_COUNT -> "0 / ${form.targetCount.ifBlank { "3" }}"
        Goal.KIND_TIME -> "0:00"
        else -> "–"
    }
    Surface(
        shape = RoundedCornerShape(AevumRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text(form.icon, fontSize = 22.sp) }
            Spacer(Modifier.width(AevumSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    form.title.ifBlank { stringResource(R.string.goal_title_placeholder) },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (kind) {
                        Goal.KIND_COUNT -> stringResource(R.string.goal_kind_count) + " · " + value
                        Goal.KIND_TIME -> stringResource(R.string.goal_kind_time) + " · " + value
                        else -> stringResource(R.string.goal_kind_done) + " · " + value
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (kind == Goal.KIND_COUNT) Icons.Filled.Add else Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private val HABIT_ICONS = listOf(
    "🎯", "💧", "📚", "🏃", "🧘", "💪", "🥗", "😴", "🧹", "💊", "✍️", "🎸",
    "🙏", "🌿", "☀️", "🧠", "💰", "🚭", "📵", "🐶"
)
