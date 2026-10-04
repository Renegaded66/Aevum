package com.d_drostes_apps.aevum.ui.screens.activitystats

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.LanguageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * M18.146: Detail-Statistikseite einer Aktivität — „fancy graph" mit
 * täglich/wöchentlich/monatlich umschaltbarem Verlauf.
 *
 * Bewusste Design-Entscheidungen:
 *
 *  1. **Reaktiv über Room**, wie alle Statistik-Sichten der App: ändert
 *     sich eine Aufzeichnung dieser Aktivität, baut der Graph sich sofort
 *     neu auf. Kein Snapshot beim Öffnen.
 *
 *  2. **Der Sprach-Flow hängt am combine** (L10N-RUNTIME-FIX-Muster): die
 *     Labels (Wochentage, Monatsnamen, „Heute") werden bei einem
 *     Sprachwechsel zur Laufzeit neu gebaut — sonst bliebe die Seite in der
 *     Sprache des Öffnens stehen.
 *
 *  3. **Die Granularität wird persistiert** (SharedPreferences-Präferenz,
 *     gleiches Muster wie die Period-Auswahl der Insights, M18.34): wer die
 *     Seite einmal auf „monatlich" gestellt hat, findet sie beim nächsten
 *     Besuch so wieder.
 *
 *  4. **Digital-Balance fließt NICHT ein** (dokumentiertes Nicht-Bestandteil):
 *     Die Statistikseite zeigt die Aufzeichnungen einer Aktivität; die
 *     Bildschirmzeit der Digital-Balance ist ein eigene Kennzahl mit eigener
 *     Heimat (Balance-Tab) und würde hier eine teure UsageStats-Abfrage
 *     über viele Monate erfordern.
 */
@HiltViewModel
class ActivityStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val application: Application,
    private val activityRepository: ActivityRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    languageRepository: LanguageRepository
) : ViewModel() {

    /** activityTypeId aus der Route („stats/{activityTypeId}"). */
    private val activityTypeId: String = checkNotNull(savedStateHandle["activityTypeId"])

    private val prefs = application.getSharedPreferences(
        com.d_drostes_apps.aevum.ui.screens.insights.InsightsViewModel.PREFS_NAME,
        android.content.Context.MODE_PRIVATE
    )

    private val _range = MutableStateFlow(
        ActivityStatsRange.fromStorage(prefs.getString(KEY_RANGE, null))
    )
    private val zoneId = java.time.ZoneId.systemDefault()

    val uiState: StateFlow<ActivityStatsUiState> = combine(
        languageRepository.language,
        activityRepository.getAll(),
        activityTypeRepository.getAll(),
        _range
    ) { _, sessions, types, range ->
        val type = types.firstOrNull { it.id == activityTypeId }
        val today = java.time.LocalDate.now()
        val model = ActivityStatsAnalytics.build(
            sessions = sessions,
            activityTypeId = activityTypeId,
            range = range,
            today = today,
            zoneId = zoneId,
            context = application
        )
        ActivityStatsUiState(
            activityTypeId = activityTypeId,
            activityName = type?.name ?: activityTypeId,
            activityIcon = type?.icon ?: "•",
            activityColor = type?.color ?: 0L,
            model = model
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ActivityStatsUiState(activityTypeId = activityTypeId, activityName = activityTypeId)
    )

    /** Umschalten der Granularität (täglich / wöchentlich / monatlich). */
    fun selectRange(range: ActivityStatsRange) {
        _range.value = range
        prefs.edit().putString(KEY_RANGE, range.name).apply()
    }

    companion object {
        private const val KEY_RANGE = "activity_stats_range"
    }
}

/** Zustand der Aktivitäts-Statistikseite. */
data class ActivityStatsUiState(
    val activityTypeId: String = "",
    val activityName: String = "",
    val activityIcon: String = "•",
    /** Custom-Farbe der Aktivität (ARGB-Long, 0 = keine gesetzt). */
    val activityColor: Long = 0L,
    val model: ActivityStatsUiModel = ActivityStatsUiModel()
)
