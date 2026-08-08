package com.d_drostes_apps.aevum.ui.screens.dashboard

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.QualityRing
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.AevumTheme

/**
 * M18.7: KOMPLETT NEUES Dashboard — "Der Puls deines Tages".
 *
 * Design-Philosophie (nach User-Feedback "App hat Fokus verloren,
 * überladen"):
 *
 *   Nur Daten, die die eine Frage beantworten:
 *   "Wie gut habe ich meine Zeit heute genutzt?"
 *
 * Layout (Top → Bottom, nach Wichtigkeit):
 *  1. Live-Banner — wenn eine Session läuft, ist DAS die wichtigste Info.
 *     Gleitet von oben rein, mit Timer + Pause/Stop.
 *  2. Puls-Hero — QualityRing (Zeitqualität) + die 3 fundamentalen
 *     Zeit-Blöcke eines Tages (Erfasst / Schlaf / Bildschirm) + Tagesfluss
 *     + Tagesfortschritt. Eine Karte, vier Aussagen.
 *  3. Schnellstart — LiveActivityCard NUR wenn nichts läuft. Sonst
 *     übernimmt das Banner die Kontrolle (keine Dopplung).
 *  4. "Wo deine Zeit hingeht" — Top-4-Kategorien, Balkenbreite = Dauer,
 *     Balkenfarbe = Positivität (rot→grün).
 *  5. Insights — max 2, nur wenn relevant.
 *  6. Review-Hinweis — nur wenn Vorschläge warten.
 *
 * ENTFERNT (bewusst, gegen Überladung):
 *  - 5 KeyMetric-Karten (Erfasst/Fokus/Schlaf/Bewegung/Bildschirm/Top-Kat):
 *    Fokus/Bewegung/Top-Kat sind Interpretationen, keine Fakten. Die
 *    Top-Kategorie zeigt bereits der Balken-Block. Die 3 fundamentalen
 *    Blöcke leben jetzt im Hero.
 *  - TopAppsStrip: Top-Apps sind Detail-Data (gehören in die Statistik),
 *    nicht aufs Dashboard. Die App soll Zeit-QUALITÄT zeigen, nicht
 *    App-Nutzung.
 *  - "DEIN TAG"-Textblock mit narrative: Text reduziert auf das Minimum.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onOpenTimeline: () -> Unit = {},
    onOpenReview: () -> Unit = onOpenTimeline,
    onOpenGoals: () -> Unit = {},
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    // M18.37: Todos-Karte auf dem Dashboard
    onOpenTodos: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val liveState by viewModel.liveActivityManager.liveState.collectAsState()
    val nowMs by viewModel.liveActivityManager.nowMs.collectAsState()
    val recents by viewModel.liveActivityManager.recentActivityTypes.collectAsState()
    val favorites by viewModel.liveActivityManager.favoriteActivityTypes.collectAsState()
    DashboardContent(
        modifier = modifier,
        state = state,
        liveState = liveState,
        nowMs = nowMs,
        recents = recents,
        favorites = favorites,
        onOpenTimeline = onOpenTimeline,
        onOpenReview = onOpenReview,
        onOpenGoals = onOpenGoals,
        onOpenSleepStatus = onOpenSleepStatus,
        onOpenUsageSettings = onOpenUsageSettings,
        onOpenTodos = onOpenTodos,
        onStartLive = viewModel::startLiveActivity,
        onPauseLive = viewModel::pauseLiveActivity,
        onResumeLive = viewModel::resumeLiveActivity,
        onStopLive = viewModel::stopLiveActivity,
        onDiscardLive = viewModel::discardLiveActivity,
        onToggleFavorite = viewModel::toggleFavorite,
        // M18.12: Neue Aktivität anlegen + starten
        onCreateActivity = viewModel::createAndStartActivity,
        // M18.23: Aktivität wechseln
        onSwitchLive = viewModel::switchActivity
    )
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    liveState: LiveActivityState,
    nowMs: Long,
    recents: List<com.d_drostes_apps.aevum.domain.liveactivity.RecentActivityType>,
    favorites: List<com.d_drostes_apps.aevum.data.model.ActivityType>,
    onOpenTimeline: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenGoals: () -> Unit = {},
    onOpenSleepStatus: () -> Unit = {},
    onOpenUsageSettings: () -> Unit = {},
    // M18.37: Todos-Karte auf dem Dashboard
    onOpenTodos: () -> Unit = {},
    onStartLive: (String, String?, Long) -> Unit,
    onPauseLive: () -> Unit,
    onResumeLive: () -> Unit,
    onStopLive: () -> Unit,
    onDiscardLive: () -> Unit = {},
    onToggleFavorite: (com.d_drostes_apps.aevum.data.model.ActivityType) -> Unit,
    // M18.12: Neue Aktivität anlegen + starten
    onCreateActivity: (String) -> Unit = {},
    // M18.23: Aktivität wechseln
    onSwitchLive: (String, String?) -> Unit = { _, _ -> }
) {
    val isLive = liveState is LiveActivityState.Running || liveState is LiveActivityState.Paused
    val slideIn = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn()
    val slideOut = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            DashboardAtmosphere()
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // 1) Live-Banner — wichtigste Info zuerst. Gleitet von oben rein.
            item {
                AnimatedVisibility(visible = isLive, enter = slideIn, exit = slideOut) {
                    LiveActivityBanner(
                        state = liveState,
                        nowMs = nowMs,
                        onPause = onPauseLive,
                        onResume = onResumeLive,
                        onStop = onStopLive
                    )
                }
            }

            // 2) Puls-Hero — die Antwort auf "Wie war mein Tag?"
            item { PulsHero(state = state) }

            // 3) Schnellstart — NUR wenn nichts läuft. Wenn eine Session
            // aktiv ist, übernimmt das Banner die Steuerung (keine Dopplung).
            item {
                AnimatedVisibility(visible = !isLive, enter = slideIn, exit = slideOut) {
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
                        onToggleFavorite = onToggleFavorite,
                        // M18.12: Neue Aktivität direkt aus dem Picker anlegen
                        onCreateActivity = onCreateActivity,
                        // M18.23: Aktivität wechseln
                        onSwitch = onSwitchLive
                    )
                }
            }

            // 4) Wo deine Zeit hingeht — Top-4 mit Score-Farbe
            if (state.qualityBreakdown.isNotEmpty()) {
                item { QualityBreakdownBars(slices = state.qualityBreakdown.take(4)) }
            }

            // 5) Insights — max 2, nur wenn relevant
            if (state.insights.isNotEmpty()) {
                item { InsightStrip(state = state) }
            }

            // M18.37: Kompakte Todos-Karte — das Herzstueck zeigt, was
            // heute noch ansteht. Nur sichtbar, wenn Todos existieren.
            if (state.todoTotalCount > 0) {
                item {
                    DashboardTodosCard(
                        done = state.todoDoneCount,
                        open = state.todoOpenCount,
                        total = state.todoTotalCount,
                        onOpenTodos = onOpenTodos
                    )
                }
            }

            // M18.37: Pauschalen-Zeile — jede enabled Pauschale explizit
            // sichtbar (Name + Minuten/Tag), nicht nur in der Summe versteckt.
            if (state.allowanceSummary.isNotEmpty()) {
                item { DashboardAllowancesRow(summary = state.allowanceSummary) }
            }

            // 6) Review-Hinweis — nur wenn Vorschläge warten
            if (state.candidateCount > 0 || state.sleepCandidateCount > 0) {
                item { ReviewHintCard(reviewCount = state.candidateCount, sleepCount = state.sleepCandidateCount, onOpenReview = onOpenReview) }
            }

            item { Spacer(Modifier.height(AevumSpacing.xl)) }
            }
        }
    }
}

@Composable
private fun DashboardAtmosphere() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val primary = Color(0xFF8F82FF)
        val mint = Color(0xFF5EEAD4)
        drawCircle(
            color = primary.copy(alpha = 0.10f),
            radius = size.minDimension * 0.52f,
            center = Offset(size.width * 0.92f, size.height * 0.04f)
        )
        drawCircle(
            color = mint.copy(alpha = 0.055f),
            radius = size.minDimension * 0.46f,
            center = Offset(size.width * 0.05f, size.height * 0.66f)
        )
    }
}

/**
 * M18.7: Puls-Hero — das neue Herzstück des Dashboards.
 *
 * Eine Karte, vier Aussagen:
 *  - QualityRing (groß): "Wie wertvoll war deine Zeit?" — die Kern-Kennzahl
 *  - 3 fundamentale Zeit-Blöcke: Erfasst / Schlaf / Bildschirm
 *  - Tagesfluss-Miniatur (24h-Spur)
 *  - Tagesfortschritt (dünne Bar mit %)
 *
 * Keine Interpretationen (Fokus/Balance/Top-Kat) — nur Fakten.
 */
