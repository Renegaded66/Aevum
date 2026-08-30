package com.d_drostes_apps.aevum.ui.screens.placetimeline

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceVisit
import com.d_drostes_apps.aevum.domain.placetimeline.VisitEvidence
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.LocalDate

// __CHUNK_MARKER__

/**
 * M18.83: Place Timeline — "Wo war ich wann?" als Google-Maps-artige
 * Tag-Story (eigener Punkt in den Einstellungen → Automatisierung).
 *
 * Layout-Anatomie (Maps-Metapher, adaptiert auf Aevums Design-Sprache):
 *  ┌─────────────────────────────────────┐
 *  │  Hero: „Mittwoch, 30.08." + Summary │  ← GlassCard mit Farbbalken
 *  ├─────────────────────────────────────┤
 *  │  ‹  Mi, 30.08.  ›   [Heute]         │  ← Day-Navigator
 *  ├─────────────────────────────────────┤
 *  │  ●── 09:15–12:30   🏢 Büro  3h 15m │  ← Visit-Zeile (Timeline-Punkt)
 *  │   ╎                                 │
 *  │   ╎── 12:30–13:02  🔀 Unterwegs    │  ← Gap-Zeile (dezent)
 *  │   ╎                                 │
 *  │  ●── 13:02–17:45   🏢 Büro  …      │
 *  └─────────────────────────────────────┘
 */
@Composable
fun PlaceTimelineScreen(
    onBack: () -> Unit,
    viewModel: PlaceTimelineViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlaceTimelineHeader(onBack = onBack)
            DayNavigator(
                selectedDate = state.selectedDate,
                onPrevious = viewModel::previousDay,
                onNext = viewModel::nextDay,
                onToday = viewModel::today
            )
            if (state.hasData) {
                PlaceTimelineContent(state = state)
            } else {
                PlaceTimelineEmpty(state = state)
            }
        }
    }
}

@Composable
private fun PlaceTimelineHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.common_back)
            )
        }
        Column {
            Text(
                text = stringResource(R.string.place_timeline_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.place_timeline_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DayNavigator(
    selectedDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.common_yesterday)
            )
        }
        Text(
            text = formatNavigatorDate(selectedDate),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToday) {
                Text(stringResource(R.string.common_today))
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.common_tomorrow)
                )
            }
        }
    }
}

// __CHUNK_2_MARKER__

// ─────────────────────────────────────────────────────────────────────
// Inhalt: Summary-Hero + Story-Liste (Visits + Unterwegs-Gaps)
// ─────────────────────────────────────────────────────────────────────

/** Eine Zeile der Story — entweder ein Besuch oder eine "Unterwegs"-Lücke. */
private sealed interface StoryRow {
    data class Visit(val visit: PlaceVisit) : StoryRow
    data class Road(val startAt: Long, val endAt: Long) : StoryRow
}

private fun buildStoryRows(visits: List<PlaceVisit>): List<StoryRow> {
    val rows = mutableListOf<StoryRow>()
    visits.forEachIndexed { i, visit ->
        rows += StoryRow.Visit(visit)
        val next = visits.getOrNull(i + 1)
        if (next != null && next.startAt > visit.endAt) {
            rows += StoryRow.Road(startAt = visit.endAt, endAt = next.startAt)
        }
    }
    return rows
}

