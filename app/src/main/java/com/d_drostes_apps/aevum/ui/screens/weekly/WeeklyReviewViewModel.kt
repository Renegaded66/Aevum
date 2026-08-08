package com.d_drostes_apps.aevum.ui.screens.weekly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class WeeklyReviewViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    candidateRepository: ActivityCandidateRepository,
    categoryRepository: CategoryRepository,
    activityTypeRepository: ActivityTypeRepository,
    goalRepository: GoalRepository
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val anchorDate = LocalDate.now()

    val uiState: StateFlow<WeeklyReviewUiState> = combine(
        activityRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        goalRepository.getByStatus("ACTIVE")
    ) { sessions, candidates, categories, types, activeGoals ->
        WeeklyReviewAnalytics.build(
            sessions = sessions,
            candidates = candidates,
            categories = categories,
            activityTypes = types,
            activeGoals = activeGoals,
            anchorDate = anchorDate,
            zoneId = zoneId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklyReviewUiState())
}
