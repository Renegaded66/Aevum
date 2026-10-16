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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
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
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.domain.projects.PmDayHistory
import com.d_drostes_apps.aevum.ui.components.FireworksOverlay
import com.d_drostes_apps.aevum.ui.components.GemGlass
import com.d_drostes_apps.aevum.ui.components.PlayfulProgressBar
import com.d_drostes_apps.aevum.ui.components.PmColorDot
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * M19.1: Projektmanagement — das neue Fragment.
 *
 * ÖFFNET SICH VON UNTEN: Der Übergang selbst passiert in der Navigation
 * (`AppNavHost`, eigener enterTransition für diese Route: Slide von unten).
 * Hier liegt die Sheet-Metapher selbst: abgerundete obere Ecken, ein Griff am
 * Kopf, Herunterziehen am Kopf schließt die Seite wieder. Bewusst NUR am Kopf
 * ziehbar — sonst würde jede Scroll-Geste zum Schließen führen.
 *
 * AUFBAU (siehe docs/M19-1-PROJEKTMANAGEMENT.md §4.2):
 *  - Tab „Heute" (Standard): Glas mit Juwelen und Gegenplätzen, Tages-Karussell
 *    je Aufgabe (Swipe links/rechts), Backlog zum Hereinziehen.
 *  - Tab „Projekte": der komplette Baum, beliebig tief.
 */
@Composable
fun ProjectsScreen(
    onClose: () -> Unit,
    onOpenNode: (String) -> Unit,
    onNewChild: (parentId: String?, type: String) -> Unit,
    onEditNode: (String) -> Unit,
    onNewTodos: (parentId: String?) -> Unit,
    onEditTodo: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val actions = remember(onClose, onOpenNode, onNewChild, onEditNode, onNewTodos, onEditTodo) {
        PmActions(
            onClose = onClose,
            onOpenNode = onOpenNode,
            onNewChild = onNewChild,
            onEditNode = onEditNode,
            onNewTodos = onNewTodos,
            onEditTodo = onEditTodo,
            onTab = viewModel::setTab,
            onToggle = viewModel::toggleTodo,
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
            onDeleteTodo = viewModel::deleteTodo
        )
    }
    ProjectsContent(state = state, actions = actions, modifier = modifier)
}

