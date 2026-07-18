package de.devondroste.aevum.ui.screens.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.ChartContainer
import de.devondroste.aevum.ui.components.ChartLegendItem
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.ProgressRing
import de.devondroste.aevum.ui.components.SectionHeader
import de.devondroste.aevum.ui.components.StatisticCard
import de.devondroste.aevum.ui.components.TimelineItem
import de.devondroste.aevum.ui.components.categoryColor
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTimeline: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    DashboardContent(modifier = modifier, state = state, onOpenTimeline = onOpenTimeline)
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    onOpenTimeline: () -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { DashboardHero(state, onOpenTimeline) }
            item { PremiumSignalStrip(state) }
            if (state.hasData) {
                item { TimeDistributionCard(state) }
                item { TodayFlowCard(state, onOpenTimeline) }
            } else {
                item {
                    EmptyState(
                        title = "Dein Tag wartet",
                        message = "M5 ist benutzbar: Lege deine erste manuelle Aktivität an und das Dashboard füllt sich sofort mit echten Room-Daten.",
                        actionLabel = "Timeline öffnen",
                        onActionClick = onOpenTimeline
                    )
                }
            }
            item { GrowthFocusCard(state) }
            item { DigitalBalanceCard(state) }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun DashboardHero(state: DashboardUiState, onOpenTimeline: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Heute", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (state.hasData) "Dein Tag nimmt Form an." else "Starte mit deinem ersten Eintrag.",
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(AevumSpacing.sm))
                    Text("Aktuell: ${state.currentActivity} · ${state.currentDuration}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(AevumSpacing.md))
                    Button(onClick = onOpenTimeline) { Text(if (state.hasData) "Timeline öffnen" else "Tag erfassen") }
                }
                ProgressRing(
                    progress = state.focusScore / 100f,
                    size = 92.dp,
                    strokeWidth = 9.dp,
                    progressColor = MaterialTheme.colorScheme.secondary,
                    valueText = state.focusScore.toString()
                )
            }
            FocusWave(score = state.focusScore, modifier = Modifier.fillMaxWidth().height(42.dp))
        }
    }
}

@Composable
private fun PremiumSignalStrip(state: DashboardUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        StatisticCard(modifier = Modifier.weight(1f), label = "Erfasst", value = state.totalTracked, unit = "", icon = "◷", subtitle = "Heute")
        StatisticCard(modifier = Modifier.weight(1f), label = "Fokus", value = state.focusScore.toString(), unit = "%", icon = "◎")
        StatisticCard(modifier = Modifier.weight(1f), label = "Einträge", value = state.sessionCount.toString(), unit = "", icon = "◆", subtitle = "Room")
    }
}

@Composable
private fun TimeDistributionCard(state: DashboardUiState) {
    val items = state.distribution.map { slice ->
        ChartLegendItem(slice.label, categoryColor(slice.label), de.devondroste.aevum.domain.time.TimeFormatting.formatDuration(slice.durationMs))
    }
    ChartContainer(title = "Zeitverteilung", subtitle = "Echte Daten aus Room", legendItems = items) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
            TimeDonut(items = items, values = state.distribution.map { it.durationMs.toFloat().coerceAtLeast(1f) }, modifier = Modifier.size(176.dp))
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                val top = state.distribution.firstOrNull()
                Text("Top Investment", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(top?.label ?: "—", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(top?.let { de.devondroste.aevum.domain.time.TimeFormatting.formatDuration(it.durationMs) } ?: "0m", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TodayFlowCard(state: DashboardUiState, onOpenTimeline: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        SectionHeader("Tagesfluss", "Timeline", onActionClick = onOpenTimeline)
        state.timeline.forEach { row ->
            TimelineItem(
                time = row.time,
                title = row.title,
                category = row.categoryName,
                duration = row.duration,
                source = row.source,
                isCurrent = row.isCurrent
            )
        }
    }
}

@Composable
private fun GrowthFocusCard(state: DashboardUiState) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Text("Wachstum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (state.hasData) "Ziele und Habits werden in M8 aus diesen Sessions berechnet." else "Erst Daten erfassen, dann entstehen Ziele, Habits und Streaks.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MiniHeatmap(active = state.hasData)
        }
    }
}

@Composable
private fun DigitalBalanceCard(state: DashboardUiState) {
    val digital = state.distribution.firstOrNull { it.categoryId == "digital" }?.durationMs ?: 0L
    AevumCard(variant = CardVariant.Filled) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Digital Balance", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Aktuell manuell erfassbar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(de.devondroste.aevum.domain.time.TimeFormatting.formatDuration(digital), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
            SparkBars(values = if (state.hasData) listOf(.2f, .45f, .35f, .72f, .52f, .34f, .58f, .40f) else List(8) { .08f }, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun TimeDonut(items: List<ChartLegendItem>, values: List<Float>, modifier: Modifier = Modifier) {
    val total = values.sum().coerceAtLeast(1f)
    val fallbackColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val stroke = 20.dp.toPx()
        var start = -90f
        values.forEachIndexed { index, value ->
            val sweep = value / total * 360f
            drawArc(
                color = items.getOrNull(index)?.color ?: fallbackColor,
                startAngle = start,
                sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            start += sweep
        }
    }
}

@Composable
private fun FocusWave(score: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Color(0xFF2DD4BF)
        val maxHeight = size.height
        val widthStep = size.width / 24f
        repeat(24) { i ->
            val normalized = ((i * 37 + score) % 100) / 100f
            val barHeight = maxHeight * (0.25f + normalized * 0.65f)
            drawRoundRect(
                color = color.copy(alpha = 0.18f + normalized * 0.38f),
                topLeft = Offset(i * widthStep, maxHeight - barHeight),
                size = Size(widthStep * .62f, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )
        }
    }
}

@Composable
private fun MiniHeatmap(active: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(21) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(
                        if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f + (index % 4) * .10f) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AevumRadius.xs)
                    )
            )
        }
    }
}

@Composable
private fun SparkBars(values: List<Float>, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEach { value ->
            Box(modifier = Modifier.weight(1f).height((8 + value * 48).dp).background(color.copy(alpha = 0.20f + value * 0.45f), RoundedCornerShape(AevumRadius.sm)))
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun DashboardScreenPreview() {
    AevumTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                currentActivity = "Deep Work",
                currentDuration = "1h 20m",
                focusScore = 82,
                totalTracked = "5h 10m",
                sessionCount = 3,
                distribution = listOf(DashboardCategorySlice("work", "Arbeit", 12_000_000), DashboardCategorySlice("sport", "Sport", 3_000_000)),
                timeline = listOf(DashboardTimelineRow("1", "08:00", "Deep Work", "Arbeit", "2h", "MANUAL", false)),
                hasData = true
            ),
            onOpenTimeline = {}
        )
    }
}
