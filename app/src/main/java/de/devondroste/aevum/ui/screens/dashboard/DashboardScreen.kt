package de.devondroste.aevum.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import de.devondroste.aevum.data.model.AppUsageSample
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.components.AevumCard
import de.devondroste.aevum.ui.components.CardVariant
import de.devondroste.aevum.ui.components.EmptyState
import de.devondroste.aevum.ui.components.ProgressRing
import de.devondroste.aevum.ui.components.categoryColor
import de.devondroste.aevum.domain.liveactivity.LiveActivityState
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.theme.AevumRadius
import de.devondroste.aevum.ui.theme.AevumSpacing
import de.devondroste.aevum.ui.theme.AevumTheme

/**
 * P1 / M13: Komplett überarbeitetes Dashboard.
 *
 * Layout (less = more):
 *  1. Hero-Karte: 5 Kennzahlen auf einen Blick (Erfasst, Fokus, Schlaf, Bewegung, Bildschirmzeit)
 *  2. LiveActivity-Card: kompakt mit Start/Pause/Stop
 *  3. Insights-Strip: 2-3 ruhige Hinweise
 *
 * Keine redundanten Karten mehr. Jede Information hat genau einen Platz.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTimeline: () -> Unit = {},
    onOpenReview: () -> Unit = onOpenTimeline,
    onOpenGoals: () -> Unit = {},
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val liveState by viewModel.liveActivityManager.liveState.collectAsState()
    val nowMs by viewModel.liveActivityManager.nowMs.collectAsState()
    val recents by viewModel.liveActivityManager.recentActivityTypes.collectAsState()
    val favorites by viewModel.liveActivityManager.favoriteActivityTypes.collectAsState()
    val topApps by viewModel.topApps.collectAsState()
    val usageGranted by viewModel.usageStatsGranted.collectAsState()
    DashboardContent(
        modifier = modifier,
        state = state,
        liveState = liveState,
        nowMs = nowMs,
        recents = recents,
        favorites = favorites,
        topApps = topApps,
        usageStatsGranted = usageGranted,
        onOpenTimeline = onOpenTimeline,
        onOpenReview = onOpenReview,
        onOpenGoals = onOpenGoals,
        onOpenSleepStatus = onOpenSleepStatus,
        onOpenUsageSettings = onOpenUsageSettings,
        onStartLive = viewModel::startLiveActivity,
        onPauseLive = viewModel::pauseLiveActivity,
        onResumeLive = viewModel::resumeLiveActivity,
        onStopLive = viewModel::stopLiveActivity,
        onDiscardLive = viewModel::discardLiveActivity,
        onToggleFavorite = viewModel::toggleFavorite
    )
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    liveState: LiveActivityState,
    nowMs: Long,
    recents: List<de.devondroste.aevum.domain.liveactivity.RecentActivityType>,
    favorites: List<de.devondroste.aevum.data.model.ActivityType>,
    topApps: List<AppUsageSample>,
    usageStatsGranted: Boolean,
    onOpenTimeline: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenGoals: () -> Unit = {},
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    onStartLive: (String, String?, Long) -> Unit,
    onPauseLive: () -> Unit,
    onResumeLive: () -> Unit,
    onStopLive: () -> Unit,
    onDiscardLive: () -> Unit = {},
    onToggleFavorite: (de.devondroste.aevum.data.model.ActivityType) -> Unit
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // 1) HERO — Tageszusammenfassung auf einen Blick
            item { DailySummaryHero(state = state, topApps = topApps, usageGranted = usageStatsGranted, onOpenUsageSettings = onOpenUsageSettings) }
            // 2) Live Activity (kompakt) — eine prominente Karte
            item {
                LiveActivityCard(
                    state = liveState,
                    nowMs = nowMs,
                    activityTypes = state.activityTypes,
                    recents = recents,
                    favorites = favorites,
                    onStart = { typeId, note -> onStartLive(typeId, note, System.currentTimeMillis()) },
                    onStartWithTime = { typeId, note, time -> onStartLive(typeId, note, time) },
                    onPause = onPauseLive,
                    onResume = onResumeLive,
                    onStop = onStopLive,
                    onDiscard = onDiscardLive,
                    onToggleFavorite = onToggleFavorite
                )
            }
            // 3) Insights — nur 2-3 ruhige Hinweise
            if (state.insights.isNotEmpty()) {
                item { InsightStrip(state = state) }
            }
            // 4) Review / Sleep Quiet Hint — nur wenn was zu tun ist
            if (state.candidateCount > 0 || state.sleepCandidateCount > 0) {
                item { ReviewHintCard(reviewCount = state.candidateCount, sleepCount = state.sleepCandidateCount, onOpenReview = onOpenReview) }
            }
            item { Spacer(Modifier.height(AevumSpacing.xl)) }
        }
    }
}

/**
 * P1: Daily Summary Hero — die Hauptkarte des Dashboards.
 *
 * Zeigt in einer einzigen Karte:
 *  - Tageszusammenfassung (Headline)
 *  - 5 Kennzahlen (Erfasst, Fokus, Schlaf, Bewegung, Bildschirmzeit)
 *  - Tagesfluss-Miniatur (24h-Spur)
 *  - Sleep + Top-App Verweise
 *
 * Kein Text-Overload, eine Karte statt 5.
 */
