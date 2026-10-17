package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.domain.projects.PmDayHistory
import com.d_drostes_apps.aevum.ui.components.FireworksOverlay
import com.d_drostes_apps.aevum.ui.components.GemGlass
import com.d_drostes_apps.aevum.ui.components.PlayfulProgressBar
import com.d_drostes_apps.aevum.ui.components.PmColorDot
import com.d_drostes_apps.aevum.ui.components.shimmering
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * M19.1/M19.2: Projektmanagement — das neue Fragment.
 *
 * ÖFFNET SICH VON UNTEN: Der Übergang selbst passiert in der Navigation
 * (`AppNavHost`, eigener enterTransition für diese Route: Slide von unten).
 * Hier liegt die Sheet-Metapher selbst: abgerundete obere Ecken, ein Griff am
 * Kopf, Herunterziehen am Kopf schließt die Seite wieder. Bewusst NUR am Kopf
 * ziehbar — sonst würde jede Scroll-Geste zum Schließen führen.
 *
 * AUFBAU (M19.2):
 *  - Tab „Heute": NUR die für heute zugewiesenen Aufgaben als To-do-Liste
 *    (abhaken, ziehen, löschen) — und daneben, SEHR SCHMAL, das Glas, das
 *    sich mit jedem abgehakten Schritt sichtbar füllt.
 *  - Tab „Projekte": der komplette Baum (Projekte + Aufgaben), beliebig tief.
 */
@Composable
fun ProjectsScreen(
    onClose: () -> Unit,
    onOpenNode: (String) -> Unit,
    onNewChild: (parentId: String?, type: String) -> Unit,
    onEditNode: (String) -> Unit,
    onEditTask: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val actions = remember(onClose, onOpenNode, onNewChild, onEditNode, onEditTask) {
        PmActions(
            onClose = onClose,
            onOpenNode = onOpenNode,
            onNewChild = onNewChild,
            onEditNode = onEditNode,
            onEditTask = onEditTask,
            onTab = viewModel::setTab,
            onToggle = viewModel::toggleTask,
            onSchedule = viewModel::schedule,
            onScheduleAll = viewModel::scheduleAllOpen,
            onExpand = viewModel::toggleExpand,
            onExpandAll = viewModel::expandAll,
            onCollapseAll = viewModel::collapseAll,
            onOpenPlanner = viewModel::openPlanner,
            onClosePlanner = viewModel::closePlanner,
            onTogglePlannerSelection = viewModel::togglePlannerSelection,
            onPlannerGroupSelection = viewModel::setPlannerSelection,
            onPlannerDate = viewModel::setPlannerDate,
            onApplyPlanner = viewModel::applyPlanner,
            onRemoveFromPlan = viewModel::removeFromPlan,
            onExample = viewModel::createExample,
            onFinishCelebration = viewModel::finishCelebration,
            onDeleteTask = viewModel::deleteTask,
            onReorderToday = viewModel::reorderToday
        )
    }
    ProjectsContent(state = state, actions = actions, modifier = modifier)
}

/** Gebündelte Rückrufe — hält die Signatur des Inhalts lesbar und Previews einfach. */
data class PmActions(
    val onClose: () -> Unit = {},
    val onOpenNode: (String) -> Unit = {},
    /** Öffnet den Editor für ein neues (Unter-)Projekt — Aufgaben entstehen inline in der Liste. */
    val onNewChild: (String?, String) -> Unit = { _, _ -> },
    val onEditNode: (String) -> Unit = {},
    val onEditTask: (String) -> Unit = {},
    val onTab: (PmTab) -> Unit = {},
    val onToggle: (PmNode) -> Unit = {},
    val onSchedule: (List<String>, String?) -> Unit = { _, _ -> },
    val onScheduleAll: (String, String?) -> Unit = { _, _ -> },
    val onExpand: (String) -> Unit = {},
    val onExpandAll: (List<String>) -> Unit = {},
    val onCollapseAll: () -> Unit = {},
    val onOpenPlanner: () -> Unit = {},
    val onClosePlanner: () -> Unit = {},
    val onTogglePlannerSelection: (String) -> Unit = {},
    val onPlannerGroupSelection: (List<String>, Boolean) -> Unit = { _, _ -> },
    val onPlannerDate: (String?) -> Unit = {},
    val onApplyPlanner: () -> Unit = {},
    val onRemoveFromPlan: (List<String>) -> Unit = {},
    val onExample: () -> Unit = {},
    val onFinishCelebration: () -> Unit = {},
    val onDeleteTask: (PmNode) -> Unit = {},
    /** Drag & Drop in der Heute-Liste: lokale Indizes innerhalb der Projekt-Gruppe. */
    val onReorderToday: (String, Int, Int) -> Unit = { _, _, _ -> }
)

