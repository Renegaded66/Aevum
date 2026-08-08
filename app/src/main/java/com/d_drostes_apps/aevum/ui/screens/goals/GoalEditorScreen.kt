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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
                        TextButton(onClick = onBack) { Text("Zurück", fontSize = 14.sp) }
                        Text(
                            if (goalId == null) "Ziel anlegen" else "Ziel bearbeiten",
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
                        label = { Text("Titel") },
                        placeholder = { Text("z. B. 8h Schlaf pro Nacht") },
                        singleLine = true
                    )
                }
            }

            // Activity Type
            item {
                AevumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        Text("Aktivitätstyp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(
                                onClick = { viewModel.setShowActivityTypeMenu(true) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(state.form.selectedActivityTypeName ?: "Aktivitätstyp auswählen")
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
                        Text("Zeitraum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf("DAILY" to "Täglich", "WEEKLY" to "Wöchentlich", "MONTHLY" to "Monatlich").forEach { (value, label) ->
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
                        Text("Zieltyp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Mindestens = du möchtest diese Zeit erreichen. Maximal = du möchtest diese Zeit nicht überschreiten.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            listOf("AT_LEAST" to "Mindestens", "AT_MOST" to "Maximal").forEach { (value, label) ->
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
                        Text("Zielwert & Einheit", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                            OutlinedTextField(
                                value = state.form.targetValue,
                                onValueChange = viewModel::setTargetValue,
                                modifier = Modifier.weight(1f),
                                label = { Text("Wert") },
                                placeholder = { Text("z. B. 8") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedButton(
                                    onClick = { viewModel.setShowUnitMenu(true) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(if (state.form.targetUnit == "HOURS") "Stunden" else "Minuten")
                                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                DropdownMenu(
                                    expanded = state.form.showUnitMenu,
                                    onDismissRequest = { viewModel.setShowUnitMenu(false) }
                                ) {
                                    listOf("HOURS" to "Stunden", "MINUTES" to "Minuten").forEach { (value, label) ->
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
            state.form.error?.let { error ->
                item {
                    AevumCard(variant = CardVariant.Filled) {
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }

            // Save button
            item {
                Button(
                    onClick = viewModel::saveGoal,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Speichern", fontSize = 16.sp)
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
                        Text("Ziel löschen", fontSize = 14.sp)
                    }
                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Ziel löschen?") },
                            text = {
                                Text(
                                    "Dieses Ziel wird dauerhaft entfernt.\n\n" +
                                    "Bereits aufgezeichnete Aktivitäten bleiben davon unberührt.",
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showDeleteDialog = false
                                        viewModel.deleteGoal(goalId)
                                    }
                                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) { Text("Abbrechen") }
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}
