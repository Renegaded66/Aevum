package de.devondroste.aevum.ui.screens.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : ViewModel() {
    private val zoneId = java.time.ZoneId.systemDefault()
    private val anchorDate = java.time.LocalDate.now()
    private val _selectedPeriod = MutableStateFlow(InsightPeriod.Week)
    private val _selectedHeatmapDate = MutableStateFlow<java.time.LocalDate?>(null)

    val uiState: StateFlow<InsightsUiState> = combine(
        activityRepository.getAll(),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        _selectedPeriod,
        _selectedHeatmapDate
    ) { sessions, categories, types, period, heatmapDate ->
        InsightsAnalytics.build(
            sessions = sessions,
            categories = categories,
            activityTypes = types,
            selectedPeriod = period,
            anchorDate = anchorDate,
            zoneId = zoneId
        ).copy(selectedHeatmapDate = heatmapDate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun selectPeriod(period: InsightPeriod) {
        _selectedPeriod.value = period
        _selectedHeatmapDate.value = null
    }

    fun selectHeatmapDay(date: java.time.LocalDate) {
        _selectedHeatmapDate.value = date
    }
}