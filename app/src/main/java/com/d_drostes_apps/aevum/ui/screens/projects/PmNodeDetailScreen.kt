package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.d_drostes_apps.aevum.ui.components.PlayfulProgressBar
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.ui.theme.PmPalette

/**
 * M19.1: Detailseite eines Projekts, einer Kategorie oder einer Aufgabe.
 *
 * DER NUTZEN: Im Baum sieht man die Struktur, hier sieht man die ARBEIT. Alle
 * Schritte einer Aufgabe liegen an einem Ort, in einer Reihenfolge (offene
 * zuerst, übernommene oben), mit Fortschritt, Frist und Notiz. Hier passiert
 * auch die Mehrfachzuweisung von Tagen: Langdruck markiert, ein Datum wählen,
 * fertig — statt 25 Einzelklicks.
 */
@Composable
fun PmNodeDetailScreen(
    onBack: () -> Unit,
    onEditNode: (String) -> Unit,
    onNewTodos: (String) -> Unit,
    onEditTodo: (String) -> Unit,
    onNewChild: (parentId: String, type: String) -> Unit,
    onOpenNode: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PmNodeDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val accent = PmPalette.color(state.colorHex)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            PmDetailTopBar(
                state = state,
                onBack = onBack,
                onEdit = { onEditNode(state.node?.id.orEmpty()) },
                onMove = viewModel::move,
                onScheduleAll = { viewModel.scheduleAllOpen(state.today.toString()) },
                onRequestDelete = viewModel::requestDelete
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selectionMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                PmSelectionBar(
                    count = state.selection.size,
                    todayIso = state.today.toString(),
                    onSchedule = { iso -> viewModel.scheduleSelected(iso) },
                    onClear = viewModel::clearSelection
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = AevumSpacing.md,
                end = AevumSpacing.md,
                bottom = AevumSpacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            item(key = "progress") {
                PmDetailProgressCard(
                    state = state,
                    accent = accent,
                    onOpenPlannerHint = { onNewTodos(state.node?.id.orEmpty()) }
                )
            }

            item(key = "quick-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.scheduleAllOpen(state.today.toString()) },
                        shape = RoundedCornerShape(percent = 50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.pm_pull_all_today), fontSize = 12.sp)
                    }
                    if (state.todos.isNotEmpty()) {
                        OutlinedButton(
                            onClick = viewModel::selectAll,
                            shape = RoundedCornerShape(percent = 50)
                        ) {
                            Text(
                                text = stringResource(R.string.pm_select_all),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (state.children.isNotEmpty()) {
                item(key = "children-header") {
                    SectionTitle(stringResource(R.string.pm_node_subs), count = state.children.size)
                }
                items(state.children, key = { "child-" + it.node.id }) { child ->
                    PmContainerRowView(
                        node = child.node,
                        progress = child.progress,
                        accent = PmPalette.color(child.colorHex),
                        breadcrumb = pmTypeLabel(child.node.type),
                        hasChildren = child.progress.total > 0,
                        onClick = { onOpenNode(child.node.id) },
                        menuActions = listOf(
                            PmRowAction(stringResource(R.string.pm_edit)) { onEditNode(child.node.id) },
                            PmRowAction(stringResource(R.string.pm_node_add_todo)) { onNewTodos(child.node.id) }
                        )
                    )
                }
            }

            item(key = "todos-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(
                        text = stringResource(R.string.pm_node_todos),
                        count = state.todos.size,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onNewTodos(state.node?.id.orEmpty()) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pm_node_add_todo), fontSize = 12.sp)
                    }
                }
            }

            if (state.todos.isEmpty()) {
                item(key = "todos-empty") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AevumRadius.lg),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                    ) {
                        Column(
                            modifier = Modifier.padding(AevumSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.pm_node_empty_todos),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.pm_node_empty_todos_hint),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(AevumSpacing.sm))
                            Button(
                                onClick = { onNewTodos(state.node?.id.orEmpty()) },
                                shape = RoundedCornerShape(percent = 50)
                            ) {
                                Text(stringResource(R.string.pm_node_add_todo))
                            }
                        }
                    }
                }
            } else {
                items(state.todos, key = { "todo-" + it.node.id }) { todo ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AevumRadius.md))
                            .clickable { viewModel.toggleDone(todo.node) },
                        shape = RoundedCornerShape(AevumRadius.md),
                        color = if (state.selection.contains(todo.node.id)) {
                            PmPalette.soft(accent, 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                        }
                    ) {
                        PmTodoRowView(
                            todo = todo.node,
                            accent = accent,
                            rolloverDays = todo.rolloverDays,
                            burst = state.lastCompletedId == todo.node.id,
                            selectionMode = state.selectionMode,
                            selected = state.selection.contains(todo.node.id),
                            dateChip = todo.dateChip,
                            menuActions = listOf(
                                PmRowAction(stringResource(R.string.pm_today)) {
                                    viewModel.scheduleOne(todo.node.id, state.today.toString())
                                },
                                PmRowAction(stringResource(R.string.pm_tomorrow)) {
                                    viewModel.scheduleOne(todo.node.id, state.today.plusDays(1).toString())
                                },
                                PmRowAction(stringResource(R.string.pm_date_none)) {
                                    viewModel.scheduleOne(todo.node.id, null)
                                },
                                PmRowAction(stringResource(R.string.pm_edit)) { onEditTodo(todo.node.id) }
                            ),
                            onToggle = { viewModel.toggleDone(todo.node) },
                            onLongPress = { viewModel.select(todo.node.id) },
                            onClick = {
                                if (state.selectionMode) viewModel.select(todo.node.id)
                                else viewModel.toggleDone(todo.node)
                            }
                        )
                    }
                }
            }

            item(key = "add-inside") {
                PmAddInsideRow(onAdd = { type -> onNewChild(state.node?.id.orEmpty(), type) })
            }
        }
    }

    if (state.deleteRequested) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.pm_delete_confirm_title)) },
            text = { Text(stringResource(R.string.pm_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete(onBack) }) {
                    Text(stringResource(R.string.pm_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.pm_cancel))
                }
            }
        )
    }
}

