package com.d_drostes_apps.aevum.ui.screens.insights

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.data.repository.HabitRepository
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.ui.screens.goals.GoalWithProgress
import com.d_drostes_apps.aevum.ui.screens.goals.toGoalWithProgress
import com.d_drostes_apps.aevum.ui.screens.habits.HabitWithProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val application: Application,
    private val activityRepository: ActivityRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val goalRepository: GoalRepository,
    private val habitRepository: HabitRepository,
    // M17.4: Tagespauschalen für die Statistik-Aggregation.
    private val dailyAllowanceRepository: DailyAllowanceRepository
) : ViewModel() {
    private val zoneId = java.time.ZoneId.systemDefault()
    private val anchorDate = java.time.LocalDate.now()

    // M18.34: Die letzte Period-Auswahl wird in SharedPreferences
    // persistiert und beim naechsten Oeffnen wiederhergestellt.
    // Default: Today (User-Praeferenz: "ich praefriere die heute ansicht").
    private val prefs = application.getSharedPreferences("aevum_insights", android.content.Context.MODE_PRIVATE)
    private val _selectedPeriod = MutableStateFlow(
        InsightPeriod.fromStorage(prefs.getString(KEY_PERIOD, null))
    )
    private val _selectedHeatmapDate = MutableStateFlow<java.time.LocalDate?>(null)
    // M17.4: Toggle zwischen Aktivitäts- und Kategorie-Aufschlüsselung.
    private val _breakdownMode = MutableStateFlow(BreakdownMode.Activity)

    private val dataFlow = combine(
        activityRepository.getAll(),
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        goalRepository.getByStatus("ACTIVE"),
        habitRepository.getActive(),
        // M17.4: Tagespauschalen — wir laden ALLE Accumululations, weil
        // die Period-Filter (Woche/Monat) in InsightsAnalytics.apply()
        // entschieden werden, nicht hier im ViewModel.
        dailyAllowanceRepository.getAll()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val sessions = values[0] as List<ActivitySession>
        val categories = values[1] as List<Category>
        val types = values[2] as List<ActivityType>
        @Suppress("UNCHECKED_CAST")
        val goals = values[3] as List<com.d_drostes_apps.aevum.data.model.Goal>
        @Suppress("UNCHECKED_CAST")
        val habits = values[4] as List<com.d_drostes_apps.aevum.data.model.Habit>
        @Suppress("UNCHECKED_CAST")
        val allowances = values[5] as List<com.d_drostes_apps.aevum.data.model.DailyAllowance>
        // M17.4: Hole die Accumulations einmalig (suspend → first())
        // Achtung: getAll() auf Accumulation existiert nicht im
        // Repository, also müssen wir die Accumulation-Reads im
        // Analytics-Build machen. Wir übergeben nur die Allowance-Liste
        // und laden die Accumulations dort on-demand.
        DataLayer(sessions, categories, types, goals, habits, allowances)
    }

    val uiState: StateFlow<InsightsUiState> = combine(
        dataFlow,
        _selectedPeriod,
        _selectedHeatmapDate,
        _breakdownMode
    ) { data, period, heatmapDate, breakdownMode ->
        val typeMap = data.types.associateBy { it.id }
        val goalProgress = data.goals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, data.sessions, anchorDate, zoneId, typeMap)
        }.sortedByDescending { it.progress }.map { it.toGoalWithProgress() }
        val habitProgress = data.habits.map { habit ->
            GoalProgressAnalytics.evaluateHabit(habit, data.sessions, anchorDate, zoneId, typeMap)
        }.map { result ->
            HabitWithProgress(
                habit = result.habit,
                streak = result.streak,
                successRate = result.successRate,
                activeDays = result.activeDays,
                totalDays = result.totalDays,
                heatmap = result.heatmap.map { day ->
                    com.d_drostes_apps.aevum.ui.screens.habits.HeatmapDay(
                        date = day.date,
                        completed = day.completed,
                        intensity = day.intensity
                    )
                },
                frequencyLabel = result.frequencyLabel,
                activityTypeName = result.activityTypeName
            )
        }
        // M17.4: Tagespauschalen-Accumulations im aktuellen Zeitraum laden
        // und zu den Sessions addieren. Bewusst nur in der Statistik, nicht
        // in der Timeline.
        // M18.33: ZUSAETZLICH enabled Allowances on-the-fly fuer die
        // aktuellen Tage einrechnen — eine heute erstellte Pauschale
        // erscheint SOFORT, nicht erst nach dem Midnight-Worker (00:05).
        val (periodStart, periodEnd) = computePeriodRange(period)
        val allowanceAccums = dailyAllowanceRepository.getAccumulationInRange(
            periodStart.toString(), periodEnd.toString()
        )
            // M18.37-FIX (Self-Healing): Verwaiste Accumulations ignorieren —
            // wenn die zugehoerige Allowance geloescht wurde (z.B. Pauschale
            // geloescht + neu erstellt), zaehlt die alte Accumulation sonst
            // doppelt. Nur Accumulations behalten, deren Allowance existiert.
            .filter { acc -> data.allowances.any { it.id == acc.allowanceId } }
            .toMutableList()
        // Heutige Pauschalen (und alle Tage ohne Accumulation) on-the-fly:
        // pro Tag im Zeitraum wird jede enabled Allowance addiert, die noch
        // keinen Accumulation-Eintrag hat (idempotent via PK-Check).
        val existingKeys = allowanceAccums.map { it.date to it.allowanceId }.toSet()
        val today = java.time.LocalDate.now()
        val enabledAllowances = data.allowances.filter { it.enabled }
        var cursor = periodStart
        while (!cursor.isAfter(periodEnd)) {
            if (cursor <= today) {
                enabledAllowances.forEach { allowance ->
                    val key = cursor.toString() to allowance.id
                    if (key !in existingKeys) {
                        allowanceAccums.add(
                            com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay(
                                date = cursor.toString(),
                                timezoneId = zoneId.id,
                                allowanceId = allowance.id,
                                activityTypeId = allowance.activityTypeId,
                                minutes = allowance.minutesPerDay
                            )
                        )
                    }
                }
            }
            cursor = cursor.plusDays(1)
        }
        InsightsAnalytics.build(
            sessions = data.sessions,
            categories = data.categories,
            activityTypes = data.types,
            selectedPeriod = period,
            anchorDate = anchorDate,
            zoneId = zoneId,
            goalProgress = goalProgress,
            habitProgress = habitProgress,
            // M17.4: neue Parameter
            allowanceAccumulations = allowanceAccums,
            breakdownMode = breakdownMode
        ).copy(selectedHeatmapDate = heatmapDate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun selectPeriod(period: InsightPeriod) {
        _selectedPeriod.value = period
        _selectedHeatmapDate.value = null
        // M18.34: Auswahl persistieren — beim naechsten Oeffnen direkt da.
        prefs.edit().putString(KEY_PERIOD, period.name).apply()
    }

    fun selectHeatmapDay(date: java.time.LocalDate) {
        _selectedHeatmapDate.value = date
    }

    /** M17.4: Toggle zwischen Aktivitäts- und Kategorie-Aufschlüsselung. */
    fun setBreakdownMode(mode: BreakdownMode) {
        _breakdownMode.value = mode
    }

    private fun computePeriodRange(period: InsightPeriod): Pair<java.time.LocalDate, java.time.LocalDate> {
        return when (period) {
            InsightPeriod.Today -> anchorDate to anchorDate
            InsightPeriod.Week -> {
                val start = anchorDate.minusDays(6)
                start to anchorDate
            }
            InsightPeriod.Month -> {
                val start = anchorDate.minusDays(29)
                start to anchorDate
            }
        }
    }

    private data class DataLayer(
        val sessions: List<ActivitySession>,
        val categories: List<Category>,
        val types: List<ActivityType>,
        val goals: List<com.d_drostes_apps.aevum.data.model.Goal>,
        val habits: List<com.d_drostes_apps.aevum.data.model.Habit>,
        val allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance>
    )

    companion object {
        // M18.34: Storage-Key fuer die persistierte Period-Auswahl.
        private const val KEY_PERIOD = "selected_period"
    }
}

/** M17.4: Aufschlüsselungs-Modus für die Top-Liste. */
enum class BreakdownMode { Activity, Category }
