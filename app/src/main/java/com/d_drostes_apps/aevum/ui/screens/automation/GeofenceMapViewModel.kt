package com.d_drostes_apps.aevum.ui.screens.automation

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
import com.d_drostes_apps.aevum.automation.location.CurrentLocationResult
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.92 — GeofenceMapViewModel: Daten-Grundlage für die Geofence-Übersichts-
 * karte ("Einstellungen → Geofences → 🗺️ → fancy Karte mit allen Geofences").
 *
 * Liefert drei Streams kombiniert:
 *  - Aktive Geofences (deletedAt == null — das DAO filtert bereits, aber
 *    defensiv doppelt geprüft, falls künftige DAO-Änderungen das lockern)
 *    alphabetisch sortiert (konsistent mit der Geofence-Liste)
 *  - Den letzten Live-Standort (CurrentLocationProvider, Puls-Pattern)
 *  - Ein UI-Flag für den "Standort anzeigen"-Button (nur bei erteilter
 *    Fein-Standort-Berechtigung — M18.60-Pattern: Buttons nur zeigen, wenn
 *    sie tatsächlich etwas tun können)
 *
 * Standort-Puls: alle 60s ein frischer Fix, solange die Karte offen ist.
 * CurrentLocationProvider.getCurrentLocation() hat ein internes 8s-Timeout
 * und liefert max. 2 Min alte Caches — für eine Übersichtskarte ausreichend
 * und akkuschonend (kein kontinuierlicher Stream nötig, der nur die
 * DriveDetection-Infrastruktur duplizieren würde).
 */
@HiltViewModel
class GeofenceMapViewModel @Inject constructor(
    private val app: Application,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val locationProvider: CurrentLocationProvider
) : ViewModel() {

    private val location = MutableStateFlow<UserLocationState?>(null)
    private var locationJob: Job? = null

    val uiState: StateFlow<GeofenceMapUiState> = combine(
        geofenceRepository.getAll(),
        location
    ) { geofences, loc ->
        GeofenceMapUiState(
            // Alphabetisch wie die Liste (M18.66-FIX20-Sortierung) — dieselbe
            // Ordnung in Karte und Liste, keine Überraschung beim Wechsel.
            geofences = geofences
                .filter { it.deletedAt == null }
                .sortedBy { it.name.lowercase() },
            location = loc,
            locationPermissionGranted = hasFineOrCoarseLocation()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofenceMapUiState())

    /** Startet den 60s-Standort-Puls (idempotent — mehrfach ok). */
    fun startLocationPulse() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            while (true) {
                if (hasFineOrCoarseLocation()) {
                    when (val r = locationProvider.getCurrentLocation()) {
                        is CurrentLocationResult.Success ->
                            location.value = UserLocationState(
                                latitude = r.latitude,
                                longitude = r.longitude,
                                accuracyMeters = r.accuracyMeters
                            )
                        is CurrentLocationResult.MissingPermission -> location.value = null
                        is CurrentLocationResult.Unavailable -> {
                            // Bestehenden Fix behalten (besser als nichts),
                            // nur loggen — die Karte ist trotzdem voll nutzbar.
                            Log.w(TAG, "Standort nicht verfügbar: ${r.message}")
                        }
                    }
                } else {
                    location.value = null
                }
                delay(PULSE_INTERVAL_MS)
            }
        }
    }

    /** Manueller Refresh (Re-Center-Button) — sofortiger Fix statt Warten. */
    fun refreshLocationNow() {
        viewModelScope.launch {
            if (!hasFineOrCoarseLocation()) return@launch
            when (val r = locationProvider.getCurrentLocation()) {
                is CurrentLocationResult.Success ->
                    location.value = UserLocationState(r.latitude, r.longitude, r.accuracyMeters)
                else -> Unit
            }
        }
    }

    fun stopLocationPulse() {
        locationJob?.cancel()
        locationJob = null
    }

    private fun hasFineOrCoarseLocation(): Boolean =
        ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCleared() {
        stopLocationPulse()
        super.onCleared()
    }

    companion object {
        private const val TAG = "GeofenceMapViewModel"
        /** 60s-Puls: frisch genug für eine Übersichtskarte, akkuschonend. */
        private const val PULSE_INTERVAL_MS = 60_000L
    }
}

/** Letzter bekannter Standort des Nutzers für den pulsierenden 🧍-Marker. */
data class UserLocationState(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

data class GeofenceMapUiState(
    val geofences: List<PlaceGeofence> = emptyList(),
    val location: UserLocationState? = null,
    val locationPermissionGranted: Boolean = false
)