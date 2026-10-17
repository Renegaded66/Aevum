package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.ui.components.PlayfulProgressBar
import com.d_drostes_apps.aevum.ui.components.PmCheckbox
import com.d_drostes_apps.aevum.ui.components.PmColorDot
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette
import java.time.LocalDate

/** M19.1: Ein Eintrag im Kontextmenü einer Zeile (Heute, Morgen, …). */
data class PmRowAction(val label: String, val onClick: () -> Unit)

/**
 * M19.2: Eine AUFGABEN-Zeile im Projektmanagement — die To-do-Zeile.
 *
 * EINE Zeile für alle Orte (Heute-Liste, Projekt-Detailseite, Tag-planen),
 * damit sich die Bedienung nie unterschiedlich anfühlt:
 *  - Tipp auf das Häkchen oder die Zeile = erledigt/rückgängig (die Kernhandlung)
 *  - Langdruck = Mehrfachauswahl (nur wo [selectionMode] aktiv ist)
 *  - „⋮⋮" rechts = ziehen (Drag & Drop, siehe [pmDragHandle]) — der Griff
 *    wird nur dort gezeigt, wo sortiert werden darf
 *  - „⌄" = Menü der Zeile (Datum, Bearbeiten, Löschen)
 *
 * Der Funke ([burst]) läuft nur für die zuletzt abgehakte Aufgabe — gesteuert
 * über den ViewModel-Zustand, nicht über die Komposition (sonst würde jede
 * Neukomposition funkeln).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PmTaskRowView(
    task: PmNode,
    accent: Color,
    modifier: Modifier = Modifier,
    rolloverDays: Int = 0,
    burst: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = true,
    dateChip: String? = null,
    menuActions: List<PmRowAction> = emptyList(),
    dragHandle: (@Composable () -> Unit)? = null,
    onToggle: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val done = task.doneAt != null
    val titleColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(240),
        label = "pm-task-title"
    )
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.md))
            .background(
                if (selected && selectionMode) PmPalette.soft(accent, 0.16f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggle() else (onClick ?: onToggle).invoke()
                },
                onLongClick = onLongPress?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                }
            )
            .padding(horizontal = AevumSpacing.xs, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) accent else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    )
                }
            }
            Spacer(Modifier.width(AevumSpacing.xs))
        } else {
            PmCheckbox(
                checked = done,
                accent = accent,
                onToggle = onToggle,
                size = 26.dp
            )
        }

        if (!selectionMode) {
            PmColorDot(color = accent, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.width(AevumSpacing.xs))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = titleColor,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (rolloverDays > 0 && !done) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PmChip(
                        text = "↻ " + androidx.compose.ui.res.stringResource(R.string.pm_carried_from),
                        color = PmPalette.rollover
                    )
                    if (dateChip != null) {
                        Spacer(Modifier.width(4.dp))
                        PmChip(text = dateChip, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (dateChip != null && !done) {
                Spacer(Modifier.height(3.dp))
                PmChip(text = dateChip, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (menuActions.isNotEmpty()) {
            Box {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.pm_pick_date),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label, fontSize = 14.sp) },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            }
                        )
                    }
                }
            }
        }

        dragHandle?.invoke()
    }
}

/**
 * M19.2: Der Zieh-Griff einer Aufgaben-Zeile („⋮⋮"). Nur hier beginnt das
 * Ziehen — Tippen und Scrollen der Liste bleiben unberührt.
 */
@Composable
fun PmDragHandleIcon(
    controller: PmDragController,
    key: String,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Filled.DragHandle,
        contentDescription = androidx.compose.ui.res.stringResource(R.string.pm_drag_to_sort),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .pmDragHandle(controller, key)
    )
}

/**
 * M19.1: Eine Container-Zeile (Projekt) für Baum- und Detail-Listen — mit
 * Fortschrittsbalken: „dass das Projekt tatsächlich fortschreitet" ist die
 * eigentliche Anforderung hinter dem Balken.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PmContainerRowView(
    node: PmNode,
    progress: PmProgress,
    accent: Color,
    modifier: Modifier = Modifier,
    breadcrumb: String? = null,
    dueLabel: String? = null,
    hasChildren: Boolean = false,
    expanded: Boolean = false,
    showExpand: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    menuActions: List<PmRowAction> = emptyList()
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.lg))
            .clickable { onClick() },
        shape = RoundedCornerShape(AevumRadius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(modifier = Modifier.padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showExpand && hasChildren) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(if (expanded) 90f else 0f)
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClick = { onExpand?.invoke() },
                                onLongClick = { onExpand?.invoke() }
                            )
                    )
                    Spacer(Modifier.width(2.dp))
                } else {
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = pmTypeGlyph(node.type),
                    fontSize = 14.sp
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (!breadcrumb.isNullOrEmpty()) {
                        Text(
                            text = breadcrumb,
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = node.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (progress.isComplete) {
                    Text("✓", color = PmPalette.complete, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = progress.label,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (progress.isComplete) PmPalette.complete else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (menuActions.isNotEmpty()) {
                    Box {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            menuActions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label, fontSize = 14.sp) },
                                    onClick = {
                                        menuOpen = false
                                        action.onClick()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (progress.total > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayfulProgressBar(
                        progress = progress.fraction,
                        accent = accent,
                        height = 7.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(7.dp)
                    )
                    Spacer(Modifier.width(AevumSpacing.sm))
                    Text(
                        text = progress.percent.toString() + " %",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (dueLabel != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dueLabel,
                    fontSize = 11.sp,
                    color = PmPalette.rollover
                )
            }
        }
    }
}

/** Kleiner Chip (übernommen, Datum, Frist). */
@Composable
fun PmChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AevumRadius.full))
            .background(PmPalette.soft(color, if (filled) 0.24f else 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Symbol je Knotenart — bewusst Textzeichen, damit es in jedem Theme sitzt. */
internal fun pmTypeGlyph(type: String): String = when (type) {
    PmType.PROJECT -> "📁"
    else -> "•"
}

/** ISO → "17.10." für Chips (kurz, lokal unabhängig lesbar). */
internal fun pmShortDate(iso: String?): String? = try {
    iso?.let { LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.")) }
} catch (e: Exception) {
    null
}

/** Frist in Worte gefasst: „Frist 27.10. · noch 10 Tage". */
@Composable
internal fun pmDueLabel(dueDate: String?, today: LocalDate): String? {
    val due = try {
        dueDate?.let { LocalDate.parse(it) }
    } catch (e: Exception) {
        null
    } ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
    val relative = when {
        days < 0 -> androidx.compose.ui.res.stringResource(R.string.pm_due_over, -days)
        days == 0 -> androidx.compose.ui.res.stringResource(R.string.pm_due_today)
        days == 1 -> androidx.compose.ui.res.stringResource(R.string.pm_due_tomorrow)
        else -> androidx.compose.ui.res.stringResource(R.string.pm_due_in_days, days)
    }
    return androidx.compose.ui.res.stringResource(R.string.pm_due, due.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))) +
        " · " + relative
}

/** Fortschrittsbalken-Zeile für Köpfe (Detailseite). */
@Composable
fun PmProgressRow(
    progress: PmProgress,
    accent: Color,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val animated by animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = tween(600),
        label = "pm-progress-row"
    )
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PlayfulProgressBar(
            progress = animated,
            accent = accent,
            height = 10.dp,
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
        )
        if (showLabel) {
            Spacer(Modifier.width(AevumSpacing.sm))
            Text(
                text = progress.label,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
