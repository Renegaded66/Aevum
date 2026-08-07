package de.devondroste.aevum.ui.screens.activitytypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M18.2: Verwaltet die Positivitäts-Scores aller Activity Types.
 *
 * - [pendingScores]: lokaler UI-State pro Type — der Slider schreibt
 *   hier während des Drags rein (kein DB-Spam).
 * - [commitScore]: schreibt den finalen Wert bei Drag-Ende in die DB.
 *
 * M18.12: Erweitert um Icon + Farbe + manuelles Anlegen.
 */
@HiltViewModel
class ActivityTypesViewModel @Inject constructor(
    private val activityTypeRepository: ActivityTypeRepository,
    // M18.17: Kategorien für die Zuordnung pro Aktivität.
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    // M18.2: Lokaler Drag-State: typeId -> pendingScore.
    // Wird beim Öffnen geleert und bei DB-Write aktualisiert.
    private val pendingScores = MutableStateFlow<Map<String, Int>>(emptyMap())

    val uiState: StateFlow<ActivityTypesUiState> = combine(
        activityTypeRepository.getAll(),
        categoryRepository.getAll(),
        pendingScores
    ) { types, categories, pending ->
        ActivityTypesUiState(
            activityTypes = types.map { type ->
                val pendingScore = pending[type.id]
                ActivityTypeRow(
                    id = type.id,
                    name = type.name,
                    score = pendingScore ?: type.positivityScore,
                    isSystem = type.isSystem,
                    icon = type.icon,
                    color = type.color,
                    categoryId = type.defaultCategoryId,
                    categoryName = categories.firstOrNull { it.id == type.defaultCategoryId }?.name
                )
            },
            categories = categories.map { cat ->
                CategoryRow(
                    id = cat.id,
                    name = cat.name,
                    color = cat.color,
                    icon = cat.icon
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityTypesUiState())

    /** M18.2: Slider-Drag — nur lokaler State, kein DB-Write. */
    fun onScoreDragged(typeId: String, score: Int) {
        pendingScores.value = pendingScores.value + (typeId to score)
    }

    /**
     * M18.6: Drag-Ende — Score in die DB schreiben.
     *
     * ROOT-CAUSE-FIX des "Slider springt auf 50 zurück"-Bugs:
     * Vorher: `commitScore(row.id, row.score)` im Screen-Lambda — das
     * referenzierte `row.score` aus der letzten RECOMPOSITION (Stale
     * Closure). Beim Drag feuern Dutzende Events, die Recomposition
     * hinkt hinterher → beim Loslassen wurde oft noch der ALTE Wert
     * (z.B. 50) committet → DB-Write 50 → Flow → Slider springt zurück.
     *
     * Jetzt: Der ViewModel liest den letzten Drag-Wert aus [pendingScores]
     * (Single Source of Truth für den aktuellen UI-Score). Kein Stale
     * Closure mehr möglich.
     */
    fun commitScore(typeId: String) {
        val score = pendingScores.value[typeId]
        if (score == null) return
        viewModelScope.launch {
            activityTypeRepository.setPositivityScore(typeId, score)
        }
    }

    // M18.12: Icon + Farbe setzen (direkt, kein Drag-State nötig).
    fun setIcon(typeId: String, icon: String) {
        viewModelScope.launch {
            activityTypeRepository.setIcon(typeId, icon)
        }
    }

    fun setColor(typeId: String, color: Long) {
        viewModelScope.launch {
            activityTypeRepository.setColor(typeId, color)
        }
    }

    // M18.17: Kategorie einer Aktivität zuweisen (null = keine).
    fun setCategory(typeId: String, categoryId: String?) {
        viewModelScope.launch {
            activityTypeRepository.setCategory(typeId, categoryId)
        }
    }

    // M18.17: Neue Kategorie anlegen (z. B. "Sport" für Joggen + Gym).
    fun createCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val cat = Category(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = trimmed,
            color = "#6366F1",
            icon = "◆",
            isSystem = false,
            sortOrder = 1000
        )
        viewModelScope.launch {
            categoryRepository.insert(cat)
        }
    }

    /**
     * M18.12: Neue Aktivität manuell anlegen.
     * Erzeugt einen eigenen ActivityType (isSystem=false) mit Defaults:
     * neutraler Score 50, Icon '•', Primärfarbe 0 (UI zeigt dann die
     * Standard-Akzentfarbe).
     */
    fun createActivity(name: String, categoryId: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val type = ActivityType(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            name = trimmed,
            defaultCategoryId = categoryId,
            isSystem = false,
            propertiesJson = null,
            isFavorite = false,
            positivityScore = 50,
            icon = "•",
            color = 0L
        )
        viewModelScope.launch {
            activityTypeRepository.insert(type)
        }
    }
}

data class ActivityTypesUiState(
    val activityTypes: List<ActivityTypeRow> = emptyList(),
    // M18.17: Kategorien für die Zuordnung.
    val categories: List<CategoryRow> = emptyList()
)

data class ActivityTypeRow(
    val id: String,
    val name: String,
    val score: Int,
    val isSystem: Boolean,
    // M18.12: Icon (Emoji) + custom Farbe (ARGB-Int, 0 = Primärfarbe)
    val icon: String = "•",
    val color: Long = 0L,
    // M18.17: Kategorie-Zuordnung (z. B. Joggen -> Sport)
    val categoryId: String? = null,
    val categoryName: String? = null
)

// M18.17: Kategorie für die Zuordnung im Screen.
data class CategoryRow(
    val id: String,
    val name: String,
    val color: String,
    val icon: String
)
