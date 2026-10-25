package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M31: Interaktive Diagramme der Statistik-Ansicht.
 *
 * Die drei Karten ersetzen die früheren Text-Blöcke („Änderungen" und die
 * Insight-Karten). Sie hängen alle am oben gewählten Zeitraum:
 *
 *  - [InsightsDistributionChart] — Donut der Kategorien mit auswählbaren
 *    Segmenten (Segment fährt heraus und wird größer, Mitte zeigt Wert).
 *  - [InsightsTrendChart] — Balken je Stunde (heute) bzw. je Tag (Woche/Monat)
 *    mit antippbaren Balken und Wertefeld.
 *  - [InsightsBalanceChart] — Radar der fünf Lebensbereiche mit antippbaren
 *    Achsen/Chips und Wertefeld.
 *
 * Bewusst ohne Chart-Bibliothek: die App zeichnet auch an anderen Stellen
 * (Timeline, QualityTrendCard, DigitalBalance) mit Canvas. Eine zusätzliche
 * Abhängigkeit würde nur APK-Größe und Build-Risiko kaufen.
 */

/** Test-Tag der Verteilungs-Karte (Donut). */
internal const val INSIGHTS_DONUT_TEST_TAG = "insightsDonut"

/** Test-Tag der Verlaufs-Karte (Balken). */
internal const val INSIGHTS_TREND_TEST_TAG = "insightsTrend"

/** Test-Tag der Balance-Karte (Radar). */
internal const val INSIGHTS_RADAR_TEST_TAG = "insightsRadar"

/** Test-Tag des großen Wert-Textes in der Donut-Mitte. */
internal const val INSIGHTS_CHART_VALUE_TEST_TAG = "insightsChartValue"

/** Test-Tag des Wertefeldes im Verlaufs-Diagramm. */
internal const val INSIGHTS_TREND_VALUE_TEST_TAG = "insightsTrendValue"

/** Test-Tag des Wertefeldes im Radar-Diagramm. */
internal const val INSIGHTS_RADAR_VALUE_TEST_TAG = "insightsRadarValue"

/** Test-Tag eines Legendeneintrags im Donut. */
internal fun insightsDonutLegendTestTag(index: Int): String = "insightsDonutLegend_$index"

/** Test-Tag eines Bereichs-Chips im Radar. */
internal fun insightsRadarChipTestTag(index: Int): String = "insightsRadarChip_$index"

// ----------------------------------------------------------------------
// Gemeinsame Bausteine
// ----------------------------------------------------------------------

/**
 * Kopfzeile einer Diagramm-Karte: Icon im farbigen Kreis, Titel, Untertitel.
 * Der Untertitel trägt die Zeitraum-Abhängigkeit (z.B. „Erfasste Zeit pro
 * Stunde"), damit die Karte ohne Blick auf den Perioden-Toggle verständlich
 * bleibt.
 */
@Composable
private fun ChartHeader(
    icon: String,
    title: String,
    subtitle: String,
    accent: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 14.sp, color = accent)
        }
        Spacer(Modifier.width(AevumSpacing.sm))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** Dezenter Hinweis unter einem Diagramm — erklärt die Interaktion. */
