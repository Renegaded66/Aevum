package com.d_drostes_apps.aevum.ui.screens.addiction

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.apps.InstalledApp
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import com.d_drostes_apps.aevum.domain.addiction.AddictionAnalytics
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.components.EmptyState
import com.d_drostes_apps.aevum.ui.components.ProgressRing
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * M27: Sucht-Detektor — dritte Seite im Balance-Tab.
 *
 * Bewusst als eigene, scrollbare Seite statt als weitere Karte in der
 * schon langen Balance-Liste: Die Auswertung braucht Platz für Heute-Karte,
 * Verlauf, App-Vergleich und Sitzungsdetails.
 */
@Composable
fun AddictionDetectorPage(
    viewModel: AddictionDetectorViewModel,
    // M28: true, solange die Detektor-Seite im Balance-Pager wirklich
    // sichtbar ist. Jedes erneute Anzeigen liest den aktuellen Tag neu
    // (User: „bei jedem neuen öffnen aktualisieren mit den neuen Daten des
    // aktuellen Tages"). Vorher lief `LaunchedEffect(Unit)` nur einmal beim
    // ersten Zusammensetzen — ein späteres Öffnen bekam keine frische Runde.
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Nur solange die Detektor-Seite sichtbar ist: beim Öffnen sofort und
    // danach jede Minute den Collector nachfassen lassen. Außerhalb dieser
    // Seite übernimmt der 30-Minuten-Worker.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        viewModel.refresh()
        while (true) {
            delay(60_000)
            viewModel.refresh()
        }
    }

    AddictionDetectorContent(
        state = state,
        onRangeChange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onLoadInstalledApps = { viewModel.loadInstalledApps() },
        onAddApps = viewModel::addApps,
        onRemoveApp = viewModel::removeApp,
        modifier = modifier
    )
}

/**
 * Zustandsgetriebener Inhalt — ohne ViewModel, damit die echte Seite in
 * einem Robolectric-Render-Test als PNG geprüft werden kann.
 */
