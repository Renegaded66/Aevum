package com.d_drostes_apps.aevum.ui.screens.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * M36: Zeiteingabe als drei Drehregler nebeneinander — Stunden, Minuten,
 * Sekunden. Ersetzt den alten Minuten-Textdialog („nichts mehr mit optional").
 *
 * Die Räder sind echte, schnappende LazyColumns: flingbar, mit Haptik-Tick
 * und mittiger Auswahl. Schnellwahl-Chips setzen die Räder zusätzlich
 * programmatisch.
 */
@Composable
fun HabitTimePickerDialog(
    prompt: GoalTimePrompt?,
    onConfirm: (seconds: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    if (prompt == null) return

    val initial = prompt.initialSeconds.coerceAtLeast(0L).toInt()
    var hours by remember(prompt.goalId, prompt.checkinId, prompt.dateKey) { mutableIntStateOf(initial / 3600) }
    var minutes by remember(prompt.goalId, prompt.checkinId, prompt.dateKey) { mutableIntStateOf((initial % 3600) / 60) }
    var seconds by remember(prompt.goalId, prompt.checkinId, prompt.dateKey) { mutableIntStateOf(initial % 60) }
    val total = hours * 3600 + minutes * 60 + seconds
    val dateLabel = habitDateLabel(prompt.dateKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        if (prompt.initialSeconds > 0L) R.string.goal_time_edit_title
                        else R.string.goal_time_title
                    ),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = prompt.goalTitle + " · " + dateLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                ) {
                    WheelPicker(
                        values = (0..23).toList(),
                        selected = hours,
                        onSelected = { hours = it },
                        label = stringResource(R.string.goal_time_hours),
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        values = (0..59).toList(),
                        selected = minutes,
                        onSelected = { minutes = it },
                        label = stringResource(R.string.goal_time_minutes),
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        values = (0..59).toList(),
                        selected = seconds,
                        onSelected = { seconds = it },
                        label = stringResource(R.string.goal_time_seconds),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics.formatClock(total.toLong()),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(5 * 60, 10 * 60, 15 * 60, 30 * 60, 3600).forEach { preset ->
                        Surface(
                            onClick = {
                                hours = preset / 3600
                                minutes = (preset % 3600) / 60
                                seconds = 0
                            },
                            shape = RoundedCornerShape(AevumRadius.full),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (preset < 3600) "${preset / 60}m" else "1h",
                                modifier = Modifier.padding(vertical = 6.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Text(
                    stringResource(R.string.goal_time_not_timeline),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(total) },
                enabled = total > 0
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (prompt.initialSeconds > 0L) {
                    TextButton(onClick = onRemove) {
                        Text(
                            stringResource(R.string.goal_time_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}

@Composable
private fun WheelPicker(
    values: List<Int>,
    selected: Int,
    onSelected: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 40.dp
) {
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    val state = rememberPagerState(initialPage = initialIndex, pageCount = { values.size })
    val haptics = LocalHapticFeedback.current
    val centered = state.currentPage
    val currentSelected by rememberUpdatedState(selected)

    // Beim Scrollen: nach dem Einrasten den mittigen Wert übernehmen.
    // VerticalPager + Fixed-Page-Size + Content-Padding macht die mittige
    // Seite zur currentPage — keine Offset-Arithmetik, kein Danebenliegen.
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage to state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (page, scrolling) ->
                if (!scrolling) {
                    val value = values.getOrNull(page) ?: return@collect
                    if (value != currentSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelected(value)
                    }
                }
            }
    }

    // Programmatische Änderung (Schnellwahl): Rad dorthin drehen.
    LaunchedEffect(selected) {
        val index = values.indexOf(selected)
        if (index >= 0 && !state.isScrollInProgress && state.currentPage != index) {
            state.scrollToPage(index)
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * 5),
            contentAlignment = Alignment.Center
        ) {
            // Auswahlband hinter den Zahlen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            )
            VerticalPager(
                state = state,
                pageSize = PageSize.Fixed(itemHeight),
                contentPadding = PaddingValues(vertical = itemHeight * 2),
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    val active = page == centered
                    Text(
                        text = (values.getOrNull(page) ?: 0).toString().padStart(2, '0'),
                        fontSize = if (active) 26.sp else 18.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Nur für die Vorschau im Dialog — dieselbe Formatierung wie die Karten. */
@Composable
private fun habitDateLabel(dateKey: String): String {
    val date = runCatching { java.time.LocalDate.parse(dateKey) }.getOrNull() ?: return dateKey
    val today = java.time.LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.goal_tracker_today)
        today.minusDays(1) -> stringResource(R.string.goal_tracker_yesterday)
        else -> date.format(
            java.time.format.DateTimeFormatter.ofPattern(
                "EEE, d. MMM",
                java.util.Locale.getDefault()
            )
        )
    }
}
