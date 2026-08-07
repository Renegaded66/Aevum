package de.devondroste.aevum.ui.screens.bucketlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.BucketListItem
import de.devondroste.aevum.data.repository.BucketListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M18.39: Bucket-List-ViewModel.
 *
 * Verwaltet die komplette Bucket-List:
 *  - Alle Eintraege (offen + erledigt)
 *  - Erledigt/Offen umschalten (mit completedAt-Zeitstempel)
 *  - Loeschen
 *  - Fortschritt (X von Y geschafft)
 */
@HiltViewModel
class BucketListViewModel @Inject constructor(
    private val repository: BucketListRepository
) : ViewModel() {

    val uiState: StateFlow<BucketListUiState> = repository.getAll()
        .map { items ->
            val done = items.count { it.completed }
            BucketListUiState(
                items = items,
                doneCount = done,
                totalCount = items.size,
                progress = if (items.isEmpty()) 0f else done.toFloat() / items.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BucketListUiState())

    fun toggleCompleted(item: BucketListItem) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.setCompleted(
                id = item.id,
                completed = !item.completed,
                completedAt = if (!item.completed) now else null,
                now = now
            )
        }
    }

    fun delete(item: BucketListItem) {
        viewModelScope.launch { repository.delete(item.id) }
    }
}

data class BucketListUiState(
    val items: List<BucketListItem> = emptyList(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val progress: Float = 0f
)
