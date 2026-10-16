package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.repository.PmRepository
import com.d_drostes_apps.aevum.domain.projects.PmColors
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmToday
import com.d_drostes_apps.aevum.domain.projects.PmTree
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** M19.1: Ein Todo in der Detailansicht. */
data class PmTodoUi(
    val node: PmNode,
    val done: Boolean,
    val rolloverDays: Int,
    val dateChip: String?
)

/** M19.1: Ein enthaltener Knoten (Unterprojekt, Kategorie, Aufgabe). */
data class PmChildUi(
    val node: PmNode,
    val progress: PmProgress,
    val colorHex: String
)

/** M19.1: Zustand der Detailseite eines Knotens. */
data class PmNodeDetailUiState(
    val loaded: Boolean = false,
    val exists: Boolean = false,
    val node: PmNode? = null,
    val breadcrumb: String = "",
    val progress: PmProgress = PmProgress.EMPTY,
    val colorHex: String = "#8B7CFF",
    val today: LocalDate = LocalDate.now(),
    val children: List<PmChildUi> = emptyList(),
    val todos: List<PmTodoUi> = emptyList(),
    val openTodos: Int = 0,
    val doneTodos: Int = 0,
    val scheduledToday: Int = 0,
    val selection: Set<String> = emptySet(),
    val lastCompletedId: String? = null,
    val deleteRequested: Boolean = false,
    val siblingIndex: Int = 0,
    val siblingCount: Int = 1,
    val parentId: String? = null,
    val parentTitle: String = ""
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val hasChildren: Boolean get() = children.isNotEmpty()
    val isTask: Boolean get() = node?.type == com.d_drostes_apps.aevum.data.model.PmType.TASK
    val allSelected: Boolean get() = todos.isNotEmpty() && todos.all { selection.contains(it.node.id) }
}

/**
 * M19.1: Detailseite eines Knotens — hier wird gearbeitet.
 *
 * Sie zeigt genau den Ausschnitt, den der Nutzer im Kopf hat: den Pfad
 * (Studium › 2. Semester › Projektgruppe › Präsentation), den Gesamtfortschritt
 * der Aufgabe, die enthaltenen Ebenen und alle Schritte. Die Mehrfachauswahl
 * (Langdruck) ist der zweite Massenweg neben dem Tag-planen-Sheet: mehrere
 * Todos markieren, ein Datum wählen, fertig.
 */
@HiltViewModel
class PmNodeDetailViewModel @Inject constructor(
    private val repository: PmRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val nodeId: String = savedStateHandle.get<String>("nodeId").orEmpty()

    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val lastCompleted = MutableStateFlow<String?>(null)
    private val deleteRequested = MutableStateFlow(false)

    val uiState: StateFlow<PmNodeDetailUiState> = combine(
        repository.observeAll(),
        selection,
        lastCompleted,
        deleteRequested
    ) { nodes, selected, last, deleteWanted ->
        val today = LocalDate.now()
        val tree = PmTree(nodes)
        val node = tree.byId(nodeId)
        val todos = tree.todosOf(nodeId)
            .sortedWith(compareBy({ it.doneAt != null }, { it.sortOrder }, { it.createdAt }))
            .map { todo ->
                PmTodoUi(
                    node = todo,
                    done = todo.doneAt != null,
                    rolloverDays = if (todo.doneAt == null) PmToday.rolloverDays(todo, today) else 0,
                    dateChip = pmShortDate(todo.scheduledDate)
                )
            }
        val siblings = tree.children(node?.parentId)
        val parent = tree.byId(node?.parentId)

        PmNodeDetailUiState(
            loaded = true,
            exists = node != null,
            node = node,
            breadcrumb = tree.breadcrumb(nodeId),
            progress = tree.progress(nodeId),
            colorHex = node?.let { PmColors.resolve(it, tree) } ?: PmColors.colorFor(nodeId),
            today = today,
            children = tree.children(nodeId).filter { it.isContainer }.map { child ->
                PmChildUi(
                    node = child,
                    progress = tree.progress(child.id),
                    colorHex = PmColors.resolve(child, tree)
                )
            },
            todos = todos,
            openTodos = todos.count { !it.done },
            doneTodos = todos.count { it.done },
            scheduledToday = todos.count { !it.done && it.node.scheduledDate == today.toString() },
            selection = selected,
            lastCompletedId = last,
            deleteRequested = deleteWanted,
            siblingIndex = siblings.indexOfFirst { it.id == nodeId },
            siblingCount = siblings.size,
            parentId = node?.parentId,
            parentTitle = parent?.title.orEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PmNodeDetailUiState()
    )

    // ── Aktionen ──────────────────────────────────────────────────────────

    fun toggleDone(node: PmNode) {
        val target = node.doneAt == null
        viewModelScope.launch {
            repository.setDone(node.id, target)
            if (target) {
                lastCompleted.value = node.id
                viewModelScope.launch {
                    delay(1400L)
                    if (lastCompleted.value == node.id) lastCompleted.value = null
                }
            } else if (lastCompleted.value == node.id) {
                lastCompleted.value = null
            }
        }
    }

    fun select(id: String) {
        val current = selection.value
        selection.value = if (current.contains(id)) current - id else current + id
    }

    fun selectAll() {
        val current = uiState.value
        selection.value = if (current.allSelected) emptySet() else current.todos.map { it.node.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Auswahl auf ein Datum setzen (null = zurück in den Backlog). */
    fun scheduleSelected(date: String?) {
        val ids = selection.value.toList()
        selection.value = emptySet()
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.setScheduled(ids, date) }
    }

    fun scheduleAllOpen(date: String?) {
        viewModelScope.launch { repository.setScheduledForSubtree(nodeId, date, onlyOpen = true) }
    }

    fun scheduleOne(id: String, date: String?) {
        viewModelScope.launch { repository.setScheduled(listOf(id), date) }
    }

    fun requestDelete() {
        deleteRequested.value = true
    }

    fun cancelDelete() {
        deleteRequested.value = false
    }

    fun confirmDelete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(nodeId)
            onDeleted()
        }
    }

    /** Reihenfolge innerhalb einer Ebene ändern (Hoch/Runter statt Drag&Drop). */
    fun move(delta: Int) {
        viewModelScope.launch {
            val current = uiState.value
            val target = (current.siblingIndex + delta).coerceAtLeast(0)
            repository.move(nodeId, current.parentId, target)
        }
    }
}