@Composable
private fun ProjectsContent(
    state: PmProjectsUiState,
    actions: PmActions,
    modifier: Modifier = Modifier
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val closeThreshold = with(density) { 140.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        PmSky()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffset
                    alpha = 1f - 0.35f * (dragOffset / closeThreshold).coerceIn(0f, 1f)
                    val f = (dragOffset / closeThreshold).coerceIn(0f, 1f)
                    scaleX = 1f - 0.03f * f
                    scaleY = 1f - 0.03f * f
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    PmDragHandle(
                        onClose = actions.onClose,
                        dragModifier = Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset > closeThreshold) {
                                        actions.onClose()
                                    } else {
                                        scope.launch {
                                            androidx.compose.animation.core.animate(
                                                initialValue = dragOffset,
                                                targetValue = 0f
                                            ) { value, _ -> dragOffset = value }
                                        }
                                    }
                                },
                                onVerticalDrag = { _, delta ->
                                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                }
                            )
                        }
                    )
                    PmTopBar(state = state, actions = actions)
                    PmTabSwitcher(tab = state.tab, onSelect = actions.onTab)
                    AnimatedContent(
                        targetState = state.tab,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(140))
                        },
                        modifier = Modifier.weight(1f),
                        label = "pm-tab-content"
                    ) { tab ->
                        when (tab) {
                            PmTab.TODAY -> ProjectsTodayTab(state = state, actions = actions)
                            PmTab.PROJECTS -> ProjectsTreeTab(state = state, actions = actions)
                        }
                    }
                }
            }
        }

        FireworksOverlay(
            play = state.celebrating,
            title = stringResource(R.string.pm_all_done),
            subtitle = stringResource(R.string.pm_all_done_sub),
            onFinished = actions.onFinishCelebration
        )
    }

    if (state.plannerOpen) {
        PmDayPlannerSheet(state = state, actions = actions)
    }
}

/** Griff + Kopfzeile: ziehbar (Sheet-Gefühl), schließt beim Ziehen nach unten. */
@Composable
private fun PmDragHandle(onClose: () -> Unit, dragModifier: Modifier) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .padding(top = AevumSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
        )
        Spacer(Modifier.height(AevumSpacing.xs))
        Text(
            text = stringResource(R.string.pm_drag_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PmTopBar(state: PmProjectsUiState, actions: PmActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.projects_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = state.today.format(DateTimeFormatter.ofPattern("EEEE, dd.MM.")) + " · " +
                    stringResource(
                        R.string.pm_structure_summary,
                        state.projectCount,
                        state.taskCount
                    ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!state.isCompletelyEmpty) {
            OutlinedButton(
                onClick = actions.onOpenPlanner,
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pm_plan_day), fontSize = 12.sp)
            }
        }
        IconButton(onClick = actions.onClose) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.pm_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tab-Umschalter mit gleitendem Indikator. */
@Composable
private fun PmTabSwitcher(tab: PmTab, onSelect: (PmTab) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AevumSpacing.md)
            .height(40.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        val half = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (tab == PmTab.TODAY) 0.dp else half,
            animationSpec = tween(240),
            label = "pm-tab-indicator"
        )
        Box(
            modifier = Modifier
                .padding(start = 3.dp, top = 3.dp)
                .width(half - 6.dp)
                .height(34.dp)
                .graphicsLayer { translationX = indicatorOffset.toPx() }
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            PmPalette.soft(MaterialTheme.colorScheme.primary, 0.55f),
                            PmPalette.soft(MaterialTheme.colorScheme.secondary, 0.45f)
                        )
                    )
                )
        )
        Row(modifier = Modifier.fillMaxSize()) {
            PmTabButton(
                label = stringResource(R.string.pm_tab_today),
                count = null,
                selected = tab == PmTab.TODAY,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(PmTab.TODAY) }
            )
            PmTabButton(
                label = stringResource(R.string.pm_tab_projects),
                count = null,
                selected = tab == PmTab.PROJECTS,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(PmTab.PROJECTS) }
            )
        }
    }
}

