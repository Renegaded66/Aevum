package com.d_drostes_apps.aevum.ui.screens.digitalbalance

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.AppLimit
import com.d_drostes_apps.aevum.data.model.BalanceProfile
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * M18.61: Digital Balance — ersetzt den Kalender-Tab.
 *
 *  - Heute-Übersicht: Gesamt-Bildschirmzeit, Top-App, gesperrte Apps
 *  - 7/30-Tage-Umschalter: Balken pro Tag + Durchschnitt
 *  - App-Liste: Nutzung heute + Zeitraum, Limit-Slider, Sperr-Schalter,
 *    Ausnahmen (immer erlauben / Zeitfenster)
 */
@Composable
fun DigitalBalanceScreen(
    viewModel: DigitalBalanceViewModel,
    // M18.67: Navigation zur App-Aufzeichnung (Button oben)
    onOpenAppTracking: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // M18.61e-FIX: Nach Rückkehr aus den System-Settings (Permission
    // erteilt) sofort neu prüfen. Vorher blieb die PermissionCard
    // dauerhaft stehen, bis man manuell refreshte.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!state.hasPermission) {
        PermissionCard(onOpenSettings = viewModel::openUsageAccessSettings)
        return
    }

    // M18.61f: Zwei Seiten — Balance & Pomodoro — per Swipe (HorizontalPager).
    // Oben ein dezenter Seiten-Indikator, damit klar ist, dass man swipen kann.
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.xs),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                stringResource(R.string.balance_tab_balance),
                stringResource(R.string.balance_tab_pomodoro)
            ).forEachIndexed { index, label ->
                val selected = pagerState.currentPage == index
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
                BalancePage(state, viewModel, onOpenAppTracking)
            } else {
                PomodoroPage(viewModel)
            }
        }
    }
}

