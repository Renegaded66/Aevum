package com.d_drostes_apps.aevum.ui.screens.weekly

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.R
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WeeklyReviewViewModel @Inject constructor(
    private val application: Application,
    activityRepository: ActivityRepository,
    candidateRepository: ActivityCandidateRepository,
    categoryRepository: CategoryRepository,
    activityTypeRepository: ActivityTypeRepository,
    // L10N-RUNTIME-FIX: Sprach-Flow — bei Sprachwechsel zur Laufzeit wird
    // der komplette Wochenrückblick (inkl. application.getString-Texte) neu gebaut.
    languageRepository: com.d_drostes_apps.aevum.data.repository.LanguageRepository,
    // M18.138: Bildschirmzeit-Quelle (Digital Balance).
    private val balanceSource: com.d_drostes_apps.aevum.domain.digital.DigitalBalanceSource
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val anchorDate = LocalDate.now()

    private val initialUiState = WeeklyReviewUiState(
        heroTitle = application.getString(R.string.weekly_hero_title),
        narrative = application.getString(R.string.weekly_narrative_empty),
        weekLabel = application.getString(R.string.insights_period_this_week),
        closingText = application.getString(R.string.weekly_closing_1),
        emptyTitle = application.getString(R.string.weekly_empty_title),
        emptyMessage = application.getString(R.string.weekly_empty_message)
    )

    val uiState: StateFlow<WeeklyReviewUiState> = languageRepository.language
        .flatMapLatest { _ ->
            // L10N-RUNTIME-FIX: Sprachwechsel → kompletter Rebuild.
            combine(
                activityRepository.getAll(),
                candidateRepository.getByStatus("PENDING"),
                categoryRepository.getAll(),
                activityTypeRepository.getAll(),
                // M18.138: Bildschirmzeit je Tag (Digital Balance).
                balanceSource.dailyTotals(LOOKBACK_DAYS)
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val sessions = values[0] as List<com.d_drostes_apps.aevum.data.model.ActivitySession>
                @Suppress("UNCHECKED_CAST")
                val candidates = values[1] as List<com.d_drostes_apps.aevum.data.model.ActivityCandidate>
                @Suppress("UNCHECKED_CAST")
                val categories = values[2] as List<com.d_drostes_apps.aevum.data.model.Category>
                @Suppress("UNCHECKED_CAST")
                val types = values[3] as List<com.d_drostes_apps.aevum.data.model.ActivityType>
                @Suppress("UNCHECKED_CAST")
                val balance = values[4] as Map<LocalDate, Long>
                WeeklyReviewAnalytics.build(
                    context = application,
                    sessions = sessions,
                    candidates = candidates,
                    categories = categories,
                    activityTypes = types,
                    anchorDate = anchorDate,
                    zoneId = zoneId,
                    balanceMsPerDay = balance
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialUiState)

    companion object {
        /**
         * M18.138: Rückblick-Fenster für die Bildschirmzeit. Der
         * Wochenrückblick zeigt eine Woche, der Vergleich braucht die
         * Vorwoche → 15 Tage genügen mit Reserve.
         */
        private const val LOOKBACK_DAYS = 15
    }
}
