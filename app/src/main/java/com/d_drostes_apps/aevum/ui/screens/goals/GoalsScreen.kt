package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.ProgressRing
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * M36: Ziele als vollständiger Habit-Tracker.
 *
 * Aufbau:
 *  - Tages-Hero: „3 von 5 erledigt", Serie, perfekte Tage.
 *  - Tagesleiste mit 7 Tagen — antippbar; darunter ein HorizontalPager,
 *    durch den man durch die letzten [PAGE_DAYS] Tage swipen kann.
 *  - Je Tag die fälligen Habits: abhaken, zählen oder Zeit eintragen.
 *
 * Der Pager ist die zentrale Interaktion: Wischen = Tag wechseln, Tippen
 * auf einen Tag = dorthin springen. Dadurch lassen sich vergessene Tage
 * nachträglich abhaken und mit Zeit füllen.
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
    val timePrompt by viewModel.timePrompt.collectAsState()

    val today = remember { LocalDate.now() }
    val startDate = remember { today.minusDays((PAGE_DAYS - 1).toLong()) }
    val pagerState = rememberPagerState(initialPage = PAGE_DAYS - 1, pageCount = { PAGE_DAYS })
    val selectedPage by remember { derivedStateOf { pagerState.settledPage } }
    val selectedDate = remember(selectedPage) { startDate.plusDays(selectedPage.toLong()) }
    val scope = rememberCoroutineScope()

    val dayHabits = state.habitGoals.filter { it.isDueOn(selectedDate) }
    val dayDone = dayHabits.count { it.isDoneOn(selectedDate) }
    val heroSummary = if (selectedDate == today) {
        state.summary
    } else {
        state.summary.copy(
            dueToday = dayHabits.size,
            metDue = dayDone,
            perfectToday = dayHabits.isNotEmpty() && dayDone == dayHabits.size
        )
    }

    fun jumpTo(date: LocalDate) {
        val index = java.time.temporal.ChronoUnit.DAYS.between(startDate, date)
            .toInt().coerceIn(0, PAGE_DAYS - 1)
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                GoalsHeader(onBack = onBack, onCreate = onCreate)
                GoalsHero(
                    summary = heroSummary,
                    date = selectedDate,
                    isToday = selectedDate == today
                )
                DayStrip(
                    selectedDate = selectedDate,
                    habits = state.habitGoals,
                    today = today,
                    onSelect = ::jumpTo
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top
                ) { page ->
                    val date = startDate.plusDays(page.toLong())
                    HabitDayPage(
                        date = date,
                        isToday = date == today,
                        habits = state.habitGoals,
                        legacyGoals = if (date == today) state.legacyGoals else emptyList(),
                        archivedHabitGoals = state.archivedHabitGoals,
                        archivedLegacyGoals = state.archivedLegacyGoals,
                        showArchived = state.showArchived,
                        onCreate = onCreate,
                        onEdit = onEdit,
                        onOpenDetail = onOpenDetail,
                        onCheckIn = { id -> viewModel.checkIn(id, date) },
                        onUndo = { id -> viewModel.undo(id, date) },
                        onLogTime = { id -> viewModel.openTimePrompt(id, date) },
                        onToggleArchived = viewModel::setShowArchived,
                        onJumpToday = { jumpTo(today) }
                    )
                }
            }
        }

        GoalCelebrationOverlay(event = celebration, onDismiss = viewModel::consumeCelebration)
        HabitTimePickerDialog(
            prompt = timePrompt,
            onConfirm = viewModel::confirmTimePrompt,
            onRemove = viewModel::removeTimeFromPrompt,
            onDismiss = viewModel::dismissTimePrompt
        )
    }
}

@Composable
private fun GoalsHeader(onBack: () -> Unit, onCreate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.goals_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.goal_tracker_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onCreate,
            shape = RoundedCornerShape(AevumRadius.full),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.goal_create), fontSize = 13.sp)
        }
    }
}

