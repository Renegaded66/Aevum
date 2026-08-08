package com.d_drostes_apps.aevum.ui.screens.allowance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DailyAllowancesViewModel @Inject constructor(
    private val repo: DailyAllowanceRepository,
    private val activityTypeRepo: ActivityTypeRepository
) : ViewModel() {

    val uiState: StateFlow<DailyAllowancesUiState> = combine(
        repo.getAll(),
        activityTypeRepo.getAll()
    ) { allowances, types ->
        DailyAllowancesUiState(
            allowances = allowances,
            activityTypes = types,
            activityTypesById = types.associateBy { it.id }
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyAllowancesUiState()
    )

    fun insert(name: String, activityTypeId: String, minutes: Int) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repo.insert(
                DailyAllowance(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    activityTypeId = activityTypeId,
                    minutesPerDay = minutes,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    // M18.29: Edit — bestehende Pauschale aktualisieren
    fun update(id: String, name: String, activityTypeId: String, minutes: Int) {
        viewModelScope.launch {
            val existing = repo.getById(id)
            if (existing != null) {
                repo.insert(
                    existing.copy(
                        name = name,
                        activityTypeId = activityTypeId,
                        minutesPerDay = minutes,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            // M18.37-FIX: Accumulations mitloeschen — sonst zaehlt eine
            // geloeschte + neu erstellte Pauschale doppelt.
            repo.deleteAccumulationsForAllowance(id)
            repo.delete(id)
        }
    }
}

data class DailyAllowancesUiState(
    val allowances: List<DailyAllowance> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val activityTypesById: Map<String, ActivityType> = emptyMap()
)
