package de.devondroste.aevum.ui.screens.automation

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.automation.geofence.GeofenceDebugLogger
import de.devondroste.aevum.automation.geofence.GeofenceRegistrar
import de.devondroste.aevum.automation.geofence.GeofenceRegistrationResult
import de.devondroste.aevum.automation.location.CurrentLocationProvider
import de.devondroste.aevum.automation.location.CurrentLocationResult
import de.devondroste.aevum.automation.sleep.SleepHeuristicEngine
import de.devondroste.aevum.automation.notification.CandidateReviewNotifier
import de.devondroste.aevum.automation.rules.CandidateRuleOrchestrator
import de.devondroste.aevum.data.model.AutomationSettings
import de.devondroste.aevum.data.model.GeofenceEventLogEntry
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.AutomationSettingsRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.GeofenceEventLogRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.TagRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import de.devondroste.aevum.domain.digital.UsageStatsCollector
import de.devondroste.aevum.domain.health.HealthConnectManager
import de.devondroste.aevum.domain.time.TimeFormatting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

// ═══════════════════════════════════════════════
// AutomationSettingsViewModel (M8.1: refined)
// ═══════════════════════════════════════════════

@HiltViewModel
class AutomationSettingsViewModel @Inject constructor(
    private val app: Application,
    private val automationSettingsRepository: AutomationSettingsRepository,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val triggerRepository: TriggerEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val usageStatsCollector: UsageStatsCollector,
    private val sleepHeuristicEngine: SleepHeuristicEngine
) : ViewModel() {
    private val registrationMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AutomationSettingsUiState> = combine(
        automationSettingsRepository.get(),
        geofenceRepository.getAll(),
        triggerRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        registrationMessage
    ) { settings, geofences, triggers, candidates, message ->
        val perms = geofenceRegistrar.getPermissionStatus()
        AutomationSettingsUiState(
            settings = settings ?: AutomationSettings(),
            geofenceCount = geofences.size,
            triggerCount = triggers.size,
            pendingCandidateCount = candidates.size,
            foregroundLocationGranted = perms.foregroundGranted,
            backgroundLocationGranted = perms.backgroundGranted,
            notificationsGranted = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            usageStatsGranted = usageStatsCollector.hasPermission(),
            registrationMessage = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationSettingsUiState())

    fun setBackgroundCapture(enabled: Boolean) = upsert { it.copy(backgroundCaptureEnabled = enabled, geofencingEnabled = enabled) }
    fun setReviewNotifications(enabled: Boolean) = upsert { it.copy(reviewNotificationsEnabled = enabled) }
    fun setHealthSleep(enabled: Boolean) = upsert { it.copy(healthSleepEnabled = enabled) }
    fun setDigitalBalance(enabled: Boolean) = upsert { it.copy(digitalBalanceEnabled = enabled) }

    fun openUsageAccess() = usageStatsCollector.openUsageAccessSettings()
    fun openBackgroundLocationSettings() = geofenceRegistrar.openBackgroundLocationSettings()

    /**
     * M13: Manually trigger sleep-heuristic analysis (e.g. for the previous night).
     * Creates a candidate in the Review Inbox if the pattern is plausible.
     */
    private val _isAnalyzingSleep = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isAnalyzingSleep: kotlinx.coroutines.flow.StateFlow<Boolean> = _isAnalyzingSleep

    private val _sleepStatus = kotlinx.coroutines.flow.MutableStateFlow<de.devondroste.aevum.automation.sleep.SleepHeuristicStatus?>(null)
    val sleepStatus: kotlinx.coroutines.flow.StateFlow<de.devondroste.aevum.automation.sleep.SleepHeuristicStatus?> = _sleepStatus

    fun analyzeSleepNow() {
        viewModelScope.launch {
            _isAnalyzingSleep.value = true
            try {
                sleepHeuristicEngine.init(app)
                sleepHeuristicEngine.analyzeLatest()
                // Auch einen Status-Refresh anstoßen, damit der Dialog aktuell ist
                _sleepStatus.value = sleepHeuristicEngine.getStatus()
                registrationMessage.value = "✓ Schlaf-Analyse gestartet. Vorschlag in der Review-Inbox prüfen."
            } catch (e: Exception) {
                registrationMessage.value = "Analyse fehlgeschlagen: ${e.message}"
            } finally {
                _isAnalyzingSleep.value = false
            }
        }
    }

    /**
     * M12.1: Öffnet den Status-Dialog mit allen Heuristik-Informationen.
     * Lädt die Daten frisch aus dem Engine (kein Cache).
     */
    fun openSleepStatus() {
        viewModelScope.launch {
            try {
                sleepHeuristicEngine.init(app)
                _sleepStatus.value = sleepHeuristicEngine.getStatus()
            } catch (e: Exception) {
                registrationMessage.value = "Status nicht verfügbar: ${e.message}"
            }
        }
    }

    fun dismissSleepStatus() {
        _sleepStatus.value = null
    }

    fun refreshGeofences() {
        viewModelScope.launch {
            registrationMessage.value = when (val result = geofenceRegistrar.refreshRegisteredGeofences()) {
                is GeofenceRegistrationResult.Registered -> "${result.count} Geofences aktiv registriert"
                GeofenceRegistrationResult.MissingForegroundLocation -> "Standortberechtigung fehlt"
                GeofenceRegistrationResult.MissingBackgroundLocation -> "Hintergrundstandort nicht explizit gewährt (Android 14+ kann trotzdem funktionieren)"
                GeofenceRegistrationResult.SecurityDenied -> "Android hat die Standortregistrierung abgelehnt"
                is GeofenceRegistrationResult.Failed -> result.message
            }
        }
    }

    private fun upsert(transform: (AutomationSettings) -> AutomationSettings) {
        viewModelScope.launch {
            val current = uiState.value.settings
            automationSettingsRepository.upsert(transform(current).copy(updatedAt = System.currentTimeMillis()))
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
    val usageStatsGranted: Boolean = false,
    val registrationMessage: String? = null
)

// ═══════════════════════════════════════════════
// AutomationStatusViewModel (M8.1: new)
// ═══════════════════════════════════════════════

@HiltViewModel
class AutomationStatusViewModel @Inject constructor(
    private val app: Application,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val triggerRepository: TriggerEventRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val healthConnectManager: HealthConnectManager,
    private val usageStatsCollector: UsageStatsCollector,
    private val eventLog: GeofenceEventLogRepository
) : ViewModel() {
    private val actionMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AutomationStatusUiState> = combine(
        geofenceRepository.getAll(),
        triggerRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        try { eventLog.getRecent(50) } catch (_: Exception) { MutableStateFlow(emptyList()) },
        actionMessage
    ) { geofences, triggers, candidates, logEntries, msg ->
        val perms = geofenceRegistrar.getPermissionStatus()
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val triggersToday = triggers.count { it.occurredAt >= todayStart }
        val lastTrigger = triggers.maxByOrNull { it.occurredAt }
        val lastCandidate = candidates.maxByOrNull { it.createdAt }

        // M8.2: Diagnostic counts (safe — uses in-memory list)
        val entries = try { logEntries } catch (_: Exception) { emptyList() }
        val systemEventsToday = entries.count { it.category == "SYSTEM_EVENT" && it.occurredAt >= todayStart }
        val failuresToday = entries.count { !it.success && it.occurredAt >= todayStart }
        val lastSystemEvent = entries.firstOrNull { it.category == "SYSTEM_EVENT" }
        val lastRegistration = entries.firstOrNull { it.category == "REGISTRATION" && it.eventType == "REGISTERED" }

        AutomationStatusUiState(
            foregroundGranted = perms.foregroundGranted,
            backgroundGranted = perms.backgroundGranted,
            notificationsGranted = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            usageStatsGranted = usageStatsCollector.hasPermission(),
            healthConnectReady = healthConnectManager.hasSleepPermission(),
            geofenceCount = geofences.count { it.enabled && it.deletedAt == null },
            triggersToday = triggersToday,
            lastTriggerTime = lastTrigger?.let { TimeFormatting.formatTime(it.occurredAt) } ?: "—",
            pendingCandidates = candidates.size,
            lastAutoActivity = lastCandidate?.suggestedTitle ?: "",
            foregroundServiceRunning = true,
            systemEventsToday = systemEventsToday,
            failuresToday = failuresToday,
            lastSystemEventType = lastSystemEvent?.eventType ?: "—",
            lastSystemEventTime = lastSystemEvent?.let { TimeFormatting.formatTime(it.occurredAt) } ?: "—",
            lastRegistrationTime = lastRegistration?.let { TimeFormatting.formatTime(it.occurredAt) } ?: "—",
            recentLog = entries.take(15),
            actionMessage = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationStatusUiState())

    fun refreshAll() {
        viewModelScope.launch {
            actionMessage.value = when (val r = geofenceRegistrar.refreshRegisteredGeofences()) {
                is GeofenceRegistrationResult.Registered -> "✓ ${r.count} Geofences aktiv"
                else -> "Prüfung: ${r.javaClass.simpleName}"
            }
        }
    }

    fun reRegisterGeofences() = refreshAll()

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
}

data class AutomationStatusUiState(
    val foregroundGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val healthConnectReady: Boolean = false,
    val geofenceCount: Int = 0,
    val triggersToday: Int = 0,
    val lastTriggerTime: String = "—",
    val pendingCandidates: Int = 0,
    val lastAutoActivity: String = "",
    val foregroundServiceRunning: Boolean = false,
    // M8.2: Diagnostic data
    val systemEventsToday: Int = 0,
    val failuresToday: Int = 0,
    val lastSystemEventType: String = "—",
    val lastSystemEventTime: String = "—",
    val lastRegistrationTime: String = "—",
    val recentLog: List<GeofenceEventLogEntry> = emptyList(),
    val actionMessage: String? = null
)

// ═══════════════════════════════════════════════
// GeofenceListViewModel (unchanged)
// ═══════════════════════════════════════════════

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

// ═══════════════════════════════════════════════
// GeofenceEditorViewModel (M8.1: quickKind support)
// ═══════════════════════════════════════════════

@HiltViewModel
class GeofenceEditorViewModel @Inject constructor(
    private val geofenceRepository: PlaceGeofenceRepository,
    private val categoryRepository: CategoryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val tagRepository: TagRepository,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val currentLocationProvider: CurrentLocationProvider,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val geofenceId: String? = savedStateHandle["geofenceId"]
    val quickKind: QuickPlaceKind? = savedStateHandle.get<String>("quickKind")?.let { QuickPlaceKind.valueOf(it) }
    private val form = MutableStateFlow(GeofenceForm())
    private val saved = MutableStateFlow(false)
    private val locationMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            geofenceId?.let { id ->
                geofenceRepository.getById(id).collect { geofence ->
                    geofence ?: return@collect
                    val tags = geofenceRepository.getTagIdsForGeofence(id).first()
                    form.value = GeofenceForm(
                        id = geofence.id, name = geofence.name,
                        latitude = geofence.latitude.toString(), longitude = geofence.longitude.toString(),
                        radius = geofence.radiusMeters.toInt().toString(), icon = geofence.icon,
                        color = geofence.color, enabled = geofence.enabled,
                        activityTypeId = geofence.activityTypeId, categoryId = geofence.categoryId,
                        selectedTagIds = tags,
                        // M11+: separate autoStart activity type (may differ from default).
                        // Falls back to default if null.
                        autoEnabled = geofence.autoStartActivityTypeId != null,
                        autoStopEnabled = geofence.autoStopEnabled,
                        autoStartActivityTypeId = geofence.autoStartActivityTypeId
                    )
                }
            }
            // M8.1: Auto-apply quick setup if quickKind is set
            quickKind?.let { kind ->
                applyQuickSetup(kind)
            }
        }
    }

    val uiState: StateFlow<GeofenceEditorUiState> = combine(
        form, categoryRepository.getAll(), activityTypeRepository.getAll(), tagRepository.getAll()
    ) { f, cats, types, tags -> GeofenceEditorBase(f, cats, types, tags) }
        .combine(saved) { base, s -> base to s }
        .combine(locationMessage) { (base, s), msg ->
            GeofenceEditorUiState(base.form, base.categories, base.activityTypes, base.tags, s, msg)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofenceEditorUiState())

    fun setName(v: String) = form.update { it.copy(name = v, error = null) }
    fun setLatitude(v: String) = form.update { it.copy(latitude = v, error = null) }
    fun setLongitude(v: String) = form.update { it.copy(longitude = v, error = null) }
    fun setRadius(v: String) = form.update { it.copy(radius = v.filter { it.isDigit() }.take(4), error = null) }
    fun setIcon(v: String) = form.update { it.copy(icon = v.take(4), error = null) }
    fun setColor(v: String) = form.update { it.copy(color = v, error = null) }
    fun setEnabled(v: Boolean) = form.update { it.copy(enabled = v, error = null) }
    fun setActivityType(id: String?, catId: String?) = form.update { it.copy(activityTypeId = id, categoryId = catId ?: it.categoryId, error = null) }
    fun toggleTag(id: String) = form.update { it.copy(selectedTagIds = if (id in it.selectedTagIds) it.selectedTagIds - id else it.selectedTagIds + id, error = null) }
    fun setCoordinates(lat: Double, lon: Double) = form.update { it.copy(latitude = "%.6f".format(Locale.US, lat), longitude = "%.6f".format(Locale.US, lon), error = null) }
    // M11: Automation rules
    fun setAutoEnabled(v: Boolean) = form.update { it.copy(autoEnabled = v, error = null) }
    fun setAutoStopEnabled(v: Boolean) = form.update { it.copy(autoStopEnabled = v, error = null) }
    fun setAutoStartActivityTypeId(id: String?) = form.update { it.copy(autoStartActivityTypeId = id, error = null) }

    fun useCurrentLocation() {
        viewModelScope.launch {
            locationMessage.value = "Position wird ermittelt…"
            when (val r = currentLocationProvider.getCurrentLocation()) {
                is CurrentLocationResult.Success -> {
                    setCoordinates(r.latitude, r.longitude)
                    locationMessage.value = "Position übernommen · ±${r.accuracyMeters.toInt()}m"
                }
                CurrentLocationResult.MissingPermission -> locationMessage.value = "Standortberechtigung fehlt"
                is CurrentLocationResult.Unavailable -> locationMessage.value = r.message
            }
        }
    }

    fun applyQuickSetup(kind: QuickPlaceKind) {
        val typeId = if (kind == QuickPlaceKind.Home) "household" else "work"
        val catId = if (kind == QuickPlaceKind.Home) "household" else "work"
        form.update {
            it.copy(
                name = if (kind == QuickPlaceKind.Home) "Zuhause" else "Arbeit",
                icon = if (kind == QuickPlaceKind.Home) "🏠" else "💼",
                color = if (kind == QuickPlaceKind.Home) "#2DD4BF" else "#6366F1",
                radius = if (kind == QuickPlaceKind.Home) "120" else "150",
                activityTypeId = typeId, categoryId = catId, error = null,
                quickKind = kind
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val c = form.value
            val lat = c.latitude.replace(',', '.').toDoubleOrNull()
            val lon = c.longitude.replace(',', '.').toDoubleOrNull()
            val r = c.radius.toFloatOrNull()
            if (c.name.isBlank() || lat == null || lat !in -90.0..90.0 || lon == null || lon !in -180.0..180.0 || r == null || r < 50f) {
                form.update { it.copy(error = "Name, Position und Radius ab 50m prüfen") }
                return@launch
            }
            val now = System.currentTimeMillis()
            val existing = c.id?.let { geofenceRepository.getById(it).first() }
            val gf = PlaceGeofence(
                id = c.id ?: UUID.randomUUID().toString(), name = c.name.trim(),
                latitude = lat, longitude = lon, radiusMeters = r,
                icon = c.icon.ifBlank { "📍" }, color = c.color.ifBlank { "#6366F1" },
                enabled = c.enabled, activityTypeId = c.activityTypeId, categoryId = c.categoryId,
                createdAt = existing?.createdAt ?: now, updatedAt = now, deletedAt = existing?.deletedAt,
                // M11+: separate autoStart activity type (may differ from default).
                // When null, no auto-start happens. When set, the system starts
                // a session of that type whenever the geofence is entered.
                autoStartActivityTypeId = if (c.autoEnabled) c.autoStartActivityTypeId ?: c.activityTypeId else null,
                autoStopEnabled = c.autoStopEnabled
            )
            geofenceRepository.insertWithTags(gf, c.selectedTagIds)
            geofenceRegistrar.refreshRegisteredGeofences()
            saved.value = true
        }
    }
}

enum class QuickPlaceKind { Home, Work }

private data class GeofenceEditorBase(
    val form: GeofenceForm,
    val categories: List<de.devondroste.aevum.data.model.Category>,
    val activityTypes: List<de.devondroste.aevum.data.model.ActivityType>,
    val tags: List<de.devondroste.aevum.data.model.Tag>
)

data class GeofenceForm(
    val id: String? = null, val name: String = "", val latitude: String = "", val longitude: String = "",
    val radius: String = "150", val icon: String = "📍", val color: String = "#6366F1",
    val enabled: Boolean = true, val activityTypeId: String? = null, val categoryId: String? = null,
    val selectedTagIds: List<String> = emptyList(), val error: String? = null,
    val quickKind: QuickPlaceKind? = null,
    // M11: Automatisierung
    val autoEnabled: Boolean = false,
    val autoStopEnabled: Boolean = false,
    // M11+: Separate activity type for auto-start (defaults to activityTypeId)
    val autoStartActivityTypeId: String? = null
)

data class GeofenceEditorUiState(
    val form: GeofenceForm = GeofenceForm(),
    val categories: List<de.devondroste.aevum.data.model.Category> = emptyList(),
    val activityTypes: List<de.devondroste.aevum.data.model.ActivityType> = emptyList(),
    val tags: List<de.devondroste.aevum.data.model.Tag> = emptyList(),
    val saved: Boolean = false,
    val locationMessage: String? = null
)

// ═══════════════════════════════════════════════
// TriggerEventsViewModel (unchanged)
// ═══════════════════════════════════════════════

@HiltViewModel
class TriggerEventsViewModel @Inject constructor(
    triggerRepository: TriggerEventRepository,
    geofenceRepository: PlaceGeofenceRepository
) : ViewModel() {
    val uiState: StateFlow<TriggerEventsUiState> = combine(
        triggerRepository.getAll(), geofenceRepository.getAll()
    ) { triggers, geofences -> TriggerEventsUiState(triggers, geofences.associateBy { it.id }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TriggerEventsUiState())
}

data class TriggerEventsUiState(
    val triggers: List<TriggerEvent> = emptyList(),
    val geofenceNames: Map<String, PlaceGeofence> = emptyMap()
)

// ═══════════════════════════════════════════════
// GeofenceDebugViewModel (unchanged)
// ═══════════════════════════════════════════════

@HiltViewModel
class GeofenceDebugViewModel @Inject constructor(
    private val app: Application,
    private val geofenceRegistrar: GeofenceRegistrar,
    private val candidateRuleOrchestrator: CandidateRuleOrchestrator,
    private val debugLogger: GeofenceDebugLogger,
    automationSettingsRepository: AutomationSettingsRepository,
    geofenceRepository: PlaceGeofenceRepository,
    triggerRepository: TriggerEventRepository,
    candidateRepository: ActivityCandidateRepository
) : ViewModel() {
    private val lastAction = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GeofenceDebugUiState> = combine(
        automationSettingsRepository.get(), geofenceRepository.getAll(),
        triggerRepository.getAll(), candidateRepository.getByStatus("PENDING"), lastAction
    ) { settings, geofences, triggers, candidates, msg ->
        val perms = geofenceRegistrar.getPermissionStatus()
        GeofenceDebugUiState(
            settings = settings ?: AutomationSettings(),
            activeGeofences = geofences.count { it.enabled && it.deletedAt == null },
            inactiveGeofences = geofences.count { !it.enabled && it.deletedAt == null },
            triggerCount = triggers.size, pendingCandidates = candidates.size,
            foregroundLocationGranted = perms.foregroundGranted,
            backgroundLocationGranted = perms.backgroundGranted,
            notificationsGranted = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            geofenceReady = perms.foregroundGranted && perms.backgroundGranted,
            lastAction = msg, debugLog = debugLogger.entries()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeofenceDebugUiState())

    fun refreshRegistration() {
        viewModelScope.launch {
            lastAction.value = when (val r = geofenceRegistrar.refreshRegisteredGeofences()) {
                is GeofenceRegistrationResult.Registered -> "✓ ${r.count} Geofences"
                else -> r.javaClass.simpleName
            }
        }
    }

    fun runRulesNow() {
        viewModelScope.launch {
            val r = candidateRuleOrchestrator.evaluateRecentTriggers()
            lastAction.value = "${r.consideredTriggers} Trigger, ${r.insertedCandidates.size} neue Candidates"
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
}

data class GeofenceDebugUiState(
    val settings: AutomationSettings = AutomationSettings(),
    val activeGeofences: Int = 0, val inactiveGeofences: Int = 0,
    val triggerCount: Int = 0, val pendingCandidates: Int = 0,
    val foregroundLocationGranted: Boolean = false, val backgroundLocationGranted: Boolean = false,
    val notificationsGranted: Boolean = false, val geofenceReady: Boolean = false,
    val lastAction: String? = null, val debugLog: List<GeofenceDebugLogger.DebugEntry> = emptyList()
)