@Composable
private fun PmTabButton(
    label: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count != null) "$label · $count" else label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Tab „Heute" ────────────────────────────────────────────────────────────

/**
 * M19.2: Nur Aufgaben, die HEUTE dran sind (inkl. übernommener vom Vortag) —
 * als To-do-Liste zum Abhaken, Verschieben und Löschen. Daneben die schmale
 * Glas-Spalte: Jeder Haken lässt ein Juwel hineinfallen.
 */
@Composable
private fun ProjectsTodayTab(state: PmProjectsUiState, actions: PmActions) {
    val listState = rememberLazyListState()

    // Ziehbare Zeilen: die OFFENEN Aufgaben in Anzeige-Reihenfolge, mit ihrer
    // Projekt-Gruppe (Grenzen bleiben innerhalb der Gruppe).
    val openKeys = state.groups.flatMap { group ->
        group.rows.filter { !it.done }.map { it.node.id }
    }
    val groupOf = state.groups.flatMap { group ->
        group.rows.filter { !it.done }.map { it.node.id to group.id }
    }.toMap()
    val drag = rememberPmDragController(listState, openKeys, groupOf, actions.onReorderToday)

    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = AevumSpacing.md,
                end = AevumSpacing.sm,
                top = AevumSpacing.sm,
                bottom = AevumSpacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
        ) {
            item(key = "today-status") {
                PmTodayStatus(state = state, actions = actions)
            }

            if (state.groups.isNotEmpty()) {
                state.groups.forEach { group ->
                    val accent = PmPalette.color(group.colorHex)
                    item(key = "group-" + group.id) {
                        PmTodayGroupHeader(group = group, accent = accent, actions = actions)
                    }
                    items(group.rows, key = { "task-" + it.node.id }) { row ->
                        val draggable = !row.done
                        PmTaskRowView(
                            task = row.node,
                            accent = accent,
                            rolloverDays = row.rolloverDays,
                            burst = state.lastCompletedId == row.node.id,
                            dateChip = pmShortDate(row.node.scheduledDate),
                            menuActions = pmTodayMenuActions(row.node, state, actions),
                            dragHandle = if (draggable) {
                                { PmDragHandleIcon(controller = drag, key = row.node.id) }
                            } else null,
                            onToggle = { actions.onToggle(row.node) },
                            modifier = Modifier.pmDragFloat(drag, row.node.id)
                        )
                    }
                }
            } else if (!state.isCompletelyEmpty) {
                item(key = "no-plan") {
                    PmNoPlanCard(state = state, actions = actions)
                }
            }

            if (state.isCompletelyEmpty) {
                item(key = "empty") {
                    PmEmptyProjects(actions = actions)
                }
            }
        }

        PmGlassPanel(state = state)
    }
}

