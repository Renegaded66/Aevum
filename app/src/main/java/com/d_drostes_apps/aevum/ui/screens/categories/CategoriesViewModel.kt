package com.d_drostes_apps.aevum.ui.screens.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M18.59: Kategorien verwalten — eigene Seite in den Einstellungen.
 *
 * User-Wunsch: "Eine neue Seite Kategorien in den Einstellungen unter
 * Activitys, wo alle Kategorien aufgelistet sind und die zugehörigen
 * Activities, mit der Möglichkeit, neue Kategorien zu erstellen und
 * Activities den Kategorien zuzuordnen. Die Kategorien sollten auch
 * personalisierbar mit Icon und Farbe sein."
 *
 * Usability-Entscheidungen (hinterfragt):
 * - Statt Drag&Drop (fehleranfällig, auf Touch schwer zu entdecken)
 *   eine ZWEI-SPALTEN-Ansicht: links Kategorien, rechts die Aktivitäten
 *   der gewählten Kategorie. Zuordnung per Dropdown pro Aktivität —
 *   sofort sichtbar, ein Tipp, kein langes Drücken.
 * - Neue Kategorie: Dialog mit Name + Icon-Picker (Emoji-Grid) +
 *   Farb-Picker (Palette) — gleiche Muster wie ActivityTypesScreen.
 * - System-Kategorien sind geschützt (kein Löschen), aber personalisierbar
 *   (Icon + Farbe) — User-Wunsch "personalisierbar".
 */
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : ViewModel() {

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId

    val uiState: StateFlow<CategoriesUiState> = combine(
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        _selectedCategoryId
    ) { categories, types, selectedId ->
        val effectiveSelected = selectedId
            ?: categories.firstOrNull()?.id
        CategoriesUiState(
            categories = categories,
            activityTypes = types,
            selectedCategoryId = effectiveSelected,
            selectedCategory = categories.firstOrNull { it.id == effectiveSelected },
            activitiesOfSelected = types.filter { it.defaultCategoryId == effectiveSelected },
            unassigned = types.filter { it.defaultCategoryId == null }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun selectCategory(id: String) {
        _selectedCategoryId.value = id
    }

    /** M18.59: Aktivität einer Kategorie zuordnen (null = keine/entfernen). */
    fun assignActivity(typeId: String, categoryId: String?) {
        viewModelScope.launch {
            activityTypeRepository.setCategory(typeId, categoryId)
        }
    }

    /** M18.59: Neue Kategorie anlegen (Name + Icon + Farbe). */
    fun createCategory(name: String, icon: String, colorHex: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val existing = categoryRepository.getAll().firstOrNull() ?: emptyList()
            val category = Category(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                name = trimmed,
                color = colorHex,
                icon = icon.ifBlank { "•" },
                isSystem = false,
                sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 10
            )
            categoryRepository.insert(category)
            _selectedCategoryId.value = category.id
        }
    }

    /** M18.59: Kategorie personalisieren (Icon + Farbe + Name). */
    fun updateCategory(category: Category, name: String, icon: String, colorHex: String) {
        viewModelScope.launch {
            categoryRepository.update(
                category.copy(
                    name = name.trim().ifBlank { category.name },
                    icon = icon.ifBlank { category.icon },
                    color = colorHex
                )
            )
        }
    }

    /** M18.59: Eigene Kategorie löschen (nur isSystem=false). */
    fun deleteCategory(category: Category) {
        if (category.isSystem) return
        viewModelScope.launch {
            // Aktivitäten der Kategorie werden "unassigned" (kein FK-Bruch).
            val types = activityTypeRepository.getAll().firstOrNull() ?: emptyList()
            types.filter { it.defaultCategoryId == category.id }.forEach { type ->
                activityTypeRepository.setCategory(type.id, null)
            }
            categoryRepository.delete(category.id)
            _selectedCategoryId.value = null
        }
    }
}

data class CategoriesUiState(
    val categories: List<Category> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val selectedCategoryId: String? = null,
    val selectedCategory: Category? = null,
    val activitiesOfSelected: List<ActivityType> = emptyList(),
    val unassigned: List<ActivityType> = emptyList()
)
