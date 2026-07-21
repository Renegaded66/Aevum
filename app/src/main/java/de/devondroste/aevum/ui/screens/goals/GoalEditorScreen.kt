package de.devondroste.aevum.ui.screens.goals

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.model.Goal
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme
import java.util.Locale
import java.util.UUID

@Composable
fun GoalEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    goalId: String? = null,
    viewModel: GoalEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    val context = LocalContext.current
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { 
        viewModel.onLocationPermissionResult(it)
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
            item { 
                GoalEditorHeader(
                    title = if (goalId == null) "Ziel anlegen" else "Ziel bearbeiten",
                    onBack = onBack,
                    onSave = viewModel::saveGoal
                ) 
            }
            item { GoalForm(state.form, viewModel, state.activityTypes, state.categories) }
            item { GoalTypeSection(viewModel) }
            item { GoalPeriodSection(viewModel) }
            item { GoalTargetSection(viewModel) }
            item { GoalCategorySection(viewModel, state.activityTypes) }
            state.form.error?.let { error ->
                item { 
                    AevumCard(variant = CardVariant.Filled) {
                        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md))
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalEditorHeader(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            TextButton(onClick = onBack) { Text("Zurück", fontSize = 14.sp) }
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

@Composable
private fun GoalForm(form: GoalFormState, viewModel: GoalEditorViewModel, activityTypes: List<ActivityType>, categories: List<Category>) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            OutlinedTextField(
                value = form.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Titel") },
                placeholder = { Text("z. B. 8h Schlaf pro Nacht") },
                singleLine = true
            )

            // Activity Type selector
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Aktivitätstyp", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.DropdownMenu(
                    expanded = form.showActivityTypeMenu,
                    onDismissRequest = { viewModel.setShowActivityTypeMenu(false) }
                ) {
                    activityTypes.forEach { type ->
                        androidx.compose.material3.DropdownMenuItem(
                            onClick = {
                                viewModel.setActivityType(type.id)
                                viewModel.setShowActivityTypeMenu(false)
                            },
                            text = { Text(type.name) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.setShowActivityTypeMenu(true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(form.selectedActivityTypeName ?: "Aktivitätstyp auswählen")
                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Period selector
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Zeitraum", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    listOf("DAILY" to "Täglich", "WEEKLY" to "Wöchentlich", "MONTHLY" to "Monatlich").forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = form.period == value,
                            onClick = { viewModel.setPeriod(value) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Type selector (At least / At most)
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Zieltyp", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    listOf("AT_LEAST" to "Mindestens", "AT_MOST" to "Maximal").forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = form.goalType == value,
                            onClick = { viewModel.setGoalType(value) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Target value and unit
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                OutlinedTextField(
                    value = form.targetValue,
                    onValueChange = viewModel::setTargetValue,
                    modifier = Modifier.weight(1f),
                    label = { Text("Wert") },
                    placeholder = { Text("3") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = form.showUnitMenu,
                    onDismissRequest = { viewModel.setShowUnitMenu(false) }
                ) {
                    listOf("HOURS", "MINUTES").forEach { unit ->
                        androidx.compose.material3.DropdownMenuItem(
                            onClick = {
                                viewModel.setTargetUnit(unit)
                                viewModel.setShowUnitMenu(false)
                            },
                            text = { Text(if (unit == "HOURS") "Stunden" else "Minuten") }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.setShowUnitMenu(true) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (form.targetUnit == "HOURS") "Stunden" else "Minuten")
                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalTypeSection(viewModel: GoalEditorViewModel) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Zieltyp", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Mindestens = du möchtest diese Zeit erreichen. Maximal = du möchtest diese Zeit nicht überschreiten.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                listOf("AT_LEAST" to "Mindestens", "AT_MOST" to "Maximal").forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(
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

@Composable
private fun GoalPeriodSection(viewModel: GoalEditorViewModel) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Zeitraum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                listOf("DAILY" to "Täglich", "WEEKLY" to "Wöchentlich", "MONTHLY" to "Monatlich").forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(
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

@Composable
private fun GoalTargetSection(viewModel: GoalEditorViewModel) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
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
                androidx.compose.material3.DropdownMenu(
                    expanded = state.form.showUnitMenu,
                    onDismissRequest = { viewModel.setShowUnitMenu(false) }
                ) {
                    listOf("HOURS" to "Stunden", "MINUTES" to "Minuten").forEach { (value, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            onClick = {
                                viewModel.setTargetUnit(value)
                                viewModel.setShowUnitMenu(false)
                            },
                            text = { Text(label) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.setShowUnitMenu(true) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (state.form.targetUnit == "HOURS") "Stunden" else "Minuten")
                        Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCategorySection(viewModel: GoalEditorViewModel, activityTypes: List<ActivityType>) {
    val state by viewModel.uiState.collectAsState(initial = GoalEditorUiState())
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Aktivitätstyp (setzt Kategorie)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.DropdownMenu(
                expanded = state.form.showActivityTypeMenu,
                onDismissRequest = { viewModel.setShowActivityTypeMenu(false) }
            ) {
                activityTypes.forEach { type ->
                    androidx.compose.material3.DropdownMenuItem(
                        onClick = {
                            viewModel.setActivityType(type.id)
                            viewModel.setShowActivityTypeMenu(false)
                        },
                        text = { Text(type.name) }
                    )
                }
            }
            OutlinedButton(
                onClick = { viewModel.setShowActivityTypeMenu(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.form.selectedActivityTypeName ?: "Aktivitätstyp auswählen")
                    Text(" ▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}