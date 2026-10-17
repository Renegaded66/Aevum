package com.d_drostes_apps.aevum.ui.screens.goals

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.ChartContainer
import com.d_drostes_apps.aevum.ui.components.ProgressRing
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * M20: Detail-Ansicht eines Ziels — Fortschritt groß, Statistiken,
 * Verlauf (Balken + Heatmap), Check-in-Liste und Verwaltung
 * (bearbeiten · archivieren · löschen).
 */
@Composable
fun GoalDetailScreen(
    goalId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    viewModel: GoalDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(goalId) { viewModel.load(goalId) }
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val goal = state.goal
            if (goal == null) {
                // Laden oder bereits gelöscht (onBack läuft über closed).
                return@Surface
            }
            val g = state.progress
            val accent = GoalAccents.resolve(
                key = goal.accent,
                activityColor = g?.activityColorLong,
                fallback = MaterialTheme.colorScheme.secondary
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                item {
                    DetailHero(
                        goal = goal,
                        g = g,
                        accent = accent,
                        onBack = onBack,
                        onCheckIn = viewModel::checkIn,
                        onUndo = viewModel::undoLastCheckin
                    )
                }

                if (g != null) {
                    item { DetailStats(g = g) }
                }

                if (state.history.isNotEmpty()) {
                    item {
                        DetailHistory(
                            goal = goal,
                            history = state.history,
                            accent = accent
                        )
                    }
                }

                if (state.history.isNotEmpty() && goal.period == "DAILY") {
                    item {
                        ChartContainer(
                            title = stringResource(R.string.goal_heatmap_title),
                            subtitle = stringResource(R.string.goal_heatmap_subtitle)
                        ) {
                            GoalHeatmap(
                                periods = state.history,
                                target = goal.targetValue,
                                accent = accent,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (!GoalProgressAnalytics.isAuto(goal)) {
                    item {
                        CheckinList(
                            checkins = state.checkins,
                            accent = accent,
                            onDelete = viewModel::removeCheckin
                        )
                    }
                }

                item {
                    DetailActions(
                        isArchived = goal.status == "ARCHIVED",
                        onEdit = { onEdit(goal.id) },
                        onToggleArchive = { viewModel.setArchived(goal.status != "ARCHIVED") },
                        onDelete = viewModel::deleteGoal
                    )
                }

                item { Spacer(Modifier.height(AevumSpacing.xxl)) }
            }
        }

        GoalCelebrationOverlay(event = state.celebration, onDismiss = viewModel::consumeCelebration)
    }
}

@Composable
private fun DetailHero(
    goal: com.d_drostes_apps.aevum.data.model.Goal,
    g: GoalWithProgress?,
    accent: Color,
    onBack: () -> Unit,
    onCheckIn: () -> Unit,
    onUndo: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val isAtMost = goal.type == GoalProgressAnalytics.TYPE_AT_MOST
    val progress = (g?.progress ?: 0f).coerceIn(0f, 2f)
    val isManual = !GoalProgressAnalytics.isAuto(goal)

    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                if (g != null) {
                    GoalStreakBadge(g.streakCurrent, showLabel = true)
                }
            }

            Text(goal.title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g != null) {
                    GoalPill(text = goalSourceText(g), color = colors.onSurfaceVariant)
                }
                GoalPill(text = g?.periodLabel ?: goal.period, color = colors.onSurfaceVariant)
                if (goal.scheduleDays and GoalProgressAnalytics.ALL_DAYS != GoalProgressAnalytics.ALL_DAYS) {
                    GoalPill(text = scheduleText(goal.scheduleDays), color = colors.onSurfaceVariant)
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (isAtMost) {
                    val remaining = (1f - progress).coerceIn(0f, 1f)
                    ProgressRing(
                        progress = remaining,
                        size = 116.dp,
                        strokeWidth = 10.dp,
                        progressColor = if (progress <= 1f) accent else colors.error,
                        valueText = "${(remaining * 100).toInt()}%"
                    )
                } else {
                    ProgressRing(
                        progress = progress.coerceIn(0f, 1f),
                        size = 116.dp,
                        strokeWidth = 10.dp,
                        progressColor = accent,
                        valueText = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    )
                }
            }

            if (g != null) {
                Text(
                    text = g.progressText,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (g.streakBest > 0) {
                    Text(
                        text = stringResource(R.string.goal_stat_best_streak) + ": " + g.streakBest,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            if (isManual) {
                GoalCheckButton(
                    countToday = g?.todayCheckins ?: 0,
                    accent = accent,
                    onCheck = onCheckIn,
                    onUndo = onUndo,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DetailStats(g: GoalWithProgress) {
    AevumCard(contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.goal_detail_stats), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                StatBox(
                    value = "${g.streakCurrent}",
                    label = stringResource(R.string.goal_stat_current_streak),
                    modifier = Modifier.weight(1f),
                    highlight = g.streakCurrent > 0
                )
                StatBox(
                    value = "${g.streakBest}",
                    label = stringResource(R.string.goal_stat_best_streak),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                StatBox(
                    value = "${g.metPeriods}/${g.duePeriods}",
                    label = stringResource(R.string.goal_stat_met),
                    modifier = Modifier.weight(1f),
                    highlight = g.duePeriods > 0 && g.metPeriods >= g.duePeriods
                )
                StatBox(
                    value = g.progressText,
                    label = g.periodLabel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(com.d_drostes_apps.aevum.ui.theme.AevumRadius.md),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFFFF7A2F) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DetailHistory(
    goal: com.d_drostes_apps.aevum.data.model.Goal,
    history: List<GoalProgressAnalytics.PeriodStatus>,
    accent: Color
) {
    val display: List<GoalProgressAnalytics.PeriodStatus>
    val labels: List<String>
    val subtitle: Int
    when (goal.period) {
        "WEEKLY" -> {
            display = history.takeLast(8)
            labels = display.map { "%d.%d.".format(it.startDate.dayOfMonth, it.startDate.monthValue) }
            subtitle = R.string.goal_history_weekly
        }
        "MONTHLY" -> {
            display = history.takeLast(6)
            labels = display.map { it.startDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.') }
            subtitle = R.string.goal_history_monthly
        }
        else -> {
            display = history.takeLast(14)
            labels = display.map { it.startDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.') }
            subtitle = R.string.goal_history_daily
        }
    }

    ChartContainer(
        title = stringResource(R.string.goal_history),
        subtitle = stringResource(subtitle)
    ) {
        GoalHistoryBars(
            periods = display,
            target = goal.targetValue,
            accent = accent,
            labels = labels,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CheckinList(
    checkins: List<com.d_drostes_apps.aevum.data.model.GoalCheckin>,
    accent: Color,
    onDelete: (String) -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }

    AevumCard(contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(stringResource(R.string.goal_checkins), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (checkins.isEmpty()) {
                Text(
                    stringResource(R.string.goal_no_checkins),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                checkins.take(30).forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", fontSize = 13.sp, color = accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(AevumSpacing.sm))
                        val instant = Instant.ofEpochMilli(c.timestampMs).atZone(zone)
                        Text(
                            text = dateFormatter.format(instant) + " · " + timeFormatter.format(instant),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDelete(c.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.goal_checkin_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailActions(
    isArchived: Boolean,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_edit))
            }
            OutlinedButton(onClick = onToggleArchive, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (isArchived) R.string.goal_unarchive else R.string.goal_archive))
            }
        }
        TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.goal_delete), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.goal_delete_title)) },
            text = { Text(stringResource(R.string.goal_delete_message), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
