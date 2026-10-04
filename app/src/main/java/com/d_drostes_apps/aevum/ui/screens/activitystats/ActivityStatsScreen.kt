package com.d_drostes_apps.aevum.ui.screens.activitystats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/** Test-Tags der Statistikseite (Compose-UI-Tests). */
internal const val ACTIVITY_STATS_CHART_TEST_TAG = "activityStatsChart"
internal const val ACTIVITY_STATS_EMPTY_TEST_TAG = "activityStatsEmpty"
internal const val ACTIVITY_STATS_RANGE_TOGGLE_PREFIX = "activityStatsRange_"
internal const val ACTIVITY_STATS_BACK_TEST_TAG = "activityStatsBack"

/** Test-Tag eines Granularitäts-Chips im Umschalter. */
internal fun activityStatsRangeTestTag(range: ActivityStatsRange): String =
    "$ACTIVITY_STATS_RANGE_TOGGLE_PREFIX${range.name}"

/**
 * M18.146: Detail-Statistikseite einer Aktivität — „fancy graph" mit
 * umschaltbarem Verlauf (täglich / wöchentlich / monatlich).
 *
 * Erreichbar aus drei Richtungen (Nutzer-Auftrag):
 *  1. Insights → Klick auf eine Aktivität in der Top-Liste,
 *  2. Timeline → Aufzeichnung → Detailseite → „Verlauf ansehen",
 *  3. Einstellungen → Aktivitäten → Verlinkung je Aktivität.
 *
 * Die Seite ist bewusst spracharm („ohne viele Wörter"): der Graph trägt die
 * Aussage, darunter wenige Kennzahl-Kacheln, ein Wochenmuster und die
 * Entwicklung gegenüber dem Zeitraum davor.
 */
@Composable
fun ActivityStatsScreen(
    onBack: () -> Unit,
    viewModel: ActivityStatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ActivityStatsContent(
        state = state,
        onBack = onBack,
        onSelectRange = viewModel::selectRange
    )
}

/**
 * Zustandsfreier Rumpf (Muster aus InsightsScreenContent): rendert jeden
 * UiState ohne Hilt-Graph und ist damit der Anker der Compose-UI-Tests.
 */
