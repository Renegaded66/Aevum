package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.ProgressRing
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun GoalsScreen(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onBack: () -> Unit = {},
    onCreate: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: GoalsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    GoalsContent(
        modifier = modifier,
        state = state,
        onBack = onBack,
        onCreate = onCreate,
        onEdit = onEdit
    )
}

@Composable
private fun GoalsContent(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    state: GoalsUiState,
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
            item { GoalsHero(onBack = onBack, onCreate = onCreate) }
            if (state.activeGoals.isEmpty()) {
                item { GoalsEmptyState(onCreate = onCreate) }
            } else {
                item { SectionHeader("Aktive Ziele", "${state.activeGoals.size} Ziele") }
                items(state.activeGoals.size, key = { state.activeGoals[it].goal.id }) { index ->
                    val goalProgress = state.activeGoals[index]
                    GoalProgressCard(goalProgress = goalProgress, onEdit = { onEdit(goalProgress.goal.id) })
                }
            }
            if (state.inactiveGoals.isNotEmpty() && state.showCompleted) {
                item { Spacer(Modifier.height(AevumSpacing.lg)) }
                item { SectionHeader("Archiviert", "${state.inactiveGoals.size} Ziele") }
                items(state.inactiveGoals.size, key = { state.inactiveGoals[it].goal.id }) { index ->
                    val goalProgress = state.inactiveGoals[index]
                    GoalProgressCard(goalProgress = goalProgress, onEdit = { onEdit(goalProgress.goal.id) }, isArchived = true)
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
private fun GoalsHero(onBack: () -> Unit, onCreate: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ZIELE", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Dein Fortschritt sichtbar machen", fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(AevumSpacing.md))
                OutlinedButton(onClick = onBack) { Text("Zurück") }
            }
            Text(
                "Ziele geben deiner Zeit eine Richtung. Keine Punkte, keine Level — nur ruhiger Fortschritt.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Ziel anlegen") }
        }
    }
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

@Composable
private fun GoalsEmptyState(onCreate: () -> Unit) {
    EmptyState(
        title = "Noch keine Ziele",
        message = "Du kannst Ziele anlegen, um deinen Fortschritt sichtbar zu machen. Zum Beispiel: 8 Stunden Schlaf pro Nacht, 3 Stunden Sport pro Woche, maximal 2 Stunden Digitalzeit pro Tag.",
        actionLabel = "Erstes Ziel anlegen",
        onActionClick = onCreate
    )
}

@Composable
private fun GoalProgressCard(
    goalProgress: GoalWithProgress,
    onEdit: () -> Unit,
    isArchived: Boolean = false
) {
    val goal = goalProgress.goal
    val progress = goalProgress.progress.coerceIn(0f, 2f) // Allow up to 200% for "at most" goals
    val isAtMost = goal.type == "AT_MOST"
    val progressText = goalProgress.progressText
    val progressPercent = (progress * 100).toInt().coerceIn(0, 200)

    AevumCard(
        variant = if (isArchived) CardVariant.Outlined else CardVariant.Elevated,
        contentPadding = PaddingValues(AevumSpacing.md)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(goal.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(goalProgress.activityTypeName ?: "Aktivität", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text(goalProgress.periodLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (isAtMost) {
                    // For "at most" goals, show different visual
                    ProgressRing(
                        progress = (1f - progress).coerceIn(0f, 1f),
                        size = 48.dp,
                        strokeWidth = 6.dp,
                        progressColor = if (progress <= 1f) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        valueText = "${((1f - progress).coerceIn(0f, 1f) * 100).toInt()}%"
                    )
                } else {
                    ProgressRing(
                        progress = progress.coerceIn(0f, 1f),
                        size = 48.dp,
                        strokeWidth = 6.dp,
                        progressColor = MaterialTheme.colorScheme.secondary,
                        valueText = "$progressPercent%"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    progressText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                if (!isArchived) {
                    OutlinedButton(onClick = onEdit) { Text("Bearbeiten") }
                }
            }

            // Visual progress bar
            ProgressBarIndicator(
                progress = progress,
                isAtMost = isAtMost,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )

            // Type indicator
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (isAtMost) "Zieltyp: Maximal" else "Zieltyp: Mindestens",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (goal.status == "ACTIVE") {
                    Text("Aktiv", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                } else {
                    Text("Archiviert", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun ProgressBarIndicator(
    progress: Float,
    isAtMost: Boolean,
    modifier: Modifier = Modifier
) {
    val clampedProgress = if (isAtMost) (1f - progress).coerceIn(0f, 1f) else progress.coerceIn(0f, 1f)
    val color = if (isAtMost && progress > 1f) MaterialTheme.colorScheme.error
    else if (progress > 1f) MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.secondary

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(AevumRadius.full))) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clampedProgress)
                .background(color, RoundedCornerShape(AevumRadius.full))
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun GoalsScreenPreview() {
    AevumTheme(darkTheme = true) {
        GoalsContent(
            state = GoalsUiState(
                activeGoals = listOf(
                    GoalWithProgress(
                        goal = com.d_drostes_apps.aevum.data.model.Goal(
                            id = "1",
                            title = "8h Schlaf pro Nacht",
                            activityTypeId = "sleep",
                            type = "AT_LEAST",
                            period = "DAILY",
                            targetValue = 8f,
                            targetUnit = "HOURS"
                        ),
                        currentValue = 7.5f,
                        progress = 0.94f,
                        periodLabel = "Heute",
                        activityTypeName = "Schlaf"
                    ),
                    GoalWithProgress(
                        goal = com.d_drostes_apps.aevum.data.model.Goal(
                            id = "2",
                            title = "3h Sport pro Woche",
                            activityTypeId = "fitness",
                            type = "AT_LEAST",
                            period = "WEEKLY",
                            targetValue = 3f,
                            targetUnit = "HOURS"
                        ),
                        currentValue = 2.25f,
                        progress = 0.75f,
                        periodLabel = "Diese Woche",
                        activityTypeName = "Fitness"
                    ),
                    GoalWithProgress(
                        goal = com.d_drostes_apps.aevum.data.model.Goal(
                            id = "3",
                            title = "Max 2h Digitalzeit pro Tag",
                            activityTypeId = "digital",
                            type = "AT_MOST",
                            period = "DAILY",
                            targetValue = 2f,
                            targetUnit = "HOURS"
                        ),
                        currentValue = 1.33f,
                        progress = 0.67f,
                        periodLabel = "Heute",
                        activityTypeName = "Digital"
                    )
                ),
                inactiveGoals = emptyList()
            ),
            onBack = {},
            onCreate = {},
            onEdit = {}
        )
    }
}