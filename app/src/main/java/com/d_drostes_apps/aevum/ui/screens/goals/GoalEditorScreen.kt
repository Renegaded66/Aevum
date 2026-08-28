package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

@Composable
fun GoalEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    goalId: String? = null,
    viewModel: GoalEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())

    // M18.59-FIX: Beim Bearbeiten das bestehende Ziel laden — vorher
    // zeigte der Editor immer das leere Neu-Formular.
    LaunchedEffect(goalId) {
        if (goalId != null) viewModel.loadGoal(goalId)
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
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
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.common_back), fontSize = 14.sp) }
                        Text(
                            if (goalId == null) stringResource(R.string.goal_create) else stringResource(R.string.goal_edit),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Title
            item {
                AevumCard {
                    OutlinedTextField(
                        value = state.form.title,
                        onValueChange = viewModel::setTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.goal_title_label)) },
                        placeholder = { Text(stringResource(R.string.goal_title_placeholder)) },
                        singleLine = true
                    )
                }
            }

            // Activity Type
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_activity_type), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(
                                onClick = { viewModel.setShowActivityTypeMenu(true) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(state.form.selectedActivityTypeName ?: stringResource(R.string.goal_activity_type_select))
                                    Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            DropdownMenu(
                                expanded = state.form.showActivityTypeMenu,
                                onDismissRequest = { viewModel.setShowActivityTypeMenu(false) }
                            ) {
                                state.activityTypes.forEach { type ->
                                    DropdownMenuItem(
                                        onClick = {
                                            viewModel.setActivityType(type.id, type.name)
                                            viewModel.setShowActivityTypeMenu(false)
                                        },
                                        text = { Text(type.name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Period
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_period), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf(
                                "DAILY" to stringResource(R.string.common_daily),
                                "WEEKLY" to stringResource(R.string.goal_weekly),
                                "MONTHLY" to stringResource(R.string.goal_monthly)
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = state.form.period == value,
                                    onClick = { viewModel.setPeriod(value) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Goal Type
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_type), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.goal_type_hint),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf(
                                "AT_LEAST" to stringResource(R.string.goal_at_least),
                                "AT_MOST" to stringResource(R.string.goal_at_most)
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = state.form.goalType == value,
                                    onClick = { viewModel.setGoalType(value) },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Target Value & Unit
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text(stringResource(R.string.goal_value_unit), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            OutlinedTextField(
                                value = state.form.targetValue,
                                onValueChange = viewModel::setTargetValue,
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.goal_value)) },
                                placeholder = { Text(stringResource(R.string.goal_value_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { viewModel.setShowUnitMenu(true) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (state.form.targetUnit == "HOURS") stringResource(R.string.common_hours) else stringResource(R.string.common_minutes))
                                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                DropdownMenu(
                                    expanded = state.form.showUnitMenu,
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

            // Error
            state.form.errorRes?.let { errorRes ->
                item {
                    AevumCard(variant = CardVariant.Filled) {
                        Text(stringResource(errorRes), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            // Save button
            item {
                Button(
                    onClick = viewModel::saveGoal,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.common_save), fontSize = 16.sp)
                }
            }

            // M11.2: Delete button — nur im Edit-Modus
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
                            text = {
                                Text(
                                    stringResource(R.string.goal_delete_message),
                                    fontSize = 14.sp
                                )
                            },
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
