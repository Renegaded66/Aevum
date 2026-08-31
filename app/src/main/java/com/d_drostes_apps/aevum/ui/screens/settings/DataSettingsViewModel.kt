package com.d_drostes_apps.aevum.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.manager.DataManager
import com.d_drostes_apps.aevum.data.manager.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.55: ViewModel für Datenschutz, Export und Backup.
 * Kapselt alle Daten-Operationen hinter einem einzigen State.
 */
@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    private val dataManager: DataManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DataSettingsUiState())
    val uiState: StateFlow<DataSettingsUiState> = _uiState.asStateFlow()

    /** Exportiert alle Daten als JSON in die gewählte Zieldatei. */
    fun exportJson(target: Uri) = launchOperation {
        dataManager.exportJson(target)
    }

    /** Erstellt ein ZIP-Backup in die gewählte Zieldatei. */
    fun createBackup(target: Uri) = launchOperation {
        dataManager.createBackup(target)
    }

    /** Stellt ein ZIP-Backup wieder her. */
    fun restoreBackup(source: Uri) = launchOperation {
        dataManager.restoreBackup(source)
    }

    /** Löscht alle lokalen Daten. */
    fun deleteAllData() = launchOperation {
        dataManager.deleteAllData()
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, isError = false)
    }

    private fun launchOperation(block: suspend () -> ExportResult) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, message = null, isError = false)
            val result = block()
            _uiState.value = when (result) {
                is ExportResult.Success -> _uiState.value.copy(
                    isWorking = false,
                    message = result.message,
                    isError = false,
                    needsRestart = result.needsRestart
                )
                is ExportResult.Error -> _uiState.value.copy(
                    isWorking = false,
                    message = result.message,
                    isError = true
                )
            }
        }
    }
}

data class DataSettingsUiState(
    val isWorking: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val needsRestart: Boolean = false
)
