package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.QualityRing
import com.d_drostes_apps.aevum.ui.components.positivityColor
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.sin

/**
 * M18.96 — DASHBOARD-HERO (Kalorien-Tracker-Optik, Parallax-Collapsing).
 *
 * Der obere Bereich des Dashboards wird zum "Tages-Header" wie in
 * Kalorien-Apps (MyFitnessPal/Yazio-Muster): ein großer, animierter
 * Farbbereich mit dem Tagesziel-Ring (QualityRing) und den drei
 * fundamentalen Zeit-Blöcken als farbige Makro-Kacheln (Erfasst/Schlaf/
 * Bildschirm). Der Inhalt darunter schiebt sich beim Scrollen ÜBER den
 * Hero (zwei Ebenen: HeroLayer im Hintergrund, LazyColumn mit
 * topPadding + zIndex im Vordergrund).
 *
 * Design-Entscheidungen (hinterfragt, claude-design-Surface "Monitor"):
 *  - KEINE neue Chart-Library: Aevum ist Offline-Prinzip mit bewusstem
 *    Minimal-Dependency-Pfad; QualityRing/AnimatedGradientBar/BubbleStream
 *    sind bereits hochwertig. Collapsing + Animationen sind reine
 *    Compose-Bordmittel (graphicsLayer, animation-core).
 *  - Animierter Gradient + driftende Orbs statt statischem Kasten:
 *    "schöner Hintergrund im oberen Bereich" — dezent (alpha ≤ 0.5),
 *    kein Regenbogen, Aevum-Palette (primary/secondary/tertiary).
 *  - Makro-Kacheln mit Mini-Balken (Anteil an 24h) statt nackter Zahlen:
 *    Kalorien-Tracker-Metapher "Makros", Farben = Aevum-Kategorien.
 *  - Live-Ansicht: Der LiveActivityBanner (FlipTimeText etc.) bleibt
 *    Item 1 der LazyColumn und gleitet über den Hero; zusätzlich zeigt
 *    der Hero-Kopf einen pulsierenden Live-Chip mit der laufenden Dauer,
 *    damit die "Time-Ansicht oben" im neuen Layout präsent bleibt.
 */

/** Feste Höhe des Hero-Bereichs (Inhalt schiebt sich darüber). */
internal val DashboardHeroHeight = 336.dp

/**
 * Ebene 1: Der Hero-Hintergrund + Inhalt. Wird von DashboardContent
 * hinter der LazyColumn platziert und beim Scrollen per graphicsLayer
 * zusammengeschoben (Inhalt schneller weg, Hintergrund als Parallax).
 */
