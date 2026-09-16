package com.d_drostes_apps.aevum.ui.screens.calendar

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.automation.calendar.CalendarAutoRunScheduler
import com.d_drostes_apps.aevum.automation.calendar.CalendarReader
import com.d_drostes_apps.aevum.automation.calendar.CalendarSyncScheduler
import com.d_drostes_apps.aevum.data.db.AutomationSettingsDao
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * M18.129: ViewModel der Kalender-Regeln-Seite.
 *
 * Deckt alles ab, was die Seite braucht:
 *  - Permission-Status (4-State-Modell, siehe [CalendarPermissionState])
 *  - Feature-Toggles (Sync + Auto-Aufzeichnung getrennt)
 *  - Regel-Liste mit CRUD
 *  - Manueller Sync + „letzter Sync"-Zeitstempel
 *  - Kalender-Liste für die CALENDAR_IS-Regel
 */
@HiltViewModel
class CalendarRulesViewModel @Inject constructor(
    application: Application,
    private val calendarRepository: CalendarRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val settingsDao: AutomationSettingsDao,
    private val calendarReader: CalendarReader,
    private val syncScheduler: CalendarSyncScheduler
) : AndroidViewModel(application) {

    private val TAG = "CalendarRulesVM"

    /** Permission-Status — wird bei jedem Resume neu gelesen (Hot-Reload). */
    // Explizite Typ-Parameter nötig: ohne sie inferiert Kotlin aus
    // `NotAsked` den Typ der konkreten Objekt-Instanz statt der
    // Basisklasse, und jede Zuweisung von Granted/Denied schlägt fehl.
    private val _permissionState = MutableStateFlow<CalendarPermissionState>(CalendarPermissionState.NotAsked)
    val permissionState: StateFlow<CalendarPermissionState> = _permissionState

    /** Laufender manueller Sync (Button-Spinner). */
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** Ergebnis-Meldung des letzten manuellen Syncs (transient). */
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage

    /** Verfügbare Kalender (für die CALENDAR_IS-Auswahl). */
    private val _calendars = MutableStateFlow<List<CalendarOption>>(emptyList())
    val calendars: StateFlow<List<CalendarOption>> = _calendars

    val uiState: StateFlow<CalendarRulesUiState> = combine(
        calendarRepository.getRules(),
        activityTypeRepository.getAll(),
        settingsDao.get(),
        calendarRepository.eventCountFlow(),
        calendarRepository.ruleCount()
    ) { rules: List<CalendarRule>, types: List<ActivityType>, settings, eventCount: Int, _: Int ->
        CalendarRulesUiState(
            rules = rules,
            activityTypes = types,
            syncEnabled = settings?.calendarSyncEnabled == true,
            autoTrackingEnabled = settings?.calendarAutoTrackingEnabled == true,
            lastSyncAt = settings?.calendarLastSyncAt ?: 0L,
            syncIntervalHours = settings?.calendarSyncIntervalHours
                ?: CalendarSyncScheduler.DEFAULT_INTERVAL_HOURS,
            cachedEventCount = eventCount
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CalendarRulesUiState()
    )

    init {
        // Permission beim ersten Aufbau lesen.
        refreshPermission()
    }

    /**
     * M18.129: Permission-Status neu lesen.
     *
     * Wird bei jedem Screen-Resume aufgerufen — der Nutzer kann die
     * Berechtigung in den System-Einstellungen entziehen und zurückkommen.
     * Ohne diesen Refresh würde die UI einen veralteten Zustand zeigen
     * (Muster aus android-permission-flow-composition, Edge-Case 2).
     */
    fun refreshPermission() {
        _permissionState.value = CalendarPermissionState.fromContext(getApplication())
        if (_permissionState.value is CalendarPermissionState.Granted) {
            loadCalendars()
        }
    }

    /** Nach dem System-Dialog: Status neu bewerten. */
    fun onPermissionResult(granted: Boolean) {
        refreshPermission()
        if (granted) {
            // Sofort einen Sync anstoßen — sonst bliebe die Timeline bis
            // zum nächsten Takt leer und es sähe kaputt aus.
            syncNow()
        }
    }

    // ── Feature-Toggles ───────────────────────────────────────────────

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setCalendarSyncEnabled(enabled, System.currentTimeMillis())
            if (enabled) {
                syncScheduler.schedule()
                syncNow()
            } else {
                syncScheduler.cancel()
                CalendarAutoRunScheduler.cancel(getApplication())
                // Geplanten Cache leeren: ohne Sync-Schalter gibt es keine
                // Termine — sonst zeigte die Timeline veraltete Pläne.
                calendarRepository.clearEvents()
            }
        }
    }

    /**
     * Auto-Aufzeichnung umschalten.
     *
     * Beim Aktivieren wird der Takt SOFORT neu gestartet (M18.61e-Lektion:
     * eine Toggle-Aktivierung muss den System-Mechanismus anstoßen, sonst
     * passiert bis zum nächsten regulären Lauf nichts).
     * Beim Deaktivieren läuft eine eventuell laufende Kalender-Session
     * nicht ewig weiter — der Worker stoppt sie beim nächsten Lauf, und
     * wir stoppen den Takt sauber.
     */
    fun setAutoTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDao.setCalendarAutoTrackingEnabled(enabled, System.currentTimeMillis())
            if (enabled) {
                CalendarAutoRunScheduler.restartNow(getApplication())
            } else {
                CalendarAutoRunScheduler.cancel(getApplication())
            }
        }
    }

    fun setSyncInterval(hours: Int) {
        viewModelScope.launch {
            settingsDao.setCalendarSyncIntervalHours(hours, System.currentTimeMillis())
            // Neu planen, damit der geänderte Takt sofort wirkt.
            syncScheduler.schedule()
        }
    }

    // ── Sync ──────────────────────────────────────────────────────────

    /** Manueller Sync. */
    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        _syncMessage.value = null
        viewModelScope.launch {
            try {
                if (!calendarReader.hasPermission()) {
                    _permissionState.value = CalendarPermissionState.fromContext(getApplication())
                    _syncMessage.value = "sync_failed_permission"
                    return@launch
                }
                // DIREKT lesen statt über den Worker: der Nutzer hat
                // getippt und erwartet ein sofortiges Ergebnis (der
                // Worker würde nur bestätigen, dass er eingeplant ist).
                val result = withContext(Dispatchers.IO) { calendarReader.readEvents() }
                when (result) {
                    is CalendarReader.CalendarReadResult.Success -> {
                        val (from, _) = calendarReader.window()
                        calendarRepository.replaceWindow(result.events, pruneBefore = from)
                        calendarRepository.markSynced(System.currentTimeMillis())
                        _syncMessage.value = "sync_ok_${result.events.size}"
                        Log.i(TAG, "Manueller Sync: ${result.events.size} Termine")
                    }
                    is CalendarReader.CalendarReadResult.PermissionMissing -> {
                        _permissionState.value = CalendarPermissionState.fromContext(getApplication())
                        _syncMessage.value = "sync_failed_permission"
                    }
                    is CalendarReader.CalendarReadResult.Failed -> {
                        _syncMessage.value = "sync_failed"
                        Log.w(TAG, "Sync fehlgeschlagen: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                _syncMessage.value = "sync_failed"
                Log.e(TAG, "Manueller Sync abgebrochen", e)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun consumeSyncMessage() { _syncMessage.value = null }

    // ── Regeln CRUD ───────────────────────────────────────────────────

    fun saveRule(rule: CalendarRule) {
        viewModelScope.launch {
            calendarRepository.upsertRule(rule.copy(updatedAt = System.currentTimeMillis()))
            // Neue/geänderte Regel → Takt sofort anstoßen, damit die
            // Timeline-Vorschau und ein evtl. gerade fälliger Termin
            // sofort greifen.
            if (rule.enabled) CalendarAutoRunScheduler.restartNow(getApplication())
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            calendarRepository.setRuleEnabled(id, enabled)
            CalendarAutoRunScheduler.restartNow(getApplication())
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch { calendarRepository.deleteRule(id) }
    }

    /** Lädt die verfügbaren Kalender für den CALENDAR_IS-Regeltyp. */
    private fun loadCalendars() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                calendarReader.listCalendars().map { (id, name, account) ->
                    CalendarOption(id = id, name = name, account = account)
                }
            }
            _calendars.value = list
        }
    }

    fun openSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getApplication<Application>().packageName)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }
}

/** M18.129: Auswahl-Option für die Kalender-Auswahl-Regel. */
data class CalendarOption(val id: String, val name: String, val account: String)

/** M18.129: Gesamtzustand der Kalender-Regeln-Seite. */
data class CalendarRulesUiState(
    val rules: List<CalendarRule> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val syncEnabled: Boolean = false,
    val autoTrackingEnabled: Boolean = false,
    val lastSyncAt: Long = 0L,
    val syncIntervalHours: Int = CalendarSyncScheduler.DEFAULT_INTERVAL_HOURS,
    val cachedEventCount: Int = 0
)
