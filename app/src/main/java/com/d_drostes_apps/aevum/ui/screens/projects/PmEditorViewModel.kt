package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.data.repository.PmRepository
import com.d_drostes_apps.aevum.domain.projects.PmColors
import com.d_drostes_apps.aevum.domain.projects.PmTree
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** M19.1: Was der Editor gerade tut. */
enum class PmEditorMode(val key: String) {
    NODE_NEW("node_new"),
    NODE_EDIT("node_edit"),
    TODOS_NEW("todos_new"),
    TODO_EDIT("todo_edit");

    val isTodoMode: Boolean get() = this == TODOS_NEW || this == TODO_EDIT

    companion object {
        fun from(key: String?): PmEditorMode = entries.firstOrNull { it.key == key } ?: NODE_NEW
    }
}

/** M19.1: Formularzustand (bewusst getrennt vom abgeleiteten Zustand). */
data class PmEditorForm(
    val type: String = PmType.TASK,
    val title: String = "",
    val note: String = "",
    val colorHex: String? = null,
    val dueDate: String? = null,
    val scheduledDate: String? = null,
    val bulkText: String = "",
    val done: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val touched: Boolean = false
)

/** M19.1: Zustand des Editors. */
data class PmEditorUiState(
    val mode: PmEditorMode = PmEditorMode.NODE_NEW,
    val loaded: Boolean = false,
    val exists: Boolean = true,
    val form: PmEditorForm = PmEditorForm(),
    val parentId: String? = null,
    val parentTitle: String = "",
    val parentBreadcrumb: String = "",
    val parentProgress: Int = 0,
    val bulkCount: Int = 0,
    val palette: List<String> = PmColors.PALETTE
) {
    val canSave: Boolean
        get() = when (mode) {
            PmEditorMode.TODOS_NEW -> bulkCount > 0
            else -> form.title.isNotBlank()
        }
    val showError: Boolean get() = form.touched && !canSave
}

/**
 * M19.1: Editor für Knoten und Schritte.
 *
 * ZWEI BESONDERHEITEN MIT ABSICHT:
 *
 * 1. **Sammel-Eingabe** (`TODOS_NEW`): ein mehrzeiliges Feld, eine Zeile = ein
 *    Schritt. Damit ist eine ganze, kopierte Schrittliste EIN Vorgang. Genau
 *    das macht die Anforderung „jede kleinste Aufgabe schriftlich festhalten"
 *    erst zumutbar — 20 Todos einzeln anzulegen wäre wieder Reibung.
 *
 * 2. **Rückmeldung statt Rätsel**: Ein Speichern, das nichts tut, weil der Titel
 *    fehlt, ist genau die Art stummen Fehlers, die den Nutzer glauben lässt,
 *    „es geht nicht". Deshalb: [PmEditorUiState.showError] und ein deutlicher
 *    Hinweis am Feld, sobald der Nutzer speichern wollte.
 */