@Composable
internal fun DashboardHeroLayer(
    state: DashboardUiState,
    isLive: Boolean,
    liveTitle: String,
    liveActiveMs: Long,
    onQualityClick: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onResetToToday: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        // Hintergrund: animierter Gradient + driftende Orbs (Parallax-
        // Ebene — bewegt sich langsamer als der Inhalt). Full-bleed:
        // beginnt bei y=0 (hinter der Statusbar) — der Farbbereich
        // reicht bis ganz oben.
        HeroBackground(modifier = Modifier.fillMaxSize())
        // Inhalt: Ring + Makro-Kacheln + Flow + Fortschritt. Beginnt
        // UNTER der Statusbar (statusBarsPadding) — die LazyColumn hat
        // dasselbe Padding, dadurch ist die Geometrie konsistent:
        // erstes Item startet exakt unterhalb des Hero-Inhalts.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            // Kopf: Tag-Navigation + Live-Chip (wenn Aufnahme läuft).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeroDayNavigation(
                    displayedDate = state.displayedDate,
                    onPrevious = onPreviousDay,
                    onNext = onNextDay,
                    onReset = onResetToToday,
                    modifier = Modifier.weight(1f)
                )
                if (isLive) {
                    Spacer(Modifier.width(AevumSpacing.sm))
                    LiveStatusChip(title = liveTitle, activeMs = liveActiveMs)
                }
            }

            // Ring-Zeile: QualityRing (Kern-Kennzahl) + Headline.
            Row(verticalAlignment = Alignment.CenterVertically) {
                QualityRing(
                    qualityScore = state.qualityScore,
                    ringSize = 104.dp,
                    strokeWidth = 10.dp,
                    label = stringResource(R.string.dashboard_quality_label),
                    onClick = onQualityClick,
                    overrideBadge = if (state.hasDayQualityOverride) "✎" else null
                )
                Spacer(Modifier.width(AevumSpacing.md))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
                ) {
                    Text(
                        stringResource(R.string.dashboard_your_day),
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.headline.ifEmpty { stringResource(R.string.dashboard_narrative_empty_title) },
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Makro-Kacheln: die 3 fundamentalen Zeit-Blöcke (Kalorien-
            // Tracker-Metapher) — farbige Kacheln mit Mini-Balken.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                MetricTile(
                    icon = "⏱️",
                    value = formatHours(state.totalTrackedMs),
                    label = stringResource(R.string.common_captured),
                    color = MaterialTheme.colorScheme.primary,
                    fraction = (state.totalTrackedMs / 86_400_000f).coerceIn(0f, 1f),
                    animatedValueMs = state.totalTrackedMs,
                    delayMs = 0,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = "🌙",
                    value = if (state.lastSleepDurationMs > 0) formatHours(state.lastSleepDurationMs) else "—",
                    label = stringResource(R.string.common_sleep),
                    color = Color(0xFF6366F1),
                    fraction = (state.lastSleepDurationMs / 86_400_000f).coerceIn(0f, 1f),
                    delayMs = 80,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    icon = "📱",
                    value = state.digitalScreenTimeFormatted,
                    label = stringResource(R.string.dashboard_screen),
                    color = MaterialTheme.colorScheme.tertiary,
                    fraction = 0f, // Bildschirmzeit hat keinen 24h-Anteil (fremde Quelle)
                    delayMs = 160,
                    modifier = Modifier.weight(1f)
                )
            }

            // Tagesfluss-Miniatur (24h-Spur).
            if (state.flowSegments.isNotEmpty()) {
                DayFlowMiniCanvas(
                    segments = state.flowSegments,
                    currentMinute = state.currentMinute
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    shape = RoundedCornerShape(AevumRadius.md),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.dashboard_flow_empty),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Tagesfortschritt — dünne Bar, keine Ring-Dopplung.
            DayProgressBar(dayProgress = state.dayProgress)
        }
    }
}

/**
 * Animierter Hero-Hintergrund: langsam wandernder Gradient (Aevum-
 * Palette, dezent) + drei driftende Orbs. Reine Dauer-Animation —
 * kein Fortschritts-Bezug (User-Geschmack: Dauerauflade statt Balken).
 */
@Composable
private fun HeroBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "heroBg")
    // Gradient-Farben wandern langsam (10s-Zyklus, hin und zurück).
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10_000), RepeatMode.Reverse),
        label = "heroGradientT"
    )
    // Orbs: drei Kreise mit langsamen Drift-Pfaden (18-26s).
    val orb1X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(22_000), RepeatMode.Reverse),
        label = "orb1X"
    )
    val orb1Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000), RepeatMode.Reverse),
        label = "orb1Y"
    )
    val orb2X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(19_000), RepeatMode.Reverse),
        label = "orb2X"
    )
    val orb2Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000), RepeatMode.Reverse),
        label = "orb2Y"
    )
    val orb3X by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(25_000), RepeatMode.Reverse),
        label = "orb3X"
    )
    val orb3Y by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(21_000), RepeatMode.Reverse),
        label = "orb3Y"
    )

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    // Farbwanderung: primaryContainer → tertiaryContainer →
                    // secondaryContainer → zurück (sinus-geglättet).
                    lerpColor(
                        primary.copy(alpha = 0.34f),
                        tertiary.copy(alpha = 0.30f),
                        t
                    ),
                    lerpColor(
                        secondary.copy(alpha = 0.20f),
                        primary.copy(alpha = 0.16f),
                        t
                    ),
                    surface.copy(alpha = 0.0f)
                )
            )
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Orb 1 — groß, oben rechts (primary).
            drawCircle(
                color = primary.copy(alpha = 0.10f),
                radius = size.minDimension * 0.42f,
                center = Offset(
                    w * (0.78f + 0.14f * sin(orb1X * 6.28f)),
                    h * (0.10f + 0.20f * sin(orb1Y * 6.28f))
                )
            )
            // Orb 2 — mittel, unten links (secondary/mint).
            drawCircle(
                color = secondary.copy(alpha = 0.08f),
                radius = size.minDimension * 0.34f,
                center = Offset(
                    w * (0.10f + 0.16f * sin(orb2X * 5.2f)),
                    h * (0.72f + 0.18f * sin(orb2Y * 4.6f))
                )
            )
            // Orb 3 — klein, Mitte (tertiary/amber).
            drawCircle(
                color = tertiary.copy(alpha = 0.07f),
                radius = size.minDimension * 0.24f,
                center = Offset(
                    w * (0.50f + 0.22f * sin(orb3X * 3.8f)),
                    h * (0.42f + 0.24f * sin(orb3Y * 5.0f))
                )
            )
        }
    }
}

