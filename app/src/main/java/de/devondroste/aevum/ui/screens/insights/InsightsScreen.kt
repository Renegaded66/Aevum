package de.devondroste.aevum.ui.screens.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.ProgressRing
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.screens.habits.HabitWithProgress
import de.devondroste.aevum.ui.theme.AevumCategoryColors
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme
import kotlin.math.abs

@Composable
fun InsightsScreen(
    modifier: Modifier = Modifier,
    onOpenTimelineDay: (Long) -> Unit = {},
    onOpenWeeklyReview: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    InsightsContent(
        modifier = modifier,
        state = state,
        onSelectPeriod = { viewModel.selectPeriod(it) },
        onOpenTimeline = onOpenTimelineDay,
        onOpenWeeklyReview = onOpenWeeklyReview,
        onOpenGoals = onOpenGoals,
        onOpenHabits = onOpenHabits,
        onHeatmapDay = { day ->
            viewModel.selectHeatmapDay(day.date)
            onOpenTimelineDay(day.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
    )
}

@Composable
private fun InsightsContent(
    modifier: Modifier = Modifier,
    state: InsightsUiState,
    onSelectPeriod: (InsightPeriod) -> Unit,
    onOpenTimeline: (Long) -> Unit,
    onOpenWeeklyReview: () -> Unit,
    onOpenGoals: () -> Unit = {},
    onOpenHabits: () -> Unit = {},
    onHeatmapDay: (HeatmapDay) -> Unit
) {
    val hasGoalOrHabitProgress = state.goalProgress.isNotEmpty() || state.habitProgress.isNotEmpty()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
        ) {
            item { InsightsHero(state, onSelectPeriod) }
            item { WeeklyReviewEntry(onOpenWeeklyReview) }
            if (!state.hasData && !hasGoalOrHabitProgress) {
                item { InsightsEmptyState(onOpenTimeline = { onOpenTimeline(state.startDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) }) }
            } else {
                if (state.hasData) {
                    item { TimeDistributionSection(state.timeDistribution) }
                    item {
                        AnimatedVisibility(visible = state.changes.isNotEmpty()) {
                            ChangesSection(state.changes)
                        }
                    }
                    item { TopActivitiesSection(state.topActivities) }
                    item { BalanceSection(state.balance) }
                    item { InsightCardsSection(state.insightCards) }
                    item { WeekHeatmapSection(state.weekHeatmap, onHeatmapDay) }
                }
                if (hasGoalOrHabitProgress) {
                    item { Spacer(Modifier.height(AevumSpacing.md)) }
                    item {
                        FortschrittSection(
                            goalProgress = state.goalProgress,
                            habitProgress = state.habitProgress,
                            onOpenGoals = onOpenGoals,
                            onOpenHabits = onOpenHabits
                        )
                    }
                } else {
                    item {
                        FortschrittEmptyState(onOpenGoals = onOpenGoals)
                    }
                }
            }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
private fun FortschrittSection(
    goalProgress: List<GoalWithProgress>,
    habitProgress: List<HabitWithProgress>,
    onOpenGoals: () -> Unit,
    onOpenHabits: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
        Text("Fortschritt", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Deine Ziele und Gewohnheiten — ruhig sichtbar gemacht, ohne Wertung.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)

        // Goals sub-section
        if (goalProgress.isNotEmpty()) {
            AevumCard(variant = CardVariant.Elevated) {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Aktive Ziele", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = onOpenGoals, shape = RoundedCornerShape(AevumRadius.full)) { Text("Alle", fontSize = 12.sp) }
                    }
                    goalProgress.take(4).forEach { goalProgress ->
                        val goal = goalProgress.goal
                        val progress = goalProgress.progress.coerceIn(0f, 2f)
                        val isAtMost = goal.type == "AT_MOST"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                        ) {
                            ProgressRing(
                                progress = if (isAtMost) (1f - progress).coerceIn(0f, 1f) else progress.coerceIn(0f, 1f),
                                size = 40.dp,
                                strokeWidth = 5.dp,
                                progressColor = if (goalProgress.isMet) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                valueText = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(goal.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(goalProgress.progressText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                Text(goalProgress.periodLabel + if (goalProgress.isMet) " · erreicht" else "",
                                    fontSize = 11.sp, color = if (goalProgress.isMet) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }

        // Habits sub-section
        if (habitProgress.isNotEmpty()) {
            AevumCard(variant = CardVariant.Filled) {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Gewohnheiten", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = onOpenHabits, shape = RoundedCornerShape(AevumRadius.full)) { Text("Alle", fontSize = 12.sp) }
                    }
                    habitProgress.take(4).forEach { habitProgress ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(habitProgress.habit.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                                    Text("Streak ${habitProgress.streak}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (habitProgress.streak > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    Text("${habitProgress.successRate}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            // Mini heatmap (last 7 of 28 days)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                habitProgress.heatmap.takeLast(7).forEach { day ->
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(
                                                if (day.completed) MaterialTheme.colorScheme.primary.copy(alpha = day.intensity)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                RoundedCornerShape(AevumRadius.sm)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FortschrittEmptyState(onOpenGoals: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Fortschritt", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Du kannst Ziele anlegen, um deinen Fortschritt sichtbar zu machen. Zum Beispiel: 8 Stunden Schlaf pro Nacht, 3 Stunden Sport pro Woche, maximal 2 Stunden Digitalzeit pro Tag.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            OutlinedButton(onClick = onOpenGoals, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(AevumRadius.full)) {
                Text("Ziele anlegen", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun InsightsHero(state: InsightsUiState, onSelectPeriod: (InsightPeriod) -> Unit) {
    AevumCard(variant = CardVariant.Gradient, contentPadding = PaddingValues(AevumSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text("LIFE ANALYTICS", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Wie deine Zeit sichtbar wird", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                Text(state.summary, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                InsightPeriod.entries.forEach { period ->
                    PeriodPill(period = period, selected = state.selectedPeriod == period, onClick = { onSelectPeriod(period) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WeeklyReviewEntry(onOpenWeeklyReview: () -> Unit) {
    AevumCard(variant = CardVariant.Filled, onClick = onOpenWeeklyReview, contentPadding = PaddingValues(AevumSpacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text("Weekly Review", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Ein ruhiger Rückblick auf deine aktuelle Woche", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Öffnen", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PeriodPill(period: InsightPeriod, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(AevumRadius.full),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) { Text(period.label, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun TimeDistributionSection(distribution: List<TimeDistributionSlice>) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            SectionTitle("Zeitverteilung", "Welche Lebensbereiche deine erfasste Zeit prägen")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.lg), modifier = Modifier.fillMaxWidth()) {
                DonutChart(distribution = distribution, modifier = Modifier.size(154.dp))
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.weight(1f)) {
                    distribution.take(5).forEach { slice -> DistributionLegendRow(slice) }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(distribution: List<TimeDistributionSlice>, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(1f, animationSpec = tween(900), label = "insights-donut")
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val stroke = 20.dp.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        if (distribution.isEmpty()) {
            drawArc(emptyColor, -90f, 360f, false, topLeft, Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            val total = distribution.sumOf { it.durationMs }.toFloat().coerceAtLeast(1f)
            var start = -90f
            distribution.forEach { slice ->
                val sweep = (slice.durationMs / total * 360f) * progress
                drawArc(
                    color = slice.color,
                    startAngle = start,
                    sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                start += sweep
            }
        }
    }
}

@Composable
private fun DistributionLegendRow(slice: TimeDistributionSlice) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).background(slice.color, RoundedCornerShape(AevumRadius.full)))
        Column(modifier = Modifier.weight(1f)) {
            Text(slice.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(TimeFormatting.formatDuration(slice.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        Text("${slice.percent}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ChangesSection(changes: List<PeriodChange>) {
    AevumCard(variant = CardVariant.Filled) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Verändert", "Gegenüber der Vorperiode")
            changes.forEach { change ->
                val positive = change.deltaMs > 0
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Box(Modifier.size(10.dp).background(change.color, RoundedCornerShape(AevumRadius.full)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(change.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${TimeFormatting.formatDuration(change.currentMs)} statt ${TimeFormatting.formatDuration(change.previousMs)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = (if (positive) "+" else "−") + TimeFormatting.formatDuration(abs(change.deltaMs)),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun TopActivitiesSection(activities: List<TopActivitySlice>) {
    if (activities.isEmpty()) return
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Top-Aktivitäten", "Activity Types mit der meisten erfassten Zeit")
            activities.forEach { activity ->
                QuietBarRow(label = activity.label, durationMs = activity.durationMs, percent = activity.percent, color = activity.color)
            }
        }
    }
}

@Composable
private fun BalanceSection(balance: List<BalanceSlice>) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Balance", "Nur sichtbar gemacht — ohne Bewertung")
            balance.forEach { slice ->
                QuietBarRow(label = slice.area, durationMs = slice.durationMs, percent = slice.percent, color = slice.color)
            }
        }
    }
}

@Composable
private fun QuietBarRow(label: String, durationMs: Long, percent: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${TimeFormatting.formatDuration(durationMs)} · $percent%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f), RoundedCornerShape(AevumRadius.full))) {
            Box(Modifier.fillMaxWidth((percent / 100f).coerceIn(0.02f, 1f)).fillMaxHeight().background(color.copy(alpha = 0.82f), RoundedCornerShape(AevumRadius.full)))
        }
    }
}

@Composable
private fun InsightCardsSection(cards: List<InsightCard>) {
    if (cards.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Text("Hinweise", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        cards.forEach { card ->
            AevumCard(variant = CardVariant.Filled, contentPadding = PaddingValues(AevumSpacing.md)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text(card.icon, fontSize = 21.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(card.message, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekHeatmapSection(heatmap: WeekHeatmap, onHeatmapDay: (HeatmapDay) -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Wochen-Heatmap", "Tippe auf einen Tag, um ihn in der Timeline zu öffnen")
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                heatmap.days.forEach { day ->
                    HeatmapDayCell(day = day, maxDurationMs = heatmap.maxDurationMs, modifier = Modifier.weight(1f), onClick = { onHeatmapDay(day) })
                }
            }
        }
    }
}

@Composable
private fun HeatmapDayCell(day: HeatmapDay, maxDurationMs: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val base = MaterialTheme.colorScheme.primary
    val alpha = if (maxDurationMs > 0) (0.12f + day.intensity * 0.62f) else 0.08f
    Column(
        modifier = modifier
            .height(112.dp)
            .background(base.copy(alpha = alpha), RoundedCornerShape(AevumRadius.lg))
            .clickable(onClick = onClick)
            .padding(AevumSpacing.sm),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(day.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(TimeFormatting.formatDuration(day.durationMs), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
    }
}

@Composable
private fun InsightsEmptyState(onOpenTimeline: () -> Unit) {
    EmptyState(
        title = "Noch entstehen deine Muster.",
        message = "Sobald du ein paar Aktivitäten erfasst hast, zeigt Aevum hier Zeitverteilung, Veränderungen, Top-Aktivitäten, Balance und eine ruhige Wochen-Heatmap.",
        actionLabel = "Zur Timeline",
        onActionClick = onOpenTimeline
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun InsightsPreview() {
    AevumTheme(darkTheme = true) {
        InsightsContent(
            state = InsightsUiState(
                selectedPeriod = InsightPeriod.Week,
                periodLabel = "Diese Woche",
                summary = "Diese Woche prägt vor allem arbeit deine erfasste Zeit.",
                hasData = true,
                timeDistribution = listOf(
                    TimeDistributionSlice("work", "Arbeit", AevumCategoryColors.work, 7 * 60 * 60_000L, 58),
                    TimeDistributionSlice("sport", "Sport", AevumCategoryColors.sport, 2 * 60 * 60_000L, 17),
                    TimeDistributionSlice("digital", "Digital", AevumCategoryColors.smartphone, 90 * 60_000L, 12)
                ),
                changes = listOf(PeriodChange("sport", "Sport", AevumCategoryColors.sport, 2 * 60 * 60_000L, 60 * 60_000L, 60 * 60_000L, 100)),
                topActivities = listOf(TopActivitySlice("deep_work", "Deep Work", AevumCategoryColors.work, 5 * 60 * 60_000L, 42)),
                balance = listOf(
                    BalanceSlice("Arbeit", AevumCategoryColors.work, 7 * 60 * 60_000L, 58),
                    BalanceSlice("Bewegung", AevumCategoryColors.sport, 2 * 60 * 60_000L, 17),
                    BalanceSlice("Digital", AevumCategoryColors.smartphone, 90 * 60_000L, 12)
                ),
                insightCards = listOf(InsightCard("Größter Zeitblock", "Arbeit war diese Woche dein größter Bereich.", "◷")),
                weekHeatmap = WeekHeatmap(days = java.time.DayOfWeek.entries.mapIndexed { index, _ -> HeatmapDay(java.time.LocalDate.now().plusDays(index.toLong()), "T$index", index * 60 * 60_000L, index / 6f) }, maxDurationMs = 6 * 60 * 60_000L)
            ),
            onSelectPeriod = {},
            onOpenTimeline = {},
            onOpenWeeklyReview = {},
            onHeatmapDay = {}
        )
    }
}