@Composable
private fun PulsHero(state: DashboardUiState) {
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
                // QualityRing + Kern-Blöcke
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityRing(
                        qualityScore = state.qualityScore,
                        ringSize = 108.dp,
                        strokeWidth = 11.dp,
                        label = "QUALITÄT"
                    )
                    Spacer(Modifier.width(AevumSpacing.lg))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        Text(
                            "DEIN TAG",
                            fontSize = 10.sp,
                            letterSpacing = 1.4.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            state.headline,
                            fontSize = 19.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        HeroMetric(
                            icon = "⏱️",
                            value = state.totalTracked,
                            label = "Erfasst"
                        )
                        HeroMetric(
                            icon = "🌙",
                            value = if (state.lastSleepDurationMs > 0) formatHours(state.lastSleepDurationMs) else "—",
                            label = "Schlaf"
                        )
                        HeroMetric(
                            icon = "📱",
                            value = state.digitalScreenTimeFormatted,
                            label = "Bildschirm"
                        )
                    }
                }

                // Tagesfluss-Miniatur (24h-Spur)
                if (state.flowSegments.isNotEmpty()) {
                    DayFlowMiniCanvas(
                        segments = state.flowSegments,
                        currentMinute = state.currentMinute
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(40.dp),
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

                // Tagesfortschritt — dünne Bar, keine Ring-Dopplung
                DayProgressBar(dayProgress = state.dayProgress)
            }
        }
    }
}

