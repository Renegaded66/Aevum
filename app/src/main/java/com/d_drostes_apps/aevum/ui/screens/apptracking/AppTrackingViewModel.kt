package com.d_drostes_apps.aevum.ui.screens.apptracking

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.AppTrackingEntry
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val loading: Boolean = true
)

@HiltViewModel
class AppTrackingViewModel @Inject constructor(
    application: Application,
    private val trackingRepository: AppTrackingEntryRepository,
    private val activityTypeRepository: ActivityTypeRepository
) : AndroidViewModel(application) {

    private val searchQuery = MutableStateFlow("")
    private val refreshTick = MutableStateFlow(0L)

    // M18.66-FIX22-Muster: Icon-Cache mit explizitem get/put (ConcurrentHashMap
    // verbietet null-Werte — getOrPut mit null-Lambda crashte in Digital Balance).
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    val uiState: StateFlow<AppTrackingUiState> = combine(
        searchQuery,
        refreshTick,
        trackingRepository.getAll(),
        activityTypeRepository.getAll()
    ) { query, _, entries, types ->
        val permission = hasUsagePermission()
        if (!permission) {
            AppTrackingUiState(hasUsagePermission = false, loading = false)
        } else {
            buildState(query, entries, types)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTrackingUiState())

    private fun hasUsagePermission(): Boolean {
        return try {
            val appOps = getApplication<Application>().getSystemService(android.app.AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getApplication<Application>().packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun buildState(
        query: String,
        entries: List<AppTrackingEntry>,
        types: List<ActivityType>
    ): AppTrackingUiState {
        val entryMap = entries.associateBy { it.packageName }
        val pm = getApplication<Application>().packageManager

        // Alle installierten, startbaren Apps (ohne Aevum selbst)
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != getApplication<Application>().packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info ->
                    val label = try {
                        pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        info.packageName
                    }
                    TrackableApp(
                        packageName = info.packageName,
                        appLabel = label,
                        icon = iconCache[info.packageName] ?: run {
                            val bitmap = try {
                                val drawable = pm.getApplicationIcon(info.packageName)
                                drawableToImageBitmap(drawable)
                            } catch (_: Exception) { null }
                            if (bitmap != null) iconCache[info.packageName] = bitmap
                            bitmap
                        },
                        isTracked = entryMap.containsKey(info.packageName),
                        activityTypeId = entryMap[info.packageName]?.activityTypeId
                    )
                }
                .sortedBy { it.appLabel.lowercase(Locale.getDefault()) }
        } catch (_: Exception) {
            emptyList()
        }

        val q = query.trim().lowercase(Locale.getDefault())
        val filtered = if (q.isEmpty()) installed else installed.filter {
            it.appLabel.lowercase(Locale.getDefault()).contains(q) ||
                it.packageName.lowercase(Locale.getDefault()).contains(q)
        }

        return AppTrackingUiState(
            hasUsagePermission = true,
            searchQuery = query,
            allApps = filtered.filter { !it.isTracked },
            trackedApps = filtered.filter { it.isTracked },
            activityTypes = types,
            loading = false
        )
    }

    private fun drawableToImageBitmap(drawable: android.graphics.drawable.Drawable): ImageBitmap {
        return try {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
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

    /** App in die Aufzeichnung aufnehmen (rechte Spalte). */
    fun addApp(packageName: String) {
        viewModelScope.launch {
            val existing = trackingRepository.getByPackageOnce(packageName)
            if (existing != null) {
                trackingRepository.upsert(existing.copy(enabled = true, updatedAt = System.currentTimeMillis()))
            } else {
                // Ohne zugeordnete Activity: "other" als Default — der User
                // weist über das 3-Punkte-Menü die richtige Activity zu.
                trackingRepository.upsert(
                    AppTrackingEntry(
                        packageName = packageName,
                        activityTypeId = "other",
                        enabled = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            syncService()
            refreshTick.value = System.currentTimeMillis()
        }
    }

    /** App aus der Aufzeichnung entfernen (linke Spalte). */
    fun removeApp(packageName: String) {
        viewModelScope.launch {
            trackingRepository.delete(packageName)
            syncService()
            refreshTick.value = System.currentTimeMillis()
        }
    }

    /** Activity für eine getrackte App zuordnen (3-Punkte-Menü). */
    fun assignActivity(packageName: String, activityTypeId: String) {
        viewModelScope.launch {
            val existing = trackingRepository.getByPackageOnce(packageName)
            if (existing != null) {
                trackingRepository.upsert(
                    existing.copy(activityTypeId = activityTypeId, updatedAt = System.currentTimeMillis())
                )
            }
            refreshTick.value = System.currentTimeMillis()
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
}