/** Lineare Farbinterpolation (für den wandernden Gradient). */
private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

/**
 * Makro-Kachel (Kalorien-Tracker-Optik): Icon + Wert + Label + Mini-
 * Balken (Anteil an 24h). Kaskaden-Einblendung (scale + alpha).
 */
@Composable
private fun MetricTile(
    icon: String,
    value: String,
    label: String,
    color: Color,
    fraction: Float,
    delayMs: Int,
    modifier: Modifier = Modifier,
    animatedValueMs: Long = 0L
) {
    // Kaskaden-Einblendung: einmal beim Erscheinen (scale 0.92→1, alpha).
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }
    val appear by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 220f),
        label = "metricTileAppear"
    )
    // Animierter Wert (Tag-Wechsel zählt hoch — M18.60-Verhalten).
    val animatedMs by animateFloatAsState(
        targetValue = animatedValueMs.toFloat(),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "metricTileValue"
    )
    val displayValue = if (animatedValueMs > 0L) {
        val h = animatedMs.toLong() / 3_600_000
        val m = (animatedMs.toLong() % 3_600_000) / 60_000
        when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    } else value

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = 0.92f + 0.08f * appear
                scaleY = 0.92f + 0.08f * appear
                alpha = appear
            },
        shape = RoundedCornerShape(AevumRadius.lg),
        color = color.copy(alpha = 0.13f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = AevumSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(icon, fontSize = 15.sp)
            Text(
                displayValue,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            // Mini-Balken: Anteil an 24h (Makro-Optik).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

/**
 * Pulsierender Live-Chip im Hero-Kopf: roter Punkt (atmet) + Titel +
 * laufende Dauer. Hält die "Time-Ansicht oben" präsent, während der
 * große LiveActivityBanner darunter im Content liegt.
 */
@Composable
private fun LiveStatusChip(title: String, activeMs: Long) {
    val pulse = rememberInfiniteTransition(label = "liveChipPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "liveChipAlpha"
    )
    val timerText = "%02d:%02d".format(
        activeMs / 3_600_000,
        (activeMs % 3_600_000) / 60_000
    )
    Surface(
        shape = RoundedCornerShape(AevumRadius.full),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AevumSpacing.sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha))
            )
            Text(
                title,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                timerText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** M18.60/M18.81: Dezente Tag-Navigation — Chip-Zeile im Hero-Kopf. */
@Composable
private fun HeroDayNavigation(
    displayedDate: java.time.LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = java.time.LocalDate.now()
    val isToday = displayedDate == today
    val label = when {
        isToday -> stringResource(R.string.common_today)
        displayedDate == today.minusDays(1) -> stringResource(R.string.common_yesterday)
        else -> displayedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM."))
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AevumRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(horizontal = AevumSpacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            "‹",
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onPrevious)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        if (!isToday) {
            Text(
                stringResource(R.string.common_today),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.sm))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Text(
            "›",
            fontSize = 17.sp,
            color = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(enabled = !isToday, onClick = onNext)
                .padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

/** M18.7: Tagesfortschritt als dünne Gradient-Bar mit Prozent. */
@Composable
private fun DayProgressBar(dayProgress: Float) {
    val percent = (dayProgress * 100).toInt().coerceIn(0, 100)
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
                        brush = Brush.horizontalGradient(listOf(primary, tertiary)),
                        topLeft = Offset.Zero,
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2)
                    )
                }
            }
        }
        Text(
            stringResource(R.string.dashboard_day_progress, percent),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Mini Day-Flow Canvas — kompakt, mit "jetzt"-Marker. */
@Composable
private fun DayFlowMiniCanvas(
    segments: List<DashboardFlowSegment>,
    currentMinute: Int
) {
    val trackHeight = 32.dp
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

/** Helper: Stunden/Minuten-Format. */
private fun formatHours(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