/** M18.7: Eine Kennzahl-Zeile im Hero: Emoji + Wert (Monospace) + Label. */
@Composable
private fun HeroMetric(icon: String, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 15.sp)
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** M18.7: Tagesfortschritt als dünne Gradient-Bar mit Prozent. */
@Composable
private fun DayProgressBar(dayProgress: Float) {
    val percent = (dayProgress * 100).toInt().coerceIn(0, 100)
    // M18.7: Farben VOR dem Canvas ziehen — DrawScope ist kein Composable-Kontext
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(AevumRadius.full))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width * (dayProgress.coerceIn(0f, 1f))
                if (w > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(primary, tertiary)
                        ),
                        topLeft = Offset.Zero,
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2)
                    )
                }
            }
        }
        Text(
            "Tag $percent%",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                val segColor = com.d_drostes_apps.aevum.ui.components.categoryColor(seg.categoryName)
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
                digitalScreenTimeFormatted = "2h 15m",
                qualityScore = 72,
                qualityBreakdown = listOf(
                    QualitySlice("fitness", "Fitness", 60L * 60_000, 85, positivityColor(85)),
                    QualitySlice("work", "Arbeit", 3L * 3_600_000 + 20 * 60_000, 50, positivityColor(50)),
                    QualitySlice("digital", "Digital", 2L * 3_600_000, 15, positivityColor(15))
                )
            ),
            liveState = LiveActivityState.Idle,
            nowMs = 0,
            recents = emptyList(),
            favorites = emptyList(),
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

/**
 * M18.7: "Wo deine Zeit hingeht" — horizontale Balken pro Aktivität.
 * Balkenbreite = Dauer-Anteil, Balkenfarbe = Score (rot→gelb→grün).
 * Max 4 Einträge — die Top 4 des Tages. Kaskaden-Animation (80ms).
 */