/** Kopf der Heute-Liste: Zähler, Übernahme-Hinweis, dünner Balken, 7-Tage-Streifen. */
@Composable
private fun PmTodayStatus(state: PmProjectsUiState, actions: PmActions) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.totalToday > 0) {
                    stringResource(R.string.pm_counter, state.doneToday, state.totalToday) + " · " +
                        stringResource(R.string.pm_counter_label)
                } else {
                    stringResource(R.string.pm_glass_empty)
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state.allDone) PmPalette.complete else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (state.rolloverToday > 0) {
                PmChip(
                    text = stringResource(R.string.pm_rollover_chip, state.rolloverToday),
                    color = PmPalette.rollover,
                    filled = true
                )
            }
        }
        if (state.totalToday == 0) {
            Text(
                text = stringResource(R.string.pm_glass_empty_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.allDone) {
            Text(
                text = stringResource(R.string.pm_all_done_sub),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PmPalette.complete
            )
        } else {
            Text(
                text = stringResource(R.string.pm_gems_remaining, state.pendingToday),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.totalToday > 0) {
            PlayfulProgressBar(
                progress = state.fraction,
                accent = MaterialTheme.colorScheme.primary,
                height = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        }
        PmDayStrip(history = state.history)
    }
}

/** Kopf einer Projekt-Gruppe in der Heute-Liste. */
@Composable
private fun PmTodayGroupHeader(group: PmTodayGroupUi, accent: Color, actions: PmActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PmColorDot(color = accent, size = 10.dp)
        Spacer(Modifier.width(AevumSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title.ifEmpty { stringResource(R.string.pm_no_parent) },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (group.breadcrumb.isNotEmpty()) {
                Text(
                    text = group.breadcrumb,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (group.progress.total > 0) {
            Text(
                text = group.progress.label,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (group.group.node != null) {
            TextButton(onClick = { actions.onOpenNode(group.id) }) {
                Text(stringResource(R.string.pm_task_details), fontSize = 11.sp)
            }
        }
    }
}

/**
 * Das schmale Glas NEBEN den Todos (Nutzerwunsch: „sehr schmal, dass man auch
 * die Todos richtig lesen kann; beim Abhaken kommt die Animation, wie das
 * Glas voller wird").
 */
@Composable
private fun PmGlassPanel(state: PmProjectsUiState) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = AevumSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.xs)
    ) {
        Text(
            text = stringResource(R.string.pm_counter, state.doneToday, state.totalToday),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (state.allDone) PmPalette.complete else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.pm_glass_short),
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GemGlass(
            gems = state.gems,
            ghostCount = state.ghostCount,
            full = state.allDone,
            maxColumns = 3,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

/** Sieben Tage Erfolg — der Verlauf, den `done_at` ganz von selbst liefert. */
@Composable
private fun PmDayStrip(history: List<PmDayHistory>) {
    val max = history.maxOfOrNull { it.done } ?: 0
    Column {
        Text(
            text = stringResource(R.string.pm_history),
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(AevumSpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.xs),
            verticalAlignment = Alignment.Bottom
        ) {
            history.forEach { day ->
                val fill = if (max <= 0) 0f else (day.done.toFloat() / max).coerceIn(0f, 1f)
                val height by animateDpAsState(
                    targetValue = (4 + 26 * fill).dp,
                    animationSpec = tween(600),
                    label = "pm-day-bar"
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(height)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (day.done > 0) {
                                        Brush.verticalGradient(
                                            listOf(
                                                PmPalette.complete.copy(alpha = if (day.isToday) 0.95f else 0.65f),
                                                PmPalette.soft(PmPalette.complete, 0.35f)
                                            )
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                            )
                                        )
                                    }
                                )
                        )
                    }
                    Text(
                        text = day.date.format(DateTimeFormatter.ofPattern("EE")).take(2),
                        fontSize = 9.sp,
                        color = if (day.isToday) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (day.done > 0) day.done.toString() else "·",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Kein Tagesplan: kein leeres Loch, sondern die Einladung zum Planen. */
@Composable
private fun PmNoPlanCard(state: PmProjectsUiState, actions: PmActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text(
                text = stringResource(R.string.pm_no_todos_today),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.pm_no_todos_today_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = actions.onOpenPlanner,
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.pm_plan_day))
            }
        }
    }
}

/**
 * Datumsmenü einer Heute-Zeile — inklusive Bearbeiten und LÖSCHEN
 * (Nutzerwunsch: „und auch die Möglichkeit zum löschen").
 */
@Composable
private fun pmTodayMenuActions(
    task: PmNode,
    state: PmProjectsUiState,
    actions: PmActions
): List<PmRowAction> {
    val today = state.today
    return listOf(
        PmRowAction(stringResource(R.string.pm_tomorrow)) { actions.onSchedule(listOf(task.id), today.plusDays(1).toString()) },
        PmRowAction(stringResource(R.string.pm_date_none)) { actions.onSchedule(listOf(task.id), null) },
        PmRowAction(stringResource(R.string.pm_edit)) { actions.onEditTask(task.id) },
        PmRowAction(stringResource(R.string.pm_delete)) { actions.onDeleteTask(task) }
    )
}

// ── Tab „Projekte" ─────────────────────────────────────────────────────────

@Composable
private fun ProjectsTreeTab(state: PmProjectsUiState, actions: PmActions) {
    if (state.isCompletelyEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AevumSpacing.md)
        ) {
            PmEmptyProjects(actions = actions)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(
            start = AevumSpacing.md,
            end = AevumSpacing.md,
            top = AevumSpacing.sm,
            bottom = AevumSpacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        item(key = "tree-hint") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_tree_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { actions.onExpandAll(state.treeRows.map { it.node.id }) }) {
                    Text(stringResource(R.string.pm_expand_all), fontSize = 11.sp)
                }
                TextButton(onClick = actions.onCollapseAll) {
                    Text(stringResource(R.string.pm_collapse_all), fontSize = 11.sp)
                }
            }
        }
        items(state.treeRows, key = { it.node.id }) { row ->
            if (row.node.isTask) {
                PmTaskRowView(
                    task = row.node,
                    accent = PmPalette.color(row.colorHex),
                    dateChip = pmShortDate(row.node.scheduledDate),
                    burst = state.lastCompletedId == row.node.id,
                    menuActions = listOf(
                        PmRowAction(stringResource(R.string.pm_today)) { actions.onSchedule(listOf(row.node.id), state.today.toString()) },
                        PmRowAction(stringResource(R.string.pm_tomorrow)) { actions.onSchedule(listOf(row.node.id), state.today.plusDays(1).toString()) },
                        PmRowAction(stringResource(R.string.pm_date_none)) { actions.onSchedule(listOf(row.node.id), null) },
                        PmRowAction(stringResource(R.string.pm_edit)) { actions.onEditTask(row.node.id) },
                        PmRowAction(stringResource(R.string.pm_delete)) { actions.onDeleteTask(row.node) }
                    ),
                    onToggle = { actions.onToggle(row.node) },
                    modifier = Modifier.padding(start = (row.depth * 14).dp)
                )
            } else {
                PmContainerRowView(
                    node = row.node,
                    progress = row.progress,
                    accent = PmPalette.color(row.colorHex),
                    breadcrumb = row.breadcrumb,
                    dueLabel = pmDueLabel(row.node.dueDate, state.today),
                    hasChildren = row.hasChildren,
                    expanded = row.expanded,
                    showExpand = true,
                    onExpand = { actions.onExpand(row.node.id) },
                    onClick = { actions.onOpenNode(row.node.id) },
                    menuActions = listOf(
                        PmRowAction(stringResource(R.string.pm_edit)) { actions.onEditNode(row.node.id) },
                        PmRowAction(stringResource(R.string.pm_node_new_sub)) { actions.onNewChild(row.node.id, PmType.PROJECT) }
                    ),
                    modifier = Modifier.padding(start = (row.depth * 14).dp)
                )
            }
        }
        item(key = "new-project") {
            Button(
                onClick = { actions.onNewChild(null, PmType.PROJECT) },
                shape = RoundedCornerShape(AevumRadius.lg),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AevumRadius.lg))
                    .shimmering(intensity = 0.22f)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pm_editor_new_project))
            }
        }
    }
}

