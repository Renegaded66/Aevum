package com.d_drostes_apps.aevum.ui.screens.addiction

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.apps.InstalledApp
import com.d_drostes_apps.aevum.data.apps.InstalledAppsProvider
import com.d_drostes_apps.aevum.data.model.AddictionSession
import com.d_drostes_apps.aevum.data.model.AddictionWatchApp
import com.d_drostes_apps.aevum.data.repository.AddictionSessionRepository
import com.d_drostes_apps.aevum.data.repository.AddictionWatchRepository
import com.d_drostes_apps.aevum.domain.addiction.AddictionAnalytics
import com.d_drostes_apps.aevum.domain.addiction.AddictionSessionCollector
import com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * M27: ViewModel des Sucht-Detektors im Balance-Tab.
 *
 * Datenfluss:
 *  Room (beobachtete Apps + Sitzungen) ─┐
 *  Zeitraum-Auswahl ────────────────────┼─> [AddictionAnalytics.analyze]
 *  Minutentick ─────────────────────────┘
 *
 * Der Collector schreibt beim Öffnen und danach im Minutentakt neue
 * Sitzungen; Room-Flows stoßen die Auswertung automatisch an. Die Auswertung
 * läuft auf [Dispatchers.Default] — 400 Tage Sitzungen dürfen den
 * Haupt-Thread nicht blockieren.
 */
@HiltViewModel
class AddictionDetectorViewModel @Inject constructor(
    application: Application,
    private val watchRepository: AddictionWatchRepository,
    private val sessionRepository: AddictionSessionRepository,
    private val collector: AddictionSessionCollector,
    private val installedAppsProvider: InstalledAppsProvider
) : AndroidViewModel(application) {

    private val zoneId: ZoneId = ZoneId.systemDefault()

    private val range = MutableStateFlow(AddictionAnalytics.Range.DAYS_7)
    private val tick = MutableStateFlow(System.currentTimeMillis())
    private val installedApps = MutableStateFlow(installedAppsProvider.cachedApps() ?: emptyList())
    private val scanning = MutableStateFlow(false)

    private val sessionsFlow = sessionRepository.getSince(
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(
            AddictionAnalytics.RETENTION_DAYS.toLong()
        )
    )

    val uiState: StateFlow<AddictionDetectorUiState> = combine(
        watchRepository.getAll(),
        sessionsFlow,
        range,
        tick,
        installedApps,
        scanning
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val watched = values[0] as List<AddictionWatchApp>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[1] as List<AddictionSession>
        @Suppress("UNCHECKED_CAST")
        val selectedRange = values[2] as AddictionAnalytics.Range
        @Suppress("UNCHECKED_CAST")
        val apps = values[4] as List<InstalledApp>
        val scanningNow = values[5] as Boolean

        val permission = UsageStatsPermission.isGranted(getApplication())
        val today = LocalDate.now(zoneId)
        val analysis = if (permission) {
            AddictionAnalytics.analyze(
                watchedApps = watched.map {
                    AddictionAnalytics.WatchedApp(it.packageName, it.appLabel)
                },
                sessions = sessions.map {
                    AddictionAnalytics.Session(it.packageName, it.startAt, it.endAt)
                },
                anchorDate = today,
                zoneId = zoneId,
                range = selectedRange
            )
        } else {
            null
        }

        AddictionDetectorUiState(
            hasPermission = permission,
            watchedApps = watched,
            range = selectedRange,
            analysis = analysis,
            availableApps = apps,
            scanning = scanningNow,
            loading = false
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AddictionDetectorUiState(loading = true)
        )

    init {
        // Einmal beim Öffnen des Balance-Tabs: schneller Stand für die
        // Einstiegskarte. Den laufenden 60-s-Takt stößt NUR die sichtbare
        // Detektor-Seite an (siehe AddictionDetectorPage) — sonst würde
        // jede Minute eine DB-Schreiboperation für eine offene Sitzung
        // anfallen, auch wenn der Detektor gar nicht angeschaut wird.
        viewModelScope.launch {
            collector.sync()
            tick.value = System.currentTimeMillis()
        }
        if (installedApps.value.isEmpty()) {
            loadInstalledApps()
        }
    }

    fun setRange(value: AddictionAnalytics.Range) {
        range.value = value
    }

    fun refresh() {
        viewModelScope.launch {
            collector.sync()
            tick.value = System.currentTimeMillis()
        }
    }

    fun loadInstalledApps(force: Boolean = false) {
        if (scanning.value) return
        if (!force && installedApps.value.isNotEmpty()) return
        scanning.value = true
        viewModelScope.launch {
            try {
                installedAppsProvider.scanProgressive(
                    onChunk = { chunk ->
                        installedApps.value = (installedApps.value + chunk)
                            .distinctBy { it.packageName }
                            .sortedBy { it.label.lowercase() }
                    },
                    onComplete = { all ->
                        installedApps.value = all
                        scanning.value = false
                    }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                scanning.value = false
            }
        }
    }

    fun addApps(apps: List<InstalledApp>) {
        if (apps.isEmpty()) return
        viewModelScope.launch {
            watchRepository.addAll(apps.map { it.packageName to it.label })
            tick.value = System.currentTimeMillis()
            // Rückblick: Android liefert Events je nach Gerät oft nur wenige
            // Tage. Was vorhanden ist, wird mitgenommen; der Rest wächst
            // ab jetzt mit.
            collector.sync(backfillDays = AddictionAnalytics.BACKFILL_DAYS)
            tick.value = System.currentTimeMillis()
        }
    }

    /**
     * Entfernt eine App aus der Beobachtung. Die bereits erhobenen Sitzungen
     * bleiben absichtlich in der DB: Wird die App später erneut hinzugefügt,
     * ist ihr Verlauf sofort wieder da. Die Auswertung zeigt ohnehin nur
     * aktuell beobachtete Apps.
     */
    fun removeApp(packageName: String) {
        viewModelScope.launch {
            watchRepository.remove(packageName)
            tick.value = System.currentTimeMillis()
        }
    }
}

data class AddictionDetectorUiState(
    val hasPermission: Boolean = false,
    val watchedApps: List<AddictionWatchApp> = emptyList(),
    val range: AddictionAnalytics.Range = AddictionAnalytics.Range.DAYS_7,
    val analysis: AddictionAnalytics.Result? = null,
    val availableApps: List<InstalledApp> = emptyList(),
    val scanning: Boolean = false,
    val loading: Boolean = true
)
