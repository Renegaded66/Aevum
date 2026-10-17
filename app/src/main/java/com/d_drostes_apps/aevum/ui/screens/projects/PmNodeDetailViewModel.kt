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

/** M19.2: Eine Aufgabe in der Detailansicht. */
data class PmTaskUi(
    val node: PmNode,
    val done: Boolean,
    val rolloverDays: Int,
    val dateChip: String?
)

/** M19.2: Ein enthaltenes Unterprojekt. */
data class PmChildUi(
    val node: PmNode,
    val progress: PmProgress,
    val colorHex: String
)

/** M19.2: Zustand der Detailseite eines Knotens. */
data class PmNodeDetailUiState(
    val loaded: Boolean = false,
    val exists: Boolean = false,
    val node: PmNode? = null,
    val breadcrumb: String = "",
    val progress: PmProgress = PmProgress.EMPTY,
    val colorHex: String = "#38BDF8",
    val today: LocalDate = LocalDate.now(),
    val children: List<PmChildUi> = emptyList(),
    val tasks: List<PmTaskUi> = emptyList(),
    val openTasks: Int = 0,
    val doneTasks: Int = 0,
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
    val isContainer: Boolean get() = node?.isContainer == true
    val allSelected: Boolean get() = tasks.isNotEmpty() && tasks.all { selection.contains(it.node.id) }
}

/**
 * M19.1/M19.2: Detailseite eines Projekts oder einer Aufgabe — hier wird
 * gearbeitet.
 *
 * M19.2: Die Seite ist die TO-DO-LISTE des Projekts: Aufgaben erscheinen als
 * Zeilen zum Abhaken, per Griff verschiebbar (Drag & Drop) und über das
 * Zeilenmenü löschbar. Neue Aufgaben entstehen INLINE am Listenende
 * („Neue Aufgabe …" + Enter) — es gibt bewusst kein großes Textfeld mehr
 * (Nutzerwunsch). Die Mehrfachauswahl (Langdruck) bleibt als Massenweg für
 * die Tageszuweisung erhalten.
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
        val tasks = tree.tasksOf(nodeId)
            .sortedWith(compareBy({ it.doneAt != null }, { it.sortOrder }, { it.createdAt }))
            .map { task ->
                PmTaskUi(
                    node = task,
                    done = task.doneAt != null,
                    rolloverDays = if (task.doneAt == null) PmToday.rolloverDays(task, today) else 0,
                    dateChip = pmShortDate(task.scheduledDate)
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
            tasks = tasks,
            openTasks = tasks.count { !it.done },
            doneTasks = tasks.count { it.done },
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

    fun toggleTask(node: PmNode) {
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

    /**
     * M19.2 (Inline-Eingabe): legt EINE Aufgabe an — die Quelle der neuen
     * To-do-Zeile. Leere Eingaben werden verworfen; die Zeile selbst bleibt
     * als nächster Platzhalter stehen (das macht das UI).
     */
    fun addTask(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.createTasks(parentId = nodeId, titles = listOf(clean))
        }
    }

    /**
     * M19.2 (Drag & Drop): neue Reihenfolge der Aufgaben dieses Projekts.
     * [from]/[to] sind Indizes in der Anzeige-Reihenfolge der OFFENEN
     * Aufgaben — die Detailseite zeigt alle offenen Aufgaben des Projekts,
     * damit entspricht die Liste exakt den Bestandsdaten (Repository-Regel).
     */
    fun reorderTasks(from: Int, to: Int) {
        viewModelScope.launch {
            val current = uiState.value
            val openIds = current.tasks.filter { !it.done }.map { it.node.id }
            if (from !in openIds.indices || to !in openIds.indices) return@launch
            val moved = PmReorder.applyMove(openIds, from, to)
            repository.reorderTasks(nodeId, moved)
        }
    }

    /** Löschen direkt aus der Zeile (Aufgaben sind Blätter — kein Teilbaum). */
    fun deleteTask(task: PmNode) {
        viewModelScope.launch { repository.delete(task.id) }
    }

    fun select(id: String) {
        val current = selection.value
        selection.value = if (current.contains(id)) current - id else current + id
    }

    fun selectAll() {
        val current = uiState.value
        selection.value = if (current.allSelected) emptySet() else current.tasks.map { it.node.id }.toSet()
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

    /** Reihenfolge innerhalb einer Ebene ändern (Hoch/Runter im Kopfmenü). */
    fun move(delta: Int) {
        viewModelScope.launch {
            val current = uiState.value
            val target = (current.siblingIndex + delta).coerceAtLeast(0)
            repository.move(nodeId, current.parentId, target)
        }
    }
}
