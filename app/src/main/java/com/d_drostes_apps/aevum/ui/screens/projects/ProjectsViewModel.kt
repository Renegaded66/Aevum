package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.data.repository.PmCelebrationStore
import com.d_drostes_apps.aevum.data.repository.PmRepository
import com.d_drostes_apps.aevum.domain.projects.PmCelebrationGate
import com.d_drostes_apps.aevum.domain.projects.PmColors
import com.d_drostes_apps.aevum.domain.projects.PmDayHistory
import com.d_drostes_apps.aevum.domain.projects.PmPlanningGroup
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmToday
import com.d_drostes_apps.aevum.domain.projects.PmTodayGroup
import com.d_drostes_apps.aevum.domain.projects.PmTodayState
import com.d_drostes_apps.aevum.domain.projects.PmTree
import com.d_drostes_apps.aevum.domain.projects.PmTreeRow
import com.d_drostes_apps.aevum.ui.components.PmGem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** M19.1: Die beiden Ansichten des Projektmanagements. */
enum class PmTab(val key: String) {
    TODAY("today"),
    PROJECTS("projects");
}

/**
 * M19.1: Zustand des Projektmanagement-Sheets.
 *
 * Eine einzige Ableitung aus den Daten — Glas, Karussell, Backlog und Baum
 * werden IMMER aus demselben Baum gerechnet. Dadurch kann der Fortschritt im
 * Glas nicht von dem auf der Karte abweichen.
 */
data class PmProjectsUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val tab: PmTab = PmTab.TODAY,
    val todayState: PmTodayState? = null,
    /** Aufgaben mit den heutigen Todos (Karussell), inkl. Farbe und Gesamtfortschritt. */
    val groups: List<PmTodayGroupUi> = emptyList(),
    /** Reihenfolge des Abhakens — erzählt den Tagesverlauf. */
    val gems: List<PmGem> = emptyList(),
    val ghostCount: Int = 0,
    val history: List<PmDayHistory> = emptyList(),
    /**
     * Offene Todos, die NICHT im heutigen Plan stehen (Backlog-Abschnitt:
     * „was noch kommt") — gruppiert nach Aufgabe.
     */
    val backlog: List<PmPlanningGroup> = emptyList(),
    val backlogTotal: Int = 0,
    /**
     * ALLE offenen Todos, gruppiert nach Aufgabe — Grundlage des
     * Tag-planen-Sheets (dort müssen auch die heutigen sichtbar und
     * vorausgewählt sein).
     */
    val plannerGroups: List<PmPlanningGroup> = emptyList(),
    /** Baum-Ansicht. */
    val treeRows: List<PmTreeRowUi> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val projectCount: Int = 0,
    val taskCount: Int = 0,
    val totalTodos: Int = 0,
    val doneTodos: Int = 0,
    val celebrating: Boolean = false,
    /** Zuletzt abgehaktes Todo (für den Funkenstoß, genau ein Mal). */
    val lastCompletedId: String? = null,
    val isCompletelyEmpty: Boolean = false,
    /** Tag-planen-Sheet */
    val plannerOpen: Boolean = false,
    val plannerSelection: Set<String> = emptySet(),
    val plannerDate: String? = null
) {
    val fraction: Float get() = todayState?.fraction ?: 0f
    val percent: Int get() = todayState?.percent ?: 0
    val doneToday: Int get() = todayState?.done ?: 0
    val totalToday: Int get() = todayState?.total ?: 0
    val pendingToday: Int get() = todayState?.pending ?: 0
    val rolloverToday: Int get() = todayState?.rollover ?: 0
    val allDone: Boolean get() = todayState?.isComplete == true
}

/** Eine Aufgabe des Tages mit Farbe und Gesamtfortschritt (alle Todos, nicht nur heute). */
data class PmTodayGroupUi(
    val group: PmTodayGroup,
    val colorHex: String,
    val progress: PmProgress
) {
    val id: String get() = group.id
    val title: String get() = group.title
    val rows: List<com.d_drostes_apps.aevum.domain.projects.PmTodayRow> get() = group.rows
    val breadcrumb: String get() = group.breadcrumb
    val isSynthetic: Boolean get() = group.isSynthetic
}

/** Eine Zeile der Baum-Ansicht. */
data class PmTreeRowUi(
    val row: PmTreeRow,
    val progress: PmProgress,
    val colorHex: String,
    val expanded: Boolean,
    val isLastWithChildren: Boolean = false
) {
    val node: PmNode get() = row.node
    val depth: Int get() = row.depth
    val hasChildren: Boolean get() = row.hasChildren
}

