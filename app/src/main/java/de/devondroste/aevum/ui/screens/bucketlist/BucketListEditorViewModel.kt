package de.devondroste.aevum.ui.screens.bucketlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.BucketListItem
import de.devondroste.aevum.data.repository.BucketListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M18.39: Bucket-List-Editor-ViewModel.
 *
 * Form-State fuer neue + bestehende Eintraege. Felder (nach Recherche):
 * Titel, Ort, Icon (Emoji), Kategorie, optionales Zieldatum, Notizen,
 * optionales Bild (Dateipfad im App-Speicher).
 */
@HiltViewModel
class BucketListEditorViewModel @Inject constructor(
    private val repository: BucketListRepository
) : ViewModel() {

    private val formState = MutableStateFlow(BucketListEditorUiState())
    private val editingId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BucketListEditorUiState> = formState

    /** Bestehenden Eintrag laden (Edit-Modus). */
    fun loadItem(itemId: String) {
        viewModelScope.launch {
            val item = repository.getById(itemId) ?: return@launch
            editingId.value = itemId
            formState.value = BucketListEditorUiState(
                title = item.title,
                location = item.location ?: "",
                icon = item.icon ?: "",
                category = item.category ?: "",
                targetDate = item.targetDate ?: "",
                notes = item.notes ?: "",
                imagePath = item.imagePath,
                difficulty = item.difficulty
            )
        }
    }

    fun setTitle(value: String) = formState.update { it.copy(title = value) }
    fun setLocation(value: String) = formState.update { it.copy(location = value) }
    fun setIcon(value: String) = formState.update { it.copy(icon = value) }
    fun setCategory(value: String) = formState.update { it.copy(category = value) }
    fun setTargetDate(value: String) = formState.update { it.copy(targetDate = value) }
    fun setNotes(value: String) = formState.update { it.copy(notes = value) }
    fun setImagePath(value: String?) = formState.update { it.copy(imagePath = value) }
    // M18.43: Schwierigkeitsgrad (1-5 Sterne) — bestimmt die XP-Belohnung.
    fun setDifficulty(value: Int) = formState.update { it.copy(difficulty = value.coerceIn(1, 5)) }

    fun save() {
        val s = formState.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existingId = editingId.value
            if (existingId != null) {
                val existing = repository.getById(existingId)
                if (existing != null) {
                    repository.insert(
                        existing.copy(
                            title = s.title.trim(),
                            location = s.location.trim().ifBlank { null },
                            icon = s.icon.trim().ifBlank { null },
                            category = s.category.trim().ifBlank { null },
                            targetDate = s.targetDate.trim().ifBlank { null },
                            notes = s.notes.trim().ifBlank { null },
                            imagePath = s.imagePath,
                            difficulty = s.difficulty,
                            updatedAt = now
                        )
                    )
                    return@launch
                }
            }
            repository.insert(
                BucketListItem(
                    id = UUID.randomUUID().toString(),
                    title = s.title.trim(),
                    location = s.location.trim().ifBlank { null },
                    icon = s.icon.trim().ifBlank { null },
                    category = s.category.trim().ifBlank { null },
                    targetDate = s.targetDate.trim().ifBlank { null },
                    completed = false,
                    completedAt = null,
                    imagePath = s.imagePath,
                    notes = s.notes.trim().ifBlank { null },
                    difficulty = s.difficulty,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun MutableStateFlow<BucketListEditorUiState>.update(transform: (BucketListEditorUiState) -> BucketListEditorUiState) {
        this.value = transform(this.value)
    }
}

data class BucketListEditorUiState(
    val title: String = "",
    val location: String = "",
    val icon: String = "",
    val category: String = "",
    val targetDate: String = "",
    val notes: String = "",
    val imagePath: String? = null,
    // M18.43: Schwierigkeitsgrad (1-5 Sterne) — bestimmt die XP-Belohnung.
    val difficulty: Int = 1
)