@Composable
private fun ChartHint() {
    Text(
        text = stringResource(R.string.insights_chart_hint),
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

/** Dauer eines Diagramm-Wertes — gleiche Schreibweise wie die Top-Liste. */
private fun formatChartDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val total = ms / 60_000L
    val hours = total / 60
    val minutes = total % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// ----------------------------------------------------------------------
// 1) Donut — Verteilung der Kategorien
// ----------------------------------------------------------------------

/**
 * Donut-Diagramm der Zeitverteilung.
 *
 * Interaktion: Tippen auf ein Segment ODER auf die Legende wählt es aus.
 * Das Segment fährt um einen kleinen Betrag nach außen, wird dicker und
 * bekommt einen Glow; die Mitte wechselt animiert von der Gesamtsumme auf
 * Label + exakte Dauer. Erneutes Tippen hebt die Auswahl auf.
 */
@Composable
internal fun InsightsDistributionChart(
    slices: List<TimeDistributionSlice>,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) return
    val total = slices.sumOf { it.durationMs }.coerceAtLeast(1L)
    var selectedIndex by remember(slices) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.takeIf { it in slices.indices }
    val accent = MaterialTheme.colorScheme.primary

    // Eintritts-Animation: die Segmente wachsen beim Erscheinen bzw. bei jedem
    // Periodenwechsel einmal von 0 auf 360 Grad.
    val entry = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        entry.snapTo(0f)
        entry.animateTo(1f, tween(durationMillis = 850, easing = FastOutSlowInEasing))
    }
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected != null) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "distributionSelection"
    )

    GlassCard(
        modifier = modifier.testTag(INSIGHTS_DONUT_TEST_TAG),
        accentColor = accent
    ) {
        Column {
            ChartHeader(
                icon = "◔",
                title = stringResource(R.string.insights_chart_distribution_title),
                subtitle = stringResource(R.string.insights_chart_distribution_subtitle),
                accent = accent
            )
            Spacer(Modifier.height(AevumSpacing.md))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(slices, total) {
                            detectTapGestures { offset ->
                                if (slices.isEmpty()) return@detectTapGestures
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val dx = offset.x - center.x
                                val dy = offset.y - center.y
                                val distance = hypot(dx, dy)
                                val outer = minOf(size.width, size.height) / 2f * 0.86f
                                val ring = outer * 0.30f
                                val innerEdge = outer - ring
                                if (distance !in innerEdge..outer) {
                                    selectedIndex = null
                                    return@detectTapGestures
                                }
                                // Winkel ab 12 Uhr im Uhrzeigersinn — exakt die
                                // Konvention, mit der unten gezeichnet wird.
                                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                                angle = ((angle % 360.0) + 360.0) % 360.0
                                var cursor = 0f
                                var hit: Int? = null
                                slices.forEachIndexed { index, slice ->
                                    val sweep = slice.durationMs.toFloat() / total.toFloat() * 360f
                                    if (hit == null && angle >= cursor && angle < cursor + sweep) hit = index
                                    cursor += sweep
                                }
                                hit?.let { index ->
                                    selectedIndex = if (selectedIndex == index) null else index
                                }
                            }
                        }
                ) {
                    if (entry.value <= 0f) return@Canvas
                    val outer = size.minDimension / 2f * 0.86f
                    val ring = outer * 0.30f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val gap = if (slices.size > 1) 2.0f else 0f

                    var startAngle = -90f
                    slices.forEachIndexed { index, slice ->
                        val sweep = slice.durationMs.toFloat() / total.toFloat() * 360f * entry.value
                        val isSelected = selected == index
                        val weight = if (isSelected) selectionProgress else 0f
                        // Ausgewähltes Segment fährt nach außen und wird dicker.
                        val push = ring * 0.24f * weight
                        val mid = Math.toRadians((startAngle + sweep / 2f).toDouble())
                        val arcCenter = Offset(
                            center.x + cos(mid).toFloat() * push,
                            center.y + sin(mid).toFloat() * push
                        )
                        val strokeWidth = ring * (1f + 0.18f * weight)
                        val arcRadius = outer - ring / 2f
                        val topLeft = Offset(arcCenter.x - arcRadius, arcCenter.y - arcRadius)
                        val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                        val drawnSweep = (sweep - gap).coerceAtLeast(0f)

                        if (drawnSweep > 0.1f) {
                            if (isSelected) {
                                // Glow zuerst, damit das Segment darüber liegt.
                                drawArc(
                                    color = slice.color.copy(alpha = 0.22f * weight),
                                    startAngle = startAngle + gap / 2f,
                                    sweepAngle = drawnSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth * 2.0f, cap = StrokeCap.Round)
                                )
                            }
                            drawArc(
                                color = slice.color,
                                startAngle = startAngle + gap / 2f,
                                sweepAngle = drawnSweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                        startAngle += sweep
                    }
                }

                // Wertefeld in der Mitte des Donuts.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(118.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = selected,
                        label = "donutCenter"
                    ) { index ->
                        val slice = index?.let { slices.getOrNull(it) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = slice?.label
                                    ?: stringResource(R.string.insights_chart_total),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = formatChartDuration(slice?.durationMs ?: total),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag(INSIGHTS_CHART_VALUE_TEST_TAG)
                            )
                            Text(
                                text = if (slice != null) "${slice.percent} %" else "",
                                fontSize = 10.sp,
                                color = slice?.color ?: MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AevumSpacing.sm))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                slices.forEachIndexed { index, slice ->
                    val isSelected = selected == index
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(
                                if (isSelected) slice.color.copy(alpha = 0.20f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                            .clickable {
                                selectedIndex = if (selectedIndex == index) null else index
                            }
                            .testTag(insightsDonutLegendTestTag(index))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(slice.color)
                        )
                        Text(
                            slice.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            "${slice.percent} %",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(AevumSpacing.sm))
            ChartHint()
        }
    }
}

