package com.d_drostes_apps.aevum.ui.screens.weekly

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.screens.insights.InsightCard
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodChange
import com.d_drostes_apps.aevum.ui.screens.insights.TimeDistributionSlice
import com.d_drostes_apps.aevum.ui.theme.AevumCategoryColors
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme
import kotlin.math.abs

@Composable
fun WeeklyReviewScreen(
    modifier: Modifier = Modifier,
    onBackToInsights: () -> Unit = {},
    onOpenTimelineDay: (Long) -> Unit = {},
    onOpenTimeline: () -> Unit = {},
    onOpenReviewInbox: () -> Unit = {},
    viewModel: WeeklyReviewViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    WeeklyReviewContent(
        modifier = modifier,
        state = state,
        onBackToInsights = onBackToInsights,
        onOpenTimelineDay = onOpenTimelineDay,
        onOpenTimeline = onOpenTimeline,
        onOpenReviewInbox = onOpenReviewInbox
    )
}

@Composable
private fun WeeklyReviewContent(
    modifier: Modifier = Modifier,
    state: WeeklyReviewUiState,
    onBackToInsights: () -> Unit,
    onOpenTimelineDay: (Long) -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenReviewInbox: () -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
        ) {
            item { WeeklyHero(state = state, onBackToInsights = onBackToInsights) }
            if (!state.hasData) {
                item { WeeklyEmptyState(onOpenTimeline) }
            } else {
                item { WeeklyTimelineSection(state.days, onOpenTimelineDay) }
                item { WeeklyDistributionSection(state.timeDistribution) }
                if (state.changes.isNotEmpty()) item { WeeklyChangesSection(state.changes) }
                if (state.highlights.isNotEmpty()) item { HighlightsSection(state.highlights) }
                if (state.patterns.isNotEmpty()) item { PatternSection(state.patterns) }
                state.goalProgressText?.let { text ->
                    item { GoalProgressWeekSection(text) }
                }
                item { OpenTimeSection(state.openTimeMs, onOpenTimeline) }
                if (state.pendingReviewCount > 0) item { ReviewInboxSection(state.pendingReviewCount, onOpenReviewInbox) }
                item { ClosingSection(state.closingText) }
            }
            item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
private fun WeeklyHero(state: WeeklyReviewUiState, onBackToInsights: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient, contentPadding = PaddingValues(AevumSpacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("WEEKLY REVIEW", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onBackToInsights, shape = RoundedCornerShape(AevumRadius.full)) { Text("Insights", fontSize = 12.sp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text(state.heroTitle, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                Text(state.weekLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                Text(state.narrative, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeeklyTimelineSection(days: List<WeeklyDaySummary>, onOpenTimelineDay: (Long) -> Unit) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Wochen-Zeitstrahl", "Sieben Tage als ruhiger Überblick")
            Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    WeeklyDayCell(day, modifier = Modifier.weight(1f), onClick = {
                        onOpenTimelineDay(day.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                    })
                }
            }
        }
    }
}

@Composable
private fun WeeklyDayCell(day: WeeklyDaySummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .height(132.dp)
            .background(day.color.copy(alpha = 0.12f + day.intensity * 0.38f), RoundedCornerShape(AevumRadius.lg))
            .clickable(onClick = onClick)
            .padding(AevumSpacing.sm),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(day.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(10.dp).background(day.color, RoundedCornerShape(AevumRadius.full)))
        Text(day.topCategoryLabel, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(TimeFormatting.formatDuration(day.totalMs), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun WeeklyDistributionSection(distribution: List<TimeDistributionSlice>) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            SectionTitle("Zeitverteilung", "Welche Bereiche deine Woche geprägt haben")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.lg), modifier = Modifier.fillMaxWidth()) {
                WeeklyDonut(distribution = distribution, modifier = Modifier.size(156.dp))
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm), modifier = Modifier.weight(1f)) {
                    distribution.take(5).forEach { slice -> WeeklyLegendRow(slice) }
                }
            }
        }
    }
}

