package com.d_drostes_apps.aevum.ui.screens.apptracking

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.apps.InstalledApp
import com.d_drostes_apps.aevum.data.apps.InstalledAppsProvider
import com.d_drostes_apps.aevum.data.model.AppTrackingEntry
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject

/** Eine App in der Auswahl-Liste (installiert, mit Icon + Label). */
data class TrackableApp(
    val packageName: String,
    val appLabel: String,
    val icon: ImageBitmap?,
    val isTracked: Boolean,
    val activityTypeId: String? = null
)

data class AppTrackingUiState(
    val hasUsagePermission: Boolean = false,
    val searchQuery: String = "",
    val allApps: List<TrackableApp> = emptyList(),
    val trackedApps: List<TrackableApp> = emptyList(),
    val activityTypes: List<ActivityType> = emptyList(),
    val loading: Boolean = true,
    // M18.147: true, solange der App-Scan läuft — die Listen füllen sich
    // chunkweise nach ("immer mehr ploppen auf"), ein dezenter Spinner
    // erklärt das Nachladen.
    val scanning: Boolean = false
)

/**
 * M18.147 — Umbau für flüssiges Verhalten (User-Meldung: "bei diesem Klick
 * hängt die App kurz für 3 Sekunden und danach wird alles angezeigt"):
 *
 *  1. Der teure Teil (PackageManager-Scan + Icon-Dekodierung für alle
 *     installierten Apps) läuft NICHT mehr im combine-Transform auf dem
 *     Main-Thread, sondern im [InstalledAppsProvider] auf Dispatchers.IO —
 *     chunkweise, sichtbar nachwachsend. Der Screen öffnet sofort.
 *  2. Die Basis-Liste kommt aus dem prozessweiten Cache (warmer Start =
 *     augenblicklich); der Neu-Scan aktualisiert still im Hintergrund.
 *  3. Interaktionen (App verschieben, Activity zuordnen) sind optimistisch:
 *     Die UI reagiert im selben Frame, die DB folgt ([AppTrackingOptimism]).
 */