// ----------------------------------------------------------------------
// 2) Balken — Verlauf der erfassten Zeit
// ----------------------------------------------------------------------

/**
 * Balken-Diagramm des Zeitverlaufs. Die Auflösung kommt aus
 * [InsightsUiState.trendBuckets] und folgt der Perioden-Auswahl:
 * Stunden (heute), Wochentage (Woche) oder Kalendertage (Monat).
 *
 * Interaktion: Tippen auf einen Balken wählt ihn aus. Er wächst animiert um
 * zehn Prozent, bekommt einen Glow, und das Wertefeld über dem Diagramm
 * zeigt Datum/Zeitspanne + exakte Dauer. Erneutes Tippen hebt die Auswahl auf.
 */
@Composable
internal fun InsightsTrendChart(
    buckets: List<TrendBucket>,
    period: InsightPeriod,
    modifier: Modifier = Modifier
) {
    if (buckets.isEmpty()) return
    var selectedIndex by remember(buckets) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.takeIf { it in buckets.indices }
    val accent = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.onSurface
    val maxMs = buckets.maxOf { it.durationMs }.coerceAtLeast(1L)
    val textMeasurer = rememberTextMeasurer()

    val entry = remember { Animatable(0f) }
    LaunchedEffect(buckets) {
        entry.snapTo(0f)
        entry.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
    }
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected != null) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "trendSelection"
    )

    GlassCard(
        modifier = modifier.testTag(INSIGHTS_TREND_TEST_TAG),
        accentColor = accent
    ) {
        Column {
            ChartHeader(
                icon = "▥",
                title = stringResource(R.string.insights_chart_course_title),
                subtitle = stringResource(
                    when (period) {
                        InsightPeriod.Today -> R.string.insights_chart_course_subtitle_today
                        InsightPeriod.Week -> R.string.insights_chart_course_subtitle_week
                        InsightPeriod.Month -> R.string.insights_chart_course_subtitle_month
                    }
                ),
                accent = accent
            )
            Spacer(Modifier.height(AevumSpacing.sm))

            // Wertefeld: Hinweis, solange nichts ausgewählt ist — sonst der
            // zugehörige Wert des angetippten Balkens.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = selected,
                    label = "trendValue"
                ) { index ->
                    val bucket = index?.let { buckets.getOrNull(it) }
                    if (bucket == null) {
                        Text(
                            text = stringResource(R.string.insights_chart_hint),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                bucket.detail,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "·",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                text = formatChartDuration(bucket.durationMs),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = selectedColor,
                                modifier = Modifier.testTag(INSIGHTS_TREND_VALUE_TEST_TAG)
                            )
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            if (buckets.isEmpty()) return@detectTapGestures
                            val slot = size.width.toFloat() / buckets.size
                            if (slot <= 0f) return@detectTapGestures
                            val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                            selectedIndex = if (selectedIndex == index) null else index
                        }
                    }
            ) {
                drawTrendBars(
                    buckets = buckets,
                    selected = selected,
                    selectionProgress = selectionProgress,
                    entryProgress = entry.value,
                    maxMs = maxMs,
                    accent = accent,
                    selectedColor = selectedColor,
                    gridColor = gridColor,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

/** Zeichen-Logik des Verlaufs — ausgelagert, damit [InsightsTrendChart] lesbar bleibt. */
private fun DrawScope.drawTrendBars(
    buckets: List<TrendBucket>,
    selected: Int?,
    selectionProgress: Float,
    entryProgress: Float,
    maxMs: Long,
    accent: Color,
    selectedColor: Color,
    gridColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val labelSpace = 16.dp.toPx()
    val topSpace = 4.dp.toPx()
    val chartHeight = (size.height - labelSpace - topSpace).coerceAtLeast(1f)
    val baseline = topSpace + chartHeight
    val maxBarHeight = chartHeight * 0.90f
    val slot = size.width / buckets.size
    val barWidth = (slot * 0.56f).coerceIn(3.dp.toPx(), 15.dp.toPx())

    // Drei ruhige Hilfslinien + Grundlinie.
    for (step in 1..3) {
        val y = baseline - maxBarHeight * (step / 3f)
        drawLine(
            color = gridColor.copy(alpha = 0.06f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx()
        )
    }
    drawLine(
        color = gridColor.copy(alpha = 0.12f),
        start = Offset(0f, baseline),
        end = Offset(size.width, baseline),
        strokeWidth = 1.dp.toPx()
    )

    val labelStyle = TextStyle(
        fontSize = 9.sp,
        color = gridColor.copy(alpha = 0.55f)
    )

    buckets.forEachIndexed { index, bucket ->
        val isSelected = selected == index
        val extra = if (isSelected) 0.10f * selectionProgress else 0f
        val normalized = bucket.durationMs.toFloat() / maxMs.toFloat()
        val barHeight = (maxBarHeight * normalized * entryProgress * (1f + extra))
            .coerceAtMost(maxBarHeight * 1.10f)
        val x = slot * index + (slot - barWidth) / 2f
        val top = baseline - barHeight

        if (isSelected && barHeight > 0.5f) {
            val glowPad = 2.dp.toPx()
            drawRoundRect(
                color = selectedColor.copy(alpha = 0.20f * selectionProgress),
                topLeft = Offset(x - glowPad, top - glowPad),
                size = Size(barWidth + glowPad * 2f, barHeight + glowPad * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
        if (barHeight > 0.5f) {
            val barColor = if (isSelected) selectedColor else accent
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        barColor.copy(alpha = 0.95f),
                        barColor.copy(alpha = 0.32f)
                    ),
                    startY = top,
                    endY = baseline
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        } else {
            // Null-Werte bleiben als kleiner Punkt sichtbar — so ist der Tag
            // nicht einfach "leer", sondern als erfasst-null erkennbar.
            drawCircle(
                color = gridColor.copy(alpha = 0.18f),
                radius = 1.5.dp.toPx(),
                center = Offset(slot * index + slot / 2f, baseline - 1.5.dp.toPx())
            )
        }

        // Achsen-Beschriftung: je nach Datenmenge ausdünnen.
        if (bucket.label.isNotBlank() && index % labelStep(buckets.size) == 0) {
            val layout = textMeasurer.measure(bucket.label, labelStyle)
            val centerX = slot * index + slot / 2f
            val left = (centerX - layout.size.width / 2f)
                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(left, size.height - layout.size.height)
            )
        }
    }
}

private fun labelStep(bucketCount: Int): Int = when {
    bucketCount <= 7 -> 1
    bucketCount <= 12 -> 2
    else -> 5
}

// ----------------------------------------------------------------------
// 3) Radar — Balance der Lebensbereiche
// ----------------------------------------------------------------------

/**
 * Radar-Diagramm der fünf Lebensbereiche aus [InsightsUiState.balance].
 *
 * Interaktion: Tippen auf eine Achse (oder den zugehörigen Chip) wählt den
 * Bereich aus. Der Punkt an der Achse wächst animiert, die Achse wird
 * hervorgehoben, und das Wertefeld über dem Diagramm zeigt Bereich, Dauer
 * und Anteil. Erneutes Tippen hebt die Auswahl auf.
 */
@Composable
internal fun InsightsBalanceChart(
    balance: List<BalanceSlice>,
    modifier: Modifier = Modifier
) {
    if (balance.isEmpty() || balance.all { it.durationMs <= 0L }) return
    var selectedIndex by remember(balance) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.takeIf { it in balance.indices }
    val accent = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.onSurface
    val maxMs = balance.maxOf { it.durationMs }.coerceAtLeast(1L)
    val textMeasurer = rememberTextMeasurer()

    val entry = remember { Animatable(0f) }
    LaunchedEffect(balance) {
        entry.snapTo(0f)
        entry.animateTo(1f, tween(durationMillis = 800, easing = FastOutSlowInEasing))
    }
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected != null) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "radarSelection"
    )

    GlassCard(
        modifier = modifier.testTag(INSIGHTS_RADAR_TEST_TAG),
        accentColor = accent
    ) {
        Column {
            ChartHeader(
                icon = "◈",
                title = stringResource(R.string.insights_chart_balance_title),
                subtitle = stringResource(R.string.insights_chart_balance_subtitle),
                accent = accent
            )
            Spacer(Modifier.height(AevumSpacing.sm))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = selected,
                    label = "radarValue"
                ) { index ->
                    val slice = index?.let { balance.getOrNull(it) }
                    if (slice == null) {
                        Text(
                            text = stringResource(R.string.insights_chart_hint),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                slice.area,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "·",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                text = "${formatChartDuration(slice.durationMs)} · ${slice.percent} %",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = slice.color,
                                modifier = Modifier.testTag(INSIGHTS_RADAR_VALUE_TEST_TAG)
                            )
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .pointerInput(balance) {
                        detectTapGestures { offset ->
                            if (balance.isEmpty()) return@detectTapGestures
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val dx = offset.x - center.x
                            val dy = offset.y - center.y
                            val distance = hypot(dx, dy)
                            val radius = minOf(size.width, size.height) / 2f - 30.dp.toPx()
                            if (distance > radius * 1.25f) {
                                selectedIndex = null
                                return@detectTapGestures
                            }
                            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                            angle = ((angle % 360.0) + 360.0) % 360.0
                            val step = 360.0 / balance.size
                            val index = (((angle + step / 2.0) / step).toInt()) % balance.size
                            selectedIndex = if (selectedIndex == index) null else index
                        }
                    }
            ) {
                drawBalanceRadar(
                    balance = balance,
                    selected = selected,
                    selectionProgress = selectionProgress,
                    entryProgress = entry.value,
                    maxMs = maxMs,
                    accent = accent,
                    gridColor = gridColor,
                    textMeasurer = textMeasurer
                )
            }
            Spacer(Modifier.height(AevumSpacing.xs))

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
            ) {
                balance.forEachIndexed { index, slice ->
                    val isSelected = selected == index
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(
                                if (isSelected) slice.color.copy(alpha = 0.20f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                            .clickable {
                                selectedIndex = if (selectedIndex == index) null else index
                            }
                            .testTag(insightsRadarChipTestTag(index))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(slice.color)
                        )
                        Text(
                            slice.area,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            "${slice.percent} %",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/** Zeichen-Logik des Radars — Achsen, Gitter, Datenfläche, Labels. */
private fun DrawScope.drawBalanceRadar(
    balance: List<BalanceSlice>,
    selected: Int?,
    selectionProgress: Float,
    entryProgress: Float,
    maxMs: Long,
    accent: Color,
    gridColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val labelGap = 30.dp.toPx()
    val radius = (minOf(size.width, size.height) / 2f - labelGap).coerceAtLeast(1f)
    val axisCount = balance.size

    fun point(index: Int, fraction: Float): Offset {
        val angle = -PI / 2.0 + 2.0 * PI * index / axisCount
        return Offset(
            center.x + cos(angle).toFloat() * radius * fraction,
            center.y + sin(angle).toFloat() * radius * fraction
        )
    }

    // Gitter: drei konzentrische Polygone.
    for (ring in 1..3) {
        val fraction = ring / 3f
        val path = Path()
        for (index in 0 until axisCount) {
            val p = point(index, fraction)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(
            path = path,
            color = gridColor.copy(alpha = 0.07f),
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // Achsen — die ausgewählte wird dicker.
    for (index in 0 until axisCount) {
        val isSelected = selected == index
        val end = point(index, 1f)
        drawLine(
            color = if (isSelected) accent.copy(alpha = 0.55f * selectionProgress + 0.15f)
            else gridColor.copy(alpha = 0.10f),
            start = center,
            end = end,
            strokeWidth = if (isSelected) 1.8.dp.toPx() else 1.dp.toPx()
        )
    }

    // Glow des ausgewählten Punkts VOR der Datenfläche: bei kleinen Werten
    // liegen benachbarte Punkte nah am Zentrum — ein nachträglicher Glow
    // würde die kurze Verbindungskante verdecken.
    selected?.let { index ->
        val slice = balance.getOrNull(index) ?: return@let
        val fraction = (slice.durationMs.toFloat() / maxMs.toFloat()) * entryProgress
        val p = point(index, fraction)
        drawCircle(
            color = slice.color.copy(alpha = 0.20f * selectionProgress),
            radius = (10.dp.toPx() * selectionProgress).coerceAtLeast(1f),
            center = p
        )
    }

    // Datenfläche.
    val dataPath = Path()
    balance.forEachIndexed { index, slice ->
        val fraction = (slice.durationMs.toFloat() / maxMs.toFloat()) * entryProgress
        val p = point(index, fraction)
        if (index == 0) dataPath.moveTo(p.x, p.y) else dataPath.lineTo(p.x, p.y)
    }
    dataPath.close()
    drawPath(path = dataPath, color = accent.copy(alpha = 0.16f))
    drawPath(
        path = dataPath,
        color = accent.copy(alpha = 0.85f),
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    )

    // Punkte — der ausgewählte wächst animiert und bekommt einen Glow.
    balance.forEachIndexed { index, slice ->
        val fraction = (slice.durationMs.toFloat() / maxMs.toFloat()) * entryProgress
        val p = point(index, fraction)
        val isSelected = selected == index
        drawCircle(
            color = slice.color.copy(alpha = if (isSelected) 1f else 0.85f),
            radius = (4.dp.toPx() + 3.dp.toPx() * if (isSelected) selectionProgress else 0f),
            center = p
        )
    }

    // Achsen-Beschriftungen. Sie werden gemessen und an den Rand geklemmt,
    // damit lange Wörter (z.B. "Soziales") nicht aus dem Canvas laufen.
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = gridColor.copy(alpha = 0.75f),
        fontWeight = FontWeight.Medium
    )
    balance.forEachIndexed { index, slice ->
        val anchor = point(index, 1f)
        val outward = Offset(
            center.x + (anchor.x - center.x) / radius * (radius + 14.dp.toPx()),
            center.y + (anchor.y - center.y) / radius * (radius + 14.dp.toPx())
        )
        val layout = textMeasurer.measure(
            slice.area,
            if (selected == index) labelStyle.copy(color = slice.color, fontWeight = FontWeight.Bold)
            else labelStyle
        )
        val left = (outward.x - layout.size.width / 2f)
            .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
        val top = (outward.y - layout.size.height / 2f)
            .coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f))
        drawText(textLayoutResult = layout, topLeft = Offset(left, top))
    }
}
