package de.devondroste.aevum.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.ProgressRing
import de.devondroste.aevum.ui.components.categoryColor
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTimeline: () -> Unit = {},
    onOpenReview: () -> Unit = onOpenTimeline,
    onOpenGoals: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    DashboardContent(modifier = modifier, state = state, onOpenTimeline = onOpenTimeline, onOpenReview = onOpenReview, onOpenGoals = onOpenGoals)
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    onOpenTimeline: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenGoals: () -> Unit = {}
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { DailyReviewHero(state = state, onOpenTimeline = onOpenTimeline, onOpenReview = onOpenReview) }
            item { TodayFlowPanel(state = state, onOpenTimeline = onOpenTimeline) }
            item { KeyMetricsRow(state = state) }
            item {
                AnimatedVisibility(visible = state.reviewCount > 0) {
                    ReviewQuietCard(reviewCount = state.reviewCount, onOpenReview = onOpenReview)
                }
            }
            if (state.hasData) {
                item { InsightStrip(state = state) }
                item { CategoryBreathingRoom(state = state) }
                if (state.goalProgress.isNotEmpty()) {
                    item { GoalsProgressSection(goals = state.goalProgress, onOpenGoals = onOpenGoals) }
                }
                item { RecentMoments(state = state, onOpenTimeline = onOpenTimeline) }
            } else {
                item { BetterEmptyState(onOpenTimeline = onOpenTimeline) }
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun DailyReviewHero(state: DashboardUiState, onOpenTimeline: () -> Unit, onOpenReview: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient, contentPadding = PaddingValues(0.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
                .padding(AevumSpacing.lg)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DAILY REVIEW", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AevumSpacing.xs))
                        Text(
                            text = state.headline,
                            fontSize = 32.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(AevumSpacing.md))
                    ProgressRing(
                        progress = state.dayProgress,
                        size = 82.dp,
                        strokeWidth = 8.dp,
                        progressColor = MaterialTheme.colorScheme.secondary,
                        valueText = "${(state.dayProgress * 100).toInt()}%"
                    )
                }
                Text(
                    text = state.narrative,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Button(onClick = onOpenTimeline) { Text(if (state.hasData) "Tagesfluss öffnen" else "Ersten Eintrag erfassen") }
                    if (state.reviewCount > 0) OutlinedButton(onClick = onOpenReview) { Text("Vorschläge prüfen") }
                }
                DayPulse(values = state.flowSegments.map { (it.endMinute - it.startMinute).coerceAtLeast(12) / 180f }, modifier = Modifier.fillMaxWidth().height(38.dp))
            }
        }
    }
}

@Composable
private fun TodayFlowPanel(state: DashboardUiState, onOpenTimeline: () -> Unit) {
    AevumCard(variant = CardVariant.Elevated) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Tagesfluss", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("00:00–24:00 · als Lebensfluss", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onOpenTimeline) { Text("Öffnen") }
            }
            if (state.flowSegments.isEmpty()) {
                QuietFlowPlaceholder()
            } else {
                DayFlowCanvas(
                    segments = state.flowSegments,
                    gaps = state.flowGaps,
                    currentMinute = state.currentMinute,
                    onSegmentClick = { _ -> onOpenTimeline() },
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
                FlowLegend(state.flowSegments.take(3))
            }
        }
    }
}