@Composable
private fun BalancePage(
    state: DigitalBalanceUiState,
    viewModel: DigitalBalanceViewModel,
    // M18.67: App-Aufzeichnung-Button
    onOpenAppTracking: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // M18.62: Oben eine dezente Möglichkeit, den Nutzungszugriff zu
        // widerrufen (Akku sparen, wenn Digital Balance nicht benötigt wird).
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.balance_usage_access_active),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // M18.67: App-Aufzeichnung — Button oben (User: "Einfach
                    // oben einen Button hinzufügen")
                    TextButton(onClick = onOpenAppTracking) {
                        Text(stringResource(R.string.apptracking_title), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = viewModel::revokeUsageAccess) {
                        Text(stringResource(R.string.balance_revoke), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { TodayHeroCard(state, onRefresh = viewModel::refresh) }
        item {
            RangeStatsCard(
                state = state,
                onRangeChange = viewModel::setRangeDays,
                onDayClick = viewModel::selectDay
            )
        }
        // M18.61f: Profile-Karte (Lern-Profil sperrt Social Media)
        item { ProfilesCard(viewModel) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.balance_apps_header), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // M18.61g: Dezente Sortier-Buttons (Icons) —
                    // alphabetisch (A-Z) oder absteigend nach Nutzung
                    IconButton(
                        onClick = { viewModel.setSortMode("alpha") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SortByAlpha,
                            contentDescription = stringResource(R.string.balance_sort_alpha),
                            tint = if (state.sortMode == "alpha") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setSortMode("usage") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = stringResource(R.string.balance_sort_usage),
                            tint = if (state.sortMode == "usage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.blockedCount > 0) {
                        Text(
                            stringResource(R.string.balance_blocked_count, state.blockedCount),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        // M18.62: App-Karten mit kaskadenartiger Einblend-Animation
        // (Fade + Slide von unten) und animierter Platzierung beim
        // Sortieren/Neuanordnen.
        itemsIndexed(state.apps, key = { _, app -> app.packageName }) { _, app ->
            AnimatedAppCard(
                app = app,
                dayTotalMs = state.selectedDayTotalMs,
                onLimitChange = { minutes, enabled ->
                    viewModel.setLimit(app.packageName, minutes, enabled)
                },
                onException = { type, start, end ->
                    viewModel.setException(app.packageName, type, start, end)
                },
                onRemove = { viewModel.removeLimit(app.packageName) }
            )
        }
        item { Spacer(Modifier.height(AevumSpacing.lg)) }
    }
}

@Composable
private fun formatDay(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.common_today)
        today.minusDays(1) -> stringResource(R.string.common_yesterday)
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d.M.", java.util.Locale.getDefault()))
    }
}

/**
 * M18.62: App-Karte mit Einblend-Animation. Beim ersten Erscheinen
 * gleitet sie mit Fade von unten ein (kaskadenartig, 60ms × Index),
 * beim Sortieren/Neuanordnen animiert die Platzierung.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnimatedAppCard(
    app: DigitalAppUi,
    dayTotalMs: Long,
    onLimitChange: (Int, Boolean) -> Unit,
    onException: (String, Int, Int) -> Unit,
    onRemove: () -> Unit
) {
    var appeared by remember { mutableStateOf(false) }
    // Performance-Fix: Keine Kaskaden-Delays mehr. LazyColumn disposed
    // Items beim Rausscrollen — mit delay(index*60) startete die Kaskade
    // bei jedem Re-Enter neu (bei 30 Apps bis zu 1,8s Verzögerung) → Ruckeln.
    LaunchedEffect(Unit) {
        appeared = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(250),
        label = "appCardAlpha"
    )
    val offsetY by animateDpAsState(
        targetValue = if (appeared) 0.dp else 24.dp,
        animationSpec = tween(250),
        label = "appCardOffset"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .graphicsLayer { translationY = offsetY.toPx() }
    ) {
        AppLimitCard(
            app = app,
            dayTotalMs = dayTotalMs,
            onLimitChange = onLimitChange,
            onException = onException,
            onRemove = onRemove
        )
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AevumSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AevumCard(variant = CardVariant.Gradient) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
            ) {
                Text("📊", fontSize = 48.sp)
                Text(
                    stringResource(R.string.balance_permission_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    // M18.93v8 (Reciprocity — Video 2TlIg3VokY8): Statt nur
                    // „braucht Zugriff" (Verlangen ohne Gegenwert) zeigt die
                    // Karte ZUERST, was der User bekommt: Limits, die ihn
                    // schützen, und Verläufe, die sonst keiner sieht. Wer
                    // zuerst etwas bekommt (Einblick), gibt leichter zurück
                    // (Zustimmung) — Cialdinis mächtigstes Prinzip.
                    stringResource(R.string.balance_permission_desc_rec),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.balance_permission_grant))
                }
            }
        }
    }
}

@Composable
private fun TodayHeroCard(
    state: DigitalBalanceUiState,
    onRefresh: () -> Unit
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.common_today), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.balance_refresh), modifier = Modifier.size(16.dp))
                }
            }

            // M18.61: Ring-Diagramm (Google-Digital-Wellbeing-Muster) —
            // Füllung = verbrauchte Zeit vs. Tagesziel (5h), Mitte = Zeit.
            // M18.62: Sweep-Angle animiert (Spring bouncy) + zentrale Zeit
            // zählt animiert hoch.
            val goalMs = state.dailyGoalMs
            val progress = (state.todayTotalMs.toFloat() / goalMs).coerceIn(0f, 1f)
            val ringColor = when {
                progress >= 1f -> MaterialTheme.colorScheme.error
                progress >= 0.8f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            // Farben vor dem Canvas-DrawScope extrahieren (Compose-Regel:
            // @Composable-Zugriffe nur im Composable-Kontext)
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            // M18.62: Ring füllt sich animiert — tween statt bouncy Spring
            // (Spring sprang bei jedem Refresh/Recomposition neu → Haken).
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(400),
                label = "ringProgress"
            )
            // M18.62: Zentrale Zeit zählt von 0 auf den Zielwert (600ms)
            var shownMs by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(state.todayTotalMs) {
                val start = shownMs
                val diff = state.todayTotalMs - start
                val steps = 30
                for (i in 1..steps) {
                    shownMs = start + diff * (i / steps.toFloat())
                    delay(20)
                }
                shownMs = state.todayTotalMs.toFloat()
            }
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DigitalBalanceViewModel.formatDuration(shownMs.toLong()),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        // M18.93v8 (Anchoring + Goal Gradient — Video 2TlIg3VokY8):
                        // Der Ø-7-Tage-Wert ist der Vergleichsanker: „von 5h Ziel ·
                        // Ø 3h 10m" zeigt relativ, wie der heutige Tag im Schnitt
                        // liegt (Contrast-Effekt) und wie viel zum Ziel fehlt —
                        // statt einer isolierten Zahl, die niemand einordnen kann.
                        buildString {
                            append(stringResource(R.string.balance_of_goal, DigitalBalanceViewModel.formatDuration(goalMs)))
                            val pastTotals = state.dailyTotals
                                .filter { it.first != java.time.LocalDate.now() }
                                .map { it.second }
                            if (pastTotals.isNotEmpty()) {
                                val avg = pastTotals.sum() / pastTotals.size
                                if (avg > 0L) {
                                    append(" · Ø ")
                                    append(DigitalBalanceViewModel.formatDuration(avg))
                                }
                            }
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Drei Metriken nebeneinander (Google-Muster)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HeroMetric(stringResource(R.string.balance_metric_apps), state.todayAppCount.toString())
                HeroMetric(stringResource(R.string.balance_metric_unlocks), state.unlockCount.toString())
                HeroMetric(stringResource(R.string.balance_metric_top), state.topAppName?.take(8) ?: "—")
            }

            // M18.61: 24-Stunden-Breakdown (Google-Muster: "ablenkend
            // zwischen 14–16 Uhr" erkennen)
            // M18.62: Balken wachsen kaskadenartig (40ms × Stunde) mit
            // Spring-Animation und Gradient-Füllung.
            val maxHour = (state.hourlyMs.maxOrNull() ?: 0L).coerceAtLeast(1L)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                state.hourlyMs.forEachIndexed { hour, ms ->
                    val frac = ms.toFloat() / maxHour
                    // Performance-Fix: Kein Kaskaden-Delay (startete bei
                    // jedem Scroll-Re-Enter neu), kein bouncy Spring.
                    var hourVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        hourVisible = true
                    }
                    val animatedFrac by animateFloatAsState(
                        targetValue = if (hourVisible) frac else 0f,
                        animationSpec = tween(300),
                        label = "hourBar$hour"
                    )
                    val barHeight = (animatedFrac * 40f).coerceAtLeast(if (ms > 0) 3f else 1.5f)
                    val barBrush = if (ms > 0) {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(barBrush)
                    )
                }
            }
            Text(
                stringResource(R.string.balance_hourly_label),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RangeStatsCard(
    state: DigitalBalanceUiState,
    onRangeChange: (Int) -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.balance_history), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 30).forEach { days ->
                        FilterChip(
                            selected = state.rangeDays == days,
                            onClick = { onRangeChange(days) },
                            label = { Text(stringResource(R.string.balance_days, days), fontSize = 12.sp) }
                        )
                    }
                }
            }

            // M18.62: Balken-Diagramm — jeder Balken ist ein Tag und
            // antippbar. Die App-Liste darunter zeigt dann die Werte
            // des angeklickten Tages. Der gewählte Balken wird hervorgehoben.
            // M18.62: Balken wachsen kaskadenartig (50ms × Tag) mit
            // Spring-Animation; der gewählte Tag pulsiert dezent.
            val totals = state.dailyTotals
            val maxMs = (totals.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            val today = LocalDate.now()
            val selected = state.selectedDay

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                totals.forEachIndexed { index, (date, ms) ->
                    val fraction = ms.toFloat() / maxMs
                    val isToday = date == today
                    val isSelected = date == selected
                    val barColor = when {
                        isSelected -> MaterialTheme.colorScheme.tertiary
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    }
                    // Performance-Fix: Kein Kaskaden-Delay (startete bei
                    // jedem Scroll-Re-Enter neu), kein bouncy Spring.
                    // Vorher: 30× rememberInfiniteTransition (Puls) liefen
                    // PERMANENT — auch für nicht-selektierte Balken → Ruckeln.
                    var barVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        barVisible = true
                    }
                    val animatedFrac by animateFloatAsState(
                        targetValue = if (barVisible) fraction else 0f,
                        animationSpec = tween(300),
                        label = "dayBar$index"
                    )
                    val barHeight = (animatedFrac * 72f).coerceAtLeast(if (ms > 0) 4f else 2f)
                    // M18.62: Gewählter Tag pulsiert dezent — NUR der
                    // selektierte Balken, und nur solange er selektiert ist.
                    val pulseAlpha = if (isSelected) {
                        val infiniteTransition = rememberInfiniteTransition(label = "selectedPulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                            label = "pulseAlpha"
                        )
                        alpha
                    } else 1f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                if (isSelected) barColor.copy(alpha = pulseAlpha)
                                else barColor
                            )
                            .clickable { onDayClick(date) }
                    )
                }
            }

            // M18.62: Hinweis auf den gewählten Tag + Zurücksetzen
            if (selected != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.balance_showing_day, formatDay(selected)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { onDayClick(today) }) {
                        Text(stringResource(R.string.common_today), fontSize = 12.sp)
                    }
                }
            }

            // Durchschnitt
            val avg = if (totals.isNotEmpty()) totals.sumOf { it.second } / totals.size else 0L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.balance_avg_per_day, DigitalBalanceViewModel.formatDuration(avg)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.balance_sum, DigitalBalanceViewModel.formatDuration(totals.sumOf { it.second })),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppLimitCard(
    app: DigitalAppUi,
    dayTotalMs: Long,
    onLimitChange: (Int, Boolean) -> Unit,
    onException: (String, Int, Int) -> Unit,
    onRemove: () -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val limit = app.limit
    val hasLimit = limit != null && limit.enabled

    AevumCard(
        variant = if (app.isBlocked) CardVariant.Filled else CardVariant.Elevated,
        contentPadding = PaddingValues(AevumSpacing.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // M18.66-FIX16: Fertige ImageBitmap aus dem ViewModel-Cache
                // — kein drawableToBitmap() mehr bei jeder Recomposition.
                val appIcon = app.icon
                if (appIcon != null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = appIcon,
                            contentDescription = app.appLabel,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (app.isBlocked) MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            app.appLabel.take(1).uppercase(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (app.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.appLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        DigitalBalanceViewModel.formatDuration(app.dayMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (app.isBlocked) {
                    Surface(
                        shape = RoundedCornerShape(AevumRadius.full),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.error
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
                            Text(stringResource(R.string.balance_locked), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // M18.62: Farbiger Anteils-Balken — wie im Dashboard. Zeigt,
            // wie viel von der gesamten Bildschirmzeit des Tages auf diese
            // App zurückgeht. Animiert beim Anzeigen (Spring bouncy +
            // Glanz-Layer via AnimatedGradientBar).
            val share = if (dayTotalMs > 0) (app.dayMs.toFloat() / dayTotalMs).coerceIn(0f, 1f) else 0f
            val barColor = when {
                app.isBlocked -> MaterialTheme.colorScheme.error
                share >= 0.5f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            AnimatedGradientBar(
                progress = share,
                color = barColor,
                modifier = Modifier.fillMaxWidth(),
                height = 8.dp,
                cornerRadius = AevumRadius.full,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            if (hasLimit) {
                // Fortschrittsbalken zum Limit — tween statt bouncy Spring
                // (Spring sprang bei jedem Refresh neu → Haken).
                val progress = app.progress
                val animatedLimitProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(300),
                    label = "limitProgress"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedLimitProgress)
                            .height(6.dp)
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(
                                if (app.isBlocked) MaterialTheme.colorScheme.error
                                else if (progress > 0.8f) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.balance_limit_min, limit?.limitMinutes ?: 0),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    app.remainingMs?.let { rem ->
                        Text(
                            // M18.93v8 (Loss Aversion — Video 2TlIg3VokY8):
                            // Ab 80 % des Limits Verlust-Framing: „Gleich gesperrt —
                            // noch 5 min" statt neutralem „Noch 5 min". Der Schmerz
                            // des Verlusts (Zugriff weg) ist doppelt so stark wie
                            // die Freude über verbleibende Zeit (Kahneman) —
                            // Verhaltensänderung setzt eher ein.
                            when {
                                app.isBlocked -> stringResource(R.string.balance_limit_reached)
                                progress >= 0.8f -> stringResource(
                                    R.string.balance_soon_blocked,
                                    DigitalBalanceViewModel.formatDuration(rem)
                                )
                                else -> stringResource(R.string.balance_remaining, DigitalBalanceViewModel.formatDuration(rem))
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (app.isBlocked || progress >= 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (hasLimit) stringResource(R.string.balance_limit_active) else stringResource(R.string.balance_no_limit),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showEditor = true }) {
                        Text(if (hasLimit) stringResource(R.string.common_edit) else stringResource(R.string.balance_set_limit), fontSize = 12.sp)
                    }
                    if (hasLimit) {
                        Switch(
                            checked = limit?.enabled ?: false,
                            onCheckedChange = { onLimitChange(limit?.limitMinutes ?: 0, it) }
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        AppLimitEditorDialog(
            app = app,
            onSave = { minutes, enabled, exceptionType, start, end ->
                onLimitChange(minutes, enabled)
                onException(exceptionType, start, end)
                showEditor = false
            },
            onRemove = {
                onRemove()
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun AppLimitEditorDialog(
    app: DigitalAppUi,
    onSave: (Int, Boolean, String, Int, Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var minutes by remember { mutableStateOf(app.limit?.limitMinutes ?: 60) }
    var enabled by remember { mutableStateOf(app.limit?.enabled ?: true) }
    var exceptionType by remember {
        mutableStateOf(app.limit?.exceptionType ?: AppLimit.EXCEPTION_NONE)
    }
    var windowStart by remember { mutableStateOf(app.limit?.windowStartMin ?: 22 * 60) }
    var windowEnd by remember { mutableStateOf(app.limit?.windowEndMin ?: 6 * 60) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.appLabel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                Text(
                    stringResource(R.string.balance_daily_limit, minutes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = it.toInt() },
                    valueRange = 5f..480f,
                    steps = 18
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.balance_limit_active), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Text(stringResource(R.string.balance_exception), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_NONE,
                        onClick = { exceptionType = AppLimit.EXCEPTION_NONE },
                        label = { Text(stringResource(R.string.common_none), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_ALWAYS_ALLOW,
                        onClick = { exceptionType = AppLimit.EXCEPTION_ALWAYS_ALLOW },
                        label = { Text(stringResource(R.string.common_always_allow), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_TIME_WINDOW,
                        onClick = { exceptionType = AppLimit.EXCEPTION_TIME_WINDOW },
                        label = { Text(stringResource(R.string.balance_time_window), fontSize = 12.sp) }
                    )
                }

                if (exceptionType == AppLimit.EXCEPTION_TIME_WINDOW) {
                    Text(
                        stringResource(
                            R.string.balance_block_between,
                            "%02d:%02d".format(windowStart / 60, windowStart % 60),
                            "%02d:%02d".format(windowEnd / 60, windowEnd % 60)
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = windowStart.toFloat(),
                        onValueChange = { windowStart = it.toInt() },
                        valueRange = 0f..1439f
                    )
                    Slider(
                        value = windowEnd.toFloat(),
                        onValueChange = { windowEnd = it.toInt() },
                        valueRange = 0f..1439f
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(minutes, enabled, exceptionType, windowStart, windowEnd) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_save), modifier = Modifier.padding(horizontal = 8.dp))
            }
        },
        dismissButton = {
            // M18.61g-FIX: Buttons vertikal stapeln statt quetschen —
            // vorher standen "Entfernen" + "Abbrechen" in einer Row und
            // wurden bei schmalen Dialogen zusammengedrückt.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (app.limit != null) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.balance_remove_limit), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.common_cancel), modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    )
}

// ===================== M18.61f: PROFILE =====================

/**
 * Profile-Karte: erstellt Profile (z.B. "Lernen" sperrt Social Media),
 * zeigt aktive Profile, aktiviert/deaktiviert sie per Toggle.
 */
@Composable
private fun ProfilesCard(viewModel: DigitalBalanceViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    // M18.62: Profil bearbeiten — welches Profil ist im Edit-Modus?
    var editProfileId by remember { mutableStateOf<String?>(null) }

    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.balance_profiles), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showCreate = true }) {
                    Text(stringResource(R.string.activitytypes_add_new), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    stringResource(R.string.balance_profiles_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AevumRadius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Text(profile.icon, fontSize = 20.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                activeProfile?.id == profile.id && profile.scheduleEnabled ->
                                    stringResource(R.string.balance_profile_active_schedule)
                                activeProfile?.id == profile.id ->
                                    stringResource(R.string.balance_profile_active)
                                profile.scheduleEnabled -> {
                                    val days = listOf(
                                        stringResource(R.string.common_monday),
                                        stringResource(R.string.common_tuesday),
                                        stringResource(R.string.common_wednesday),
                                        stringResource(R.string.common_thursday),
                                        stringResource(R.string.common_friday),
                                        stringResource(R.string.common_saturday),
                                        stringResource(R.string.common_sunday)
                                    )
                                        .filterIndexed { idx, _ -> (profile.scheduleDays and (1 shl idx)) != 0 }
                                        .joinToString(",")
                                    stringResource(
                                        R.string.balance_profile_schedule,
                                        days,
                                        "%02d:%02d".format(profile.scheduleStartMinute/60, profile.scheduleStartMinute%60),
                                        "%02d:%02d".format(profile.scheduleEndMinute/60, profile.scheduleEndMinute%60)
                                    )
                                }
                                else -> stringResource(R.string.balance_profile_inactive)
                            },
                            fontSize = 11.sp,
                            color = if (activeProfile?.id == profile.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // M18.62: Stift-Icon zum Bearbeiten des Profils
                    IconButton(
                        onClick = { editProfileId = profile.id },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.balance_edit_profile),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = activeProfile?.id == profile.id,
                        onCheckedChange = { checked ->
                            if (checked) viewModel.setProfileActive(profile.id)
                            else viewModel.deactivateProfile()
                        }
                    )
                }
            }
        }
    }

    if (showCreate) {
        ProfileFormDialog(
            viewModel = viewModel,
            profile = null,
            onDismiss = { showCreate = false }
        )
    }

    // M18.62: Edit-Modus — Profil mit vorbefüllten Werten bearbeiten
    val editingProfile = editProfileId?.let { id -> profiles.firstOrNull { it.id == id } }
    if (editingProfile != null) {
        ProfileFormDialog(
            viewModel = viewModel,
            profile = editingProfile,
            onDismiss = { editProfileId = null }
        )
    }
}

