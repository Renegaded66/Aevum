package com.d_drostes_apps.aevum.ui.screens.todos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * M18.30: Todos-Screen — fancy Aufgaben mit Fortschrittsbalken.
 *
 * - Checkbox-Todos (Müll rausbringen) und Dauer-Todos (2h lernen)
 * - Farbiger Fortschrittsbalken: animiert, zeigt Ziel-Erfüllung
 * - Auto-Check: Dauer-Todo wird automatisch abgehakt, wenn die
 *   zugeordnete Aktivität genug Zeit erfasst hat
 * - Recurrence-Chip: "Jeden Tag", "Mo–Fr", "x-mal pro Woche", ...
 * - Archiv für erledigte/archivierte Todos
 */
@Composable
fun TodosScreen(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit,
    // M18.38: Todo bearbeiten — oeffnet den Editor mit geladenem Todo
    onEdit: (String) -> Unit = {},
    viewModel: TodosViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showArchived by remember { mutableStateOf(false) }

    // M18.31: Scaffold mit FAB — vorher gab es KEINEN sichtbaren Weg,
    // ein Todo anzulegen (nur den Hero-Text). Jetzt: schwebender
    // Plus-Button unten rechts, wie in allen anderen Listen-Screens.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "Neues Todo")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                start = AevumSpacing.md,
                end = AevumSpacing.md,
                top = AevumSpacing.lg,
                bottom = 96.dp // Platz für FAB
            ),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
        item { TodosHero(count = state.activeTodos.size, doneCount = state.activeTodos.count { it.done }, onCreate = onCreate) }

        if (state.activeTodos.isEmpty()) {
            item { TodosEmptyState(onCreate = onCreate) }
        } else {
            item { SectionHeader("Heute", "${state.activeTodos.size} Todos") }
            items(state.activeTodos.size, key = { state.activeTodos[it].todo.id }) { index ->
                val item = state.activeTodos[index]
                TodoCard(
                    item = item,
                    onToggle = { viewModel.toggle(item.todo.id, !item.done) },
                    onArchive = { viewModel.archive(item.todo.id) },
                    onDelete = { viewModel.delete(item.todo.id) },
                    // M18.38: Bearbeiten-Button
                    onEdit = { onEdit(item.todo.id) }
                )
            }
        }

        if (state.archivedTodos.isNotEmpty()) {
            item { Spacer(Modifier.height(AevumSpacing.lg)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showArchived = !showArchived },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Archiv", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${state.archivedTodos.size} erledigte Todos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (showArchived) "▾" else "▸", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                AnimatedVisibility(visible = showArchived) {
                    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        state.archivedTodos.forEach { todo ->
                            ArchivedTodoRow(todo = todo, onDelete = { viewModel.delete(todo.id) })
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(AevumSpacing.xxl)) }
        }
    }
}

@Composable
private fun TodosHero(count: Int, doneCount: Int, onCreate: () -> Unit) {
    AevumCard(variant = CardVariant.Gradient) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            Text("TODOS", fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Deine Aufgaben — automatisch abgehakt",
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (count == 0) "Alles erledigt 🎉" else "$doneCount von $count erledigt",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Mini-Fortschritt
                if (count > 0) {
                    val fraction = doneCount.toFloat() / count
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TodoCard(
    item: TodoUi,
    onToggle: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    // M18.38: Bearbeiten-Button
    onEdit: () -> Unit = {}
) {
    val todo = item.todo
    val accentColor = if (item.type?.color != null && item.type.color != 0L) {
        Color(item.type.color)
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val animatedProgress by animateFloatAsState(
        targetValue = if (item.done) 1f else item.progress,
        animationSpec = tween(600),
        label = "progress"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        color = if (item.done) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }
    ) {
        Column(
            modifier = Modifier.padding(AevumSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                // Checkbox (custom, fancy)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (item.done) accentColor
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.done) {
                        Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        todo.title,
                        fontSize = 16.sp,
                        fontWeight = if (item.done) FontWeight.Normal else FontWeight.SemiBold,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = if (item.done) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Meta-Zeile: Icon + Aktivität + Recurrence
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (item.type != null) {
                            Text(
                                "${item.type.icon?.takeIf { it.isNotBlank() } ?: "•"} ${item.type.name}",
                                fontSize = 11.sp,
                                color = accentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text(
                            item.recurrenceLabel,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Aktionen
                // M18.38: Bearbeiten-Button (Stift) — oeffnet den Editor
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Bearbeiten", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onArchive) {
                    Icon(Icons.Default.Archive, contentDescription = "Archivieren", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }

            // Fortschrittsbalken (nur bei Dauer-Todos)
            if (item.isDuration) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (item.done) accentColor
                                    else accentColor.copy(alpha = 0.75f)
                                )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Erfasst: ${TimeFormatting.formatDuration(item.progressMs)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "Ziel: ${todo.targetMinutes} min",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedTodoRow(todo: com.d_drostes_apps.aevum.data.model.Todo, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = AevumSpacing.md, vertical = AevumSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✓ ", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        Text(
            todo.title,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textDecoration = TextDecoration.LineThrough,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TodosEmptyState(onCreate: () -> Unit) {
    AevumCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AevumSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
        ) {
            Text("🎯", fontSize = 40.sp)
            Text("Noch keine Todos", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Erstelle Aufgaben wie „Müll rausbringen“ (Checkbox) oder „2h lernen“ (Dauer-Ziel, automatisch abgehakt).",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