@Composable
private fun DailySummaryHero(
    state: DashboardUiState,
    topApps: List<AppUsageSample>,
    usageGranted: Boolean,
    onOpenUsageSettings: () -> Unit
) {
    val heroBg = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.18f)
        )
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBg)
                .padding(AevumSpacing.lg)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                // Headline row
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
                    Text(
                        "DEIN TAG",
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.headline,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        state.narrative,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }

                // Day progress ring + percentage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val percent = (state.dayProgress * 100).toInt()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                        ProgressRing(
                            progress = state.dayProgress,
                            size = 56.dp,
                            strokeWidth = 6.dp,
                            progressColor = MaterialTheme.colorScheme.primary,
                            valueText = "${percent}%"
                        )
                        Column {
                            Text("Tag", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                            Text("Bisher ${percent}% gelebt", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Day-flow mini canvas
                if (state.flowSegments.isNotEmpty()) {
                    DayFlowMiniCanvas(
                        segments = state.flowSegments,
                        currentMinute = state.currentMinute
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(AevumRadius.md),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Sobald du erfasst, erscheint hier dein Tagesfluss.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 5 Key Metrics — one row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Erfasst",
                        value = state.totalTracked,
                        accent = MaterialTheme.colorScheme.primary
                    )
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Schlaf",
                        value = if (state.lastSleepDurationMs > 0) formatHours(state.lastSleepDurationMs) else "—",
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Bildschirm",
                        value = state.digitalScreenTimeFormatted,
                        accent = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Fokus",
                        value = focusMs(state),
                        accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                    )
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Bewegung",
                        value = movementMs(state),
                        accent = de.devondroste.aevum.ui.theme.AevumCategoryColors.sport
                    )
                    KeyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Balance",
                        value = "${state.balanceScore}",
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                }

                // Top apps preview (only if usage access granted)
                if (usageGranted && topApps.isNotEmpty()) {
                    TopAppsStrip(topApps = topApps)
                } else if (!usageGranted) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AevumRadius.md),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Bildschirmzeit aktivieren",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(onClick = onOpenUsageSettings) { Text("Erlauben", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AevumRadius.md),
        color = accent.copy(alpha = 0.10f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label.uppercase(),
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TopAppsStrip(topApps: List<AppUsageSample>) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
        Text(
            "Top-Apps",
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        topApps.forEach { app ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Text(
                    app.appLabel,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHours(app.durationMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Mini Day-Flow Canvas — kompakt, mit "jetzt"-Marker.
 */
@Composable
private fun DayFlowMiniCanvas(
    segments: List<DashboardFlowSegment>,
    currentMinute: Int
) {
    val trackHeight = 36.dp
    val animatedProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(600),
        label = "day-flow-mini"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val nowLineColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(trackColor)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val height = size.height
            segments.forEach { seg ->
                val start = (seg.startMinute / 1440f) * size.width
                val end = (seg.endMinute / 1440f) * size.width
                val width = ((end - start) * animatedProgress).coerceAtLeast(2f)
                val segColor = categoryColor(seg.categoryName)
                val alpha = when {
                    seg.isCandidate -> 0.30f
                    seg.isCurrent -> 0.90f
                    else -> 0.62f
                }
                drawRoundRect(
                    color = segColor.copy(alpha = alpha),
                    topLeft = Offset(start, 4f),
                    size = Size(width, height - 8f),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
            // Now line
            if (currentMinute in 0..1440) {
                val nowX = size.width * currentMinute / 1440f
                drawLine(
                    color = nowLineColor,
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, height),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun InsightStrip(state: DashboardUiState) {
    // Show only the most important 2 insights — no card overload
    val top = state.insights.take(2)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        top.forEach { insight ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(AevumRadius.lg),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ) {
                Row(
                    modifier = Modifier.padding(AevumSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(insight.icon, fontSize = 22.sp)
                    Column {
                        Text(insight.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            insight.message,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewHintCard(reviewCount: Int, sleepCount: Int, onOpenReview: () -> Unit) {
    AevumCard(variant = CardVariant.Outlined, onClick = onOpenReview) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (reviewCount > 0) "$reviewCount Vorschläge warten" else "${sleepCount} Schlaf-Vorschläge",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Tippe zum Prüfen. Aevum zählt sie erst, wenn du sie annimmst.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("→", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// Helper functions
private fun formatHours(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun focusMs(state: DashboardUiState): String {
    val workMs = state.distribution.firstOrNull { it.label.equals("arbeit", ignoreCase = true) || it.label.equals("work", ignoreCase = true) }?.durationMs ?: 0L
    val learningMs = state.distribution.firstOrNull { it.label.equals("lernen", ignoreCase = true) || it.label.equals("learning", ignoreCase = true) }?.durationMs ?: 0L
    return formatHours(workMs + learningMs)
}

private fun movementMs(state: DashboardUiState): String {
    val sportMs = state.distribution.firstOrNull { it.label.equals("sport", ignoreCase = true) }?.durationMs ?: 0L
    return formatHours(sportMs)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 1200)
@Composable
private fun DashboardScreenPreview() {
    AevumTheme(darkTheme = true) {
        DashboardContent(
            state = DashboardUiState(
                headline = "Du bist gut im Flow.",
                narrative = "Mehrere Bereiche sind aktiv — du hast schon einiges erfasst.",
                currentActivity = "Deep Work",
                currentDuration = "1h 20m",
                balanceScore = 72,
                totalTracked = "5h 10m",
                openTime = "6h 30m",
                sessionCount = 3,
                reviewCount = 2,
                distribution = listOf(
                    DashboardCategorySlice("work", "Arbeit", 12_000_000),
                    DashboardCategorySlice("sport", "Sport", 3_000_000)
                ),
                timeline = listOf(DashboardTimelineRow("1", "08:00", "Deep Work", "Arbeit", "2h", "Erfasst", false)),
                flowSegments = listOf(
                    DashboardFlowSegment("1", "Deep Work", "Arbeit", "work", 8 * 60, 11 * 60, "08:00–11:00", "3h", false),
                    DashboardFlowSegment("2", "Sport", "Sport", "sport", 18 * 60, 19 * 60, "18:00–19:00", "1h", false)
                ),
                flowGaps = listOf(),
                currentMinute = 16 * 60,
                insights = listOf(
                    DashboardInsight("Größter Block", "Arbeit macht 70% deiner erfassten Zeit aus.", "◷"),
                    DashboardInsight("Kurz prüfen", "2 Vorschläge warten auf deine Entscheidung.", "✓")
                ),
                topCategory = "Arbeit",
                topCategoryDuration = "3h 20m",
                hasData = true,
                dayProgress = .64f,
                lastSleepDurationMs = 7L * 3_600_000 + 45 * 60_000,
                digitalScreenTimeFormatted = "2h 15m"
            ),
            liveState = LiveActivityState.Idle,
            nowMs = 0,
            recents = emptyList(),
            favorites = emptyList(),
            topApps = emptyList(),
            usageStatsGranted = false,
            onOpenTimeline = {},
            onOpenReview = {},
            onStartLive = { _, _, _ -> },
            onPauseLive = {},
            onResumeLive = {},
            onStopLive = {},
            onToggleFavorite = {}
        )
    }
}