@Composable
private fun GoalsHero(summary: GoalsSummary, date: LocalDate, isToday: Boolean) {
    val done = summary.metDue
    val due = summary.dueToday
    val fraction = if (due > 0) done.toFloat() / due else 0f
    val perfect = summary.perfectToday && isToday
    val accent = if (perfect) Color(0xFFFFC53D) else MaterialTheme.colorScheme.primary

    AevumCard(
        variant = CardVariant.Gradient,
        modifier = Modifier.padding(horizontal = AevumSpacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (perfect) stringResource(R.string.goal_tracker_perfect_title)
                    else if (isToday) stringResource(R.string.goal_tracker_hero_label)
                    else dayLong(date),
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Text(
                    text = if (due == 0) stringResource(R.string.goal_tracker_free_title)
                    else stringResource(R.string.goal_tracker_hero_title, done, due),
                    fontSize = 22.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (summary.bestStreak > 0) {
                        Text(
                            "🔥 " + stringResource(R.string.goal_tracker_streak_short, summary.bestStreak),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF7A2F)
                        )
                    }
                    if (summary.perfectStreak > 0) {
                        Text(
                            "✨ " + stringResource(
                                R.string.goal_tracker_perfect_streak,
                                summary.perfectStreak
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.width(AevumSpacing.md))
            ProgressRing(
                progress = if (due == 0) 0f else fraction.coerceIn(0f, 1f),
                size = 86.dp,
                strokeWidth = 9.dp,
                progressColor = accent,
                valueText = if (due == 0) "–" else "$done/$due"
            )
        }
    }
}

@Composable
private fun DayStrip(
    selectedDate: LocalDate,
    habits: List<HabitGoalUi>,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val days = remember(selectedDate) {
        val start = selectedDate.minusDays(3)
        (0..6).map { start.plusDays(it.toLong()) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        days.forEach { date ->
            val due = habits.filter { it.isDueOn(date) }
            val done = due.count { it.isDoneOn(date) }
            val perfect = due.isNotEmpty() && done == due.size
            val selected = date == selectedDate
            val isFuture = date.isAfter(today)
            val accent = when {
                perfect -> Color(0xFF22C55E)
                due.isNotEmpty() && done > 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            }
            Surface(
                onClick = { if (!isFuture) onSelect(date) },
                enabled = !isFuture,
                shape = RoundedCornerShape(AevumRadius.lg),
                color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, accent) else null,
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).trimEnd('.'),
                        fontSize = 10.sp,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    due.isEmpty() -> Color.Transparent
                                    perfect -> Color(0xFF22C55E)
                                    done > 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitDayPage(
    date: LocalDate,
    isToday: Boolean,
    habits: List<HabitGoalUi>,
    legacyGoals: List<GoalWithProgress>,
    archivedHabitGoals: List<HabitGoalUi>,
    archivedLegacyGoals: List<GoalWithProgress>,
    showArchived: Boolean,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onCheckIn: (String) -> Unit,
    onUndo: (String) -> Unit,
    onLogTime: (String) -> Unit,
    onToggleArchived: (Boolean) -> Unit,
    onJumpToday: () -> Unit
) {
    val visible = habits.filter { it.isVisibleOn(date) }
    val due = visible.filter { it.isDueOn(date) }
    val free = visible.filterNot { it.isDueOn(date) }
    val done = due.count { it.isDoneOn(date) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AevumSpacing.md,
            end = AevumSpacing.md,
            top = AevumSpacing.xs,
            bottom = AevumSpacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isToday) stringResource(R.string.goal_tracker_today)
                    else dayLong(date),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (!isToday) {
                    GoalPill(
                        text = stringResource(R.string.goal_tracker_retro),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    TextButton(
                        onClick = onJumpToday,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(R.string.goal_tracker_today), fontSize = 12.sp)
                    }
                }
                if (due.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$done/${due.size}",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (habits.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.goal_empty_title),
                    message = stringResource(R.string.goal_tracker_empty_message),
                    actionLabel = stringResource(R.string.goal_create_first),
                    onActionClick = onCreate
                )
            }
        } else {
            items(due, key = { "due-${it.goal.id}" }) { habit ->
                HabitCard(
                    habit = habit,
                    date = date,
                    onOpenDetail = { onOpenDetail(habit.goal.id) },
                    onCheckIn = { onCheckIn(habit.goal.id) },
                    onUndo = { onUndo(habit.goal.id) },
                    onLogTime = { onLogTime(habit.goal.id) }
                )
            }

            if (due.isEmpty()) {
                item {
                    AevumCard(variant = CardVariant.Outlined) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌿", fontSize = 26.sp)
                            Spacer(Modifier.width(AevumSpacing.sm))
                            Column {
                                Text(
                                    stringResource(R.string.goal_tracker_free_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.goal_tracker_free_text),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (free.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.goal_tracker_not_due),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AevumSpacing.sm)
                    )
                }
                items(free, key = { "free-${it.goal.id}" }) { habit ->
                    HabitCard(
                        habit = habit,
                        date = date,
                        onOpenDetail = { onOpenDetail(habit.goal.id) },
                        onCheckIn = { onCheckIn(habit.goal.id) },
                        onUndo = { onUndo(habit.goal.id) },
                        onLogTime = { onLogTime(habit.goal.id) },
                        freeDay = true
                    )
                }
            }
        }

        if (legacyGoals.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.goal_tracker_legacy),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = AevumSpacing.md)
                )
                Text(
                    stringResource(R.string.goal_tracker_legacy_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(legacyGoals, key = { "legacy-${it.goal.id}" }) { g ->
                LegacyGoalCard(g = g, onOpenDetail = { onOpenDetail(g.goal.id) })
            }
        }

        val archivedCount = archivedHabitGoals.size + archivedLegacyGoals.size
        if (archivedCount > 0) {
            item {
                TextButton(
                    onClick = { onToggleArchived(!showArchived) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (showArchived) stringResource(R.string.goal_hide_archived)
                        else stringResource(R.string.goal_show_archived)
                    )
                }
            }
            if (showArchived) {
                items(archivedHabitGoals, key = { "arch-habit-${it.goal.id}" }) { habit ->
                    HabitCard(
                        habit = habit,
                        date = date,
                        onOpenDetail = { onOpenDetail(habit.goal.id) },
                        onCheckIn = {},
                        onUndo = {},
                        onLogTime = {},
                        archived = true
                    )
                }
                items(archivedLegacyGoals, key = { "arch-legacy-${it.goal.id}" }) { g ->
                    LegacyGoalCard(g = g, onOpenDetail = { onOpenDetail(g.goal.id) }, archived = true)
                }
            }
        }
    }
}

@Composable
private fun HabitCard(
    habit: HabitGoalUi,
    date: LocalDate,
    onOpenDetail: () -> Unit,
    onCheckIn: () -> Unit,
    onUndo: () -> Unit,
    onLogTime: () -> Unit,
    freeDay: Boolean = false,
    archived: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    val accent = GoalAccents.resolve(
        key = habit.goal.accent,
        activityColor = habit.colorLong,
        fallback = MaterialTheme.colorScheme.secondary
    )
    val done = habit.isDoneOn(date)
    val progress by animateFloatAsState(
        targetValue = habit.progressOn(date),
        animationSpec = tween(500),
        label = "habit-progress"
    )

    AevumCard(
        variant = if (archived) CardVariant.Outlined else CardVariant.Elevated,
        onClick = onOpenDetail,
        contentPadding = PaddingValues(AevumSpacing.md),
        modifier = Modifier.shimmering(
            enabled = done && !archived && !freeDay,
            intensity = 0.14f,
            periodMillis = 5200
        )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = if (done) 0.24f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(habit.icon, fontSize = 23.sp)
                }
                Spacer(Modifier.width(AevumSpacing.sm))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            habit.goal.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (habit.streak > 0) {
                            Spacer(Modifier.width(6.dp))
                            GoalStreakBadge(habit.streak)
                        }
                    }
                    Text(
                        text = kindLabel(habit.kind) + " · " + habit.valueOn(date) +
                            if (freeDay) " · " + stringResource(R.string.goal_badge_free_day) else "",
                        fontSize = 12.sp,
                        color = if (done) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(AevumSpacing.sm))
                when {
                    archived -> Unit
                    habit.kind == Goal.KIND_TIME -> TimeActionButton(
                        done = done,
                        accent = accent,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLogTime()
                        }
                    )
                    habit.kind == Goal.KIND_COUNT -> CountActionButton(
                        accent = accent,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCheckIn()
                        }
                    )
                    else -> CheckActionButton(
                        done = done,
                        accent = accent,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (done) onUndo() else onCheckIn()
                        }
                    )
                }
            }

            LinearProgressIndicator(
                progress = { if (freeDay) 0f else progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(AevumRadius.full)),
                color = accent,
                trackColor = accent.copy(alpha = 0.13f),
                gapSize = 0.dp,
                drawStopIndicator = {}
            )

            if (habit.kind == Goal.KIND_COUNT && habit.target in 2..12 && !archived) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(habit.target) { index ->
                        val filled = index < habit.countOn(date)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(AevumRadius.full))
                                .background(
                                    if (filled) accent
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                )
                        )
                    }
                }
            }

            if (done && habit.kind == Goal.KIND_COUNT && !archived) {
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUndo()
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("↩ " + stringResource(R.string.goal_undo_checkin), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    Goal.KIND_COUNT -> stringResource(R.string.goal_kind_count)
    Goal.KIND_TIME -> stringResource(R.string.goal_kind_time)
    else -> stringResource(R.string.goal_kind_done)
}

@Composable
private fun CheckActionButton(done: Boolean, accent: Color, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (done) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "habit-check-scale"
    )
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (done) accent else accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.goal_undo_checkin), tint = Color.White, modifier = Modifier.size(24.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.75f))
            )
        }
    }
}

@Composable
private fun CountActionButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.goal_check_now), tint = accent, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TimeActionButton(done: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (done) accent else accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.goal_time_edit_title), tint = Color.White, modifier = Modifier.size(24.dp))
        } else {
            Text("⏱", fontSize = 22.sp)
        }
    }
}

@Composable
private fun LegacyGoalCard(g: GoalWithProgress, onOpenDetail: () -> Unit, archived: Boolean = false) {
    AevumCard(
        variant = CardVariant.Outlined,
        onClick = onOpenDetail,
        contentPadding = PaddingValues(AevumSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Text("📦", fontSize = 18.sp)
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    g.goal.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = g.progressText + " · " + stringResource(R.string.goal_tracker_legacy),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun dayLong(date: LocalDate): String {
    val formatter = remember {
        DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.getDefault())
    }
    return date.format(formatter)
}

private const val PAGE_DAYS = 30
