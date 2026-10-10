package com.d_drostes_apps.aevum.ui.screens.pctracking

import android.app.Application
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.pc.DiscoveredPc
import com.d_drostes_apps.aevum.data.pc.PcTrackerPrefs
import com.d_drostes_apps.aevum.data.repository.PcTrackerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ausgewählter PC (aus der WLAN-Suche oder manuell eingegeben). */
data class PcTarget(val name: String, val host: String, val port: Int)

data class PcTrackingUiState(
    val connected: PcTrackerPrefs.StoredConnection? = null,
    val discovering: Boolean = false,
    val discoveryDone: Boolean = false,
    val devices: List<DiscoveredPc> = emptyList(),
    val target: PcTarget? = null,
    val code: String = "",
    val pairing: Boolean = false,
    val manualMode: Boolean = false,
    val manualHost: String = "",
    val manualPort: String = "47825",
    val syncing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

/**
 * PC-Tracker: Kopplung mit dem Windows-Rechner im Heimnetz + Synchronisation
 * der PC-Aufzeichnungen in die Timeline.
 *
 * Alles läuft direkt zwischen Handy und PC (gleiches WLAN) — kein Server,
 * keine Cloud. Kopplung per Kurzcode, Sync per Pull.
 */
@HiltViewModel
class PcTrackingViewModel @Inject constructor(
    private val app: Application,
    private val repository: PcTrackerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PcTrackingUiState())
    val uiState: StateFlow<PcTrackingUiState> = _uiState

    private var autoSyncTriggered = false

    init {
        // Verbindung beobachten; sobald eine besteht (auch frisch gekoppelt),
        // einmal automatisch synchronisieren — beim Öffnen und nach Pairing.
        viewModelScope.launch {
            repository.connection.collect { connection ->
                _uiState.update { it.copy(connected = connection) }
                if (connection != null && !autoSyncTriggered) {
                    autoSyncTriggered = true
                    syncNow()
                }
            }
        }
    }

    /** PCs im WLAN per Broadcast suchen. */
    fun search() {
        viewModelScope.launch {
            _uiState.update { it.copy(discovering = true, error = null) }
            val devices = try {
                repository.discover()
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update {
                it.copy(discovering = false, discoveryDone = true, devices = devices)
            }
        }
    }

    fun chooseDevice(pc: DiscoveredPc) {
        _uiState.update {
            it.copy(
                target = PcTarget(pc.name, pc.host, pc.port),
                manualMode = false,
                code = "",
                error = null
            )
        }
    }

    fun openManual() {
        _uiState.update { it.copy(manualMode = true, target = null, code = "", error = null) }
    }

    fun setManualHost(value: String) = _uiState.update { it.copy(manualHost = value.trim()) }

    fun setManualPort(value: String) = _uiState.update { it.copy(manualPort = value.filter { c -> c.isDigit() }.take(5)) }

    /** Manuelle Angabe übernehmen (Zweitweg, falls die WLAN-Suche nichts findet). */
    fun proceedManual() {
        val state = _uiState.value
        val host = state.manualHost
        val port = state.manualPort.toIntOrNull()
        when {
            host.isBlank() -> _uiState.update { it.copy(error = app.getString(R.string.pc_tracker_error_host)) }
            port == null || port !in 1..65535 ->
                _uiState.update { it.copy(error = app.getString(R.string.pc_tracker_error_port)) }
            else -> _uiState.update {
                it.copy(target = PcTarget(host, host, port), error = null, code = "")
            }
        }
    }

    fun setCode(value: String) {
        _uiState.update {
            it.copy(code = value.filter { c -> c.isDigit() }.take(6), error = null)
        }
    }

    /** Kopplung durchführen (Code wird am PC angezeigt). */
    fun connect() {
        val state = _uiState.value
        val target = state.target ?: return
        if (state.code.isBlank()) {
            _uiState.update { it.copy(error = app.getString(R.string.pc_tracker_error_code)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(pairing = true, error = null) }
            val clientName = "Aevum auf " + (Build.MODEL ?: "Android")
            when (val outcome = repository.pair(target.host, target.port, state.code, clientName)) {
                is PcTrackerRepository.PairOutcome.Success ->
                    _uiState.update {
                        it.copy(pairing = false, target = null, code = "", message = null, error = null)
                    }
                PcTrackerRepository.PairOutcome.InvalidCode ->
                    _uiState.update {
                        it.copy(pairing = false, error = app.getString(R.string.pc_tracker_error_invalid_code))
                    }
                is PcTrackerRepository.PairOutcome.RateLimited ->
                    _uiState.update {
                        it.copy(
                            pairing = false,
                            error = app.getString(
                                R.string.pc_tracker_error_rate_limited,
                                outcome.retryAfterSeconds
                            )
                        )
                    }
                PcTrackerRepository.PairOutcome.NotReachable ->
                    _uiState.update {
                        it.copy(pairing = false, error = app.getString(R.string.pc_tracker_error_unreachable))
                    }
            }
        }
    }

    fun cancelPairing() {
        _uiState.update { it.copy(target = null, code = "", error = null) }
    }

    fun backToOverview() {
        _uiState.update { it.copy(manualMode = false, target = null, code = "", error = null) }
    }

    /** Aufzeichnungen vom PC abholen und in die Timeline importieren. */
    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true, message = null, error = null) }
            val result = try {
                repository.syncNow()
            } catch (_: Exception) {
                PcTrackerRepository.SyncOutcome.NotReachable
            }
            when (result) {
                is PcTrackerRepository.SyncOutcome.Success -> {
                    val text = if (result.inserted == 0 && result.updated == 0) {
                        app.getString(R.string.pc_tracker_sync_none)
                    } else {
                        app.getString(
                            R.string.pc_tracker_sync_result,
                            result.inserted,
                            result.updated
                        )
                    }
                    val withTypes = if (result.createdTypes.isNotEmpty()) {
                        text + " " + app.getString(
                            R.string.pc_tracker_sync_new_types,
                            result.createdTypes.joinToString(", ")
                        )
                    } else text
                    _uiState.update { it.copy(syncing = false, message = withTypes) }
                }
                PcTrackerRepository.SyncOutcome.NotConnected ->
                    _uiState.update { it.copy(syncing = false, error = app.getString(R.string.pc_tracker_error_not_connected)) }
                PcTrackerRepository.SyncOutcome.Unauthorized ->
                    _uiState.update { it.copy(syncing = false, error = app.getString(R.string.pc_tracker_error_unauthorized)) }
                PcTrackerRepository.SyncOutcome.NotReachable ->
                    _uiState.update { it.copy(syncing = false, error = app.getString(R.string.pc_tracker_error_unreachable)) }
            }
        }
    }

    /** Kopplung am Handy entfernen (importierte Aufzeichnungen bleiben). */
    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            autoSyncTriggered = false
            _uiState.update {
                it.copy(
                    message = app.getString(R.string.pc_tracker_disconnected),
                    error = null,
                    target = null,
                    code = "",
                    manualMode = false
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