@Composable
private fun DayFlowCanvas(
    segments: List<DashboardFlowSegment>,
    gaps: List<FlowGap> = emptyList(),
    currentMinute: Int = 0,
    onSegmentClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(targetValue = 1f, animationSpec = tween(800), label = "day-flow")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
    // Capture colors for use inside Canvas DrawScope
    val gapColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val hourMarkerColor = Color.White.copy(alpha = 0.14f)
    val nowLineColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f)
    val nowDotColor = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { pressPosition ->
                    val trackTop = size.height * 0.45f
                    val trackHeight = 28.dp.toPx()
                    val totalWidth = size.width
                    val clickY = pressPosition.y
                    val clickX = pressPosition.x
                    if (clickY >= trackTop && clickY <= trackTop + trackHeight) {
                        val clickedMinute = (clickX / totalWidth * 1440).toInt().coerceIn(0, 1440)
                        segments.firstOrNull { seg ->
                            clickedMinute in seg.startMinute..seg.endMinute
                        }?.let { onSegmentClick(it.id) }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackTop = size.height * 0.45f
            val trackHeight = 28.dp.toPx()
            // background track
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
            )
            // gaps
            gaps.forEach { gap ->
                val gapStart = (gap.startMinute / 1440f) * size.width
                val gapEnd = (gap.endMinute / 1440f) * size.width
                drawRoundRect(
                    color = gapColor,
                    topLeft = Offset(gapStart, trackTop),
                    size = Size(gapEnd - gapStart, trackHeight),
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                )
            }
            // segments
            segments.forEach { segment ->
                val start = (segment.startMinute / 1440f) * size.width
                val end = (segment.endMinute / 1440f) * size.width
                val width = ((end - start) * animatedProgress).coerceAtLeast(3.dp.toPx())
                val segColor = categoryColor(segment.categoryName)
                drawRoundRect(
                    color = segColor.copy(alpha = if (segment.isCurrent) 0.92f else 0.76f),
                    topLeft = Offset(start, trackTop),
                    size = Size(width, trackHeight),
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
                )
                // minimum width label for very short segments
                if (segment.endMinute - segment.startMinute < 30) {
                    // short segment indicator - subtle dot above
                    drawRoundRect(
                        color = segColor.copy(alpha = 0.6f),
                        topLeft = Offset(start + width / 2 - 4.dp.toPx(), trackTop - 12.dp.toPx()),
                        size = Size(8.dp.toPx(), 8.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }
            // hour markers
            listOf(6, 12, 18).forEach { hour ->
                val x = size.width * hour / 24f
                drawRoundRect(
                    color = hourMarkerColor,
                    topLeft = Offset(x - 0.5.dp.toPx(), trackTop - 10.dp.toPx()),
                    size = Size(1.dp.toPx(), trackHeight + 20.dp.toPx()),
                    cornerRadius = CornerRadius(0.5.dp.toPx(), 0.5.dp.toPx())
                )
            }
            // current time line
            if (currentMinute in 0..1440) {
                val nowX = size.width * currentMinute / 1440f
                drawRoundRect(
                    color = nowLineColor,
                    topLeft = Offset(nowX - 1.dp.toPx(), trackTop - 18.dp.toPx()),
                    size = Size(2.dp.toPx(), trackHeight + 36.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
                drawRoundRect(
                    color = nowDotColor,
                    topLeft = Offset(nowX - 4.dp.toPx(), trackTop - 22.dp.toPx()),
                    size = Size(8.dp.toPx(), 8.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun FlowLegend(segments: List<DashboardFlowSegment>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        segments.forEach { segment ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                Box(Modifier.size(9.dp).background(categoryColor(segment.categoryName), RoundedCornerShape(AevumRadius.full)))
                Column {
                    Text(segment.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(segment.duration, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun KeyMetricsRow(state: DashboardUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        LifestyleMetric(modifier = Modifier.weight(1f), label = "Erzählt", value = state.totalTracked, detail = "erfasst")
        LifestyleMetric(modifier = Modifier.weight(1f), label = "Offen", value = state.openTime, detail = "noch frei")
        LifestyleMetric(modifier = Modifier.weight(1f), label = "Balance", value = "${state.balanceScore}", detail = "sanft")
    }
}

@Composable
private fun LifestyleMetric(modifier: Modifier = Modifier, label: String, value: String, detail: String) {
    AevumCard(modifier = modifier, variant = CardVariant.Filled, contentPadding = PaddingValues(AevumSpacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 25.sp, lineHeight = 27.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewQuietCard(reviewCount: Int, onOpenReview: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sanft prüfen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (reviewCount == 1) "1 automatischer Vorschlag wartet. Er zählt erst nach deiner Bestätigung." else "$reviewCount automatische Vorschläge warten. Du entscheidest, was stimmt.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.width(AevumSpacing.md))
            Button(onClick = onOpenReview) { Text("Review") }
        }
    }
}

@Composable
private fun InsightStrip(state: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Text("Erste Hinweise", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        state.insights.forEach { insight ->
            AevumCard(variant = CardVariant.Filled, contentPadding = PaddingValues(AevumSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text(insight.icon, fontSize = 22.sp)
                    Column {
                        Text(insight.title, fontWeight = FontWeight.SemiBold)
                        Text(insight.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBreathingRoom(state: DashboardUiState) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Größter Lebensbereich", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Heute bisher", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(state.topCategoryDuration, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            Text(state.topCategory, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            MiniDonut(state.distribution.take(5), modifier = Modifier.size(150.dp).align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun MiniDonut(distribution: List<DashboardCategorySlice>, modifier: Modifier = Modifier) {
    val values = distribution.map { it.durationMs.toFloat().coerceAtLeast(1f) }
    val total = values.sum().coerceAtLeast(1f)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val stroke = 18.dp.toPx()
        if (distribution.isEmpty()) {
            drawArc(emptyColor, -90f, 360f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            var start = -90f
            distribution.forEachIndexed { index, slice ->
                val sweep = values[index] / total * 360f
                drawArc(
                    color = categoryColor(slice.label),
                    startAngle = start,
                    sweepAngle = (sweep - 4f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                start += sweep
            }
        }
    }
}

@Composable
private fun GoalsProgressSection(goals: List<GoalWithProgress>, onOpenGoals: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Ziele", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Dein Fortschritt", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onOpenGoals) { Text("Alle") }
            }
            goals.take(3).forEach { goalProgress ->
                val goal = goalProgress.goal
                val progress = goalProgress.progress.coerceIn(0f, 2f)
                val isAtMost = goal.type == "AT_MOST"
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    ProgressRing(
                        progress = if (isAtMost) (1f - progress).coerceIn(0f, 1f) else progress.coerceIn(0f, 1f),
                        size = 36.dp,
                        strokeWidth = 4.dp,
                        progressColor = if (goalProgress.isMet) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        valueText = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(goal.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(goalProgress.progressText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentMoments(state: DashboardUiState, onOpenTimeline: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Momente", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onOpenTimeline) { Text("Alle") }
            }
            state.timeline.forEach { row ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text(row.time, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${row.categoryName} · ${row.duration}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BetterEmptyState(onOpenTimeline: () -> Unit) {
    EmptyState(
        title = "Noch nichts muss perfekt sein.",
        message = "Beginne mit einem einzigen Zeitblock. Aevum verwandelt daraus Schritt für Schritt deinen Tagesrückblick.",
        actionLabel = "Ersten Abschnitt erfassen",
        onActionClick = onOpenTimeline
    )
}

@Composable
private fun QuietFlowPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(AevumRadius.xl))
        )
        Text("Sobald du Aktivitäten erfasst oder Vorschläge bestätigst, wird hier dein Tagesfluss sichtbar.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayPulse(values: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        val bars = 24
        val widthStep = size.width / bars
        repeat(bars) { index ->
            val value = values.getOrNull(index % values.size.coerceAtLeast(1)) ?: 0.12f
            val normalized = value.coerceIn(0.08f, 1f)
            val barHeight = size.height * (0.18f + normalized * 0.72f)
            drawRoundRect(
                color = color.copy(alpha = 0.14f + normalized * 0.34f),
                topLeft = Offset(index * widthStep, size.height - barHeight),
                size = Size(widthStep * .56f, barHeight),
                cornerRadius = CornerRadius(10f, 10f)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun DashboardScreenPreview() {
    AevumTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                headline = "Das war bisher dein Tag.",
                narrative = "Vor allem arbeit prägt deinen Tagesfluss. 2 Vorschläge sind noch offen. 6h 30m sind noch nicht erzählt.",
                currentActivity = "Deep Work",
                currentDuration = "1h 20m",
                balanceScore = 72,
                totalTracked = "5h 10m",
                openTime = "6h 30m",
                sessionCount = 3,
                reviewCount = 2,
                distribution = listOf(DashboardCategorySlice("work", "Arbeit", 12_000_000), DashboardCategorySlice("sport", "Sport", 3_000_000)),
                timeline = listOf(DashboardTimelineRow("1", "08:00", "Deep Work", "Arbeit", "2h", "Erfasst", false)),
                flowSegments = listOf(DashboardFlowSegment("1", "Deep Work", "Arbeit", "work", 8 * 60, 11 * 60, "08:00–11:00", "3h", false), DashboardFlowSegment("2", "Sport", "Sport", "sport", 18 * 60, 19 * 60, "18:00–19:00", "1h", false)),
                flowGaps = listOf(),
                currentMinute = 16 * 60,
                insights = listOf(DashboardInsight("Größter Block", "Arbeit macht 70% deiner erfassten Zeit aus.", "◷")),
                topCategory = "Arbeit",
                topCategoryDuration = "3h 20m",
                hasData = true,
                dayProgress = .64f
            ),
            onOpenTimeline = {},
            onOpenReview = {}
        )
    }
}