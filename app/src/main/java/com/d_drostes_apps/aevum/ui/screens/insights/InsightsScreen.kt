package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.AnimatedGradientBar
import com.d_drostes_apps.aevum.ui.components.AnimatedNumberCounter
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.Duration

/**
 * M17.4: Statistik-Screen — futuristic Glassmorphism-Redesign.
 *
 * - Hero-Header mit animated Number-Counter (Total-Minuten)
 * - Period-Toggle (Heute / Woche / Monat)
 * - Breakdown-Toggle (Aktivität / Kategorie) — animiert
 * - Animierte Gradient-Bars (verzögert pro Item → Kaskaden-Effekt)
 * - Glass-Cards mit Gradient-Border
 */
@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    // M18.35: Link zur Lebenszeit-Ansicht
    onOpenLifeView: () -> Unit = {},
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AevumSpacing.lg)
        ) {
            // 1) Hero-Header
            item { InsightsHero(uiState, onOpenLifeView) }

            // 2) Period-Toggle
            item {
                PeriodSelector(
                    selected = uiState.selectedPeriod,
                    onSelect = viewModel::selectPeriod
                )
            }

            // 3) Breakdown-Toggle (Aktivität / Kategorie)
            item {
                BreakdownToggle(
                    mode = uiState.breakdownMode,
                    onSelect = viewModel::setBreakdownMode
                )
            }

            // 4) Top-Liste (animierte Bars)
            if (uiState.topBreakdown.isNotEmpty()) {
                item {
                    GlassCard(
                        accentColor = uiState.topBreakdown.firstOrNull()?.color
                    ) {
                        Column {
                            Text(
                                text = when (uiState.breakdownMode) {
                                    BreakdownMode.Activity -> stringResource(R.string.insights_top_activities)
                                    // M18.66-FIX17: keine Top-Begrenzung mehr —
                                    // ALLE Kategorien werden angezeigt.
                                    BreakdownMode.Category -> stringResource(R.string.common_categories)
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(AevumSpacing.md))
                            val maxMs = uiState.topBreakdown.maxOf { it.durationMs }.coerceAtLeast(1L)
                            uiState.topBreakdown.forEachIndexed { index, slice ->
                                TopSliceRow(slice = slice, maxMs = maxMs, index = index)
                                if (index < uiState.topBreakdown.lastIndex) {
                                    Spacer(Modifier.height(AevumSpacing.sm))
                                }
                            }
                        }
                    }
                }
            }

            // 5) Period-Änderungen
            if (uiState.changes.isNotEmpty()) {
                item {
                    GlassCard(accentColor = MaterialTheme.colorScheme.primary) {
                        Column {
                            Text(
                                stringResource(R.string.insights_changes_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(AevumSpacing.md))
                            uiState.changes.forEachIndexed { index, change ->
                                ChangeRow(change = change, index = index)
                                if (index < uiState.changes.lastIndex) {
                                    Spacer(Modifier.height(AevumSpacing.sm))
                                }
                            }
                        }
                    }
                }
            }

            // 6) Insight-Cards
            if (uiState.insightCards.isNotEmpty()) {
                items(uiState.insightCards) { card ->
                    GlassCard(accentColor = MaterialTheme.colorScheme.tertiary) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Spacer(Modifier.width(AevumSpacing.md))
                            Column {
                                Text(
                                    card.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    card.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // 7) Empty-State
            if (!uiState.hasData) {
                item {
                    GlassCard(accentColor = MaterialTheme.colorScheme.outline) {
                        Column {
                            Text(
                                stringResource(R.string.insights_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(AevumSpacing.sm))
                            Text(
                                stringResource(R.string.insights_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightsHero(uiState: InsightsUiState, onOpenLifeView: () -> Unit) {
    // M18.39: Exakte Anzeige OHNE Rundung. Vorher: Dezimal-Stunden mit
    // 1 Nachkommastelle ("7,5 Std") PLUS Minuten daneben — redundant und
    // wirkte gerundet. Jetzt: exakte Minuten -> "7 Std 32 Min", 100% praezise.
    val totalMinutes = uiState.totalMsIncludingAllowances / 60_000
    val hoursPart = totalMinutes / 60
    val minutesPart = totalMinutes % 60
    val accentColor = MaterialTheme.colorScheme.primary
    GlassCard(accentColor = accentColor) {
        Column {
            Text(
                uiState.periodLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(AevumSpacing.xs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$hoursPart",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_hours_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.width(AevumSpacing.md))
                Text(
                    "$minutesPart",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.insights_minutes_short),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Spacer(Modifier.height(AevumSpacing.xs))
            Text(
                uiState.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            // M18.35: Lebenszeit-Button — der Einstieg in die
            // "Was bleibt dir?"-Ansicht. Bewusst als dezenter Chip,
            // damit die Insights nicht überladen werden.
            Spacer(Modifier.height(AevumSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .clickable(onClick = onOpenLifeView)
                    .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Text("⏳", fontSize = 18.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.insights_lifetime_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        stringResource(R.string.insights_lifetime_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    selected: InsightPeriod,
    onSelect: (InsightPeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        InsightPeriod.values().forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = {
                    Text(
                        when (period) {
                            InsightPeriod.Today -> stringResource(R.string.common_today)
                            InsightPeriod.Week -> stringResource(R.string.insights_period_this_week)
                            InsightPeriod.Month -> stringResource(R.string.insights_period_this_month)
                        }
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun BreakdownToggle(
    mode: BreakdownMode,
    onSelect: (BreakdownMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        FilterChip(
            selected = mode == BreakdownMode.Activity,
            onClick = { onSelect(BreakdownMode.Activity) },
            label = { Text(stringResource(R.string.insights_breakdown_activity)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        FilterChip(
            selected = mode == BreakdownMode.Category,
            onClick = { onSelect(BreakdownMode.Category) },
            label = { Text(stringResource(R.string.insights_breakdown_category)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Category,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }
}

@Composable
private fun TopSliceRow(slice: TopActivitySlice, maxMs: Long, index: Int) {
    val progress = (slice.durationMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // M18.13: Icon in farbigem Kreis statt nacktem Punkt
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(slice.color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (slice.icon.isBlank()) "•" else slice.icon,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                slice.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${slice.percent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                formatDuration(slice.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(Modifier.height(4.dp))
        AnimatedGradientBar(
            progress = progress,
            color = slice.color,
            modifier = Modifier.fillMaxWidth(),
            // Kaskaden-Delay: 80ms pro Item
            animationDelayMs = index * 80
        )
    }
}

@Composable
private fun ChangeRow(change: PeriodChange, index: Int) {
    val percent = change.percentDelta
    val color = when {
        change.deltaMs > 0 -> Color(0xFF66BB6A)
        change.deltaMs < 0 -> Color(0xFFEF5350)
        else -> MaterialTheme.colorScheme.outline
    }
    val sign = when {
        change.deltaMs > 0 -> "+"
        change.deltaMs < 0 -> "−"
        else -> ""
    }
    val pct = percent?.let { " ($sign${kotlin.math.abs(it)}%)" }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(change.color)
        )
        Spacer(Modifier.width(AevumSpacing.sm))
        Text(
            change.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${sign}${formatDuration(kotlin.math.abs(change.deltaMs))}$pct",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val total = ms / 60_000L
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