/**
 * M18.62: Profil-Formular (Erstellen ODER Bearbeiten).
 *
 * profile == null → neues Profil anlegen (leere Felder).
 * profile != null → bestehendes Profil bearbeiten (Felder vorbefüllt,
 * App-Auswahl aus der DB geladen). Speichern ruft updateProfile auf.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFormDialog(
    viewModel: DigitalBalanceViewModel,
    profile: BalanceProfile?,
    onDismiss: () -> Unit
) {
    val isEdit = profile != null
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var icon by remember { mutableStateOf(profile?.icon ?: "📚") }
    val icons = listOf("📚", "💼", "🧘", "🎮", "🎵", "📱", "🌙", "🏋️")
    // Performance-Fix: App-Liste + Icons im Hintergrund laden. Vorher
    // blockierte loadInstalledApps() (queryIntentActivities + loadIcon
    // für ALLE Apps) den Main-Thread beim Öffnen des Dialogs → hängende
    // Klicks bei den Profilen.
    var installedApps by remember { mutableStateOf<List<Triple<String, String, android.graphics.drawable.Drawable?>>>(emptyList()) }
    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) { loadInstalledApps(viewModel.appContext()) }
    }
    val selected = remember { mutableStateListOf<String>() }
    // M18.66-FIX14: Zeitplan-State
    var scheduleEnabled by remember { mutableStateOf(profile?.scheduleEnabled ?: false) }
    // Bitmaske: Bit 0=Mo, 1=Di, ... 6=So. Default Mo-Fr = 0b11111 = 31
    var scheduleDays by remember { mutableStateOf(profile?.scheduleDays ?: 31) }
    var startHour by remember { mutableStateOf((profile?.scheduleStartMinute ?: 420) / 60) }
    var startMinute by remember { mutableStateOf((profile?.scheduleStartMinute ?: 420) % 60) }
    var endHour by remember { mutableStateOf((profile?.scheduleEndMinute ?: 960) / 60) }
    var endMinute by remember { mutableStateOf((profile?.scheduleEndMinute ?: 960) % 60) }
    val dayLabels = listOf(
        stringResource(R.string.common_monday),
        stringResource(R.string.common_tuesday),
        stringResource(R.string.common_wednesday),
        stringResource(R.string.common_thursday),
        stringResource(R.string.common_friday),
        stringResource(R.string.common_saturday),
        stringResource(R.string.common_sunday)
    )

    if (isEdit && profile != null) {
        androidx.compose.runtime.LaunchedEffect(profile.id) {
            val pkgs = viewModel.getProfilePackages(profile.id)
            selected.clear()
            selected.addAll(pkgs)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) stringResource(R.string.balance_edit_profile) else stringResource(R.string.balance_new_profile), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text(stringResource(R.string.common_name), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.balance_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.common_icon), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons.forEach { i ->
                        Text(
                            i,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (icon == i) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { icon = i }
                                .padding(8.dp)
                        )
                    }
                }

                // M18.66-FIX14: Zeitplan-Sektion
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = AevumSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.balance_schedule), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.balance_schedule_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = scheduleEnabled, onCheckedChange = { scheduleEnabled = it })
                }
                if (scheduleEnabled) {
                    Text(stringResource(R.string.balance_weekdays), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // M18.66-FIX15: FlowRow statt Row — 7 Chips in einer
                    // Zeile wurden auf schmalen Screens deformiert.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        dayLabels.forEachIndexed { idx, label ->
                            val bit = 1 shl idx
                            val isSelected = (scheduleDays and bit) != 0
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(AevumRadius.sm))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { scheduleDays = scheduleDays xor bit }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // M18.66-FIX15: Echte Material3-TimePicker statt
                        // "Tap erhöht Stunde"-Hack.
                        var showStartPicker by remember { mutableStateOf(false) }
                        var showEndPicker by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.balance_from), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { showStartPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("%02d:%02d".format(startHour, startMinute), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.balance_until), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(
                                onClick = { showEndPicker = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("%02d:%02d".format(endHour, endMinute), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (showStartPicker) {
                            val timeState = androidx.compose.material3.rememberTimePickerState(
                                initialHour = startHour,
                                initialMinute = startMinute,
                                is24Hour = true
                            )
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showStartPicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        startHour = timeState.hour
                                        startMinute = timeState.minute
                                        showStartPicker = false
                                    }) { Text(stringResource(R.string.component_ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showStartPicker = false }) { Text(stringResource(R.string.common_cancel)) }
                                },
                                text = { androidx.compose.material3.TimePicker(state = timeState) }
                            )
                        }
                        if (showEndPicker) {
                            val timeState = androidx.compose.material3.rememberTimePickerState(
                                initialHour = endHour,
                                initialMinute = endMinute,
                                is24Hour = true
                            )
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showEndPicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        endHour = timeState.hour
                                        endMinute = timeState.minute
                                        showEndPicker = false
                                    }) { Text(stringResource(R.string.component_ok)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showEndPicker = false }) { Text(stringResource(R.string.common_cancel)) }
                                },
                                text = { androidx.compose.material3.TimePicker(state = timeState) }
                            )
                        }
                    }
                    Text(
                        stringResource(
                            R.string.balance_schedule_summary,
                            dayLabels.filterIndexed { idx, _ -> (scheduleDays and (1 shl idx)) != 0 }
                                .joinToString(", ").ifEmpty { stringResource(R.string.balance_never) },
                            "%02d:%02d".format(startHour, startMinute),
                            "%02d:%02d".format(endHour, endMinute)
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // M18.66-FIX14: HorizontalDivider vor App-Auswahl
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = AevumSpacing.xs))
                Text(stringResource(R.string.balance_block_apps), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                installedApps.forEach { app ->
                    // M18.61g-FIX 3: Triple(packageName, label, icon) —
                    // Paketname wird gespeichert, Label nur angezeigt.
                    val pkg = app.first
                    val label = app.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .clickable {
                                if (selected.contains(pkg)) selected.remove(pkg)
                                else selected.add(pkg)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // M18.66-FIX16: Icon-Bitmaps EINMAL pro Dialog-Öffnung
                        // konvertieren und cachen. Vorher: drawableToBitmap()
                        // bei jeder Recomposition (jeder Toggle-Klick in der
                        // App-Liste konvertierte ALLE installierten App-Icons
                        // neu → das Ruckeln im Profil-Dialog).
                        val appDrawable = app.third
                        val iconBitmap = remember(app.third) {
                            appDrawable?.let { drawableToBitmap(it) }
                        }
                        if (iconBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) { Text(label.take(1).uppercase(), fontSize = 12.sp) }
                        }
                        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (selected.contains(pkg)) {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && selected.isNotEmpty()) {
                        val startMin = startHour * 60 + startMinute
                        val endMin = endHour * 60 + endMinute
                        if (isEdit && profile != null) {
                            // M18.66-FIX14: updateProfileWithSchedule wenn Zeitplan aktiv,
                            // sonst normales updateProfile
                            if (scheduleEnabled) {
                                viewModel.updateProfileWithSchedule(
                                    profile.id, name.trim(), icon, profile.color, selected.toList(),
                                    scheduleEnabled, scheduleDays, startMin, endMin
                                )
                            } else {
                                viewModel.updateProfileWithSchedule(
                                    profile.id, name.trim(), icon, profile.color, selected.toList(),
                                    false, 0, 0, 0
                                )
                            }
                        } else {
                            if (scheduleEnabled) {
                                viewModel.createProfileWithSchedule(
                                    name.trim(), icon, "#6366F1", selected.toList(),
                                    scheduleEnabled, scheduleDays, startMin, endMin
                                )
                            } else {
                                viewModel.createProfile(name.trim(), icon, "#6366F1", selected.toList())
                            }
                        }
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && selected.isNotEmpty()
            ) { Text(if (isEdit) stringResource(R.string.common_save) else stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

private fun loadInstalledApps(context: android.content.Context): List<Triple<String, String, android.graphics.drawable.Drawable?>> {
    return try {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            // M18.61g-FIX 3 (User: "Profil sperrt nicht"): Vorher wurde der
            // App-NAME ("Instagram") als Paket gespeichert — der Service
            // vergleicht aber Paketnamen (com.instagram.android) -> nie ein
            // Match -> keine Sperre. Jetzt: Triple(packageName, label, icon).
            .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
    } catch (_: Exception) { emptyList() }
}

/**
 * M18.61f: Drawable → ImageBitmap (für App-Icons in Compose).
 * rememberDrawablePainter existiert in dieser Compose-Version nicht.
 */
