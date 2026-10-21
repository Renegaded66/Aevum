package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.ProgressRing
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.format.TextStyle
import java.util.Locale

/**
 * M20: Ziele-Liste der Ziel-Architektur — „Heute fällig" zuerst, dann alle
 * Ziele; jede Karte mit Fortschritts-Ring, Serie, Status und (bei manuellen
 * Zielen) direktem Abhaken. Bei erreichten Zielen schimmert die Karte.
 */
@Composable
fun GoalsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onCreate: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    viewModel: GoalsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val celebration by viewModel.celebration.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                item {
                    GoalsHero(
                        summary = state.summary,
                        onBack = onBack,
                        onCreate = onCreate
                    )
                }

                val due = state.activeGoals.filter { it.isDueToday }
                val rest = state.activeGoals.filterNot { it.isDueToday }

                if (state.activeGoals.isEmpty() && !state.loading) {
                    item { GoalsEmptyState(onCreate = onCreate) }
                }

                if (due.isNotEmpty()) {
                    item { GoalsSectionHeader(stringResource(R.string.goal_section_due), "${due.count { it.isCurrentAchieved }} / ${due.size}") }
                    items(due, key = { it.goal.id }) { g ->
                        GoalCard(
                            g = g,
                            accent = goalAccentColor(g),
                            onOpenDetail = { onOpenDetail(g.goal.id) },
                            onEdit = { onEdit(g.goal.id) },
                            onCheckIn = { viewModel.checkIn(g.goal.id) },
                            onUndo = { viewModel.undoLastCheckin(g.goal.id) }
                        )
                    }
                }

                if (rest.isNotEmpty()) {
                    if (due.isNotEmpty()) item { Spacer(Modifier.height(AevumSpacing.sm)) }
                    item { GoalsSectionHeader(stringResource(R.string.goal_section_all), "${state.activeGoals.size}") }
                    items(rest, key = { it.goal.id }) { g ->
                        GoalCard(
                            g = g,
                            accent = goalAccentColor(g),
                            onOpenDetail = { onOpenDetail(g.goal.id) },
                            onEdit = { onEdit(g.goal.id) },
                            onCheckIn = { viewModel.checkIn(g.goal.id) },
                            onUndo = { viewModel.undoLastCheckin(g.goal.id) }
                        )
                    }
                }

                if (state.inactiveGoals.isNotEmpty()) {
                    item {
                        TextButton(onClick = { viewModel.setShowCompleted(!state.showCompleted) }) {
                            Text(
                                if (state.showCompleted) stringResource(R.string.goal_hide_archived)
                                else stringResource(R.string.goal_show_archived)
                            )
                        }
                    }
                    item {
                        AnimatedVisibility(visible = state.showCompleted) {
                            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                                state.inactiveGoals.forEach { g ->
                                    GoalCard(
                                        g = g,
                                        accent = goalAccentColor(g),
                                        archived = true,
                                        onOpenDetail = { onOpenDetail(g.goal.id) },
                                        onEdit = { onEdit(g.goal.id) },
                                        onCheckIn = {},
                                        onUndo = null
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(AevumSpacing.xxl)) }
            }
        }

        // M20: Feier-Overlay — genau einmal je Ziel+Periode (Store-gated).
        GoalCelebrationOverlay(event = celebration, onDismiss = viewModel::consumeCelebration)
    }
}

@Composable
private fun goalAccentColor(g: GoalWithProgress): Color =
    GoalAccents.resolve(
        key = g.goal.accent,
        activityColor = g.activityColorLong,
        fallback = MaterialTheme.colorScheme.secondary
    )

@Composable
private fun GoalsHero(summary: GoalsSummary, onBack: () -> Unit, onCreate: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.goal_hero_label),
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.goal_hero_title),
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(AevumSpacing.md))
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            }

            if (summary.totalActive > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    HeroStat(
                        value = "${summary.metDue}/${summary.dueToday}",
                        label = stringResource(R.string.goal_hero_stat_today),
                        modifier = Modifier.weight(1f)
                    )
                    HeroStat(
                        value = "${summary.bestStreak}",
                        label = stringResource(R.string.goal_hero_stat_streak),
                        modifier = Modifier.weight(1f),
                        highlight = summary.bestStreak > 0
                    )
                    HeroStat(
                        value = "${summary.totalActive}",
                        label = stringResource(R.string.goal_hero_stat_total),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                stringResource(R.string.goal_hero_text),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.goal_create)) }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(AevumRadius.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFFFF7A2F) else MaterialTheme.colorScheme.onSurface
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
private fun GoalsSectionHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalsEmptyState(onCreate: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.goal_empty_title),
        message = stringResource(R.string.goal_empty_message),
        actionLabel = stringResource(R.string.goal_create_first),
        onActionClick = onCreate
    )
}

