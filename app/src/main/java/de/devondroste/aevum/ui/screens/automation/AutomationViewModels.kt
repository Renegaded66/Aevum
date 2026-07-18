package de.devondroste.aevum.ui.screens.automation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.automation.geofence.GeofenceRegistrar
import de.devondroste.aevum.automation.geofence.GeofenceRegistrationResult
import de.devondroste.aevum.data.model.AutomationSettings
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.AutomationSettingsRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.TagRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AutomationSettingsViewModel @Inject constructor(
    private val app: Application,
    private val automationSettingsRepository: AutomationSettingsRepository,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val triggerRepository: TriggerEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val geofenceRegistrar: GeofenceRegistrar
) : ViewModel() {
    private val registrationMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AutomationSettingsUiState> = combine(
        automationSettingsRepository.get(),
        geofenceRepository.getAll(),
        triggerRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        registrationMessage
    ) { settings, geofences, triggers, candidates, message ->
        AutomationSettingsUiState(
            settings = settings ?: AutomationSettings(),
            geofenceCount = geofences.size,
            triggerCount = triggers.size,
            pendingCandidateCount = candidates.size,
            foregroundLocationGranted = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION),
            backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notificationsGranted = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            registrationMessage = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationSettingsUiState())

    fun setBackgroundCapture(enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.settings
            automationSettingsRepository.upsert(current.copy(backgroundCaptureEnabled = enabled, geofencingEnabled = enabled, updatedAt = System.currentTimeMillis()))
            if (enabled) refreshGeofences()
        }
    }

    fun refreshGeofences() {
        viewModelScope.launch {
            registrationMessage.value = when (val result = geofenceRegistrar.refreshRegisteredGeofences()) {
                is GeofenceRegistrationResult.Registered -> "${result.count} Geofences aktiv registriert"
                GeofenceRegistrationResult.MissingForegroundLocation -> "Standortberechtigung fehlt"
                GeofenceRegistrationResult.MissingBackgroundLocation -> "Hintergrundstandort fehlt"
                GeofenceRegistrationResult.SecurityDenied -> "Android hat die Standortregistrierung abgelehnt"
                is GeofenceRegistrationResult.Failed -> result.message
            }
        }
    }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
}

data class AutomationSettingsUiState(
    val settings: AutomationSettings = AutomationSettings(),
    val geofenceCount: Int = 0,
    val triggerCount: Int = 0,
    val pendingCandidateCount: Int = 0,
    val foregroundLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val registrationMessage: String? = null
)

@HiltViewModel
class GeofenceListViewModel @Inject constructor(
    private val geofenceRepository: PlaceGeofenceRepository,
    private val geofenceRegistrar: GeofenceRegistrar
) : ViewModel() {
    val uiState: StateFlow<GeofenceListUiState> = geofenceRepository.getAll()
        .combine(MutableStateFlow(Unit)) { geofences, _ -> GeofenceListUiState(geofences) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofenceListUiState())

    fun delete(id: String) {
        viewModelScope.launch {
            geofenceRepository.softDelete(id, System.currentTimeMillis())
            geofenceRegistrar.refreshRegisteredGeofences()
        }
    }
}

data class GeofenceListUiState(val geofences: List<PlaceGeofence> = emptyList())