/**
 * M19.1: Wurzel-ViewModel des Projektmanagements.
 *
 * ZWEI DINGE, DIE HIER BEWUSST GELÖST SIND:
 *
 * 1. **Mitternacht.** Der Tagesplan hängt an „heute". Ein einmal abonnierter
 *    Wert wäre nach Mitternacht falsch (bekannte Fehlerklasse der App, siehe
 *    den Kommentar an `TodoDao.getAllCompletions`). Deshalb tickt hier ein
 *    Minuten-Fluss und setzt das Datum neu — die Ansicht wechselt von selbst
 *    auf den neuen Tag, auch wenn die App die ganze Nacht offen lag.
 *
 * 2. **Feier-Gate.** Das Feuerwerk darf nicht bei jeder Neukomposition
 *    erscheinen. Die Entscheidung fällt in [buildState] über den
 *    persistierten [PmCelebrationStore] ([PmCelebrationGate]). Das Schreiben
 *    dort ist idempotent; die dadurch ausgelöste, zusätzliche Emission kann
 *    deshalb nicht in eine Schleife laufen.
 */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: PmRepository,
    private val celebrationStore: PmCelebrationStore
) : ViewModel() {

    private data class ScreenState(
        val expanded: Set<String>,
        val tab: PmTab,
        val celebrating: Boolean,
        val lastCompletedId: String?,
        val dismissedCelebration: Boolean = false,
        // Tag-planen-Sheet (Morgenritual). Liegt im ViewModel, damit eine
        // Drehung mitten in der Auswahl nichts verliert.
        val plannerOpen: Boolean = false,
        val plannerSelection: Set<String> = emptySet(),
        val plannerDate: String? = null
    )

    private val screen = MutableStateFlow(ScreenState(expanded = emptySet(), tab = PmTab.TODAY, celebrating = false, lastCompletedId = null))

    private val todayFlow = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000L)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<PmProjectsUiState> = combine(
        repository.observeAll(),
        todayFlow,
        screen
    ) { nodes, today, screenState ->
        buildState(nodes, today, screenState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PmProjectsUiState()
    )

    // ── Aktionen ──────────────────────────────────────────────────────────

    fun setTab(tab: PmTab) {
        screen.value = screen.value.copy(tab = tab)
    }

    fun toggleExpand(id: String) {
        val current = screen.value.expanded
        screen.value = screen.value.copy(
            expanded = if (current.contains(id)) current - id else current + id
        )
    }

    fun expandAll(ids: List<String>) {
        screen.value = screen.value.copy(expanded = screen.value.expanded + ids)
    }

    fun collapseAll() {
        screen.value = screen.value.copy(expanded = emptySet())
    }

    /** Abhaken bzw. Rückgängig. Der Funke läuft genau ein Mal pro Abhaken. */
    fun toggleTodo(todo: PmNode) {
        val target = todo.doneAt == null
        viewModelScope.launch {
            repository.setDone(todo.id, target)
            if (target) {
                screen.value = screen.value.copy(lastCompletedId = todo.id)
                viewModelScope.launch {
                    delay(1400L)
                    if (screen.value.lastCompletedId == todo.id) {
                        screen.value = screen.value.copy(lastCompletedId = null)
                    }
                }
            } else if (screen.value.lastCompletedId == todo.id) {
                screen.value = screen.value.copy(lastCompletedId = null)
            }
        }
    }

    /** Einzelne oder mehrere Todos auf ein Datum setzen (null = Backlog). */
    fun schedule(ids: List<String>, date: String?) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.setScheduled(ids, date) }
    }

    /** „Alle offenen Schritte dieser Aufgabe heute" — der Ein-Tipp-Weg. */
    fun scheduleAllOpen(nodeId: String, date: String?) {
        viewModelScope.launch { repository.setScheduledForSubtree(nodeId, date, onlyOpen = true) }
    }

    fun createExample() {
        viewModelScope.launch {
            repository.insertExample()
            screen.value = screen.value.copy(expanded = SEED_EXPAND)
        }
    }

    // ── Tag planen (Morgenritual) ──────────────────────────────────────────

    /** Öffnet das Sheet und wählt die bereits geplanten Schritte vor. */
    fun openPlanner() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val tree = PmTree(repository.getAllOnce())
            val suggested = PmToday.planningGroups(tree, today).flatMap { it.suggestedIds }.toSet()
            screen.value = screen.value.copy(plannerOpen = true, plannerSelection = suggested, plannerDate = null)
        }
    }

    fun closePlanner() {
        screen.value = screen.value.copy(plannerOpen = false)
    }

    fun togglePlannerSelection(id: String) {
        val current = screen.value.plannerSelection
        screen.value = screen.value.copy(
            plannerSelection = if (current.contains(id)) current - id else current + id
        )
    }

    fun setPlannerSelection(ids: List<String>, selected: Boolean) {
        val current = screen.value.plannerSelection
        screen.value = screen.value.copy(
            plannerSelection = if (selected) current + ids else current - ids.toSet()
        )
    }

    fun setPlannerDate(iso: String?) {
        screen.value = screen.value.copy(plannerDate = iso)
    }

    /** Übernimmt die Auswahl auf den gewählten Tag und schließt das Sheet. */
    fun applyPlanner() {
        val state = screen.value
        val date = state.plannerDate ?: LocalDate.now().toString()
        val ids = state.plannerSelection.toList()
        screen.value = state.copy(plannerOpen = false, plannerSelection = emptySet())
        viewModelScope.launch { repository.setScheduled(ids, date) }
    }

    /** Gewählte Schritte wieder aus dem Tag nehmen (zurück in den Backlog). */
    fun removeFromPlan(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.setScheduled(ids, null) }
    }

    fun finishCelebration() {
        screen.value = screen.value.copy(celebrating = false, dismissedCelebration = true)
    }

    fun deleteTodo(todo: PmNode) {
        viewModelScope.launch { repository.delete(todo.id) }
    }

    // ── Ableitung ─────────────────────────────────────────────────────────

    private fun buildState(nodes: List<PmNode>, today: LocalDate, screenState: ScreenState): PmProjectsUiState {
        val tree = PmTree(nodes)
        val todayState = PmToday.state(tree, today)

        // Feier-Gate: einmal pro Übergang auf „voll" (persistiert).
        val celebrated = checkCelebration(today, todayState, screenState)

        val groups = todayState.groups.map { group ->
            PmTodayGroupUi(
                group = group,
                colorHex = PmColors.groupColor(group, tree),
                progress = group.node?.let { tree.progress(it.id) } ?: PmProgress.EMPTY
            )
        }

        // Juwelen in der Reihenfolge des Abhakens — das Glas erzählt den Tag.
        val doneRows = todayState.groups
            .flatMap { it.rows }
            .filter { it.done }
            .sortedBy { it.node.doneAt ?: 0L }
        val gems = doneRows.map { row ->
            PmGem(id = row.node.id, colorHex = PmColors.resolve(row.node, tree), label = row.node.title)
        }

        // Zwei Sichten auf dieselben offenen Todos — bewusst getrennt:
        // Das Sheet braucht ALLE (sonst wären die heutigen nicht auswählbar),
        // der Backlog-Abschnitt nur die, die heute NICHT anstehen (sonst
        // stünden die heutigen Schritte doppelt auf dem Bildschirm).
        val plannerGroups = PmToday.planningGroups(tree, today)
        val todayOpenIds = todayState.groups
            .flatMap { it.rows }
            .filter { !it.done }
            .map { it.node.id }
            .toSet()
        val backlog = plannerGroups.mapNotNull { group ->
            val rest = group.todos.filter { it.id !in todayOpenIds }
            if (rest.isEmpty()) null else group.copy(todos = rest)
        }
        val backlogTotal = backlog.sumOf { it.todos.size }

        val treeRows = tree.visibleRows(screenState.expanded).map { row ->
            PmTreeRowUi(
                row = row,
                progress = tree.progress(row.node.id),
                colorHex = PmColors.resolve(row.node, tree),
                expanded = screenState.expanded.contains(row.node.id)
            )
        }

        val allTodos = tree.allTodos()

        return PmProjectsUiState(
            loaded = true,
            today = today,
            tab = screenState.tab,
            todayState = todayState,
            groups = groups,
            gems = gems,
            ghostCount = todayState.pending,
            history = PmToday.history(tree, today, days = 7),
            backlog = backlog,
            backlogTotal = backlogTotal,
            plannerGroups = plannerGroups,
            treeRows = treeRows,
            expanded = screenState.expanded,
            projectCount = tree.byType(PmType.PROJECT).size,
            taskCount = tree.byType(PmType.TASK).size,
            totalTodos = allTodos.size,
            doneTodos = allTodos.count { it.isDone },
            celebrating = screenState.celebrating || celebrated,
            lastCompletedId = screenState.lastCompletedId,
            isCompletelyEmpty = nodes.isEmpty(),
            plannerOpen = screenState.plannerOpen,
            plannerSelection = screenState.plannerSelection,
            plannerDate = screenState.plannerDate
        )
    }

    /**
     * Fällt die Entscheidung „feiern?" und schreibt sie fest.
     * Schreiben ist idempotent — dieselbe Eingabe führt nie zu zwei Feiern.
     */
    private fun checkCelebration(today: LocalDate, todayState: PmTodayState, screenState: ScreenState): Boolean {
        if (screenState.dismissedCelebration) return false
        if (screenState.celebrating) return true
        val iso = today.toString()
        val stored = celebrationStore.state()
        val celebrate = PmCelebrationGate.shouldCelebrate(stored, iso, todayState.total, todayState.done)
        if (celebrate) {
            celebrationStore.save(PmCelebrationGate.afterCelebration(stored, iso, todayState.done))
            screen.value = screen.value.copy(celebrating = true)
        }
        return celebrate
    }

    companion object {
        /** Beim Einfügen des Beispiels gleich die passenden Ebenen aufklappen. */
        val SEED_EXPAND = setOf("pm-seed-studium", "pm-seed-semester", "pm-seed-projektgruppe", "pm-seed-praesentation")
    }
}