@Composable
private fun PlaceTimelineContent(state: PlaceTimelineUiState) {
    val rows = remember(state.visits) { buildStoryRows(state.visits) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = AevumSpacing.md, vertical = AevumSpacing.sm
        ),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
    ) {
        item { SummaryCard(state) }
        item { Spacer(Modifier.height(AevumSpacing.xs)) }
        items(rows) { row ->
            when (row) {
                is StoryRow.Visit -> VisitRow(row.visit)
                is StoryRow.Road -> RoadRow(row.startAt, row.endAt)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/**
 * Kopf-Karte: Tages-Titel + dominante Farbe + Top-Orte mit Dauern +
 * Lede ("3 Besuche · 2h 05m unterwegs"). Glassmorphismus wie Insights.
 */
@Composable
private fun SummaryCard(state: PlaceTimelineUiState) {
    val summary = state.summary ?: return
    val dominantColor = state.visits.firstOrNull()
        ?.let { parseHexColorOrNull(it.color) }
    GlassCard(
        modifier = Modifier.padding(vertical = AevumSpacing.xs),
        accentColor = dominantColor
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
            Text(
                text = state.dayTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.place_timeline_summary_lede,
                    summary.visitCount,
                    TimeFormatting.formatDuration(summary.onTheRoadMs)
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            summary.placeTotals.take(3).forEach { (name, ms) ->
                val visitColor = state.visits.firstOrNull { it.name == name }
                    ?.let { parseHexColorOrNull(it.color) }
                    ?: MaterialTheme.colorScheme.primary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(visitColor)
                    )
                    Text(
                        text = name,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = TimeFormatting.formatDuration(ms),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Eine Visit-Zeile: linke Rail (Startzeit + Punkt + Linie), rechts die
 * Orts-Karte mit Icon-Kreis, Name, Dauer und Evidenz-Badge.
 */
@Composable
private fun VisitRow(visit: PlaceVisit) {
    val accent = parseHexColorOrNull(visit.color) ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Linke Rail: Startzeit über dem Punkt, Punkt + Halte-Linie darunter.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(30.dp)
        ) {
            Text(
                text = TimeFormatting.formatTime(visit.startAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (visit.isOngoing) MaterialTheme.colorScheme.primary else accent)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(accent.copy(alpha = 0.25f))
            )
        }
        Spacer(Modifier.width(AevumSpacing.sm))
        // Orts-Karte.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AevumRadius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(AevumSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = visit.icon, fontSize = 18.sp)
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = visit.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (visit.isOngoing) {
                        Spacer(Modifier.width(AevumSpacing.xs))
                        Text(
                            text = stringResource(R.string.place_timeline_ongoing),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    text = badgeLabel(visit.evidence) + " · " +
                        TimeFormatting.formatTime(visit.startAt) + "–" +
                        TimeFormatting.formatTime(visit.endAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                text = TimeFormatting.formatDuration(visit.durationMs),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = accent
            )
        }
    }
}

/**
 * "Unterwegs"-Zeile zwischen zwei Visits — bewusst dezent (keine Karte,
 * gepunktete Optik über die Rail), keine erfundene Ortsangabe.
 */
@Composable
private fun RoadRow(startAt: Long, endAt: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(30.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            )
        }
        Spacer(Modifier.width(AevumSpacing.sm))
        Text(
            text = stringResource(R.string.place_timeline_road_prefix) +
                " · " + TimeFormatting.formatTime(startAt) + "–" +
                TimeFormatting.formatTime(endAt) + " · " +
                TimeFormatting.formatDuration(endAt - startAt),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PlaceTimelineEmpty(state: PlaceTimelineUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AevumSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "🗺️", fontSize = 44.sp)
        Spacer(Modifier.height(AevumSpacing.md))
        Text(
            text = stringResource(R.string.place_timeline_empty_hint),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// Helfer (nicht-komposabel)
// ─────────────────────────────────────────────────────────────────────

private val navigatorDateFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM.", java.util.Locale.getDefault())

private fun formatNavigatorDate(date: LocalDate): String = date.format(navigatorDateFormatter)

@Composable
private fun badgeLabel(evidence: VisitEvidence): String = when (evidence) {
    VisitEvidence.NAMED_PLACE -> stringResource(R.string.place_timeline_badge_named)
    VisitEvidence.GEOFENCE_LONG -> stringResource(R.string.place_timeline_badge_long)
    VisitEvidence.GEOFENCE_SHORT -> stringResource(R.string.place_timeline_badge_short)
}

/**
 * Hilfsfunktion: Hex-Color-String (z.B. "#FF6366F1" oder "FF6366F1") zu
 * Compose Color. Same pattern as TimelineScreen.parseHexColorOrNull.
 */
private fun parseHexColorOrNull(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    return try {
        Color(cleaned.toLong(16))
    } catch (_: NumberFormatException) {
        null
    }
}