@Composable
private fun PmEmptyProjects(actions: PmActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text(
                text = stringResource(R.string.pm_empty_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.pm_empty_body),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(AevumSpacing.sm))
            Button(
                onClick = actions.onExample,
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(percent = 50))
                    .shimmering(intensity = 0.26f)
            ) {
                Text(stringResource(R.string.pm_example_insert))
            }
            OutlinedButton(
                onClick = { actions.onNewChild(null, PmType.PROJECT) },
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.pm_editor_new_project))
            }
        }
    }
}

/** Animierter Farbhimmel hinter dem Sheet. */
@Composable
private fun PmSky() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pm-sky")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(14000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pm-sky-shift"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = PmPalette.sky,
                    start = Offset(0f, 0f),
                    end = Offset(900f * (0.6f + shift * 0.8f), 1600f)
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    translationX = 40f * shift
                    translationY = -30f * shift
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            PmPalette.soft(MaterialTheme.colorScheme.primary, 0.30f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    translationX = -30f * shift
                    translationY = 20f * shift
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            PmPalette.soft(MaterialTheme.colorScheme.secondary, 0.22f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/** Beschriftung der Knotenart (nur noch zwei Arten). */
@Composable
internal fun pmTypeLabel(type: String): String = when (type) {
    PmType.PROJECT -> stringResource(R.string.pm_type_project)
    else -> stringResource(R.string.pm_type_task)
}