@Composable
private fun WeeklyDonut(distribution: List<TimeDistributionSlice>, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(1f, animationSpec = tween(900), label = "weekly-donut")
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
                drawArc(slice.color, start, (sweep - 3f).coerceAtLeast(1f), false, topLeft, Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
                start += sweep
            }
        }
    }
}

@Composable
private fun WeeklyLegendRow(slice: TimeDistributionSlice) {
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
private fun WeeklyChangesSection(changes: List<PeriodChange>) {
    AevumCard(variant = CardVariant.Filled) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            SectionTitle("Veränderungen", "Gegenüber der Vorwoche")
            changes.forEach { change ->
                val positive = change.deltaMs > 0
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Box(Modifier.size(10.dp).background(change.color, RoundedCornerShape(AevumRadius.full)))
                    Text(change.label, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
private fun HighlightsSection(highlights: List<WeeklyHighlight>) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Text("Highlights", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        highlights.forEach { item ->
            AevumCard(variant = CardVariant.Elevated, contentPadding = PaddingValues(AevumSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Box(Modifier.size(12.dp).background(item.tone, RoundedCornerShape(AevumRadius.full)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalProgressWeekSection(text: String) {
    AevumCard(variant = CardVariant.Filled) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("🎯", fontSize = 24.sp)
            Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PatternSection(patterns: List<InsightCard>) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Text("Wochenmuster", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        patterns.forEach { card ->
            AevumCard(variant = CardVariant.Filled, contentPadding = PaddingValues(AevumSpacing.md)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text(card.icon, fontSize = 20.sp)
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
private fun OpenTimeSection(openTimeMs: Long, onOpenTimeline: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text("Offene Zeit", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${TimeFormatting.formatDuration(openTimeMs)} dieser Woche sind noch nicht erfasst.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpenTimeline, shape = RoundedCornerShape(AevumRadius.full)) { Text("Zur Timeline") }
        }
    }
}

@Composable
private fun ReviewInboxSection(count: Int, onOpenReviewInbox: () -> Unit) {
    AevumCard(variant = CardVariant.Filled) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                Text("Review Inbox", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("$count Vorschläge warten noch auf deine Bestätigung.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onOpenReviewInbox, shape = RoundedCornerShape(AevumRadius.full), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("Jetzt prüfen") }
        }
    }
}

@Composable
private fun ClosingSection(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = AevumSpacing.lg),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
}

@Composable
private fun WeeklyEmptyState(onOpenTimeline: () -> Unit) {
    EmptyState(
        title = "Noch keine Woche sichtbar.",
        message = "Erfasse ein paar Aktivitäten. Danach erzählt dir Aevum hier ruhig, welche Muster, Highlights und offenen Zeiten in deiner Woche sichtbar werden.",
        actionLabel = "Zur Timeline",
        onActionClick = onOpenTimeline
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun WeeklyReviewPreview() {
    AevumTheme(darkTheme = true) {
        WeeklyReviewContent(
            state = WeeklyReviewUiState(
                hasData = true,
                narrative = "Du hast diese Woche viel Zeit in Arbeit investiert und auch Bewegung sichtbar gemacht.",
                weekLabel = "13.07.2026 – 19.07.2026",
                days = (0..6).map { index -> WeeklyDaySummary(java.time.LocalDate.now().plusDays(index.toLong()), "T$index", "Arbeit", (index + 1L) * 60 * 60_000L, AevumCategoryColors.work, (index + 1) / 7f) },
                timeDistribution = listOf(
                    TimeDistributionSlice("work", "Arbeit", AevumCategoryColors.work, 8 * 60 * 60_000L, 62),
                    TimeDistributionSlice("sport", "Bewegung", AevumCategoryColors.sport, 2 * 60 * 60_000L, 15)
                ),
                pendingReviewCount = 3,
                openTimeMs = 12 * 60 * 60_000L,
                highlights = listOf(WeeklyHighlight("Längste Aktivität", "Deep Work · 4h", AevumCategoryColors.work)),
                patterns = listOf(InsightCard("Abwechslung", "Diese Woche verteilt sich auf mehrere Lebensbereiche.", "☷"))
            ),
            onBackToInsights = {},
            onOpenTimelineDay = {},
            onOpenTimeline = {},
            onOpenReviewInbox = {}
        )
    }
}