@Composable
private fun GoalCard(
    g: GoalWithProgress,
    accent: Color,
    onOpenDetail: () -> Unit,
    onEdit: () -> Unit,
    onCheckIn: () -> Unit,
    onUndo: (() -> Unit)?,
    archived: Boolean = false
) {
    val goal = g.goal
    val colors = MaterialTheme.colorScheme
    val isAtMost = goal.type == GoalProgressAnalytics.TYPE_AT_MOST
    val isManual = !GoalProgressAnalytics.isAuto(goal)
    val progress = g.progress.coerceIn(0f, 2f)

    AevumCard(
        variant = if (archived) CardVariant.Outlined else CardVariant.Elevated,
        onClick = onOpenDetail,
        contentPadding = PaddingValues(AevumSpacing.md),
        modifier = Modifier.shimmering(
            enabled = !archived && g.isCurrentAchieved && g.isDueToday,
            intensity = 0.18f,
            periodMillis = 4200
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            goal.title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (g.streakCurrent > 0) {
                            Spacer(Modifier.width(6.dp))
                            GoalStreakBadge(g.streakCurrent)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = goalSourceText(g) + " · " + g.periodLabel,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(AevumSpacing.sm))
                if (isAtMost) {
                    val remaining = (1f - progress).coerceIn(0f, 1f)
                    ProgressRing(
                        progress = remaining,
                        size = 52.dp,
                        strokeWidth = 6.dp,
                        progressColor = if (progress <= 1f) accent else colors.error,
                        valueText = "${(remaining * 100).toInt()}%"
                    )
                } else {
                    ProgressRing(
                        progress = progress.coerceIn(0f, 1f),
                        size = 52.dp,
                        strokeWidth = 6.dp,
                        progressColor = accent,
                        valueText = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = g.progressText,
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        !g.isDueToday -> GoalPill(
                            text = if (goal.period == "DAILY") stringResource(R.string.goal_badge_free_day)
                            else stringResource(R.string.goal_badge_free), 
                            color = colors.onSurfaceVariant
                        )
                        g.isPendingFinal -> GoalPill(
                            stringResource(R.string.goal_badge_pending),
                            colors.tertiary
                        )
                        g.isCurrentAchieved -> GoalPill(stringResource(R.string.goal_badge_met), accent, filled = true)
                        else -> GoalPill(stringResource(R.string.goal_badge_due), colors.onSurfaceVariant)
                    }
                }
            }

            if (g.isPendingFinal && g.lastCompletedMet == true) {
                Text(
                    stringResource(R.string.goal_badge_last_met),
                    fontSize = 10.sp,
                    color = accent
                )
            }

            if (isManual && !archived) {
                GoalCheckButton(
                    countToday = g.todayCheckins,
                    accent = accent,
                    onCheck = onCheckIn,
                    onUndo = onUndo,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!archived) {
                TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_edit), fontSize = 13.sp)
                }
            }
        }
    }
}

/** Menschenlesbare Quellenangabe („Automatisch: Digital", „Manuell", …). */
@Composable
fun goalSourceText(g: GoalWithProgress): String {
    val goal = g.goal
    return when {
        !GoalProgressAnalytics.isAuto(goal) -> stringResource(R.string.goal_tracking_manual)
        goal.sourceKind == GoalProgressAnalytics.SOURCE_DIGITAL -> stringResource(R.string.goal_source_digital)
        goal.sourceKind == GoalProgressAnalytics.SOURCE_TODOS -> stringResource(R.string.goal_source_todos)
        goal.sourceKind == GoalProgressAnalytics.SOURCE_CATEGORY ->
            g.categoryName ?: stringResource(R.string.goal_source_category)
        else -> g.activityTypeName ?: stringResource(R.string.goal_activity_fallback)
    }
}
