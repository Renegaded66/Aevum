package com.d_drostes_apps.aevum.ui.screens.habits

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.HabitRepository
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme

@Composable
fun HabitEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    habitId: String? = null,
    viewModel: HabitEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState(initial = HabitEditorUiState())

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
                HabitEditorHeader(
                    title = if (habitId == null) "Gewohnheit anlegen" else "Gewohnheit bearbeiten",
                    onBack = onBack,
                    onSave = viewModel::saveHabit
                ) 
            }
            item { HabitForm(state.form, viewModel, state.activityTypes) }
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
private fun HabitEditorHeader(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            TextButton(onClick = onBack) { Text("Zurück", fontSize = 14.sp) }
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Speichern") }
        }
    }
}

@Composable
private fun HabitForm(form: HabitFormState, viewModel: HabitEditorViewModel, activityTypes: List<ActivityType>) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            OutlinedTextField(
                value = form.title,
                onValueChange = viewModel::setTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Titel") },
                placeholder = { Text("z. B. Täglich lesen, 3× Sport pro Woche") },
                singleLine = true
            )

            // Activity Type selector
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Aktivitätstyp", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            // Frequency
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Häufigkeit", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    listOf("daily" to "Täglich", "weekly" to "Wöchentlich", "monthly" to "Monatlich").forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = form.frequencyType == value,
                            onClick = { viewModel.setFrequencyType(value) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                if (form.frequencyType != "daily") {
                    val periodText = when (form.frequencyType) {
                        "weekly" -> "Woche"
                        "monthly" -> "Monat"
                        else -> "Zeitraum"
                    }
                    OutlinedTextField(
                        value = form.frequencyCount.toString(),
                        onValueChange = { viewModel.setFrequencyCount(it.toIntOrNull() ?: 1) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Wie oft pro $periodText") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            // Success criteria
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Erfolgskriterium", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    listOf("minDuration" to "Mindestdauer", "count" to "Anzahl").forEach { (value, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = form.successRuleType == value,
                            onClick = { viewModel.setSuccessRuleType(value) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                if (form.successRuleType == "minDuration") {
                    OutlinedTextField(
                        value = form.successMinDuration.toString(),
                        onValueChange = { viewModel.setSuccessMinDuration(it.toLongOrNull() ?: 0) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mindestdauer (Minuten)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        }
    }
}