@HiltViewModel
class PmEditorViewModel @Inject constructor(
    private val repository: PmRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val mode: PmEditorMode = PmEditorMode.from(savedStateHandle.get<String>("mode"))
    // Optionale Navigationsargumente kommen bei Navigation-Compose als leerer
    // String an (Default-Wert), nicht als null → ausdrücklich normalisieren.
    private val nodeId: String? = savedStateHandle.get<String>("nodeId")?.takeIf { it.isNotBlank() }
    private val parentIdArg: String? = savedStateHandle.get<String>("parentId")?.takeIf { it.isNotBlank() }
    private val typeArg: String = savedStateHandle.get<String>("type")?.takeIf { it.isNotBlank() } ?: PmType.TASK

    private val form = MutableStateFlow(PmEditorForm(type = typeArg))
    private var preloaded = false

    val uiState: StateFlow<PmEditorUiState> = combine(
        repository.observeAll(),
        form
    ) { nodes, currentForm ->
        val tree = PmTree(nodes)
        val editing = nodeId?.let { tree.byId(it) }
        val parentId = if (mode == PmEditorMode.NODE_EDIT) editing?.parentId else parentIdArg
        val parent = tree.byId(parentId)

        // Vorbelegung genau einmal (sonst würde jede Datenbankänderung die
        // Eingabe des Nutzers überschreiben).
        if (!preloaded) {
            preloaded = true
            when (mode) {
                PmEditorMode.NODE_EDIT, PmEditorMode.TODO_EDIT -> if (editing != null) {
                    form.value = form.value.copy(
                        type = editing.type,
                        title = editing.title,
                        note = editing.note,
                        colorHex = editing.color,
                        dueDate = editing.dueDate,
                        scheduledDate = editing.scheduledDate,
                        done = editing.doneAt != null
                    )
                }
                else -> Unit
            }
        }

        val bulkCount = currentForm.bulkText
            .split('\n')
            .count { it.trim().isNotEmpty() }

        PmEditorUiState(
            mode = mode,
            loaded = true,
            exists = editing != null || mode == PmEditorMode.NODE_NEW || mode == PmEditorMode.TODOS_NEW,
            form = currentForm,
            parentId = parentId,
            parentTitle = parent?.title.orEmpty(),
            parentBreadcrumb = parentId?.let { tree.breadcrumb(it, includeSelf = true) }.orEmpty(),
            parentProgress = parent?.let { tree.progress(it.id).percent } ?: 0,
            bulkCount = bulkCount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PmEditorUiState(mode = mode)
    )

    // ── Formular ──────────────────────────────────────────────────────────

    fun setTitle(value: String) = update { it.copy(title = value) }
    fun setNote(value: String) = update { it.copy(note = value) }
    fun setColor(hex: String?) = update { it.copy(colorHex = hex) }
    fun setType(type: String) = update { it.copy(type = type) }
    fun setDueDate(iso: String?) = update { it.copy(dueDate = iso) }
    fun setScheduledDate(iso: String?) = update { it.copy(scheduledDate = iso) }
    fun setBulkText(value: String) = update { it.copy(bulkText = value) }
    fun toggleDone() = update { it.copy(done = !it.done) }
    fun touch() = update { it.copy(touched = true) }

    private inline fun update(block: (PmEditorForm) -> PmEditorForm) {
        form.value = block(form.value)
    }

    // ── Speichern / Löschen ────────────────────────────────────────────────

    fun save() {
        update { it.copy(touched = true) }
        val state = uiState.value
        if (!state.canSave || state.form.saving) return
        update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val current = form.value
            try {
                when (mode) {
                    PmEditorMode.NODE_NEW -> repository.createNode(
                        parentId = state.parentId,
                        type = current.type,
                        title = current.title,
                        note = current.note,
                        color = current.colorHex,
                        dueDate = current.dueDate
                    )
                    PmEditorMode.NODE_EDIT -> nodeId?.let { id ->
                        repository.updateNode(id, current.title, current.note, current.colorHex, current.dueDate)
                    }
                    PmEditorMode.TODOS_NEW -> repository.createTodos(
                        parentId = state.parentId,
                        titles = current.bulkText.split('\n'),
                        scheduledDate = current.scheduledDate
                    )
                    PmEditorMode.TODO_EDIT -> nodeId?.let { id ->
                        repository.updateNode(id, current.title, current.note, current.colorHex, current.dueDate)
                        repository.setScheduled(listOf(id), current.scheduledDate)
                        // Nur schreiben, wenn sich der Zustand wirklich ändert —
                        // sonst ginge der ursprüngliche Erledigungszeitpunkt
                        // (und damit der 7-Tage-Verlauf) verloren.
                        val existing = repository.getById(id)
                        if (existing != null && (existing.doneAt != null) != current.done) {
                            repository.setDone(id, current.done)
                        }
                    }
                }
                update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                update { it.copy(saving = false, error = e.message ?: "error") }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = nodeId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }
}
