package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * M18.28: Ultra-fancy Kalender-Ansicht.
 *
 * Konzept (hinterfragt):
 * - Monatsgrid als HEATMAP der Zeitqualität: Jeder Tag bekommt eine Farbe
 *   aus der gewichteten Positivität (Dauer * Score, gewichtet über den Tag).
 *   Rot = viel negative Zeit, Grün = viel positive, Grau = nichts erfasst,
 *   Halbtransparent = wenig Zeit.
 * - Kein Standard-Calendar-Widget: komplett eigenes Compose-Grid mit
 *   runden "Pill"-Tageszellen (Figma/Linear-Stil).
 * - Antippen eines Tages -> unter dem Grid faehrt ein Detail-Panel ein:
 *   farbige Aktivitaetsbalken auf einer 24h-Achse (Mini-Timeline).
 * - Monatswechsel mit AnimatedContent (Slide/Fade).
 */
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onOpenActivity: (String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item { CalendarHero(state, viewModel::previousMonth, viewModel::nextMonth, viewModel::today) }
        item { CalendarGrid(state = state, onSelectDate = viewModel::selectDate) }
        item { DayDetailPanel(state = state, onOpenActivity = onOpenActivity) }
        item { Spacer(Modifier.height(AevumSpacing.xxl)) }
    }
}

@Composable
private fun CalendarHero(
    state: CalendarUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.calendar_hero_label),
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.calendar_subtitle),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious) {
                    Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${state.month.year}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onNext) {
                    Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.calendar_captured, TimeFormatting.formatDuration(state.totalTrackedMs)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(R.string.common_today),
                    modifier = Modifier
                        .clip(RoundedCornerShape(AevumRadius.full))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .clickable(onClick = onToday)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    state: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit
) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            // Wochentags-Header
            Row(modifier = Modifier.fillMaxWidth()) {
                val weekDayLabels = listOf(
                    stringResource(R.string.common_monday),
                    stringResource(R.string.common_tuesday),
                    stringResource(R.string.common_wednesday),
                    stringResource(R.string.common_thursday),
                    stringResource(R.string.common_friday),
                    stringResource(R.string.common_saturday),
                    stringResource(R.string.common_sunday)
                )
                weekDayLabels.forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Monatswechsel-Animation
            AnimatedContent(
                targetState = state.month,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "month"
            ) { month ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val daysInMonth = month.lengthOfMonth()
                    // Alle Zellen (inkl. Leerzellen vor dem 1.) in einer
                    // einzigen Schleife — fehlerfrei für jeden Monatsstart.
                    var rowStart = 0
                    while (rowStart < daysInMonth + state.leadingEmptyCells) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            repeat(7) { col ->
                                val cellIndex = rowStart + col
                                if (cellIndex >= state.leadingEmptyCells && cellIndex < state.leadingEmptyCells + daysInMonth) {
                                    val d = cellIndex - state.leadingEmptyCells + 1
                                    DayCell(
                                        date = month.atDay(d),
                                        aggregate = state.days[month.atDay(d)],
                                        isSelected = state.selectedDate == month.atDay(d),
                                        onClick = { onSelectDate(month.atDay(d)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                                }
                            }
                        }
                        rowStart += 7
                    }
                }
            }
            // Legende
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.calendar_less), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                listOf(
                    Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF66BB6A), Color(0xFF2E7D32)
                ).forEach { c ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(c)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calendar_more), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    aggregate: CalendarDayAggregate?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasData = aggregate != null && aggregate.totalDurationMs > 0
    val heatColor = heatColorFor(aggregate)
    val isToday = date == LocalDate.now()
    val animatedAlpha by animateFloatAsState(
        targetValue = if (hasData) 1f else 0f,
        animationSpec = tween(400),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            // Heatmap-Balken (animiertes Einblenden)
            if (hasData) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(width = 18.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(heatColor.copy(alpha = animatedAlpha * 0.85f))
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
            // Erfasst-Punkt
            if (hasData && aggregate!!.sessionCount > 0) {
                Text(
                    "${aggregate.sessionCount}",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/**
 * Heatmap-Farbe aus der gewichteten Positivität.
 * avgScore: -50 (alles negativ) .. +50 (alles positiv).
 * Kein Data -> Grau. Wenig Data -> transparente Version.
 */
@Composable
private fun heatColorFor(aggregate: CalendarDayAggregate?): Color {
    if (aggregate == null || aggregate.totalDurationMs <= 0) {
        return MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    }
    val score = aggregate.avgScore.coerceIn(-50f, 50f)
    // -50 = rot, 0 = gelb, +50 = grün
    val t = (score + 50f) / 100f
    return Color(
        red = lerp(0.90f, 0.13f, t),
        green = lerp(0.22f, 0.72f, t),
        blue = lerp(0.22f, 0.13f, t)
    )
}

private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t.coerceIn(0f, 1f)

@Composable
private fun DayDetailPanel(
    state: CalendarUiState,
    onOpenActivity: (String) -> Unit
) {
    val selected = state.selectedDate
    val dayLabel = selected.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.getDefault()))
    val totalMs = state.daySessions.sumOf { it.durationMs }
    val aggregate = state.days[selected]

    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(dayLabel.replaceFirstChar { it.titlecase(Locale.getDefault()) }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.calendar_day_captured, TimeFormatting.formatDuration(totalMs), state.daySessions.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (aggregate != null && aggregate.totalDurationMs > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AevumRadius.full))
                            .background(heatColorFor(aggregate).copy(alpha = 0.25f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${(aggregate.avgScore + 50).roundToInt()}/100",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = heatColorFor(aggregate)
                        )
                    }
                }
            }

            if (state.daySessions.isEmpty()) {
                Text(
                    stringResource(R.string.calendar_no_activities),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Mini-Timeline: farbige Balken auf 24h-Achse
                DayTimelineBars(sessions = state.daySessions)
                Spacer(Modifier.height(AevumSpacing.xs))
                // Session-Liste
                state.daySessions.forEach { session ->
                    SessionRow(session = session, onClick = { onOpenActivity(session.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun DayTimelineBars(sessions: List<CalendarDaySessionUi>) {
    val barHeight = 28.dp
    // M18.28: BoxWithConstraints liefert die Breite in dp — damit lassen
    // sich die Balken als Bruchteil der 24h-Achse positionieren.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight + 18.dp)
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        val maxW = maxWidth
        // 24h-Achse mit Stundenlabels
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            repeat(24) { hour ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (hour % 6 == 0) {
                        Text(
                            "%02d".format(hour),
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }
        // Aktivitäts-Balken — Position = Startanteil, Breite = Daueranteil
        sessions.forEach { session ->
            val startFrac = (session.startMinute / 1440f).coerceIn(0f, 0.99f)
            val endFrac = ((session.endMinute.coerceAtLeast(session.startMinute + 1)).toFloat() / 1440f).coerceIn(startFrac + 0.005f, 1f)
            val color = if (session.color != 0L) Color(session.color) else MaterialTheme.colorScheme.secondary
            Box(
                modifier = Modifier
                    .padding(start = maxW * startFrac)
                    .fillMaxWidth(endFrac - startFrac)
                    .height(barHeight)
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
private fun SessionRow(session: CalendarDaySessionUi, onClick: () -> Unit) {
    val color = if (session.color != 0L) Color(session.color) else MaterialTheme.colorScheme.secondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(session.icon, fontSize = 16.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title.ifBlank { stringResource(R.string.calendar_activity) }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${TimeFormatting.formatTime(session.startAt)} · ${TimeFormatting.formatDuration(session.durationMs)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