@HiltViewModel
class AppTrackingViewModel @Inject constructor(
    application: Application,
    private val trackingRepository: AppTrackingEntryRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val installedAppsProvider: InstalledAppsProvider
) : AndroidViewModel(application) {

    private val searchQuery = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)

    // M18.147: Basis-Liste — startet mit dem letzten Scan (falls vorhanden)
    // und füllt sich dann chunkweise nach.
    private val installedApps = MutableStateFlow(installedAppsProvider.cachedApps() ?: emptyList())

    // M18.147: Lokale, sofort wirksame Absichten (App verschoben/Activity
    // zugeordnet). Werden entfernt, sobald die DB den Stand bestätigt.
    private val overrides = MutableStateFlow<Map<String, TrackedOverride>>(emptyMap())

    // Schreibvorgänge serialisieren (schnelles Hin-und-Her bleibt in Reihenfolge).
    private val mutationMutex = Mutex()
    private var scanJob: Job? = null

    @Volatile
    private var lastScanStartedAt = 0L

    val uiState: StateFlow<AppTrackingUiState> = combine(
        searchQuery,
        installedApps,
        overrides,
        trackingRepository.getAll(),
        activityTypeRepository.getAll(),
        scanning
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val query = values[0] as String
        @Suppress("UNCHECKED_CAST")
        val installed = values[1] as List<InstalledApp>
        @Suppress("UNCHECKED_CAST")
        val overridesNow = values[2] as Map<String, TrackedOverride>
        @Suppress("UNCHECKED_CAST")
        val entries = values[3] as List<AppTrackingEntry>
        @Suppress("UNCHECKED_CAST")
        val types = values[4] as List<ActivityType>
        val scanningNow = values[5] as Boolean
        buildState(query, installed, overridesNow, entries, types, scanningNow)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTrackingUiState())

    init {
        refreshInstalled(force = true)
        // Overrides abräumen, sobald die DB den Stand bestätigt hat — erst
        // dann darf die UI wieder aus der DB lesen, sonst würde sie sichtbar
        // zurückspringen.
        viewModelScope.launch {
            trackingRepository.getAll().collect { entries -> pruneOverrides(entries) }
        }
    }

    /**
     * Scannt den App-Bestand neu — inkrementell: Die Listen füllen sich
     * chunkweise nach, die UI bleibt dabei bedienbar. Aufruf beim VM-Start
     * (force) und beim Öffnen des Screens (gedrosselt, damit schnelles
     * Hin-und-Her nicht mehrfach scannt).
     */
    fun refreshInstalled(force: Boolean = false) {
        if (scanJob?.isActive == true) return
        if (!force && System.currentTimeMillis() - lastScanStartedAt < RESCAN_MIN_GAP_MS) return
        lastScanStartedAt = System.currentTimeMillis()
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                installedAppsProvider.scanProgressive(
                    onChunk = { chunk ->
                        installedApps.value = AppTrackingOptimism
                            .mergeChunk(installedApps.value, chunk) { it.packageName }
                            .sortedBy { it.label.lowercase(Locale.getDefault()) }
                    },
                    onComplete = { all -> installedApps.value = all }
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Scan fehlgeschlagen → der zuletzt geladene Stand bleibt stehen.
            } finally {
                scanning.value = false
            }
        }
    }

    private fun buildState(
        query: String,
        installed: List<InstalledApp>,
        overridesNow: Map<String, TrackedOverride>,
        entries: List<AppTrackingEntry>,
        types: List<ActivityType>,
        scanningNow: Boolean
    ): AppTrackingUiState {
        // Zustand kommt aus der reinen Liste + DB + lokalen Absichten — KEINE
        // PackageManager-Aufrufe mehr in diesem Pfad (vorher der 3-s-Freeze).
        val permission = UsageStatsPermission.isGranted(getApplication())
        if (!permission) {
            return AppTrackingUiState(hasUsagePermission = false, loading = false, scanning = scanningNow)
        }

        val entryMap = entries.associateBy { it.packageName }
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = installed.mapNotNull { app ->
            if (q.isNotEmpty() &&
                !app.label.lowercase(Locale.getDefault()).contains(q) &&
                !app.packageName.lowercase(Locale.getDefault()).contains(q)
            ) {
                return@mapNotNull null
            }
            val entry = entryMap[app.packageName]
            val override = overridesNow[app.packageName]
            val tracked = AppTrackingOptimism.effectiveTracked(entry != null, override)
            TrackableApp(
                packageName = app.packageName,
                appLabel = app.label,
                icon = app.icon,
                isTracked = tracked,
                activityTypeId = if (tracked) {
                    AppTrackingOptimism.effectiveActivityTypeId(entry?.activityTypeId, override)
                } else {
                    null
                }
            )
        }.sortedBy { it.appLabel.lowercase(Locale.getDefault()) }

        return AppTrackingUiState(
            hasUsagePermission = true,
            searchQuery = query,
            allApps = rows.filter { !it.isTracked },
            trackedApps = rows.filter { it.isTracked },
            activityTypes = types,
            loading = false,
            scanning = scanningNow
        )
    }

    /** Entfernt Overrides, deren Absicht in der DB angekommen ist. */
    private fun pruneOverrides(entries: List<AppTrackingEntry>) {
        if (overrides.value.isEmpty()) return
        val entryMap = entries.associateBy { it.packageName }
        overrides.update { current ->
            current.filter { (packageName, override) ->
                val entry = entryMap[packageName]
                !AppTrackingOptimism.settled(entry != null, entry?.activityTypeId, override)
            }
        }
    }

    fun setSearchQuery(q: String) {
        searchQuery.value = q
    }

    /** Öffnet die System-Einstellungen für den Nutzungszugriff. */
    fun openUsageAccessSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (_: Exception) {
            // Fallback: App-Info-Seite
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:${getApplication<Application>().packageName}")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }
    }

    /** App in die Aufzeichnung aufnehmen (rechte Spalte) — sofort sichtbar. */
    fun addApp(packageName: String) {
        overrides.update { it + (packageName to TrackedOverride(tracked = true)) }
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    val existing = trackingRepository.getByPackageOnce(packageName)
                    if (existing != null) {
                        trackingRepository.upsert(
                            existing.copy(enabled = true, updatedAt = System.currentTimeMillis())
                        )
                    } else {
                        // Ohne zugeordnete Activity: "other" als Default — der
                        // User weist über das 3-Punkte-Menü die richtige zu.
                        trackingRepository.upsert(
                            AppTrackingEntry(
                                packageName = packageName,
                                activityTypeId = "other",
                                enabled = true,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // DB-Schreibvorgang fehlgeschlagen → zurück zum echten Stand.
                overrides.update { it - packageName }
            }
            syncService()
        }
    }

    /** App aus der Aufzeichnung entfernen (linke Spalte) — sofort sichtbar. */
    fun removeApp(packageName: String) {
        overrides.update { it + (packageName to TrackedOverride(tracked = false)) }
        viewModelScope.launch {
            try {
                mutationMutex.withLock { trackingRepository.delete(packageName) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                overrides.update { it - packageName }
            }
            syncService()
        }
    }

    /**
     * Activity für eine getrackte App zuordnen (3-Punkte-Menü) — sofort
     * sichtbar.
     *
     * M19.2 (User-Report: die zugewiesene Activity startete beim Öffnen
     * der App nicht — für Apps, die noch NICHT in der rechten Spalte
     * stehen, blieb die Zuweisung ein reiner UI-Override und erreichte
     * die DB nicht): Existiert kein DB-Eintrag, legt die Zuweisung ihn
     * jetzt AN (enabled=true) — nur so kann der AppTrackingService beim
     * Öffnen der App die Activity überhaupt starten (sein Gate liest
     * getEnabledOnce()). Danach syncService(): Falls die App damit
     * neu getrackt ist, muss der Service (re)starten; das Optimismus-
     * Verhalten (Override zuerst, UI sofort) bleibt unverändert.
     */
    fun assignActivity(packageName: String, activityTypeId: String) {
        overrides.update {
            it + (packageName to TrackedOverride(tracked = true, activityTypeId = activityTypeId))
        }
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                    val existing = trackingRepository.getByPackageOnce(packageName)
                    if (existing != null) {
                        trackingRepository.upsert(
                            existing.copy(
                                activityTypeId = activityTypeId,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    } else {
                        // M19.2: App war noch nicht getrackt — die reine
                        // Zuweisung erzeugt den Eintrag mit enabled=true
                        // (sonst startet die Activity nie; siehe KDoc).
                        trackingRepository.upsert(
                            AppTrackingEntry(
                                packageName = packageName,
                                activityTypeId = activityTypeId,
                                enabled = true,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                overrides.update { it - packageName }
            }
            // M19.2: Service-Sync wie in addApp/removeApp — ein neu
            // getrackter Eintrag muss den Service (re)starten (sein Gate
            // entscheidet selbst über enabled).
            syncService()
        }
    }

    /** Service synchron halten: läuft nur, wenn mindestens eine App getrackt ist. */
    private fun syncService() {
        viewModelScope.launch {
            val enabled = try {
                trackingRepository.getEnabledOnce().isNotEmpty()
            } catch (_: Exception) {
                false
            }
            val app = getApplication<Application>()
            if (enabled) {
                com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.start(app)
            } else {
                com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.stop(app)
            }
        }
    }

    private companion object {
        /** Schnelles Hin-und-Her (Zurück-Navigation) soll nicht mehrfach scannen. */
        const val RESCAN_MIN_GAP_MS = 60_000L
    }
}