@Composable
internal fun ActivityStatsContent(
    state: ActivityStatsUiState,
    onBack: () -> Unit = {},
    onSelectRange: (ActivityStatsRange) -> Unit = {}
) {
    val model = state.model
    // Aktivitätsfarbe: Custom-Farbe gewinnt, sonst Primärfarbe des Themes.
    val accent = if (state.activityColor != 0L) Color(state.activityColor)
    else MaterialTheme.colorScheme.primary

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            // Kopf: Zurück + Aktivität (Icon, Name, Untertitel)
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(ACTIVITY_STATS_BACK_TEST_TAG)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                    Spacer(Modifier.width(AevumSpacing.xs))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (state.activityIcon.isBlank()) "•" else state.activityIcon,
                            fontSize = 22.sp
                        )
                    }
                    Spacer(Modifier.width(AevumSpacing.sm))
                    Column {
                        Text(
                            state.activityName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            stringResource(R.string.activity_stats_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Granularitäts-Umschalter (täglich / wöchentlich / monatlich)
            item {
                ActivityStatsRangeToggle(
                    selected = model.range,
                    accent = accent,
                    onSelect = onSelectRange
                )
            }

            if (!model.hasData) {
                // Leerzustand: die Aktivität hat noch KEINE Aufzeichnung.
                item {
                    GlassCard(accentColor = accent, modifier = Modifier.testTag(ACTIVITY_STATS_EMPTY_TEST_TAG)) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("✦", fontSize = 28.sp, color = accent)
                            Text(
                                stringResource(R.string.activity_stats_empty_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                stringResource(R.string.activity_stats_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                // Hero: Gesamt-Zeit des Fensters + Umschalter-Kontext
                item { ActivityStatsHero(model = model, accent = accent, name = state.activityName) }

                // Der fancy Verlaufs-Graph mit Finger-Details.
                item {
                    ActivityTrendChartCard(
                        model = model,
                        accent = accent,
                        modifier = Modifier.testTag(ACTIVITY_STATS_CHART_TEST_TAG)
                    )
                }

                // Kennzahlen: 2 Spalten × 3 Zeilen — wenige Wörter, klare Zahlen.
                item { ActivityStatsGrid(model = model, accent = accent) }

                // Wochenmuster: 7 Mini-Balken — "an welchen Tagen lebt diese Aktivität".
                item { WeekdayRhythmCard(model = model, accent = accent) }

                // Entwicklung vs. Zeitraum davor (nur mit Vergleichsbasis).
                if (model.trendPercent != null) {
                    item { TrendCard(model = model, accent = accent) }
                }
            }

            item { Spacer(Modifier.height(AevumSpacing.lg)) }
        }
    }
}

/**
 * Animierter Segmented-Umschalter in Glas-Optik (Muster aus der
 * Dashboard-Qualitätskarte): der aktive Chip leuchtet in der
 * Aktivitätsfarbe, die Optionen heißen kurz „Täglich/Wöchentlich/Monatlich".
 */
@Composable
private fun ActivityStatsRangeToggle(
    selected: ActivityStatsRange,
    accent: Color,
    onSelect: (ActivityStatsRange) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ActivityStatsRange.entries.forEach { range ->
            val isSelected = range == selected
            val label = stringResource(
                when (range) {
                    ActivityStatsRange.Daily -> R.string.activity_stats_axis_daily
                    ActivityStatsRange.Weekly -> R.string.activity_stats_axis_weekly
                    ActivityStatsRange.Monthly -> R.string.activity_stats_axis_monthly
                }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(range) }
                    .testTag(activityStatsRangeTestTag(range))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Hero: Gesamtzeit des Fensters als große Monospace-Zahl + Zeitraum-Kontext
 * ("letzte 30 Tage" bzw. Fenster-Beschriftung).
 */
@Composable
private fun ActivityStatsHero(model: ActivityStatsUiModel, accent: Color, name: String) {
    val totalMs = model.totalMs
    val label = when (model.range) {
        ActivityStatsRange.Daily -> stringResource(R.string.activity_stats_axis_daily).lowercase()
        ActivityStatsRange.Weekly -> stringResource(R.string.activity_stats_axis_weekly).lowercase()
        ActivityStatsRange.Monthly -> stringResource(R.string.activity_stats_axis_monthly).lowercase()
    }
    GlassCard(accentColor = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                val formatted = remember(totalMs) { formatHoursMinutes(totalMs) }
                Text(
                    formatted.first,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_hours_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Spacer(Modifier.width(AevumSpacing.sm))
                Text(
                    formatted.second,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_minutes_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Der Verlaufs-Graph — Herz der Seite.
 *
 * Design (bewusst „fancy", nicht Standard-Android):
 *  - geglättete Bezier-Linie mit Verlaufs-Stroke (Aktivitätsfarbe → heller),
 *  - leuchtende Doppel-Linie (weicher Glow + Kerze) für den Tiefeneffekt,
 *  - Fläche darunter als vertikaler Verlauf (Aktivitätsfarbe → transparent),
 *  - animiertes Einwachsen der Fläche von unten beim Öffnen,
 *  - Punkte je Bucket, letzter Punkt (Gegenwart) mit Ring hervorgehoben,
 *  - dezente horizontale Hilfslinien,
 *  - Finger auflegen/halten → Scrubber-Linie + Karte mit Datum, Dauer,
 *    Anzahl Aufzeichnungen („Details zur jeweiligen Position").
 */
@Composable
private fun ActivityTrendChartCard(
    model: ActivityStatsUiModel,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val buckets = model.buckets
    val values = buckets.map { it.durationMs }
    val maxMs = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    // Scrubber-Zustand (Compose-State, außerhalb des Canvas gerendert).
    var scrubIndex by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current

    // Einwachsen der Fläche beim Öffnen — der „lebendige" Effekt.
    // Animatable statt animateFloatAsState: letzteres startet bereits auf
    // seinem Zielwert und würde beim ersten Rendern NICHT animieren; der
    // sichtbare Öffnungs-Effekt braucht einen echten 0 → 1-Lauf.
    val fillProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fillProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 110f)
        )
    }

    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.surface

    AevumCard(modifier = modifier) {
        Column {
            // Kopfzeile: Titel + Anzahl Aufzeichnungen im Fenster
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.activity_stats_trend_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.activity_stats_sessions_count, model.sessionCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(AevumSpacing.sm))

            // Tooltip-Karte über dem Graphen, wenn der Finger auf dem Chart liegt.
            Box(modifier = Modifier.fillMaxWidth()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .testTag("${ACTIVITY_STATS_CHART_TEST_TAG}_canvas")
                        .semantics {
                            contentDescription = "activity stats chart, ${buckets.size} buckets"
                        }
                        .pointerInput(buckets) {
                            // Sofortige Reaktion beim AUFLEGEN (awaitFirstDown),
                            // nicht erst nach Überschreiten der Drag-Schwelle —
                            // "Finger auf dem Graphen halten" muss ohne Bewegung
                            // funktionieren. Loslassen beendet die Auswahl.
                            if (buckets.isEmpty()) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val width = size.width.toFloat()
                                var index = nearestBucketIndex(down.position.x, width, buckets.size)
                                if (index != scrubIndex) {
                                    scrubIndex = index
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        scrubIndex = -1
                                        break
                                    }
                                    val newIndex = nearestBucketIndex(change.position.x, width, buckets.size)
                                    if (newIndex != index) {
                                        index = newIndex
                                        scrubIndex = newIndex
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val padTop = 14f
                    val padBottom = 26f
                    val usableH = h - padTop - padBottom

                    fun xFor(index: Int): Float =
                        if (buckets.size == 1) w / 2f
                        else w * index / (buckets.size - 1).toFloat()

                    fun yFor(ms: Long): Float =
                        padTop + usableH * (1f - ms.toFloat() / maxMs.toFloat())

                    // Dezente horizontale Hilfslinien (4 Linien).
                    for (i in 0..3) {
                        val y = padTop + usableH * i / 3f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    if (buckets.isNotEmpty()) {
                        val points = buckets.mapIndexed { i, b -> Offset(xFor(i), yFor(b.durationMs)) }
                        val baseline = h - padBottom

                        // ---- Fläche (animiert von unten) ----
                        val path = Path()
                        path.moveTo(points[0].x, baseline)
                        points.forEach { path.lineTo(it.x, it.y) }
                        path.lineTo(points.last().x, baseline)
                        path.close()

                        // Die Fläche wächst beim Öffnen optisch ein
                        // (Gradient + fillProgress-Animation des Alpha-Bereichs).
                        drawPath(
                            path = path,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.38f * fillProgress.value),
                                    accent.copy(alpha = 0.10f * fillProgress.value),
                                    Color.Transparent
                                ),
                                startY = padTop,
                                endY = baseline
                            )
                        )

                        // ---- Geglättete Bezier-Linie ----
                        val linePath = Path()
                        linePath.moveTo(points[0].x, points[0].y)
                        for (i in 0 until points.size - 1) {
                            val p0 = points[i]
                            val p1 = points[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            linePath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }

                        // Glow (weicher, breiter Stroke in niedriger Alpha) …
                        drawPath(
                            path = linePath,
                            color = accent.copy(alpha = 0.22f),
                            style = Stroke(width = 9f, cap = StrokeCap.Round)
                        )
                        // … und die Kern-Linie mit horizontalem Farbverlauf.
                        drawPath(
                            path = linePath,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.75f),
                                    accent,
                                    accent.copy(alpha = 0.85f)
                                )
                            ),
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )

                        // ---- Punkte ----
                        points.forEachIndexed { i, p ->
                            val isLast = i == points.lastIndex
                            if (isLast) {
                                // Gegenwarts-Marker: Ring + Kern.
                                drawCircle(color = accent.copy(alpha = 0.25f), radius = 9f, center = p)
                                drawCircle(color = accent, radius = 5f, center = p)
                                drawCircle(color = Color.White.copy(alpha = 0.95f), radius = 2f, center = p)
                            } else if (buckets.size <= 32) {
                                drawCircle(
                                    color = if (buckets[i].durationMs > 0L) accent else accent.copy(alpha = 0.3f),
                                    radius = 3.2f,
                                    center = p
                                )
                            }
                        }

                        // ---- Scrubber ----
                        if (scrubIndex in buckets.indices) {
                            val sx = points[scrubIndex].x
                            val sy = points[scrubIndex].y
                            drawLine(
                                color = accent.copy(alpha = 0.55f),
                                start = Offset(sx, padTop - 6f),
                                end = Offset(sx, baseline),
                                strokeWidth = 1.5f,
                                cap = StrokeCap.Round
                            )
                            drawCircle(color = accent.copy(alpha = 0.3f), radius = 10f, center = Offset(sx, sy))
                            drawCircle(color = accent, radius = 5.5f, center = Offset(sx, sy))
                            drawCircle(color = Color.White, radius = 2.2f, center = Offset(sx, sy))
                        }

                        // ---- X-Achse: erstes und letztes Bucket-Label ----
                        // (bewusst nur zwei — "ohne viele Wörter")
                    }
                }

                // Tooltip-Karte (Compose-Overlay; zeigt volle Details zur Position).
                if (scrubIndex in buckets.indices) {
                    val bucket = buckets[scrubIndex]
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(AevumRadius.sm))
                            .background(tooltipBg.copy(alpha = 0.97f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                bucket.fullLabel,
                                fontSize = 10.sp,
                                color = labelColor,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                TimeFormatting.formatDuration(bucket.durationMs),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                fontFamily = FontFamily.Monospace
                            )
                            if (bucket.sessionCount > 0) {
                                Text(
                                    stringResource(R.string.activity_stats_sessions_count, bucket.sessionCount),
                                    fontSize = 9.sp,
                                    color = labelColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // X-Achsen-Beschriftung: erstes und letztes Label unter dem Graphen.
            if (buckets.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(buckets.first().label, fontSize = 10.sp, color = labelColor)
                    Text(buckets.last().label, fontSize = 10.sp, color = labelColor)
                }
            }

            Spacer(Modifier.height(AevumSpacing.xs))
            Text(
                stringResource(R.string.activity_stats_chart_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

/** Nächster Bucket-Index zu einer X-Position auf dem Canvas. */
internal fun nearestBucketIndex(x: Float, canvasWidth: Float, bucketCount: Int): Int {
    if (bucketCount <= 0) return -1
    if (bucketCount == 1) return 0
    if (canvasWidth <= 0f) return 0
    val fraction = (x / canvasWidth).coerceIn(0f, 1f)
    return kotlin.math.round(fraction * (bucketCount - 1)).toInt()
}

/**
 * Kennzahlen 2×3 — wenige Wörter, klare Zahlen: Gesamt, Ø pro Tag,
 * Aufzeichnungen, Längste, aktive Tage, beste Serie.
 */
@Composable
private fun ActivityStatsGrid(model: ActivityStatsUiModel, accent: Color) {
    val stats = listOf(
        Triple("⧖", stringResource(R.string.activity_stats_total), TimeFormatting.formatDuration(model.totalMs)),
        Triple("◷", stringResource(R.string.activity_stats_avg_per_day), TimeFormatting.formatDuration(model.avgPerDayMs)),
        Triple("☰", stringResource(R.string.activity_stats_sessions), model.sessionCount.toString()),
        Triple("↗", stringResource(R.string.activity_stats_longest), TimeFormatting.formatDuration(model.longestMs)),
        Triple("●", stringResource(R.string.activity_stats_active_days), model.activeDays.toString()),
        Triple("✦", stringResource(R.string.activity_stats_streak), model.bestStreak.toString())
    )
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        stats.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                row.forEach { (icon, label, value) ->
                    AevumCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(AevumSpacing.md)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(icon, fontSize = 18.sp, color = accent)
                            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                value,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Wochenmuster: sieben Mini-Balken (Mo..So). Der stärkste Tag leuchtet in
 * der Aktivitätsfarbe, die übrigen dezent. Zeigt auf einen Blick, an
 * welchen Wochentagen die Aktivität lebt.
 */
@Composable
private fun WeekdayRhythmCard(model: ActivityStatsUiModel, accent: Color) {
    val totals = model.weekdayMs
    val max = totals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val labels = listOf(
        R.string.common_monday, R.string.common_tuesday, R.string.common_wednesday,
        R.string.common_thursday, R.string.common_friday, R.string.common_saturday,
        R.string.common_sunday
    )
    AevumCard {
        Column {
            Text(
                stringResource(R.string.activity_stats_weekday_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AevumSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
                verticalAlignment = Alignment.Bottom
            ) {
                totals.forEachIndexed { index, ms ->
                    val fraction = (ms.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    val isMax = ms == max && ms > 0L
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        ) {
                            val barWidth = size.width * 0.62f
                            val barHeight = (size.height * fraction).coerceAtLeast(if (ms > 0L) 4f else 0f)
                            val left = (size.width - barWidth) / 2f
                            drawRoundRect(
                                color = if (isMax) accent else accent.copy(alpha = 0.35f),
                                topLeft = Offset(left, size.height - barHeight),
                                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(labels[index]),
                            fontSize = 9.sp,
                            fontWeight = if (isMax) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMax) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Entwicklungskarte: ±X % gegenüber dem Zeitraum davor. Grün = mehr Zeit in
 * dieser Aktivität, Rot = weniger. Ohne Vergleichsbasis wird die Karte gar
 * nicht angezeigt (siehe ActivityStatsUiModel.trendPercent == null).
 */
@Composable
private fun TrendCard(model: ActivityStatsUiModel, accent: Color) {
    val trend = model.trendPercent ?: return
    val color = when {
        trend > 0 -> Color(0xFF66BB6A)
        trend < 0 -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.outline
    }
    val sign = if (trend > 0) "+" else if (trend < 0) "−" else ""
    val wording = stringResource(
        when {
            trend > 0 -> R.string.activity_stats_trend_up
            trend < 0 -> R.string.activity_stats_trend_down
            else -> R.string.activity_stats_trend_flat
        }
    )
    AevumCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$sign${kotlin.math.abs(trend)}%",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color
            )
            Spacer(Modifier.width(AevumSpacing.md))
            Text(
                wording,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Formatiert ms zu (Stunden-String, Minuten-String) für die Hero-Anzeige. */
private fun formatHoursMinutes(ms: Long): Pair<String, String> {
    val safe = ms.coerceAtLeast(0L)
    val totalMinutes = safe / 60_000
    return (totalMinutes / 60).toString() to (totalMinutes % 60).toString()
}
