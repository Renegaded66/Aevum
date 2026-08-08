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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.Habit
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme

@Composable
fun HabitsScreen(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onBack: () -> Unit = {},
    onCreate: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: HabitsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState(initial = HabitsUiState())
    HabitsContent(modifier = modifier, state = state, onBack = onBack, onCreate = onCreate, onEdit = onEdit)
}

@Composable
private fun HabitsContent(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    state: HabitsUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { HabitsHeader(state = state, onBack = onBack, onCreate = onCreate) }
            if (state.activeHabits.isEmpty() && state.inactiveHabits.isEmpty()) {
                item { HabitsEmptyState(onCreate = onCreate) }
            } else {
                if (state.activeHabits.isNotEmpty()) {
                    item { SectionHeader("Aktive Gewohnheiten", "${state.activeHabits.size} Gewohnheiten") }
                    items(state.activeHabits.size, key = { state.activeHabits[it].habit.id }) { index ->
                        val habitProgress = state.activeHabits[index]
                        HabitCard(habitProgress = habitProgress, onEdit = { onEdit(habitProgress.habit.id) })
                    }
                }
                if (state.inactiveHabits.isNotEmpty() && state.showCompleted) {
                    item { Spacer(Modifier.height(AevumSpacing.lg)) }
                    item { SectionHeader("Pausiert / Abgeschlossen", "${state.inactiveHabits.size} Gewohnheiten") }
                    items(state.inactiveHabits.size, key = { state.inactiveHabits[it].habit.id }) { index ->
                        val habitProgress = state.inactiveHabits[index]
                        HabitCard(habitProgress = habitProgress, onEdit = { onEdit(habitProgress.habit.id) }, isArchived = true)
                    }
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
private fun HabitsHeader(state: HabitsUiState, onBack: () -> Unit, onCreate: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GEWOHNHEITEN", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Kleine Schritte, große Wirkung", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(AevumSpacing.md))
                Button(onClick = onCreate) { Text("Neue Gewohnheit") }
            }
            if (state.activeHabits.isNotEmpty()) {
                Text(
                    "${state.activeHabits.count { it.streak > 0 }} mit Streak · ø ${if (state.activeHabits.isNotEmpty()) (state.activeHabits.sumOf { it.successRate } / state.activeHabits.size).toInt() else 0}% Erfolgsquote",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Gewohnheiten sind das Fundament nachhaltiger Veränderung. Keine Punkte, keine Level — nur ruhige Konsistenz.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun HabitCard(
    habitProgress: HabitWithProgress,
    onEdit: () -> Unit,
    isArchived: Boolean = false
) {
    val habit = habitProgress.habit
    val heatmap = habitProgress.heatmap

    AevumCard(
        variant = if (isArchived) CardVariant.Outlined else CardVariant.Elevated,
        contentPadding = PaddingValues(AevumSpacing.md),
        modifier = Modifier.fillMaxWidth().alpha(if (isArchived) 0.6f else 1f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(habit.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(habitProgress.activityTypeName ?: "Gewohnheit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text(habitProgress.frequencyLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = onEdit) { Text("Bearbeiten") }
            }

            // Heatmap mini calendar (last 28 days)
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    heatmap.forEach { day ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (day.completed) MaterialTheme.colorScheme.primary.copy(alpha = day.intensity)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    RoundedCornerShape(AevumRadius.sm)
                                )
                        )
                    }
                }

                // Stats row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Streak", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        Text("${habitProgress.streak}", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = if (habitProgress.streak > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Erfolgsquote", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        Text("${habitProgress.successRate}%", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Aktiv", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        Text("${habitProgress.activeDays} / ${habitProgress.totalDays} Tage", fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitsEmptyState(onCreate: () -> Unit) {
    EmptyState(
        title = "Noch keine Gewohnheiten",
        message = "Kleine tägliche Routinen machen den Unterschied. Keine Punkte, keine Level — nur ruhige Konsistenz.",
        actionLabel = "Gewohnheit anlegen",
        onActionClick = onCreate
    )
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class HabitWithProgress(
    val habit: Habit,
    val streak: Int,
    val successRate: Int,
    val activeDays: Int,
    val totalDays: Int,
    val heatmap: List<HeatmapDay>,
    val frequencyLabel: String,
    val activityTypeName: String?
)

data class HeatmapDay(
    val date: Long,
    val completed: Boolean,
    val intensity: Float = 0.6f
)

data class HabitsUiState(
    val activeHabits: List<HabitWithProgress> = emptyList(),
    val inactiveHabits: List<HabitWithProgress> = emptyList(),
    val showCompleted: Boolean = false
)