@Composable
private fun PmDetailTopBar(
    state: PmNodeDetailUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onScheduleAll: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AevumSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (state.breadcrumb.isNotEmpty()) {
                        Text(
                            text = state.breadcrumb,
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = state.node?.title.orEmpty(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.pm_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pm_move_up), fontSize = 14.sp) },
                            enabled = state.siblingIndex > 0,
                            onClick = {
                                menuOpen = false
                                onMove(-1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pm_move_down), fontSize = 14.sp) },
                            enabled = state.siblingIndex < state.siblingCount - 1,
                            onClick = {
                                menuOpen = false
                                onMove(1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pm_pull_all_today), fontSize = 14.sp) },
                            onClick = {
                                menuOpen = false
                                onScheduleAll()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.pm_delete),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRequestDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Fortschritt, Kennzahlen, Frist, Notiz — der Überblick der Aufgabe. */
@Composable
private fun PmDetailProgressCard(
    state: PmNodeDetailUiState,
    accent: androidx.compose.ui.graphics.Color,
    onOpenPlannerHint: () -> Unit
) {
    val animated by animateFloatAsState(
        targetValue = state.progress.fraction,
        animationSpec = androidx.compose.animation.core.spring(),
        label = "pm-detail-progress"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AevumRadius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.progress.percent.toString(),
                    fontSize = 36.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (state.progress.isComplete) PmPalette.complete else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = " %",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = state.progress.label,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pmTypeLabel(state.node?.type ?: PmType.TASK),
                        fontSize = 10.sp,
                        color = accent
                    )
                }
            }
            PlayfulProgressBar(
                progress = animated,
                accent = accent,
                height = 14.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_task_open, state.openTodos),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(AevumSpacing.md))
                Text(
                    text = stringResource(R.string.pm_done_today) + ": " + state.doneTodos,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val due = pmDueLabel(state.node?.dueDate, state.today)
            if (due != null) {
                PmChip(text = due, color = PmPalette.rollover, filled = true)
            }
            val note = state.node?.note.orEmpty()
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.scheduledToday == 0 && state.openTodos > 0) {
                TextButton(onClick = onOpenPlannerHint) {
                    Text(stringResource(R.string.pm_no_todos_today) + " · " + stringResource(R.string.pm_plan_day), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PmSelectionBar(
    count: Int,
    todayIso: String,
    onSchedule: (String?) -> Unit,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(AevumSpacing.md)) {
            Text(
                text = stringResource(R.string.pm_selected_count, count),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(AevumSpacing.xs))
            PmDateChips(
                currentIso = null,
                onPick = { iso -> onSchedule(iso) },
                allowNone = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.pm_cancel), fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.pm_scheduled_on, todayIso),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PmAddInsideRow(onAdd: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
    ) {
        OutlinedButton(
            onClick = { onAdd(PmType.TASK) },
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.pm_editor_new_task), fontSize = 12.sp)
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                shape = RoundedCornerShape(percent = 50)
            ) {
                Text(stringResource(R.string.pm_node_add_sub), fontSize = 12.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pm_type_project), fontSize = 14.sp) },
                    onClick = {
                        menuOpen = false
                        onAdd(PmType.PROJECT)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pm_type_category), fontSize = 14.sp) },
                    onClick = {
                        menuOpen = false
                        onAdd(PmType.CATEGORY)
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
