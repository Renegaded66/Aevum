package de.devondroste.aevum.ui.screens.activitytypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.2: Verwaltet die Positivitäts-Scores aller Activity Types.
 *
 * - [pendingScores]: lokaler UI-State pro Type — der Slider schreibt
 *   hier während des Drags rein (kein DB-Spam).
 * - [commitScore]: schreibt den finalen Wert bei Drag-Ende in die DB.
 */
@HiltViewModel
class ActivityTypesViewModel @Inject constructor(
    private val activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    // M18.2: Lokaler Drag-State: typeId -> pendingScore.
    // Wird beim Öffnen geleert und bei DB-Write aktualisiert.
    private val pendingScores = MutableStateFlow<Map<String, Int>>(emptyMap())

    val uiState: StateFlow<ActivityTypesUiState> = combine(
        activityTypeRepository.getAll(),
        pendingScores
    ) { types, pending ->
        ActivityTypesUiState(
            activityTypes = types.map { type ->
                val pendingScore = pending[type.id]
                ActivityTypeRow(
                    id = type.id,
                    name = type.name,
                    score = pendingScore ?: type.positivityScore,
                    isSystem = type.isSystem
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
}

data class ActivityTypesUiState(
    val activityTypes: List<ActivityTypeRow> = emptyList()
)

data class ActivityTypeRow(
    val id: String,
    val name: String,
    val score: Int,
    val isSystem: Boolean
)