/** Gebündelte Rückrufe — hält die Signatur des Inhalts lesbar und Previews einfach. */
data class PmActions(
    val onClose: () -> Unit = {},
    val onOpenNode: (String) -> Unit = {},
    val onNewChild: (String?, String) -> Unit = { _, _ -> },
    val onEditNode: (String) -> Unit = {},
    val onNewTodos: (String?) -> Unit = {},
    val onEditTodo: (String) -> Unit = {},
    val onTab: (PmTab) -> Unit = {},
    val onToggle: (com.d_drostes_apps.aevum.data.model.PmNode) -> Unit = {},
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
    val onDeleteTodo: (com.d_drostes_apps.aevum.data.model.PmNode) -> Unit = {}
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
                        state.taskCount,
                        state.totalTodos
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

@Composable
private fun ProjectsTodayTab(state: PmProjectsUiState, actions: PmActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AevumSpacing.md,
            end = AevumSpacing.md,
            top = AevumSpacing.sm,
            bottom = AevumSpacing.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item(key = "glass") {
            PmGlassHero(state = state, actions = actions)
        }
        if (state.groups.isNotEmpty()) {
            item(key = "carousel-header") {
                Column {
                    Text(
                        text = stringResource(R.string.pm_today_tasks),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.pm_swipe_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item(key = "carousel") {
                PmTaskCarousel(state = state, actions = actions)
            }
        } else {
            item(key = "no-today") {
                PmNoPlanCard(state = state, actions = actions)
            }
        }
        if (state.backlog.isNotEmpty()) {
            item(key = "backlog") {
                PmBacklogSection(state = state, actions = actions)
            }
        }
        if (state.isCompletelyEmpty) {
            item(key = "empty") {
                PmEmptyProjects(actions = actions)
            }
        }
    }
}

/**
 * Das Glas mit allem, was in einem Blick da sein muss:
 * Zähler, Gefäß mit Juwelen und Gegenplätzen, Balken, 7-Tage-Streifen.
 */
@Composable
private fun PmGlassHero(state: PmProjectsUiState, actions: PmActions) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (state.allDone) PmPalette.complete.copy(alpha = 0.55f) else accent.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_glass_title),
                    fontSize = 13.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.doneToday.toString(),
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (state.allDone) PmPalette.complete else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(AevumSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.totalToday > 0) {
                            stringResource(R.string.pm_counter, state.doneToday, state.totalToday) + " · " +
                                stringResource(R.string.pm_counter_label)
                        } else {
                            stringResource(R.string.pm_glass_empty)
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.allDone) stringResource(R.string.pm_all_done_sub)
                        else if (state.totalToday > 0) stringResource(R.string.pm_gems_remaining, state.pendingToday)
                        else stringResource(R.string.pm_glass_empty_hint),
                        fontSize = 11.sp,
                        color = if (state.allDone) PmPalette.complete else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            GemGlass(
                gems = state.gems,
                ghostCount = state.ghostCount,
                full = state.allDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp),
                maxGems = 60
            )

            PlayfulProgressBar(
                progress = state.fraction,
                accent = accent,
                height = 11.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(11.dp)
            )

            PmDayStrip(history = state.history)

            OutlinedButton(
                onClick = actions.onOpenPlanner,
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pm_plan_day), fontSize = 13.sp)
            }
        }
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
                    targetValue = (4 + 30 * fill).dp,
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
                            .height(34.dp),
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
            if (state.totalTodos > 0 && state.backlogTotal == 0) {
                // Alles ist verplant, aber nichts für heute → Tag bewusst leer.
                Text(
                    text = stringResource(R.string.pm_backlog_empty),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Das Karussell: eine Karte je Aufgabe mit heutigen Todos.
 * Swipe nach links/rechts wechselt die Aufgabe (Anforderung des Nutzers).
 */
@Composable
private fun PmTaskCarousel(state: PmProjectsUiState, actions: PmActions) {
    val groups = state.groups
    if (groups.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { groups.size })

    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            pageSpacing = AevumSpacing.md
        ) { page ->
            val group = groups[page]
            PmTaskCard(group = group, state = state, actions = actions)
        }
        Spacer(Modifier.height(AevumSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            groups.forEachIndexed { index, group ->
                val active = index == pagerState.currentPage
                val size by animateDpAsState(
                    targetValue = if (active) 22.dp else 8.dp,
                    animationSpec = tween(220),
                    label = "pm-dot"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .width(size)
                        .height(8.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            if (active) PmPalette.color(group.colorHex)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                )
            }
        }
    }
}

@Composable
private fun PmTaskCard(
    group: PmTodayGroupUi,
    state: PmProjectsUiState,
    actions: PmActions
) {
    val accent = PmPalette.color(group.colorHex)
    val doneCount = group.rows.count { it.done }
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(
                    start = AevumSpacing.md,
                    end = AevumSpacing.md,
                    top = AevumSpacing.md,
                    bottom = AevumSpacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = group.breadcrumb.ifEmpty { stringResource(R.string.pm_no_parent) },
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.title.ifEmpty { stringResource(R.string.pm_no_parent) },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    val due = pmDueLabel(group.group.node?.dueDate, state.today)
                    if (due != null) {
                        PmChip(text = due, color = PmPalette.rollover, filled = true)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayfulProgressBar(
                        progress = group.progress.fraction,
                        accent = accent,
                        height = 12.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                    )
                    Spacer(Modifier.width(AevumSpacing.sm))
                    Text(
                        text = stringResource(
                            R.string.pm_task_progress,
                            group.progress.done,
                            group.progress.total
                        ),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (group.progress.isComplete) PmPalette.complete
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (group.progress.isComplete) {
                    Text(
                        text = "✓ " + stringResource(R.string.pm_task_done),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PmPalette.complete
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = AevumSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(group.rows, key = { it.node.id }) { row ->
                    PmTodoRowView(
                        todo = row.node,
                        accent = accent,
                        rolloverDays = row.rolloverDays,
                        burst = state.lastCompletedId == row.node.id,
                        dateChip = pmShortDate(row.node.scheduledDate),
                        menuActions = pmTodoMenuActions(row.node, state, actions),
                        onToggle = { actions.onToggle(row.node) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    group.group.node?.let { actions.onScheduleAll(it.id, state.today.toString()) }
                }) {
                    Text(stringResource(R.string.pm_pull_all_today), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val target = group.group.node?.id
                    if (target != null) actions.onOpenNode(target) else actions.onOpenPlanner()
                }) {
                    Text(stringResource(R.string.pm_task_details), fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * Backlog: offene Todos ohne Datum, gruppiert je Aufgabe.
 * Hier zieht Devon morgens die Schritte in den Tag — einzeln oder komplett.
 */
@Composable
private fun PmBacklogSection(state: PmProjectsUiState, actions: PmActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    ) {
        Column(modifier = Modifier.padding(AevumSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pm_backlog_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.pm_backlog_hint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PmChip(
                    text = state.backlogTotal.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    filled = true
                )
            }
            Spacer(Modifier.height(AevumSpacing.sm))
            state.backlog.forEach { group ->
                val accent = PmPalette.color(
                    group.group.node?.color ?: com.d_drostes_apps.aevum.domain.projects.PmColors.colorFor(group.group.id)
                )
                Column(modifier = Modifier.padding(bottom = AevumSpacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PmColorDot(color = accent)
                        Spacer(Modifier.width(AevumSpacing.xs))
                        Text(
                            text = group.group.title.ifEmpty { stringResource(R.string.pm_no_parent) },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            group.group.node?.let { actions.onScheduleAll(it.id, state.today.toString()) }
                        }) {
                            Text(stringResource(R.string.pm_pull_all_today), fontSize = 11.sp)
                        }
                    }
                    group.todos.take(6).forEach { todo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PmTodoRowView(
                                todo = todo,
                                accent = accent,
                                menuActions = pmTodoMenuActions(todo, state, actions),
                                onToggle = { actions.onToggle(todo) },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { actions.onSchedule(listOf(todo.id), state.today.toString()) }) {
                                Text(stringResource(R.string.pm_pull_today), fontSize = 11.sp)
                            }
                        }
                    }
                    if (group.todos.size > 6) {
                        TextButton(onClick = {
                            group.group.node?.let { actions.onOpenNode(it.id) }
                        }) {
                            Text(
                                text = stringResource(R.string.pm_more_todos, group.todos.size - 6),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Datumsmenü einer Zeile — der Einzeltipp-Weg zur Tageszuweisung.
 * Bewusst als @Composable: Die Beschriftungen kommen aus den Ressourcen,
 * damit die App auch in Englisch korrekt bleibt.
 */
@Composable
private fun pmTodoMenuActions(
    todo: com.d_drostes_apps.aevum.data.model.PmNode,
    state: PmProjectsUiState,
    actions: PmActions
): List<PmRowAction> {
    val today = state.today
    return listOf(
        PmRowAction(stringResource(R.string.pm_today)) { actions.onSchedule(listOf(todo.id), today.toString()) },
        PmRowAction(stringResource(R.string.pm_tomorrow)) { actions.onSchedule(listOf(todo.id), today.plusDays(1).toString()) },
        PmRowAction(stringResource(R.string.pm_date_none)) { actions.onSchedule(listOf(todo.id), null) },
        PmRowAction(stringResource(R.string.pm_delete)) { actions.onDeleteTodo(todo) }
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
            PmContainerRowView(
                node = row.node,
                progress = row.progress,
                accent = PmPalette.color(row.colorHex),
                breadcrumb = if (row.node.type == PmType.TASK) null else pmTypeLabel(row.node.type),
                dueLabel = pmDueLabel(row.node.dueDate, state.today),
                hasChildren = row.hasChildren,
                expanded = row.expanded,
                showExpand = true,
                onExpand = { actions.onExpand(row.node.id) },
                onClick = { actions.onOpenNode(row.node.id) },
                menuActions = listOf(
                    PmRowAction(stringResource(R.string.pm_edit)) { actions.onEditNode(row.node.id) },
                    PmRowAction(stringResource(R.string.pm_node_add_todo)) { actions.onNewTodos(row.node.id) }
                ),
                modifier = Modifier.padding(start = (row.depth * 14).dp)
            )
        }
        item(key = "new-project") {
            Button(
                onClick = { actions.onNewChild(null, PmType.PROJECT) },
                shape = RoundedCornerShape(AevumRadius.lg),
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
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

/** Beschriftung der Knotenart. */
@Composable
internal fun pmTypeLabel(type: String): String = when (type) {
    PmType.PROJECT -> stringResource(R.string.pm_type_project)
    PmType.CATEGORY -> stringResource(R.string.pm_type_category)
    PmType.TASK -> stringResource(R.string.pm_type_task)
    else -> stringResource(R.string.pm_type_todo)
}
