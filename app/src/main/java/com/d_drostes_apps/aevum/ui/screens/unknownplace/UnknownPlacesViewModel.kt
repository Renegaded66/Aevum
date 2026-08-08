package com.d_drostes_apps.aevum.ui.screens.unknownplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.UnknownPlaceSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * M17.2: ViewModel für den Unknown Places Screen.
 *
 * Zeigt alle noch nicht aufgelösten unbekannten Orte. Der User kann:
 *  - Einen Namen vergeben (→ ActivitySession-Konvertierung später)
 *  - Einen Geofence daraus erstellen (mit konfigurierbarem Radius)
 *  - Den Eintrag verwerfen
 */
@HiltViewModel
class UnknownPlacesViewModel @Inject constructor(
    private val unknownRepo: UnknownPlaceSessionRepository,
    private val geofenceRepo: PlaceGeofenceRepository
) : ViewModel() {

    val uiState: StateFlow<UnknownPlacesUiState> = combine(
        unknownRepo.getAll(),
        geofenceRepo.getAll()
    ) { all, geofences ->
        UnknownPlacesUiState(
            openEntries = all.filter { !it.resolved }.sortedByDescending { it.startAt },
            resolvedEntries = all.filter { it.resolved }.sortedByDescending { it.startAt }.take(20),
            allGeofences = geofences
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), UnknownPlacesUiState()
    )

    /**
     * M17.2: Eintrag mit Namen versehen. Wir markieren ihn als "named"
     * und konvertieren die Zeit in eine ActivitySession (so dass sie
     * in der Timeline erscheint). [TODO Phase 4.5: Konvertierung in
     * ActivitySession inkl. Edit-Screen-Verlinkung. Für jetzt: nur
     * markieren, damit der Eintrag nicht mehr "unbenannt" ist.]
     */
    fun assignName(id: String, name: String) {
        viewModelScope.launch {
            unknownRepo.markNamed(id, name)
        }
    }

    /**
     * M17.2: Eintrag verwerfen.
     */
    fun dismiss(id: String) {
        viewModelScope.launch {
            unknownRepo.markDismissed(id)
        }
    }

    /**
     * M17.2: Aus einem unbekannten Ort einen Geofence erstellen.
     * Der User hat einen Namen + Radius gewählt. Wir:
     *  1) Erzeugen einen PlaceGeofence mit den Koordinaten des Eintrags
     *  2) Markieren den UnknownPlace-Eintrag als "converted" (mit geofenceId)
     *  3) Refreshen die registrierten Geofences beim System
     */
    fun convertToGeofence(
        unknownId: String,
        name: String,
        radiusMeters: Float,
        icon: String = "📍",
        color: String = "#6366F1"
    ) {
        viewModelScope.launch {
            val unknown = unknownRepo.getById(unknownId) ?: return@launch
            val geofence = PlaceGeofence(
                id = UUID.randomUUID().toString(),
                name = name,
                latitude = unknown.latitude,
                longitude = unknown.longitude,
                radiusMeters = radiusMeters,
                icon = icon,
                color = color,
                enabled = true,
                activityTypeId = null,
                categoryId = null
            )
            geofenceRepo.insert(geofence)
            unknownRepo.markConverted(unknownId, geofence.id)
        }
    }
}

data class UnknownPlacesUiState(
    val openEntries: List<UnknownPlaceSession> = emptyList(),
    val resolvedEntries: List<UnknownPlaceSession> = emptyList(),
    val allGeofences: List<PlaceGeofence> = emptyList()
)
