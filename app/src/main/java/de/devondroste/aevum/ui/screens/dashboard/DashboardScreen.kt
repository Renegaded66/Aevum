package de.devondroste.aevum.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.ChartContainer
import de.devondroste.aevum.ui.components.ChartLegendItem
import de.devondroste.aevum.ui.components.ProgressRing
import de.devondroste.aevum.ui.components.SectionHeader
import de.devondroste.aevum.ui.components.StatisticCard
import de.devondroste.aevum.ui.components.TimelineItem
import de.devondroste.aevum.ui.components.Trend
import de.devondroste.aevum.ui.theme.AevumCategoryColors
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme

private data class DashboardUiModel(
    val activity: String = "Deep Work",
    val activityDuration: String = "1h 18m",
    val focusScore: Int = 82,
    val totalTrackedHours: Float = 14.5f,
    val sleepHours: Float = 7.4f,
    val workHours: Float = 6.2f,
    val sportHours: Float = 1.1f,
    val learningHours: Float = 0.8f,
    val leisureHours: Float = 1.8f,
    val digitalHours: Float = 2.2f,
    val goalProgress: Float = 0.76f,
    val streak: Int = 12,
    val lifeProgress: Float = 0.28f,
    val bucketProgress: Float = 0.45f
)

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val model = DashboardUiModel()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item { DashboardHero(model) }
            item { PremiumSignalStrip(model) }
            item { TimeDistributionCard(model) }
            item { TodayFlowCard() }
            item { GrowthFocusCard(model) }
            item { LifePerspectiveCard(model) }
            item { DigitalBalanceCard(model.digitalHours) }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

@Composable
private fun DashboardHero(model: DashboardUiModel) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Heute", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Du investierst deine Zeit gut.", fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(AevumSpacing.sm))
                    Text("Aktuell: ${model.activity} · ${model.activityDuration}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProgressRing(
                    progress = model.focusScore / 100f,
                    size = 92.dp,
                    strokeWidth = 9.dp,
                    progressColor = MaterialTheme.colorScheme.secondary,
                    valueText = model.focusScore.toString()
                )
            }
            FocusWave(score = model.focusScore, modifier = Modifier.fillMaxWidth().height(42.dp))
        }
    }
}

@Composable
private fun PremiumSignalStrip(model: DashboardUiModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        StatisticCard(
            modifier = Modifier.weight(1f),
            label = "Erfasst",
            value = "%.1f".format(model.totalTrackedHours),
            unit = "h",
            icon = "◷",
            subtitle = "Heute"
        )
        StatisticCard(
            modifier = Modifier.weight(1f),
            label = "Ziel",
            value = "${(model.goalProgress * 100).toInt()}",
            unit = "%",
            icon = "◎",
            trend = Trend(12.5f, true, "zu gestern")
        )
        StatisticCard(
            modifier = Modifier.weight(1f),
            label = "Streak",
            value = model.streak.toString(),
            unit = "d",
            icon = "◆",
            subtitle = "Rekord 28"
        )
    }
}

@Composable
private fun TimeDistributionCard(model: DashboardUiModel) {
    val items = listOf(
        ChartLegendItem("Arbeit", AevumCategoryColors.work, "%.1fh".format(model.workHours)),
        ChartLegendItem("Schlaf", AevumCategoryColors.sleep, "%.1fh".format(model.sleepHours)),
        ChartLegendItem("Sport", AevumCategoryColors.sport, "%.1fh".format(model.sportHours)),
        ChartLegendItem("Lernen", AevumCategoryColors.learning, "%.1fh".format(model.learningHours)),
        ChartLegendItem("Digital", AevumCategoryColors.smartphone, "%.1fh".format(model.digitalHours)),
        ChartLegendItem("Freizeit", AevumCategoryColors.leisure, "%.1fh".format(model.leisureHours))
    )
    ChartContainer(
        title = "Zeitverteilung",
        subtitle = "Was deinen Tag heute geprägt hat",
        legendItems = items
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
            TimeDonut(items = items, modifier = Modifier.size(176.dp))
            Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                Text("Top Investment", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Arbeit", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("6.2h fokussiert", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TodayFlowCard() {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        SectionHeader("Tagesfluss", "Timeline", onActionClick = {})
        TimelineItem(time = "06:45", title = "Morgenroutine", category = "Schlaf", duration = "45m", source = "MANUAL")
        TimelineItem(time = "08:30", title = "Deep Work", category = "Arbeit", duration = "3h 10m", source = "GEOFENCE", confidence = 0.92f, isCurrent = true)
        TimelineItem(time = "12:20", title = "Bewegung", category = "Sport", duration = "42m", source = "ACTIVITY", confidence = 0.86f)
    }
}

@Composable
private fun GrowthFocusCard(model: DashboardUiModel) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Wachstum", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Ziele & Gewohnheiten", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${model.streak} Tage", fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                GoalPill("Lernen", model.goalProgress, AevumCategoryColors.learning, Modifier.weight(1f))
                GoalPill("Sport", 1f, AevumCategoryColors.sport, Modifier.weight(1f))
                GoalPill("Digital", .63f, AevumCategoryColors.smartphone, Modifier.weight(1f))
            }
            MiniHeatmap()
        }
    }
}

@Composable
private fun LifePerspectiveCard(model: DashboardUiModel) {
    AevumCard(variant = CardVariant.Outlined) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lebensperspektive", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Ruhiger Kontext statt täglichem Druck", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("72% vor dir", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                ProgressRing(progress = model.lifeProgress, size = 82.dp, strokeWidth = 8.dp, progressColor = MaterialTheme.colorScheme.primary, valueText = "28%")
                ProgressRing(progress = model.bucketProgress, size = 82.dp, strokeWidth = 8.dp, progressColor = MaterialTheme.colorScheme.tertiary, valueText = "45%")
            }
        }
    }
}

@Composable
private fun DigitalBalanceCard(hours: Float) {
    AevumCard(variant = CardVariant.Filled) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Digital Balance", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Ziel: unter 4h", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("%.1fh".format(hours), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = AevumCategoryColors.smartphone, fontFamily = FontFamily.Monospace)
            }
            SparkBars(values = listOf(.2f, .45f, .35f, .72f, .52f, .34f, .58f, .92f, .40f, .62f, .48f, .30f), color = AevumCategoryColors.smartphone)
        }
    }
}

@Composable
private fun TimeDonut(items: List<ChartLegendItem>, modifier: Modifier = Modifier) {
    val values = listOf(6.2f, 7.4f, 1.1f, .8f, 2.2f, 1.8f)
    val total = values.sum()
    Canvas(modifier = modifier) {
        val stroke = 20.dp.toPx()
        var start = -90f
        values.forEachIndexed { index, value ->
            val sweep = value / total * 360f
            drawArc(
                color = items[index].color,
                startAngle = start,
                sweepAngle = sweep - 3f,
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
private fun GoalPill(label: String, progress: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
        ProgressRing(progress = progress, size = 56.dp, strokeWidth = 6.dp, progressColor = color, valueText = "${(progress * 100).toInt()}%")
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MiniHeatmap() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(21) { index ->
            val active = index !in listOf(4, 12, 17)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(
                        if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f + (index % 4) * .12f) else MaterialTheme.colorScheme.surfaceVariant,
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((8 + value * 48).dp)
                    .background(color.copy(alpha = 0.20f + value * 0.45f), RoundedCornerShape(AevumRadius.sm))
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun DashboardScreenPreview() {
    AevumTheme(darkTheme = true) {
        DashboardScreen()
    }
}