@Composable
internal fun AddictionDetectorContent(
    state: AddictionDetectorUiState,
    onRangeChange: (AddictionAnalytics.Range) -> Unit,
    onRefresh: () -> Unit,
    onLoadInstalledApps: () -> Unit,
    onAddApps: (List<InstalledApp>) -> Unit,
    onRemoveApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<AddictionWatchApp?>(null) }
    val zoneId = remember { ZoneId.systemDefault() }

    if (!state.hasPermission) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.addiction_no_permission),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (state.loading || state.analysis == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val analysis = state.analysis ?: return

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AevumSpacing.md,
            end = AevumSpacing.md,
            top = AevumSpacing.sm,
            bottom = AevumSpacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item {
            DetectorHeader(
                watchedCount = state.watchedApps.size,
                onAdd = { showAddSheet = true; onLoadInstalledApps() },
                onRefresh = onRefresh,
                onInfo = { showInfo = true }
            )
        }

        if (state.watchedApps.isEmpty()) {
            item {
                DetectorEmptyState(
                    suggestions = suggestedApps(state.availableApps),
                    scanning = state.scanning,
                    onAdd = { showAddSheet = true; onLoadInstalledApps() },
                    onQuickAdd = { app -> onAddApps(listOf(app)) }
                )
            }
        } else {
            item {
                TodayHeroCard(analysis = analysis)
            }
            item {
                TrendCard(
                    analysis = analysis,
                    onRangeChange = onRangeChange
                )
            }
            item {
                HourlyCard(analysis = analysis)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.addiction_apps_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.addiction_apps_subtitle,
                            rangeLabel(analysis.range)
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val iconMap = state.availableApps.associateBy { it.packageName }
            val todayMap = analysis.todayApps.associateBy { it.packageName }
            items(analysis.appStats, key = { it.packageName }) { app ->
                AppStatCard(
                    app = app,
                    today = todayMap[app.packageName],
                    sessions = analysis.todaySessions[app.packageName].orEmpty(),
                    summaryDays = analysis.summary.days.coerceAtLeast(1),
                    icon = iconMap[app.packageName]?.icon,
                    zoneId = zoneId,
                    onRemove = {
                        removeTarget = state.watchedApps.firstOrNull { it.packageName == app.packageName }
                    }
                )
            }
            item {
                Text(
                    stringResource(R.string.addiction_sync_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = AevumSpacing.xs)
                )
            }
        }
    }

    if (showAddSheet) {
        AddAppsSheet(
            available = state.availableApps,
            watchedPackages = state.watchedApps.map { it.packageName }.toSet(),
            scanning = state.scanning,
            onDismiss = { showAddSheet = false },
            onAdd = { apps ->
                onAddApps(apps)
                showAddSheet = false
            }
        )
    }

    if (showInfo) {
        DetectorInfoDialog(onDismiss = { showInfo = false })
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.addiction_remove_title, target.appLabel)) },
            text = { Text(stringResource(R.string.addiction_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveApp(target.packageName)
                    removeTarget = null
                }) { Text(stringResource(R.string.addiction_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ── Kopf ─────────────────────────────────────────────────────────────────

@Composable
private fun DetectorHeader(
    watchedCount: Int,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onInfo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.addiction_page_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (watchedCount > 0) {
                    stringResource(R.string.addiction_tracked_count, watchedCount)
                } else {
                    stringResource(R.string.addiction_subtitle)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onInfo, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = stringResource(R.string.addiction_info),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.addiction_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.addiction_add_apps),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ── Heute ────────────────────────────────────────────────────────────────

@Composable
private fun TodayHeroCard(analysis: AddictionAnalytics.Result) {
    val colors = MaterialTheme.colorScheme
    val scoreColor = when {
        !analysis.scoreAvailable -> colors.onSurfaceVariant
        analysis.score <= AddictionAnalytics.SCORE_LOW_MAX -> colors.primary
        analysis.score < AddictionAnalytics.SCORE_HIGH_MIN -> colors.tertiary
        else -> colors.error
    }
    val scoreLabel = when {
        !analysis.scoreAvailable -> stringResource(R.string.addiction_pressure_no_data)
        analysis.score <= AddictionAnalytics.SCORE_LOW_MAX -> stringResource(R.string.addiction_pressure_low)
        analysis.score < AddictionAnalytics.SCORE_HIGH_MIN -> stringResource(R.string.addiction_pressure_mid)
        else -> stringResource(R.string.addiction_pressure_high)
    }

    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.addiction_today),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = colors.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.addiction_since_midnight, analysis.trackedAppCount),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        formatDurationZero(analysis.today.totalMs),
                        fontSize = 42.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurface
                    )
                    Text(
                        if (analysis.today.totalMs <= 0L) {
                            stringResource(R.string.addiction_no_use_today)
                        } else {
                            stringResource(R.string.addiction_opens) + " · " +
                                analysis.today.opens + "×"
                        },
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ProgressRing(
                        progress = if (analysis.scoreAvailable) analysis.score / 100f else 0f,
                        size = 96.dp,
                        strokeWidth = 9.dp,
                        progressColor = scoreColor,
                        valueText = if (analysis.scoreAvailable) "${analysis.score}" else "–"
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.addiction_pressure),
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        scoreLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scoreColor
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                HeroMetric(
                    label = stringResource(R.string.addiction_opens),
                    value = "${analysis.today.opens}×",
                    modifier = Modifier.weight(1f)
                )
                HeroMetric(
                    label = stringResource(R.string.addiction_longest),
                    value = formatDurationZero(analysis.today.longestSessionMs),
                    modifier = Modifier.weight(1f)
                )
                HeroMetric(
                    label = stringResource(R.string.addiction_avg_session),
                    value = if (analysis.today.opens > 0) {
                        formatDurationZero(analysis.today.totalMs / analysis.today.opens)
                    } else "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Verlauf ─────────────────────────────────────────────────────────────

private enum class ChartMetric { TIME, OPENS }

@Composable
private fun TrendCard(
    analysis: AddictionAnalytics.Result,
    onRangeChange: (AddictionAnalytics.Range) -> Unit
) {
    var metric by remember { mutableStateOf(ChartMetric.TIME) }
    val colors = MaterialTheme.colorScheme

    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.addiction_trend_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        rangeLabel(analysis.range),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartMetric.values().forEach { value ->
                        val selected = metric == value
                        FilterChip(
                            selected = selected,
                            onClick = { metric = value },
                            label = {
                                Text(
                                    stringResource(
                                        if (value == ChartMetric.TIME) R.string.addiction_metric_time
                                        else R.string.addiction_metric_opens
                                    ),
                                    fontSize = 10.sp
                                )
                            }
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AddictionAnalytics.Range.entries.forEach { range ->
                    FilterChip(
                        selected = analysis.range == range,
                        onClick = { onRangeChange(range) },
                        label = { Text(rangeShortLabel(range), fontSize = 10.sp) }
                    )
                }
            }

            TrendBars(
                points = analysis.trend,
                range = analysis.range,
                metric = metric
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                SummaryMetric(
                    label = stringResource(R.string.addiction_avg_per_day),
                    value = if (metric == ChartMetric.TIME) {
                        formatDurationZero(analysis.summary.avgPerDayMs)
                    } else {
                        "%.1f×".format(
                            if (analysis.summary.days > 0) {
                                analysis.summary.opens.toFloat() / analysis.summary.days
                            } else 0f
                        )
                    },
                    trend = percentChange(
                        current = if (metric == ChartMetric.TIME) {
                            analysis.summary.avgPerDayMs.toFloat()
                        } else {
                            if (analysis.summary.days > 0) analysis.summary.opens.toFloat() / analysis.summary.days else 0f
                        },
                        previous = if (metric == ChartMetric.TIME) {
                            analysis.previousSummary.avgPerDayMs.toFloat()
                        } else {
                            if (analysis.previousSummary.days > 0) {
                                analysis.previousSummary.opens.toFloat() / analysis.previousSummary.days
                            } else 0f
                        }
                    ),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.addiction_total),
                    value = if (metric == ChartMetric.TIME) {
                        formatDurationZero(analysis.summary.totalMs)
                    } else {
                        "${analysis.summary.opens}×"
                    },
                    trend = percentChange(
                        current = if (metric == ChartMetric.TIME) analysis.summary.totalMs.toFloat()
                        else analysis.summary.opens.toFloat(),
                        previous = if (metric == ChartMetric.TIME) analysis.previousSummary.totalMs.toFloat()
                        else analysis.previousSummary.opens.toFloat()
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                stringResource(R.string.addiction_period_note),
                fontSize = 11.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun TrendBars(
    points: List<AddictionAnalytics.TrendPoint>,
    range: AddictionAnalytics.Range,
    metric: ChartMetric
) {
    val values = points.map {
        if (metric == ChartMetric.TIME) it.totalMs.toFloat() else it.opens.toFloat()
    }
    val maxValue = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val colors = MaterialTheme.colorScheme

    if (points.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.addiction_pressure_no_data),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(156.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { point ->
            val raw = if (metric == ChartMetric.TIME) point.totalMs.toFloat() else point.opens.toFloat()
            val fraction = (raw / maxValue).coerceIn(0f, 1f)
            val animated by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(500),
                label = "trend-bar"
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    compactValue(raw, metric),
                    fontSize = 8.sp,
                    maxLines = 1,
                    color = colors.onSurfaceVariant
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.58f)
                            .fillMaxHeight(animated.coerceAtLeast(0.012f))
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(
                                if (metric == ChartMetric.TIME) {
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(colors.primary, colors.primary.copy(alpha = 0.42f))
                                    )
                                } else {
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(colors.tertiary, colors.tertiary.copy(alpha = 0.42f))
                                    )
                                }
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    pointLabel(point, range),
                    fontSize = 9.sp,
                    maxLines = 1,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    trend: Float?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            if (trend != null) trendLabel(trend) else stringResource(R.string.addiction_no_previous),
            fontSize = 10.sp,
            color = trendColor(trend)
        )
    }
}

// ── Uhrzeit ──────────────────────────────────────────────────────────────

@Composable
private fun HourlyCard(analysis: AddictionAnalytics.Result) {
    val max = analysis.hourlyTodayMs.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val colors = MaterialTheme.colorScheme
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Column {
                Text(
                    stringResource(R.string.addiction_hourly_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.addiction_hourly_subtitle),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(76.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                analysis.hourlyTodayMs.forEachIndexed { hour, ms ->
                    val fraction = (ms.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .fillMaxHeight(fraction.coerceAtLeast(if (ms > 0) 0.04f else 0f))
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(
                                        if (hour >= 22 || hour < 6) colors.error.copy(alpha = 0.75f)
                                        else colors.primary.copy(alpha = 0.75f)
                                    )
                            )
                        }
                        Text(
                            if (hour % 6 == 0) "$hour" else "",
                            fontSize = 8.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── App-Karten ───────────────────────────────────────────────────────────

@Composable
private fun AppStatCard(
    app: AddictionAnalytics.AppStat,
    today: AddictionAnalytics.AppStat?,
    sessions: List<AddictionAnalytics.SessionRow>,
    summaryDays: Int,
    icon: ImageBitmap?,
    zoneId: ZoneId,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val todayMs = today?.totalMs ?: 0L
    val todayOpens = today?.opens ?: 0

    AevumCard(
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(AevumSpacing.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon, app.appLabel)
                Spacer(Modifier.width(AevumSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.appLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.addiction_app_today, formatDurationZero(todayMs), todayOpens),
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatDurationZero(app.totalMs),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        stringResource(R.string.addiction_opens_count, app.opens),
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedGradientBar(
                progress = app.share,
                color = colors.primary,
                trackColor = colors.surfaceVariant.copy(alpha = 0.45f),
                height = 8.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.addiction_share,
                        (app.share * 100).roundToInt()
                    ),
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant
                )
                Text(
                    trendLabel(app.trendPercent),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (app.trendPercent == null) colors.primary
                    else trendColor(app.trendPercent)
                )
            }

            if (expanded) {
                androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    DetailMetric(
                        label = stringResource(R.string.addiction_day_avg),
                        value = formatDurationZero(app.totalMs / summaryDays.coerceAtLeast(1)),
                        modifier = Modifier.weight(1f)
                    )
                    DetailMetric(
                        label = stringResource(R.string.addiction_avg_session),
                        value = if (app.opens > 0) formatDurationZero(app.avgSessionMs) else "—",
                        modifier = Modifier.weight(1f)
                    )
                    DetailMetric(
                        label = stringResource(R.string.addiction_longest),
                        value = formatDurationZero(app.longestSessionMs),
                        modifier = Modifier.weight(1f)
                    )
                    DetailMetric(
                        label = stringResource(R.string.addiction_opens),
                        value = "${app.opens}×",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    stringResource(R.string.addiction_sessions_today),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant
                )
                if (sessions.isEmpty()) {
                    Text(
                        stringResource(R.string.addiction_no_sessions_today),
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sessions.take(10).forEach { session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(AevumRadius.sm))
                                    .background(colors.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    AddictionAnalytics.formatTime(session.startAt, zoneId),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatDurationZero(session.durationMs),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface
                                )
                            }
                        }
                        if (sessions.size > 10) {
                            Text(
                                stringResource(R.string.addiction_more_sessions, sessions.size - 10),
                                fontSize = 10.sp,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
                TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.End)) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.addiction_remove), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, label: String) {
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("📱", fontSize = 18.sp)
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        Text(
            label,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Leerzustand + Vorschläge ─────────────────────────────────────────────

private val SUGGESTED_PACKAGES = listOf(
    "com.instagram.android",
    "com.zhiliaoapp.musically",
    "com.google.android.youtube",
    "com.snapchat.android",
    "com.facebook.katana",
    "com.twitter.android",
    "com.reddit.frontpage",
    "com.netflix.mediaclient"
)

private fun suggestedApps(apps: List<InstalledApp>): List<InstalledApp> =
    SUGGESTED_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }

@Composable
private fun DetectorEmptyState(
    suggestions: List<InstalledApp>,
    scanning: Boolean,
    onAdd: () -> Unit,
    onQuickAdd: (InstalledApp) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
        EmptyState(
            icon = { Text("📱", fontSize = 30.sp) },
            title = stringResource(R.string.addiction_empty_title),
            message = stringResource(R.string.addiction_empty_body),
            actionLabel = stringResource(R.string.addiction_empty_cta),
            onActionClick = onAdd,
            messageMaxLines = 10
        )
        if (suggestions.isNotEmpty()) {
            AevumCard(variant = CardVariant.Filled) {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text(
                        stringResource(R.string.addiction_suggestions),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    suggestions.forEach { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AevumRadius.md))
                                .clickable { onQuickAdd(app) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.icon, app.label)
                            Spacer(Modifier.width(AevumSpacing.sm))
                            Text(
                                app.label,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.addiction_add_apps),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        } else if (scanning) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// ── App-Auswahl ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppsSheet(
    available: List<InstalledApp>,
    watchedPackages: Set<String>,
    scanning: Boolean,
    onDismiss: () -> Unit,
    onAdd: (List<InstalledApp>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    val filtered = remember(available, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        available
            .filter { q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md)
                .padding(bottom = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text(
                stringResource(R.string.addiction_add_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.addiction_search)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            if (scanning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val already = app.packageName in watchedPackages
                    val checked = already || app.packageName in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .clickable(enabled = !already) {
                                if (app.packageName in selected) {
                                    selected.remove(app.packageName)
                                } else {
                                    selected.add(app.packageName)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            enabled = !already
                        )
                        Spacer(Modifier.width(AevumSpacing.sm))
                        AppIcon(app.icon, app.label)
                        Spacer(Modifier.width(AevumSpacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                app.label,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (already) {
                                Text(
                                    stringResource(R.string.addiction_already_tracked),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (filtered.isEmpty() && !scanning) {
                    item {
                        Text(
                            stringResource(R.string.addiction_no_apps_found),
                            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Button(
                onClick = {
                    val apps = selected.mapNotNull { pkg -> available.firstOrNull { it.packageName == pkg } }
                    onAdd(apps)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.addiction_add_selected, selected.size))
            }
        }
    }
}

// ── Info ─────────────────────────────────────────────────────────────────

@Composable
private fun DetectorInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.addiction_info_title)) },
        text = {
            Text(
                stringResource(R.string.addiction_info_body),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

// ── Formatierung ─────────────────────────────────────────────────────────

private fun formatDurationZero(ms: Long): String =
    if (ms <= 0L) "0m" else AddictionAnalytics.formatDuration(ms)

private fun compactValue(value: Float, metric: ChartMetric): String = when (metric) {
    ChartMetric.TIME -> {
        val minutes = (value / 60_000f).roundToInt()
        if (minutes >= 60) "%.1fh".format(Locale.getDefault(), minutes / 60f)
        else "${minutes}m"
    }
    ChartMetric.OPENS -> "${value.roundToInt()}"
}

@Composable
private fun rangeLabel(range: AddictionAnalytics.Range): String = when (range) {
    AddictionAnalytics.Range.DAYS_7 -> stringResource(R.string.addiction_trend_days)
    AddictionAnalytics.Range.WEEKS_4 -> stringResource(R.string.addiction_trend_weeks)
    AddictionAnalytics.Range.MONTHS_6 -> stringResource(R.string.addiction_trend_months)
}

@Composable
private fun rangeShortLabel(range: AddictionAnalytics.Range): String = range.id

private fun pointLabel(point: AddictionAnalytics.TrendPoint, range: AddictionAnalytics.Range): String =
    when (range) {
        AddictionAnalytics.Range.DAYS_7 ->
            point.startDate.format(DateTimeFormatter.ofPattern("EE", Locale.getDefault()))
        AddictionAnalytics.Range.WEEKS_4 -> {
            val week = point.startDate.get(WeekFields.ISO.weekOfWeekBasedYear())
            "KW $week"
        }
        AddictionAnalytics.Range.MONTHS_6 ->
            point.startDate.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
    }

private fun percentChange(current: Float, previous: Float): Float? {
    if (previous <= 0f) return if (current > 0f) null else 0f
    return ((current - previous) / previous) * 100f
}

@Composable
private fun trendLabel(trend: Float?): String {
    if (trend == null) return stringResource(R.string.addiction_new)
    val sign = if (trend > 0f) "+" else ""
    return "$sign${trend.roundToInt()} % ${stringResource(R.string.addiction_vs_prev)}"
}

@Composable
private fun trendColor(trend: Float?): Color = when {
    trend == null -> MaterialTheme.colorScheme.onSurfaceVariant
    trend > 3f -> MaterialTheme.colorScheme.error
    trend < -3f -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