private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): ImageBitmap {
    return try {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        // Fallback: 1x1 transparentes Bitmap
        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

// ===================== M18.61f: POMODORO =====================

/**
 * Pomodoro-Seite: Fokus-Timer mit Phasen (Fokus/Kurzpause/Lange Pause),
 * Start/Pause, Reset, Minuten-Wahl. Kompakt und nicht überladen.
 */
@Composable
private fun PomodoroPage(viewModel: DigitalBalanceViewModel) {
    val pomodoro by viewModel.pomodoro.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        // Phasen-Auswahl
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DigitalBalanceViewModel.PomodoroPhase.entries.forEach { phase ->
                val selected = pomodoro.phase == phase
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setPomodoroPhase(phase) },
                    label = { Text(stringResource(phase.labelRes), fontSize = 12.sp) }
                )
            }
        }

        // Minuten-Wahl (nur im Fokus)
        if (pomodoro.phase == DigitalBalanceViewModel.PomodoroPhase.FOCUS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.balance_pomodoro_duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(15, 25, 45, 60).forEach { min ->
                    FilterChip(
                        selected = pomodoro.customMinutes == min,
                        onClick = { viewModel.setPomodoroMinutes(min) },
                        label = { Text(stringResource(R.string.balance_pomodoro_min, min), fontSize = 12.sp) }
                    )
                }
            }
        }

        // Großer Timer
        AevumCard(variant = CardVariant.Gradient) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text(
                    stringResource(pomodoro.phase.labelRes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "%02d:%02d".format(pomodoro.remainingSeconds / 60, pomodoro.remainingSeconds % 60),
                    fontSize = 56.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1).sp
                )
                // Fortschrittsring
                val progress = if (pomodoro.totalSeconds > 0) {
                    pomodoro.remainingSeconds.toFloat() / pomodoro.totalSeconds
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(6.dp)
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    stringResource(R.string.balance_pomodoro_completed, pomodoro.completedFocusSessions),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Button(
                        onClick = viewModel::togglePomodoro,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(if (pomodoro.running) stringResource(R.string.balance_pomodoro_pause) else stringResource(R.string.balance_pomodoro_start), fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = viewModel::resetPomodoro,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(stringResource(R.string.balance_pomodoro_reset), fontSize = 14.sp)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.balance_pomodoro_tip),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