@Composable
private fun QualityBreakdownBars(slices: List<QualitySlice>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "WO DEINE ZEIT HINGEHT",
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        val maxMs = slices.maxOf { it.durationMs }.coerceAtLeast(1L)
        slices.forEachIndexed { index, slice ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(slice.color)
                    )
                    // M18.38: Pauschalen-Balken mit ⏱-Marker kennzeichnen
                    if (slice.activityTypeId.startsWith("allowance_")) {
                        Text("⏱", fontSize = 11.sp)
                    }
                    Text(
                        slice.label,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatHours(slice.durationMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        slice.score.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = slice.color,
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.End
                    )
                }
                AnimatedGradientBar(
                    progress = slice.durationMs.toFloat() / maxMs.toFloat(),
                    color = slice.color,
                    modifier = Modifier.fillMaxWidth(),
                    height = 8.dp,
                    animationDelayMs = index * 80
                )
            }
        }
    }
}

/**
 * M18.4: LiveActivityBanner — der "richtig deutlich sichtbare Banner" in
 * der App. Gleitet von oben rein (AnimatedVisibility), zeigt:
 *  - Aktivitätstitel + Live-Timer (aktualisiert jede Sekunde)
 *  - Pause/Fortsetzen + Stoppen Buttons (groß, klar)
 *  - Farbverlauf je nach Session-Typ (Auto = primary, manuell = tertiary)
 */
@Composable
private fun LiveActivityBanner(
    state: LiveActivityState,
    nowMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val title = when (state) {
        is LiveActivityState.Running -> state.title
        is LiveActivityState.Paused -> state.title
        else -> return
    }
    val isPaused = state is LiveActivityState.Paused
    val accent = if (isPaused) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val timerText = when (state) {
        is LiveActivityState.Running -> {
            val active = state.activeMs(nowMs)
            "%02d:%02d:%02d".format(
                active / 3_600_000,
                (active % 3_600_000) / 60_000,
                (active % 60_000) / 1000
            )
        }
        is LiveActivityState.Paused -> {
            val active = state.activeMs(nowMs)
            "%02d:%02d".format(active / 60_000, (active % 60_000) / 1000)
        }
        else -> ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            accent.copy(alpha = 0.6f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.42f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f)
                        )
                    )
                )
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                // Status-Punkt
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isPaused) "Pausiert" else "Aufnahme läuft",
                        fontSize = 10.sp,
                        letterSpacing = 1.0.sp,
                        color = accent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Timer (Monospace, groß)
                Text(
                    timerText,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                // Pause/Fortsetzen
                Button(
                    onClick = if (isPaused) onResume else onPause,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (isPaused) "▶" else "⏸", fontSize = 14.sp)
                }
                // Stoppen
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("■", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * M18.37: Kompakte Todos-Karte auf dem Dashboard.
 *
 * Das Dashboard ist das Herzstueck und schon dicht — deshalb bewusst
 * MINIMAL: eine Zeile mit Icon, "X von Y erledigt", Fortschrittsbalken
 * und einem dezente Pfeil. Kein Listen-Detail, keine Checkboxen —
 * dafuer gibt es den Todos-Tab. Ein Tipp auf die Karte oeffnet Todos.
 */
@Composable
private fun DashboardTodosCard(
    done: Int,
    open: Int,
    total: Int,
    onOpenTodos: () -> Unit
) {
    val progress = if (total > 0) done.toFloat() / total else 0f
    val accent = MaterialTheme.colorScheme.primary
    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AevumRadius.md))
                .clickable(onClick = onOpenTodos)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Icon-Kreis
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("✅", fontSize = 16.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Todos",
                    fontSize = 11.sp,
                    letterSpacing = 1.0.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (open > 0) "$open offen · $done erledigt" else "Alle erledigt 🎉",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Dünner Fortschrittsbalken
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent, MaterialTheme.colorScheme.tertiary)
                                )
                            )
                    )
                }
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * M18.37: Kompakte Pauschalen-Zeile auf dem Dashboard.
 *
 * Zeigt jede enabled Tagespauschale explizit (Name + Minuten/Tag) —
 * vorher gingen sie nur in der Gesamtsumme auf und der User sah
 * "Fertig machen 30m" nie. Bewusst schlank: eine Zeile pro Pauschale,
 * Chip-Optik, kein Scroll, kein Detail.
 */
@Composable
private fun DashboardAllowancesRow(summary: List<Pair<String, Int>>) {
    AevumCard(
        variant = CardVariant.Gradient,
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Tagespauschalen",
                fontSize = 11.sp,
                letterSpacing = 1.0.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            summary.forEach { (name, minutes) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$minutes min/Tag",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
