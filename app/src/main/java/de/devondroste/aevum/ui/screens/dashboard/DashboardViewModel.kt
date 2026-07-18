package de.devondroste.aevum.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.domain.seed.EnsureDefaultDataUseCase
import de.devondroste.aevum.domain.time.TimeFormatting
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val start = TimeFormatting.startOfDayMillis(today, zoneId)
    private val end = TimeFormatting.endOfDayMillis(today, zoneId)

    init {
        viewModelScope.launch { ensureDefaultData() }
    }

    val uiState: StateFlow<DashboardUiState> = activityRepository.getOverlappingRange(start, end)
        .combine(categoryRepository.getAll()) { sessions, categories -> buildState(sessions, categories) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun buildState(sessions: List<ActivitySession>, categories: List<Category>): DashboardUiState {
        val activeSessions = sessions.filter { it.deletedAt == null }
        val now = System.currentTimeMillis()
        val totalMs = activeSessions.sumOf { (it.endAt ?: now) - it.startAt }
        val categoryMap = categories.associateBy { it.id }
        val distribution = activeSessions
            .groupBy { it.categoryId ?: "unknown" }
            .map { (categoryId, values) ->
                DashboardCategorySlice(
                    categoryId = categoryId,
                    label = categoryMap[categoryId]?.name ?: "Sonstiges",
                    durationMs = values.sumOf { (it.endAt ?: now) - it.startAt }
                )
            }
            .sortedByDescending { it.durationMs }
        val current = activeSessions.filter { it.endAt == null }.maxByOrNull { it.startAt } ?: activeSessions.maxByOrNull { it.startAt }
        val timeline = activeSessions.sortedBy { it.startAt }.take(4).map { session ->
            DashboardTimelineRow(
                id = session.id,
                time = TimeFormatting.formatTime(session.startAt, zoneId),
                title = session.title,
                categoryName = categoryMap[session.categoryId]?.name ?: "Sonstiges",
                duration = TimeFormatting.formatDuration((session.endAt ?: now) - session.startAt),
                source = session.sourceType,
                isCurrent = session.endAt == null
            )
        }
        return DashboardUiState(
            currentActivity = current?.title ?: "Noch nichts erfasst",
            currentDuration = current?.let { TimeFormatting.formatDuration((it.endAt ?: now) - it.startAt) } ?: "0m",
            focusScore = estimateFocusScore(distribution, totalMs),
            totalTracked = TimeFormatting.formatDuration(totalMs),
            sessionCount = activeSessions.size,
            distribution = distribution,
            timeline = timeline,
            hasData = activeSessions.isNotEmpty()
        )
    }

    private fun estimateFocusScore(distribution: List<DashboardCategorySlice>, totalMs: Long): Int {
        if (totalMs <= 0) return 0
        val productiveMs = distribution
            .filter { it.categoryId in setOf("work", "learning", "sport", "health") }
            .sumOf { it.durationMs }
        return ((productiveMs.toFloat() / totalMs.toFloat()) * 100).toInt().coerceIn(0, 100)
    }
}

data class DashboardUiState(
    val currentActivity: String = "Noch nichts erfasst",
    val currentDuration: String = "0m",
    val focusScore: Int = 0,
    val totalTracked: String = "0m",
    val sessionCount: Int = 0,
    val distribution: List<DashboardCategorySlice> = emptyList(),
    val timeline: List<DashboardTimelineRow> = emptyList(),
    val hasData: Boolean = false
)

data class DashboardCategorySlice(
    val categoryId: String,
    val label: String,
    val durationMs: Long
)

data class DashboardTimelineRow(
    val id: String,
    val time: String,
    val title: String,
    val categoryName: String,
    val duration: String,
    val source: String,
    val isCurrent: Boolean
)
