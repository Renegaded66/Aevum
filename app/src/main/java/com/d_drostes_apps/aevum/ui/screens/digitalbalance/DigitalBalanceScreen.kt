package com.d_drostes_apps.aevum.ui.screens.digitalbalance

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.data.model.AppLimit
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.launch
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
    viewModel: DigitalBalanceViewModel
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
            listOf("Balance", "Pomodoro").forEachIndexed { index, label ->
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
                BalancePage(state, viewModel)
            } else {
                PomodoroPage(viewModel)
            }
        }
    }
}

@Composable
private fun BalancePage(
    state: DigitalBalanceUiState,
    viewModel: DigitalBalanceViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item { TodayHeroCard(state, onRefresh = viewModel::refresh) }
        item { RangeStatsCard(state, onRangeChange = viewModel::setRangeDays) }
        // M18.61f: Profile-Karte (Lern-Profil sperrt Social Media)
        item { ProfilesCard(viewModel) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Apps", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // M18.61g: Dezente Sortier-Buttons (Icons) —
                    // alphabetisch (A-Z) oder absteigend nach Nutzung
                    IconButton(
                        onClick = { viewModel.setSortMode("alpha") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SortByAlpha,
                            contentDescription = "Alphabetisch sortieren",
                            tint = if (state.sortMode == "alpha") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setSortMode("usage") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = "Nach Nutzung sortieren",
                            tint = if (state.sortMode == "usage") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.blockedCount > 0) {
                        Text(
                            "${state.blockedCount} gesperrt",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            AppLimitCard(
                app = app,
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
                    "Digital Balance",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Um deine App-Nutzung zu sehen und Limits zu setzen, " +
                        "braucht Aevum Zugriff auf die Nutzungsdaten.",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Nutzungszugriff erlauben")
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
                Text("Heute", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", modifier = Modifier.size(16.dp))
                }
            }

            // M18.61: Ring-Diagramm (Google-Digital-Wellbeing-Muster) —
            // Füllung = verbrauchte Zeit vs. Tagesziel (5h), Mitte = Zeit.
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
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DigitalBalanceViewModel.formatDuration(state.todayTotalMs),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "von ${DigitalBalanceViewModel.formatDuration(goalMs)} Ziel",
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
                HeroMetric("Apps", state.todayAppCount.toString())
                HeroMetric("Unlocks", state.unlockCount.toString())
                HeroMetric("Top", state.topAppName?.take(8) ?: "—")
            }

            // M18.61: 24-Stunden-Breakdown (Google-Muster: "ablenkend
            // zwischen 14–16 Uhr" erkennen)
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
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((frac * 40f).coerceAtLeast(if (ms > 0) 3f else 1.5f).dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(
                                if (ms > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }
            Text(
                "Nutzung pro Stunde (0–24 Uhr)",
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
    onRangeChange: (Int) -> Unit
) {
    AevumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Verlauf", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7, 30).forEach { days ->
                        FilterChip(
                            selected = state.rangeDays == days,
                            onClick = { onRangeChange(days) },
                            label = { Text("$days Tage", fontSize = 12.sp) }
                        )
                    }
                }
            }

            // Balken-Diagramm: letzte N Tage
            val totals = state.dailyTotals
            val maxMs = (totals.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            val today = LocalDate.now()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                totals.forEach { (date, ms) ->
                    val fraction = ms.toFloat() / maxMs
                    val isToday = date == today
                    val barColor = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((fraction * 72f).coerceAtLeast(if (ms > 0) 4f else 2f).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(barColor)
                    )
                }
            }

            // Durchschnitt
            val avg = if (totals.isNotEmpty()) totals.sumOf { it.second } / totals.size else 0L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Ø ${DigitalBalanceViewModel.formatDuration(avg)}/Tag",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Σ ${DigitalBalanceViewModel.formatDuration(totals.sumOf { it.second })}",
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
                // M18.61f: ECHTES App-Icon (Drawable aus dem PackageManager)
                // statt Buchstaben-Kreis. Fallback: erster Buchstabe.
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
                            painter = BitmapPainter(drawableToBitmap(appIcon)),
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
                        "Heute ${DigitalBalanceViewModel.formatDuration(app.todayMs)}" +
                            if (app.rangeMs > app.todayMs) " · ${app.rangeMs / 3_600_000}h im Zeitraum" else "",
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
                            Text("Gesperrt", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (hasLimit) {
                // Fortschrittsbalken zum Limit
                val progress = app.progress
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
                        "Limit ${limit?.limitMinutes ?: 0} min",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    app.remainingMs?.let { rem ->
                        Text(
                            if (app.isBlocked) "Limit erreicht" else "Noch ${DigitalBalanceViewModel.formatDuration(rem)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (app.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
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
                    if (hasLimit) "Limit aktiv" else "Kein Limit",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showEditor = true }) {
                        Text(if (hasLimit) "Bearbeiten" else "Limit setzen", fontSize = 12.sp)
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
                    "Tägliches Limit: ${minutes} min",
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
                    Text("Limit aktiv", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Text("Ausnahme", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_NONE,
                        onClick = { exceptionType = AppLimit.EXCEPTION_NONE },
                        label = { Text("Keine", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_ALWAYS_ALLOW,
                        onClick = { exceptionType = AppLimit.EXCEPTION_ALWAYS_ALLOW },
                        label = { Text("Immer erlauben", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = exceptionType == AppLimit.EXCEPTION_TIME_WINDOW,
                        onClick = { exceptionType = AppLimit.EXCEPTION_TIME_WINDOW },
                        label = { Text("Zeitfenster", fontSize = 12.sp) }
                    )
                }

                if (exceptionType == AppLimit.EXCEPTION_TIME_WINDOW) {
                    Text(
                        "Sperre nur zwischen ${windowStart / 60}:%02d".format(windowStart % 60) +
                            " und ${windowEnd / 60}:%02d".format(windowEnd % 60),
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
                Text("Speichern", modifier = Modifier.padding(horizontal = 8.dp))
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
                        Text("Limit entfernen", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abbrechen", modifier = Modifier.padding(horizontal = 8.dp))
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

    AevumCard(variant = CardVariant.Gradient) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Profile", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showCreate = true }) {
                    Text("+ Neu", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (profiles.isEmpty()) {
                Text(
                    "Erstelle Profile, um mehrere Apps auf einmal zu sperren — z.B. ein Lern-Profil, das alle Social-Media-Apps blockiert.",
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
                            if (activeProfile?.id == profile.id) "Aktiv — Apps gesperrt" else "Inaktiv",
                            fontSize = 11.sp,
                            color = if (activeProfile?.id == profile.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
        ProfileCreateDialog(
            viewModel = viewModel,
            onDismiss = { showCreate = false }
        )
    }
}

/**
 * Dialog zum Erstellen eines Profils: Name + Icon + App-Auswahl
 * (alle installierten Apps mit Namen + echten Icons).
 */
@Composable
private fun ProfileCreateDialog(
    viewModel: DigitalBalanceViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📚") }
    val icons = listOf("📚", "💼", "🧘", "🎮", "🎵", "📱", "🌙", "🏋️")
    val installedApps by remember { mutableStateOf(loadInstalledApps(viewModel.appContext())) }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Profil", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text("Name", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("z.B. Lernen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Icon", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Apps sperren", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        val appDrawable = app.third
                        if (appDrawable != null) {
                            androidx.compose.foundation.Image(
                                painter = BitmapPainter(drawableToBitmap(appDrawable)),
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
                        viewModel.createProfile(name.trim(), icon, "#6366F1", selected.toList())
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && selected.isNotEmpty()
            ) { Text("Erstellen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
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
                    label = { Text(phase.label, fontSize = 12.sp) }
                )
            }
        }

        // Minuten-Wahl (nur im Fokus)
        if (pomodoro.phase == DigitalBalanceViewModel.PomodoroPhase.FOCUS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dauer:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(15, 25, 45, 60).forEach { min ->
                    FilterChip(
                        selected = pomodoro.customMinutes == min,
                        onClick = { viewModel.setPomodoroMinutes(min) },
                        label = { Text("$min min", fontSize = 12.sp) }
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
                    pomodoro.phase.label,
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
                    "Abgeschlossene Fokus-Sessions: ${pomodoro.completedFocusSessions}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Button(
                        onClick = viewModel::togglePomodoro,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(if (pomodoro.running) "⏸ Pause" else "▶ Start", fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = viewModel::resetPomodoro,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("↺ Reset", fontSize = 14.sp)
                    }
                }
            }
        }

        Text(
            "Tipp: Nutze den Fokus-Timer zusammen mit einem Profil — während des Lernens bleiben Social-Media-Apps gesperrt.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