@HiltViewModel
class GeofenceEditorViewModel @Inject constructor(
    private val geofenceRepository: PlaceGeofenceRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val tagRepository: TagRepository,
    private val geofenceRegistrar: GeofenceRegistrar,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val geofenceId: String? = savedStateHandle["geofenceId"]
    private val form = MutableStateFlow(GeofenceForm())
    private val saved = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            geofenceId?.let { id ->
                geofenceRepository.getById(id).collect { geofence ->
                    geofence ?: return@collect
                    val tags = geofenceRepository.getTagIdsForGeofence(id).first()
                    form.value = GeofenceForm(
                        id = geofence.id,
                        name = geofence.name,
                        latitude = geofence.latitude.toString(),
                        longitude = geofence.longitude.toString(),
                        radius = geofence.radiusMeters.toInt().toString(),
                        icon = geofence.icon,
                        color = geofence.color,
                        enabled = geofence.enabled,
                        activityTypeId = geofence.activityTypeId,
                        categoryId = geofence.categoryId,
                        selectedTagIds = tags
                    )
                }
            }
        }
    }

    val uiState: StateFlow<GeofenceEditorUiState> = combine(
        form,
        categoryRepository.getAll(),
        activityTypeRepository.getAll(),
        tagRepository.getAll(),
        saved
    ) { form, categories, types, tags, isSaved -> GeofenceEditorUiState(form, categories, types, tags, isSaved) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofenceEditorUiState())

    fun setName(value: String) = form.update { it.copy(name = value) }
    fun setLatitude(value: String) = form.update { it.copy(latitude = value) }
    fun setLongitude(value: String) = form.update { it.copy(longitude = value) }
    fun setRadius(value: String) = form.update { it.copy(radius = value.filter { char -> char.isDigit() }.take(4)) }
    fun setIcon(value: String) = form.update { it.copy(icon = value.take(4)) }
    fun setColor(value: String) = form.update { it.copy(color = value) }
    fun setEnabled(value: Boolean) = form.update { it.copy(enabled = value) }
    fun setActivityType(id: String?, defaultCategoryId: String?) = form.update { it.copy(activityTypeId = id, categoryId = defaultCategoryId ?: it.categoryId) }
    fun toggleTag(id: String) = form.update { current -> current.copy(selectedTagIds = if (id in current.selectedTagIds) current.selectedTagIds - id else current.selectedTagIds + id) }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val latitude = current.latitude.toDoubleOrNull()
            val longitude = current.longitude.toDoubleOrNull()
            val radius = current.radius.toFloatOrNull()
            if (current.name.isBlank() || latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0 || radius == null || radius < 50f) {
                form.value = current.copy(error = "Name, Koordinaten und Radius ab 50m prüfen")
                return@launch
            }
            val now = System.currentTimeMillis()
            val geofence = PlaceGeofence(
                id = current.id ?: UUID.randomUUID().toString(),
                name = current.name.trim(),
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radius,
                icon = current.icon.ifBlank { "📍" },
                color = current.color.ifBlank { "#6366F1" },
                enabled = current.enabled,
                activityTypeId = current.activityTypeId,
                categoryId = current.categoryId,
                createdAt = now,
                updatedAt = now
            )
            geofenceRepository.insertWithTags(geofence, current.selectedTagIds)
            geofenceRegistrar.refreshRegisteredGeofences()
            saved.value = true
        }
    }
}

data class GeofenceForm(
    val id: String? = null,
    val name: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val radius: String = "150",
    val icon: String = "📍",
    val color: String = "#6366F1",
    val enabled: Boolean = true,
    val activityTypeId: String? = null,
    val categoryId: String? = null,
    val selectedTagIds: List<String> = emptyList(),
    val error: String? = null
)

data class GeofenceEditorUiState(
    val form: GeofenceForm = GeofenceForm(),
    val categories: List<de.devondroste.aevum.data.model.Category> = emptyList(),
    val activityTypes: List<de.devondroste.aevum.data.model.ActivityType> = emptyList(),
    val tags: List<de.devondroste.aevum.data.model.Tag> = emptyList(),
    val saved: Boolean = false
)

@HiltViewModel
class TriggerEventsViewModel @Inject constructor(
    triggerRepository: TriggerEventRepository,
    geofenceRepository: PlaceGeofenceRepository
) : ViewModel() {
    val uiState: StateFlow<TriggerEventsUiState> = combine(
        triggerRepository.getAll(),
        geofenceRepository.getAll()
    ) { triggers, geofences -> TriggerEventsUiState(triggers, geofences.associateBy { it.id }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerEventsUiState())
}

data class TriggerEventsUiState(
    val triggers: List<TriggerEvent> = emptyList(),
    val geofenceNames: Map<String, PlaceGeofence> = emptyMap()
)
