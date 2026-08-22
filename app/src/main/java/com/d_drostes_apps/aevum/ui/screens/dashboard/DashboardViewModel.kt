package com.d_drostes_apps.aevum.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.AppUsageSample
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import com.d_drostes_apps.aevum.data.repository.CategoryRepository
import com.d_drostes_apps.aevum.domain.digital.UsageStatsCollector
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.domain.seed.EnsureDefaultDataUseCase
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val application: Application,
    private val activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    candidateRepository: ActivityCandidateRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase,
    val liveActivityManager: LiveActivityManager,
    private val usageStatsCollector: UsageStatsCollector,
    // M18.33: Tagespauschalen — erscheinen sofort im Dashboard (on-the-fly),
    // ohne auf den Midnight-Worker zu warten.
    private val dailyAllowanceRepository: com.d_drostes_apps.aevum.data.repository.DailyAllowanceRepository,
    // M18.37: Todos — kompakte Uebersicht auf dem Dashboard (Herzstueck).
    private val todoRepository: com.d_drostes_apps.aevum.data.repository.TodoRepository,
    // M18.45-FIX (User: "1h 8m Bildschirmzeit passt nicht, ich bin erst
    // vor 15 Minuten aufgestanden"): UsageStatsManager kumuliert
    // totalTimeInForeground seit Tagesbeginn (inkl. Nutzung VOR dem
    // Aufwachen). Der Wake-Detector liefert die erste echte Nutzung —
    // damit capen wir die Anzeige auf die Wachzeit.
    private val usageWakeDetector: com.d_drostes_apps.aevum.automation.sleep.UsageWakeDetector,
    // M18.58: Garmin Connect — Kachel-Daten (Schritte/Distanz/Kalorien)
    // + importierte Aktivitäten.
    private val garminRepository: com.d_drostes_apps.aevum.data.repository.GarminRepository,
    // M18.66-FIX3: CurrentZoneProvider — liefert die aktuelle Geofence-Zone
    // für den Zone-Banner im Dashboard.
    private val currentZoneProvider: com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    // M18.43-FIX (Root Cause "abgehakte wiederkehrende Todo zeigt im
    // Dashboard noch offen"): `today`/`start`/`end` wurden EINMAL beim
    // ViewModel-Init berechnet. Wenn das Dashboard über Mitternacht
    // offen blieb (oder das ViewModel vor Mitternacht erstellt wurde),
    // zeigte es den VORTAG — die Completion von HEUTE (neues Datum)
    // wurde nie geladen -> "1 offen" obwohl abgehakt. Jetzt: frisches
    // Datum bei jedem State-Build (der Minuten-Tick erzwingt den Rebuild).
    //
    // M18.60: Tages-Navigation — das Dashboard zeigt per Offset auch
    // vergangene Tage (User: "wie bei der Timeline andere Tage sehen").
    // 0 = heute, -1 = gestern, ... Der Offset wird beim State-Build
    // berücksichtigt, sodass alle Statistiken (Sessions, Pauschalen,
    // Todos, Schlaf) den gewählten Tag zeigen. Bewusst dezent in der UI.
    private val _selectedDayOffset = MutableStateFlow(0)
    val selectedDayOffset: StateFlow<Int> = _selectedDayOffset

    // M18.60: Overrides des gewählten Tages — wird bei Tag-Wechsel und
    // nach set/clear neu geladen. Der combine nimmt diesen Flow auf,
    // damit Änderungen sofort im UI-State landen.
    private val _dayOverrides = MutableStateFlow<List<com.d_drostes_apps.aevum.data.model.AllowanceDayOverride>>(emptyList())

    private val today: LocalDate get() = LocalDate.now().plusDays(_selectedDayOffset.value.toLong())
    private val start: Long get() = TimeFormatting.startOfDayMillis(today, zoneId)
    private val end: Long get() = TimeFormatting.endOfDayMillis(today, zoneId)

    /** M18.60: Einen Tag vor/zurück springen (dezent im Dashboard). */
    fun navigateDay(delta: Int) {
        val newOffset = (_selectedDayOffset.value + delta).coerceIn(-365, 0)
        if (newOffset == _selectedDayOffset.value) return
        _selectedDayOffset.value = newOffset
        reloadDayOverrides()
    }

    /** M18.60: Zurück zu heute springen. */
    fun resetToToday() {
        if (_selectedDayOffset.value == 0) return
        _selectedDayOffset.value = 0
        reloadDayOverrides()
    }

    private fun reloadDayOverrides() {
        viewModelScope.launch {
            _dayOverrides.value = dailyAllowanceRepository.getOverridesForDate(today.toString())
        }
    }

    /**
     * M18.60: Pauschalzeit für den GEWÄHLTEN Tag einmalig anpassen —
     * die Pauschale selbst bleibt unverändert. Der Override überschreibt
     * nur die Minuten dieses Tages (User: "an einem Tag mal mehr/weniger
     * Zeit gebraucht"). Zusätzlich wird die Accumulation des Tages sofort
     * angepasst, damit Insights/Statistik den neuen Wert zeigen (der
     * Midnight-Worker läuft sonst erst wieder um 00:05).
     */
    fun setAllowanceOverride(allowanceId: String, minutes: Int) {
        viewModelScope.launch {
            val date = today.toString()
            dailyAllowanceRepository.insertOverride(
                com.d_drostes_apps.aevum.data.model.AllowanceDayOverride(
                    date = date,
                    allowanceId = allowanceId,
                    minutes = minutes.coerceIn(0, 1440)
                )
            )
            syncAccumulationForDay(date, allowanceId)
            reloadDayOverrides()
        }
    }

    /** M18.60: Override für den gewählten Tag entfernen → Pauschalen-Wert gilt wieder. */
    fun clearAllowanceOverride(allowanceId: String) {
        viewModelScope.launch {
            val date = today.toString()
            dailyAllowanceRepository.deleteOverride(date, allowanceId)
            syncAccumulationForDay(date, allowanceId)
            reloadDayOverrides()
        }
    }

    /**
     * AEVUM-3: Güte (Positivity-Score) für den GEWÄHLTEN Tag manuell
     * anpassen. Der Override wird auf ALLE Sessions des Tages geschrieben
     * (Spalte manual_quality_override) — die ActivityType-Einstellung bleibt
     * unverändert. Am nächsten Tag existieren neue Sessions ohne Override →
     * die automatische Berechnung gilt wieder. score = null entfernt die
     * Overrides des Tages.
     */
    fun setDayQualityOverride(score: Int?) {
        viewModelScope.launch {
            try {
                activityRepository.setManualQualityOverrideForRange(start, end, score?.coerceIn(0, 100))
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "setDayQualityOverride failed", e)
            }
        }
    }

    /**
     * M18.60: Accumulation des Tages auf den effektiven Wert (Override
     * oder Standard) bringen — damit Statistik/Insights sofort stimmen,
     * nicht erst nach dem nächsten Midnight-Lauf.
     */
    private suspend fun syncAccumulationForDay(date: String, allowanceId: String) {
        try {
            val allowance = dailyAllowanceRepository.getById(allowanceId) ?: return
            val override = dailyAllowanceRepository.getOverride(date, allowanceId)
            val effective = override?.minutes ?: allowance.minutesPerDay
            dailyAllowanceRepository.insertAccumulation(
                com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay(
                    date = date,
                    timezoneId = zoneId.id,
                    allowanceId = allowance.id,
                    activityTypeId = allowance.activityTypeId,
                    minutes = effective
                )
            )
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "syncAccumulationForDay failed", e)
        }
    }

    // M16: Diese Properties MÜSSEN vor dem init-Block deklariert werden.
    // Kotlin führt init-Blöcke und Property-Initializer in Deklarations-
    // reihenfolge aus. Der init-Block referenziert _topApps und _screenTimeMs
    // via viewModelScope.launch (Dispatchers.Main.immediate → synchron).
    // Wenn die Properties dahinter stehen, sind sie noch null → NPE → Crash.
    private val _topApps = MutableStateFlow<List<AppUsageSample>>(emptyList())
    val topApps: StateFlow<List<AppUsageSample>> = _topApps.asStateFlow()

    // M16: Summe der Bildschirmzeit aus topApps. Wird von _topApps.collect
    // aktualisiert. Wenn keine Permission oder keine Daten → 0L → UI zeigt "—".
    private val _screenTimeMs = MutableStateFlow(0L)

    init {
        // M12.0.2: Defensive Initialisierung — ensureDefaultData darf niemals
        // den Start des DashboardViewModels blockieren. Fehler werden geloggt,
        // die App läuft mit Default-Werten weiter.
        viewModelScope.launch {
            try {
                ensureDefaultData()
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "ensureDefaultData failed — continuing with defaults", e)
            }
        }
        // M13: Initial load of usage stats. Refetched on demand.
        // M16: refreshUsageStats lädt topApps. Der Bildschirm-Wert im Dashboard
        // wird aus topApps berechnet. Vorher blieb digitalScreenTimeFormatted
        // immer "0m", weil buildState() nie einen echten Wert setzte.
        viewModelScope.launch {
            try {
                refreshUsageStats()
            } catch (_: Exception) { /* noop */ }
        }
        // M16: topApps als Flow beobachten und das uiState aktualisieren,
        // sobald echte Usage-Stats-Werte ankommen. Ohne das konnte das
        // Dashboard die Bildschirmzeit erst nach komplettem Neustart der
        // App anzeigen, weil der combine-Flow topApps nie kannte.
        viewModelScope.launch {
            _topApps.collect { apps ->
                // M18.45-FIX: Cap auf die Wachzeit. totalTimeInForeground
                // kumuliert seit Mitternacht — der User sieht sonst Nutzung
                // von VOR dem Aufwachen. Erste echte Nutzung heute = Wake.
                // M18.59-FIX (User: "2h 39 Bildschirmzeit stimmt nicht"):
                // totalTimeInForeground kumuliert auf vielen Geräten über
                // MEHRERE Tage (OEM-Bug) und zählt Screen-off-Zeit (Musik
                // im Hintergrund). Primärquelle ist jetzt die Event-API
                // (SCREEN_INTERACTIVE-Intervalle seit Mitternacht = echte
                // Bildschirmzeit). Nur wenn die Events fehlen (OEM ohne
                // Screen-Events), fällt die Berechnung auf die gecappte
                // topApps-Summe zurück.
                val precise = try {
                    usageStatsCollector.screenTimeTodayMs()
                } catch (_: Exception) { null }
                if (precise != null) {
                    _screenTimeMs.value = precise
                } else {
                    val wakeMs = try {
                        usageWakeDetector.firstUsageSince(start)
                    } catch (_: Exception) { null }
                    val now = System.currentTimeMillis()
                    val capMs = if (wakeMs != null) (now - wakeMs).coerceAtLeast(0L) else Long.MAX_VALUE
                    _screenTimeMs.value = apps.sumOf { it.durationMs.coerceAtMost(capMs) }
                }
            }
        }
    }

    // M15: Permission-State wird jetzt beim Erzeugen des ViewModels initial
    // geladen UND bei jedem Foreground-Wechsel neu geprüft. Vorher blieb der
    // State auf dem Wert vom App-Start stehen — der User hat in den Settings
    // die Usage-Stats-Permission erteilt, kam zurück, und das Dashboard zeigte
    // weiterhin "App-Nutzung erlauben".
    //
    // Lösung: eigener Coroutine-Loop, der auf RESUME der Activity horcht
    // (Lifecycle-Process ist im Classpath NICHT garantiert, daher
    // ProcessLifecycleOwner.lightweight Fallback via Flow-Tick).
    private val _usageStatsGranted = MutableStateFlow(false)
    val usageStatsGranted: StateFlow<Boolean> = _usageStatsGranted.asStateFlow()

    init {
        // M15: Der Permission-State wurde vorher nur einmal beim App-Start
        // geladen. Der User hat in den Android-Settings Usage-Stats erteilt,
        // kam zurück, und das Dashboard zeigte weiterhin "App-Nutzung erlauben".
        //
        // Lösung: Application.ActivityLifecycleCallbacks. Bei jedem Activity-
        // onResume (Foreground-Wechsel) wird refreshUsageStats() aufgerufen.
        // Das ist die einfachste API, die ohne androidx.lifecycle:lifecycle-process
        // Dependency auskommt und in jedem Android-Version funktioniert.
        try {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var activeActivities = 0
                override fun onActivityStarted(activity: Activity) {
                    if (activeActivities == 0) refreshUsageStats()
                    activeActivities++
                }
                override fun onActivityStopped(activity: Activity) {
                    activeActivities = (activeActivities - 1).coerceAtLeast(0)
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (e: Exception) {
            // Fallback: einmaliger Refresh + periodischer Tick alle 5s.
            viewModelScope.launch {
                while (true) {
                    refreshUsageStats()
                    kotlinx.coroutines.delay(5_000)
                }
            }
        }
    }

    fun refreshUsageStats() {
        viewModelScope.launch {
            try {
                val granted = usageStatsCollector.hasPermission()
                _usageStatsGranted.value = granted
                if (granted) {
                    val top = usageStatsCollector.topAppsForDay(LocalDate.now(), limit = 5)
                    _topApps.value = top
                }
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "refreshUsageStats failed", e)
            }
        }
    }

    fun openUsageAccessSettings() {
        usageStatsCollector.openUsageAccessSettings()
    }

    // M9.1: Live Activity actions — Schnellstart per activityTypeId
    fun startLiveActivity(activityTypeId: String, note: String? = null, startedAt: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            liveActivityManager.start(activityTypeId, note = note, startedAt = startedAt)
            LiveActivityService.start(application)
        }
    }

    /**
     * M18.12: Neue Aktivität anlegen UND direkt starten.
     * Erzeugt einen echten ActivityType (isSystem=false, Default-Icon '•',
     * Primärfarbe 0) und startet sofort eine Session — der User will
     * tracken, nicht erst konfigurieren. Icon/Farbe kann er danach im
     * ActivityTypes-Screen (Settings) anpassen.
     */
    fun createAndStartActivity(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val type = com.d_drostes_apps.aevum.data.model.ActivityType(
                id = "custom_${System.currentTimeMillis()}_${trimmed.hashCode().toLong().let { kotlin.math.abs(it) }}",
                name = trimmed,
                defaultCategoryId = null,
                isSystem = false,
                propertiesJson = null,
                isFavorite = false,
                positivityScore = 50,
                icon = "•",
                color = 0L
            )
            activityTypeRepository.insert(type)
            liveActivityManager.start(type.id, note = null, startedAt = System.currentTimeMillis())
            LiveActivityService.start(application)
        }
    }

    // M9.2: Toggle favorite for an activity type
    fun toggleFavorite(type: com.d_drostes_apps.aevum.data.model.ActivityType) {
        viewModelScope.launch {
            activityTypeRepository.setFavorite(type.id, !type.isFavorite)
        }
    }

    fun pauseLiveActivity() {
        viewModelScope.launch { liveActivityManager.pause() }
    }

    fun resumeLiveActivity() {
        viewModelScope.launch { liveActivityManager.resume() }
    }

    fun stopLiveActivity() {
        viewModelScope.launch {
            liveActivityManager.stop()
            LiveActivityService.stop(application)
        }
    }

    /** M12.1: Discard an auto-started live session — stop + soft-delete. */
    fun discardLiveActivity() {
        viewModelScope.launch {
            liveActivityManager.discardLiveSession()
            LiveActivityService.stop(application)
        }
    }

    // M18.23: Aktivitaet wechseln — beendet die aktuelle Session und startet
    // sofort die neue. Kein Pause-Zustand, keine Zwischen-Schritt.
    fun switchActivity(newActivityTypeId: String, categoryId: String?) {
        viewModelScope.launch {
            liveActivityManager.stop()
            LiveActivityService.stop(application)
            // Kurz warten damit die alte Session sauber geschlossen ist
            kotlinx.coroutines.delay(100)
            liveActivityManager.start(newActivityTypeId, sourceType = "MANUAL")
            LiveActivityService.start(application)
        }
    }

    // M16.3: Sleep-Sessions werden mit einem 36h-Overlap-Fenster gelesen,
    // damit Schlaf über Mitternacht (z.B. Start 23:30 Vortag, Ende 08:20 heute)
    // korrekt erfasst wird. Eine reine "heute"-Query wie
    // getByActivityTypeAndDateRange("sleep", start, end) verlangt start_at
    // im heutigen Tag — Schlaf, der nachts beginnt, würde durch das Raster
    // fallen. Wir lesen daher [start - 24h, end + 12h] und filtern unten
    // auf Sessions, die den heutigen Tag überlappen.
    val sleepWindowStart = start - 24L * 60 * 60 * 1000
    val sleepWindowEnd = end + 12L * 60 * 60 * 1000
    // M18.42: Minuetlicher Tick — das Dashboard soll sich alle 60s
    // aktualisieren, damit die laufende Session-Dauer und alle
    // Statistiken (Erfasst, Pauschalen-Sichtbarkeit ab 00:30, Balken)
    // auf dem neuesten Stand bleiben. Room-Flows emittieren nur bei
    // DB-Aenderungen — eine laufende Session aendert die DB aber nicht
    // jede Sekunde. Der Tick erzeugt eine Emittierung pro Minute.
    private val minuteTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                minuteTick.value = System.currentTimeMillis()
            }
        }
    }
    /**
     * M18.60-FIX 3 (User: "Tage wechseln funktioniert nicht — da passiert
     * nichts"): ROOT CAUSE — der combine abonnierte die datumsabhaengigen
     * Room-Queries (getOverlappingRange(start, end)) EINMAL beim Aufbau.
     * Room-Queries mit festen Parametern emittieren nur bei DB-Aenderungen —
     * ein Offset-Wechsel aenderte zwar das Label, aber die Sessions kamen
     * weiterhin vom urspruenglich abonnierten Tag. Fix: flatMapLatest auf
     * den Tag-Key — bei jedem Tag-Wechsel werden ALLE datumsabhaengigen
     * Flows frisch abonniert (der alte wird automatisch abbestellt).
     *
     * Der Tag-Key-Flow (tagKey) emittiert bei Offset-Wechsel eine neue
     * Instanz → flatMapLatest baut den kompletten combine neu auf.
     * Datumsunabhaengige Flows (Kategorien, Typen, Todos) werden dabei
     * zwar auch neu abonniert — das ist harmlos (Room cached).
     */
    private val tagKey: kotlinx.coroutines.flow.Flow<String> =
        _selectedDayOffset.map { offset -> LocalDate.now().plusDays(offset.toLong()).toString() }

    val uiState: StateFlow<DashboardUiState> = tagKey
        .flatMapLatest { dayStr ->
            val day = LocalDate.parse(dayStr)
            val dayStart = TimeFormatting.startOfDayMillis(day, zoneId)
            val dayEnd = TimeFormatting.endOfDayMillis(day, zoneId)
            val sleepStart = dayStart - 24L * 60 * 60 * 1000
            val sleepEnd = dayEnd + 12L * 60 * 60 * 1000
            combine(
                activityRepository.getOverlappingRange(dayStart, dayEnd),
                categoryRepository.getAll(),
                candidateRepository.getByStatus("PENDING"),
                activityTypeRepository.getAll(),
                activityRepository.getOverlappingRange(sleepStart, sleepEnd),
                _screenTimeMs,
                dailyAllowanceRepository.getAll(),
                todoRepository.getAll(),
                todoRepository.getAllCompletions(),
                minuteTick,
                dailyAllowanceRepository.getOverridesForDateFlow(dayStr)
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val sessions = values[0] as List<ActivitySession>
                val categories = values[1] as List<com.d_drostes_apps.aevum.data.model.Category>
                val candidates = values[2] as List<ActivityCandidate>
                val types = values[3] as List<com.d_drostes_apps.aevum.data.model.ActivityType>
                val allSleepSessions = values[4] as List<ActivitySession>
                val screenMs = values[5] as Long
                val allowances = values[6] as List<com.d_drostes_apps.aevum.data.model.DailyAllowance>
                val allTodos = values[7] as List<com.d_drostes_apps.aevum.data.model.Todo>
                val allCompletions = values[8] as List<com.d_drostes_apps.aevum.data.model.TodoCompletion>
                // values[9] = minuteTick — nur Trigger, kein Inhalt noetig.
                val dayOverrides = values[10] as List<com.d_drostes_apps.aevum.data.model.AllowanceDayOverride>
                val todayCompletions = allCompletions.filter { it.date == dayStr }
                val nowApprox = System.currentTimeMillis()
                val sleepSessionsToday = allSleepSessions.filter { session ->
                    val s = session.startAt
                    val e = session.endAt ?: nowApprox
                    val isSleep = session.activityTypeId == "sleep" || session.categoryId == "sleep"
                    isSleep && s < dayEnd && e > dayStart
                }
                buildState(
                    sessions = sessions,
                    categories = categories,
                    candidates = candidates.filter { it.startAt < dayEnd && it.endAt > dayStart },
                    typeMap = types.associateBy { it.id },
                    allTypes = types,
                    sleepSessions = sleepSessionsToday,
                    screenTimeMs = screenMs,
                    allowances = allowances,
                    todos = allTodos,
                    todayCompletions = todayCompletions,
                    dayOverrides = dayOverrides,
                    displayedDate = day
                )
            }
        }
        .catch { e ->
            Log.e("DashboardViewModel", "uiState combine() failed — emitting default state", e)
            emit(DashboardUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    // M18.58: Garmin-Kacheln — Schritte, Distanz, Kalorien für den heutigen Tag.
    // Die Kacheln erscheinen NUR, wenn Daten synchronisiert wurden (User:
    // "schöne moderne kleine Kacheln haben, aber nur wenn auch was
    // synchronisiert ist").
    private val _garminSummary = MutableStateFlow<com.d_drostes_apps.aevum.data.model.GarminDailySummary?>(null)
    val garminSummary: StateFlow<com.d_drostes_apps.aevum.data.model.GarminDailySummary?> = _garminSummary.asStateFlow()

    private val _garminActivities = MutableStateFlow<List<com.d_drostes_apps.aevum.data.model.GarminActivity>>(emptyList())
    val garminActivities: StateFlow<List<com.d_drostes_apps.aevum.data.model.GarminActivity>> = _garminActivities.asStateFlow()

    // M18.66-FIX3: Aktuelle Geofence-Zone für den Zone-Banner.
    // Beim init sofort prüfen, danach über ProactiveGeofenceCheckWorker (2-Min).
    val currentZone: StateFlow<com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.ZoneInfo?> =
        currentZoneProvider.currentZone

    // M18.66-FIX7: Debug-Info für den Zone-Banner (temporär).
    val zoneDebugInfo: StateFlow<String> = currentZoneProvider.debugInfo

    init {
        // M18.66-FIX3: Sofort beim ViewModel-Init den Standort checken,
        // damit der Banner sofort beim App-Öffnen die richtige Zone zeigt.
        viewModelScope.launch {
            try {
                currentZoneProvider.checkNow()
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Zone-Check beim Init fehlgeschlagen: ${e.message}")
            }
        }
    }

    // M18.58: Güte-Verlauf — Quality-Score pro Tag für die letzten 365 Tage.
    // Wird on-the-fly aus allen Sessions berechnet (keine neue Aggregations-
    // Infrastruktur). Die UI wählt das Fenster (7/30/365 Tage) und zeichnet
    // den Verlauf als animierten Area-Chart.
    private val _qualityTrend = MutableStateFlow<List<DailyQualityPoint>>(emptyList())
    val qualityTrend: StateFlow<List<DailyQualityPoint>> = _qualityTrend.asStateFlow()

    init {
        // M18.58: Alle Sessions beobachten (deletedAt==null via DAO-Filter)
        // und pro Tag den gewichteten Positivitäts-Score berechnen.
        // AEVUM-2-FIX: Pauschalen + Overrides fließen jetzt auch in den
        // Verlauf ein (gleiche Datenquelle wie die Headline) — vorher
        // zählte der Trend NUR Sessions, die Headline Sessions + Pauschalen
        // (daher z.B. 27 im Trend vs 75 in der Headline für heute).
        // minuteTick hält den heutigen Punkt frisch (Pauschalen-Regel
        // "gilt ab Uhrzeit" + Mitternachts-Wechsel).
        viewModelScope.launch {
            // flatMapLatest: Bei jeder Emissionsrunde (Sessions/Typen/
            // Pauschalen/min) wird das Datum frisch bestimmt und der
            // Override-Flow für HEUTE neu abonniert — Override-Änderungen
            // (M18.60) aktualisieren den Verlauf sofort, genau wie die
            // Headline im uiState-combine.
            combine(
                activityRepository.getAll(),
                activityTypeRepository.getAll(),
                dailyAllowanceRepository.getAll(),
                minuteTick
            ) { sessions, types, allowances, _ ->
                Triple(sessions, types, allowances)
            }.flatMapLatest { (sessions, types, allowances) ->
                val todayStr = LocalDate.now(zoneId).toString()
                dailyAllowanceRepository.getOverridesForDateFlow(todayStr).map { overrides ->
                    computeDailyQuality(
                        sessions = sessions,
                        typeMap = types.associateBy { it.id },
                        allowances = allowances,
                        overrideByAllowance = overrides.associateBy { it.allowanceId },
                        zoneId = zoneId
                    )
                }
            }.collect { trend ->
                _qualityTrend.value = trend
            }
        }
        // M18.58: Garmin-Tageszusammenfassung + Aktivitäten für heute beobachten.
        viewModelScope.launch {
            garminRepository.getSummaryByDate(LocalDate.now().toString()).collect { summary ->
                _garminSummary.value = summary
            }
        }
        viewModelScope.launch {
            garminRepository.getActivitiesByRange(start, end).collect { activities ->
                _garminActivities.value = activities
            }
        }
    }

    /**
     * M18.58: Berechnet pro Kalendertag den gewichteten Quality-Score
     * (gleiche Formel wie computeQualityScore, nur tagesweise):
     * score = Σ(dauer × positivität) / Σ(dauer). Tage ohne Sessions
     * werden weggelassen — die UI zeichnet nur vorhandene Tage.
     *
     * AEVUM-2-FIX: Pauschalen + Tages-Overrides werden mit einbezogen —
     * identische Logik wie computeQualityScore (M18.62/M18.38-Regel:
     * "30 min Pauschale gilt ab 00:30"). Für HEUTE gilt die Regel mit der
     * aktuellen Uhrzeit, für VERGANGENE Tage zählt die Pauschale immer
     * (der Tag ist vorbei — sie war voll wirksam). Dadurch liefert der
     * Trend-Punkt für heute exakt denselben Wert wie die Headline.
     * Heute wird IMMER als Punkt ausgegeben (notfalls Score 0), damit
     * Headline und Verlauf auch bei reinen Pauschalen-Tagen übereinstimmen.
     */
    private fun computeDailyQuality(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance> = emptyList(),
        overrideByAllowance: Map<String, com.d_drostes_apps.aevum.data.model.AllowanceDayOverride> = emptyMap(),
        zoneId: ZoneId
    ): List<DailyQualityPoint> {
        val todayStr = LocalDate.now(zoneId).toString()
        val currentMinute = TimeFormatting.minutesOfDay(System.currentTimeMillis(), zoneId).coerceIn(0, 1440)
        val byDay = mutableMapOf<String, MutableList<ActivitySession>>()
        sessions.forEach { session ->
            val day = java.time.Instant.ofEpochMilli(session.startAt).atZone(zoneId).toLocalDate().toString()
            byDay.getOrPut(day) { mutableListOf() }.add(session)
        }
        // AEVUM-2-FIX: Heute immer berechnen (auch ohne Sessions), damit
        // der Trend-Punkt denselben Wert wie die Headline zeigt.
        if (todayStr !in byDay) byDay[todayStr] = mutableListOf()
        return byDay.map { (day, daySessions) ->
            var totalWeight = 0L
            var weighted = 0.0
            daySessions.forEach { session ->
                // M18.62-FIX: Pausen abziehen (vorher volle Wanduhrzeit)
                val duration = session.activeDurationMs()
                if (duration <= 0L) return@forEach
                val score = session.manualQualityOverride ?: typeMap[session.activityTypeId]?.positivityScore ?: 50
                totalWeight += duration
                weighted += duration * score
            }
            // AEVUM-2-FIX: Pauschalen wie in computeQualityScore gewichten.
            // Heute: nur wenn die Tageszeit die Pauschaldauer erreicht hat
            // (M18.38-User-Logik "30 min Pauschale gilt ab 00:30").
            // Vergangene Tage: immer — der Tag ist vorbei.
            val allowanceMinuteThreshold = if (day == todayStr) currentMinute else 1440
            allowances.filter { it.enabled }.forEach { allowance ->
                val effectiveMinutes = overrideByAllowance[allowance.id]?.minutes ?: allowance.minutesPerDay
                if (allowanceMinuteThreshold >= effectiveMinutes) {
                    val score = typeMap[allowance.activityTypeId]?.positivityScore ?: 50
                    val durationMs = effectiveMinutes * 60_000L
                    totalWeight += durationMs
                    weighted += durationMs * score
                }
            }
            val score = if (totalWeight <= 0L) 0 else (weighted / totalWeight).toInt().coerceIn(0, 100)
            DailyQualityPoint(
                date = day,
                score = score,
                durationMs = totalWeight
            )
        }.sortedBy { it.date }
    }

    private fun buildState(
        sessions: List<ActivitySession>,
        categories: List<com.d_drostes_apps.aevum.data.model.Category>,
        candidates: List<com.d_drostes_apps.aevum.data.model.ActivityCandidate>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        allTypes: List<com.d_drostes_apps.aevum.data.model.ActivityType> = emptyList(),
        sleepSessions: List<ActivitySession> = emptyList(),
        screenTimeMs: Long = 0L,
        // M18.33: Tagespauschalen — enabled Allowances werden sofort
        // (on-the-fly) in die Tages-Statistik eingerechnet. Vorher musste
        // man bis zum naechsten Midnight-Worker (00:05) warten.
        allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance> = emptyList(),
        // M18.37: Todos fuer die Dashboard-Karte
        todos: List<com.d_drostes_apps.aevum.data.model.Todo> = emptyList(),
        todayCompletions: List<com.d_drostes_apps.aevum.data.model.TodoCompletion> = emptyList(),
        // M18.60: Overrides des gewählten Tages + angezeigtes Datum
        dayOverrides: List<com.d_drostes_apps.aevum.data.model.AllowanceDayOverride> = emptyList(),
        displayedDate: LocalDate = LocalDate.now()
    ): DashboardUiState {
        val activeSessions = sessions.filter { it.deletedAt == null }
        val now = System.currentTimeMillis().coerceIn(start, end)
        val categoryMap = categories.associateBy { it.id }
        val clippedSessions = activeSessions.map { it.clipped(now) }
        val totalMs = clippedSessions.sumOf { it.durationMs }
        // M18.37: Kompakte Todo-Summary fuer die Dashboard-Karte.
        // Nur aktive Todos zaehlen; erledigt = Completion heute vorhanden.
        val activeTodos = todos.filter { it.active }
        val doneIds = todayCompletions.map { it.todoId }.toSet()
        // M18.44-FIX (Root Cause "Dashboard zeigt 1 offen, Todos-Screen
        // zeigt abgehakt"): TodosViewModel berechnet done = completion
        // ODER autoDone — Dauer-Todos (targetMinutes > 0) gelten als
        // erledigt, sobald die heutige Aktivitaetszeit des zugehoerigen
        // ActivityType das Ziel erreicht, OHNE Completion-Eintrag in der
        // DB. Das Dashboard zaehlte vorher NUR DB-Completions → jede
        // auto-erledigte Dauer-Todo erschien als offen. Jetzt identische
        // Logik wie im Todos-Screen.
        val durationByType = mutableMapOf<String, Long>()
        activeSessions
            .filter { it.startAt < end && (it.endAt == null || it.endAt > start) }
            .forEach { session ->
                val typeId = session.activityTypeId ?: return@forEach
                val clipStart = maxOf(session.startAt, start)
                val clipEnd = minOf(session.endAt ?: now, end)
                durationByType[typeId] = (durationByType[typeId] ?: 0L) + (clipEnd - clipStart).coerceAtLeast(0L)
            }
        val todoDoneCount = activeTodos.count { todo ->
            val isDuration = todo.targetMinutes > 0
            if (!isDuration) {
                todo.id in doneIds
            } else {
                (todo.id in doneIds) ||
                    (todo.activityTypeId != null &&
                        (durationByType[todo.activityTypeId] ?: 0L) >= todo.targetMinutes * 60_000L)
            }
        }
        val todoOpenCount = activeTodos.size - todoDoneCount
        // M18.37: Pauschalen-Summary fuer die Dashboard-Zeile — jede
        // enabled Pauschale wird explizit sichtbar (Name + Minuten),
        // nicht nur in der Gesamtsumme versteckt.
        // M18.60: Overrides gewinnen — hat der User die Pauschale fuer
        // den GEWAEHLTEN Tag angepasst, zaehlt der Tageswert statt der
        // Standard-Minuten. Die Pauschale selbst bleibt unveraendert.
        val overrideByAllowance = dayOverrides.associateBy { it.allowanceId }
        val allowanceSummary = allowances
            .filter { it.enabled }
            .map { allowance ->
                val override = overrideByAllowance[allowance.id]
                val effectiveMinutes = override?.minutes ?: allowance.minutesPerDay
                Triple(allowance.id, allowance.name, effectiveMinutes) to (override != null)
            }
        // M18.33: Pauschalen-Minuten addieren (nur enabled, mit Overrides)
        val allowanceMs = allowances.filter { it.enabled }.sumOf {
            (overrideByAllowance[it.id]?.minutes ?: it.minutesPerDay) * 60_000L
        }
        val totalMsWithAllowances = totalMs + allowanceMs
        val openMs = (DAY_MS - totalMsWithAllowances).coerceAtLeast(0L)
        // M18.65-FIX 2 (User: "nicht die Kategorie Schlaf angezeigt
        // bekommen, sondern die Activity Zeit Schlaf"): Die Verteilung
        // gruppiert nach ACTIVITY TYPE (wie die Balken in
        // computeQualityBreakdown) — vorher nach Kategorie, dadurch
        // erschien z.B. Schlaf als "Kategorie Schlaf" (bzw. "Sonstiges"
        // bei fehlender categoryId) statt als Activity "Schlaf".
        val distribution = clippedSessions
            .groupBy { it.activityTypeId ?: "unknown" }
            .map { (typeId, values) ->
                DashboardCategorySlice(
                    categoryId = typeId,
                    label = typeMap[typeId]?.name ?: "Sonstiges",
                    durationMs = values.sumOf { it.durationMs }
                )
            }
            .sortedByDescending { it.durationMs }
        // M12.2: SourceType wird zur Anzeige eines dezenten "Auto"-Hinweises genutzt.
        // Wenn die aktuelle Session automatisch gestartet wurde (GEOFENCE_AUTO,
        // HEALTH_SLEEP_AUTO, ACTIVITY_RECOGNITION_AUTO), wird der Source-Hinweis
        // im Dashboard angepasst. Verwendet AUTO_SOURCES, um die zentrale Konstante
        // als Single Source of Truth zu nutzen.
        val current = activeSessions.filter { it.endAt == null }.maxByOrNull { it.startAt }
            ?: activeSessions.maxByOrNull { it.startAt }
        // M12.2: Map von Session-ID → sourceType, damit der Flow (der nur
        // ClippedSessions kennt) den Auto-Flag korrekt setzen kann.
        val sourceTypeById = activeSessions.associate { it.id to it.sourceType }
        val flow = clippedSessions.sortedBy { it.startAt }.map { session ->
            val startMinute = TimeFormatting.minutesOfDay(session.startAt, zoneId).coerceIn(0, 1440)
            val endMinute = TimeFormatting.minutesOfDay(session.endAt, zoneId).coerceIn(startMinute, 1440)
            val isAutoSession = sourceTypeById[session.id] in com.d_drostes_apps.aevum.ui.screens.timeline.AUTO_SOURCES
            DashboardFlowSegment(
                id = session.id,
                title = session.title,
                categoryName = categoryMap[session.categoryId]?.name ?: "Sonstiges",
                categoryId = session.categoryId ?: "unknown",
                startMinute = startMinute,
                endMinute = endMinute,
                timeRange = "${formatMinute(startMinute)}–${formatMinute(endMinute)}",
                duration = TimeFormatting.formatDuration(session.durationMs),
                isCurrent = session.isCurrent,
                isCandidate = false,
                // M12.2: Auto-Sessions werden im Dashboard als "Auto" markiert.
                isAuto = isAutoSession
            )
        }

        // M7: Candidate flow segments (semi-transparent in timeline)
        val candidateSegments = candidates.map { c ->
            val startMin = TimeFormatting.minutesOfDay(c.startAt, zoneId).coerceIn(0, 1440)
            val endMin = TimeFormatting.minutesOfDay(c.endAt, zoneId).coerceIn(startMin, 1440)
            DashboardFlowSegment(
                id = c.id,
                title = c.suggestedTitle,
                categoryName = categoryMap[c.suggestedCategoryId]?.name ?: "Sonstiges",
                categoryId = c.suggestedCategoryId ?: "unknown",
                startMinute = startMin,
                endMinute = endMin,
                timeRange = "${formatMinute(startMin)}–${formatMinute(endMin)}",
                duration = TimeFormatting.formatDuration(c.endAt - c.startAt),
                isCurrent = false,
                isCandidate = true
            )
        }

        // Combine and sort all segments
        val allSegments = (flow + candidateSegments).sortedBy { it.startMinute }
        val gaps = buildFlowGaps(allSegments)

        val timeline = allSegments.takeLast(4).map { segment ->
            DashboardTimelineRow(
                id = segment.id,
                time = "%02d:%02d".format(segment.startMinute / 60, segment.startMinute % 60),
                title = segment.title,
                categoryName = segment.categoryName,
                duration = segment.duration,
                source = when {
                    segment.isCandidate -> "Vorschlag"
                    segment.isCurrent -> "Jetzt"
                    else -> "Erfasst"
                },
                isCurrent = segment.isCurrent,
                isCandidate = segment.isCandidate
            )
        }
        val top = distribution.firstOrNull()
        val narrative = buildNarrative(totalMsWithAllowances, openMs, top, candidates.size, current)
        val insights = buildInsights(distribution, totalMsWithAllowances, openMs, candidates.size)

        // M7: Accepted today count
        val acceptedToday = candidates.count { it.status == "ACCEPTED" && it.resolvedAt?.let { it in start..end } == true }

        val activeSessionsList = sessions
        return DashboardUiState(
            headline = narrative.headline,
            narrative = narrative.body,
            currentActivity = current?.title ?: "Noch nichts erfasst",
            // M18.62-FIX: Pausen abziehen (vorher volle Wanduhrzeit)
            currentDuration = current?.let { TimeFormatting.formatDuration(it.activeDurationInWindow(start, end, now)) } ?: "0m",
            balanceScore = estimateBalanceScore(distribution, totalMsWithAllowances, openMs),
            totalTracked = TimeFormatting.formatDuration(totalMsWithAllowances),
            // M18.60: Numerischer Wert fuer die Lade-Animation (Werte
            // zaehlen sich beim Tag-Wechsel animiert hoch/runter).
            totalTrackedMs = totalMsWithAllowances,
            openTime = TimeFormatting.formatDuration(openMs),
            openMsValue = openMs,
            sessionCount = activeSessions.size,
            reviewCount = candidates.size,
            distribution = distribution,
            timeline = timeline,
            flowSegments = allSegments,
            flowGaps = gaps,
            currentMinute = currentMinute(now),
            insights = insights,
            topCategory = top?.label ?: "Noch offen",
            topCategoryDuration = top?.let { TimeFormatting.formatDuration(it.durationMs) } ?: "0m",
            hasData = activeSessions.isNotEmpty(),
            dayProgress = ((now - start).toFloat() / DAY_MS.toFloat()).coerceIn(0f, 1f),
            // M7: Automation capture
            capturedTodayCount = activeSessions.size + candidates.size,
            candidateCount = candidates.size,
            acceptedTodayCount = acceptedToday,
            activityTypes = allTypes,
            // M11: fix sleepCandidateCount — bisher nie berechnet
            sleepCandidateCount = candidates.count { it.activityTypeId == "sleep" },
            // M10: today's sleep summary
            lastSleepSession = sleepSessions.maxByOrNull { it.endAt ?: it.startAt },
            lastSleepDurationMs = sleepSessions.maxByOrNull { it.endAt ?: it.startAt }
                ?.let { (it.endAt ?: now) - it.startAt }
                ?: 0L,
            // M16: Bildschirmzeit aus echten Usage-Stats. screenTimeMs wird
            // über _screenTimeMs aus den topApps gespeist. Wenn Permission
            // fehlt oder keine Daten → 0L → UI zeigt "—" statt "0m".
            digitalScreenTimeMs = screenTimeMs,
            digitalScreenTimeFormatted = if (screenTimeMs > 0L) {
                val hours = screenTimeMs / 3_600_000
                val minutes = (screenTimeMs % 3_600_000) / 60_000
                when {
                    hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                    hours > 0 -> "${hours}h"
                    else -> "${minutes}m"
                }
            } else "—",
            digitalTopApp = _topApps.value.firstOrNull()?.appLabel ?: "—",
            // M18: Zeitqualität berechnen. Gewichtete Summe:
            // quality = Σ(dauer × score) / Σ(dauer). Pro Aktivität.
            // M18.62-FIX: Pauschalen fließen mit ein (User: "die
            // Tagespunktzahl soll auch die Pauschalen berücksichtigen").
            qualityScore = computeQualityScore(activeSessions, typeMap, allowances, overrideByAllowance, now),
            qualityBreakdown = computeQualityBreakdown(activeSessions, typeMap, allowances, overrideByAllowance, now),
            // M18.37: Todos fuer die Dashboard-Karte
            todoDoneCount = todoDoneCount,
            todoOpenCount = todoOpenCount,
            todoTotalCount = activeTodos.size,
            // M18.37: Pauschalen explizit sichtbar
            allowanceSummary = allowanceSummary,
            // M18.60: angezeigtes Datum + Tages-Overrides fuer die UI
            displayedDate = displayedDate,
            allowanceOverrides = overrideByAllowance,
            // AEVUM-3: Manuelle Güte-Anpassung des Tages aktiv?
            hasDayQualityOverride = activeSessions.any { it.manualQualityOverride != null }
        )
    }

    // M18: Zeitqualitäts-Score 0..100 — gewichtetes Mittel über alle
    // heute erfassten Sessions (nur abgeschlossene + laufende mit Dauer).
    // Score 0 = alles schlecht, 100 = alles gut.
    // M18.62-FIX (User: "die Tagespunktzahl soll auch die Pauschalen
    // berücksichtigen"): Pauschalen werden wie Sessions gewichtet mit
    // einbezogen — gleiche Regel wie im Breakdown (M18.38-User-Logik:
    // "30 min Pauschale gilt ab 00:30"). Dadurch zeigt der QualityRing
    // schon am Morgen eine Punktzahl, auch wenn noch nichts erfasst ist.
    // Overrides (M18.60) bestimmen die effektiven Minuten der Pauschale.
    private fun computeQualityScore(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance> = emptyList(),
        overrideByAllowance: Map<String, com.d_drostes_apps.aevum.data.model.AllowanceDayOverride> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): Int {
        var totalWeight = 0L
        var weighted = 0.0
        sessions.forEach { session ->
            // M18.62-FIX: Pausen abziehen (vorher volle Wanduhrzeit)
            val duration = session.activeDurationMs()
            if (duration <= 0L) return@forEach
            val score = session.manualQualityOverride ?: typeMap[session.activityTypeId]?.positivityScore ?: 50
            totalWeight += duration
            weighted += duration * score
        }
        // M18.62-FIX: Pauschalen einbeziehen (nur wenn die Tageszeit die
        // Pauschaldauer erreicht hat — identische Logik wie im Breakdown).
        val currentMinute = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)
        allowances.filter { it.enabled }.forEach { allowance ->
            val effectiveMinutes = overrideByAllowance[allowance.id]?.minutes ?: allowance.minutesPerDay
            if (currentMinute >= effectiveMinutes) {
                val score = typeMap[allowance.activityTypeId]?.positivityScore ?: 50
                val durationMs = effectiveMinutes * 60_000L
                totalWeight += durationMs
                weighted += durationMs * score
            }
        }
        if (totalWeight <= 0L) return 0
        return (weighted / totalWeight).toInt().coerceIn(0, 100)
    }

    // M18: Pro-Aktivitäts-Breakdown für die Positivitäts-Balken.
    // M18.38: Pauschalen werden als eigene Balken ergänzt — aber nur,
    // wenn die Tageszeit die Pauschaldauer schon überschritten hat
    // (User-Logik: "30 min Pauschale gilt ab 00:30"). So erscheint
    // "Fertig machen 30m" sofort, wenn der Tag schon weiter ist.
    // M18.62-FIX: Overrides (M18.60) bestimmen auch hier die effektiven
    // Minuten — konsistent mit dem QualityRing.
    private fun computeQualityBreakdown(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance> = emptyList(),
        overrideByAllowance: Map<String, com.d_drostes_apps.aevum.data.model.AllowanceDayOverride> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): List<QualitySlice> {
        val slices = sessions
            .filter { it.activityTypeId != null }
            .groupBy { it.activityTypeId!! }
            .map { (typeId, typeSessions) ->
                val type = typeMap[typeId]
                // AEVUM-3: Pro-Session-Override gewinnt. Sessions einer
                // Gruppe können unterschiedliche Overrides haben — für den
                // Balken nehmen wir den dauer-gewichteten Mittelwert.
                val totalDur = typeSessions.sumOf { it.activeDurationMs(now) }
                val weightedScore = typeSessions.sumOf { session ->
                    val s = session.manualQualityOverride ?: type?.positivityScore ?: 50
                    session.activeDurationMs(now).toDouble() * s
                }
                val score = if (totalDur > 0L) (weightedScore / totalDur).toInt().coerceIn(0, 100)
                            else type?.positivityScore ?: 50
                // M18.62-FIX: Pausen abziehen (vorher volle Wanduhrzeit)
                val duration = typeSessions.sumOf { it.activeDurationMs(now) }
                QualitySlice(
                    activityTypeId = typeId,
                    label = type?.name ?: typeId,
                    durationMs = duration,
                    score = score,
                    color = com.d_drostes_apps.aevum.ui.components.positivityColor(score),
                    // M18.66-FIX16: Icon der Aktivität (wie Insights)
                    icon = type?.icon ?: "•"
                )
            }
            .toMutableList()
        // M18.38: Pauschalen-Balken — nur wenn die Tageszeit die
        // Pauschaldauer erreicht hat (z.B. 30 min ab 00:30).
        // M18.62-FIX: Overrides bestimmen die effektiven Minuten.
        val currentMinute = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)
        allowances.filter { it.enabled }.forEach { allowance ->
            val effectiveMinutes = overrideByAllowance[allowance.id]?.minutes ?: allowance.minutesPerDay
            if (currentMinute >= effectiveMinutes) {
                val type = typeMap[allowance.activityTypeId]
                val score = type?.positivityScore ?: 50
                slices.add(
                    QualitySlice(
                        activityTypeId = "allowance_${allowance.id}",
                        label = allowance.name,
                        durationMs = effectiveMinutes * 60_000L,
                        score = score,
                        color = com.d_drostes_apps.aevum.ui.components.positivityColor(score),
                        icon = type?.icon ?: "⏱"
                    )
                )
            }
        }
        // M18.66-FIX16: Prozent-Anteil berechnen (wie Insights) —
        // relativ zur Summe aller Slices, Top-5 statt Top-4.
        val totalMs = slices.sumOf { it.durationMs }.coerceAtLeast(1L)
        return slices
            .map { it.copy(percent = ((it.durationMs * 100) / totalMs).toInt()) }
            .sortedByDescending { it.durationMs }
            .take(5) // M18.66-FIX16: 5 wie Insights "Top Aktivitäten"
    }

    private fun currentMinute(now: Long) = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)

    private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun buildFlowGaps(segments: List<DashboardFlowSegment>): List<FlowGap> {
        val gaps = mutableListOf<FlowGap>()
        var prevEnd = 0
        for (segment in segments) {
            if (segment.startMinute > prevEnd) {
                gaps += FlowGap(
                    startMinute = prevEnd,
                    endMinute = segment.startMinute,
                    durationMs = (segment.startMinute - prevEnd).toLong() * 60_000L
                )
            }
            prevEnd = segment.endMinute.coerceAtLeast(prevEnd)
        }
        if (prevEnd < 1440) {
            gaps += FlowGap(
                startMinute = prevEnd,
                endMinute = 1440,
                durationMs = (1440 - prevEnd).toLong() * 60_000L
            )
        }
        return gaps
    }

    private fun ActivitySession.clipped(now: Long): ClippedSession {
        val clippedStart = startAt.coerceIn(start, end)
        val clippedEnd = (endAt ?: now).coerceIn(start, end)
        return ClippedSession(
            id = id,
            title = title,
            categoryId = categoryId,
            activityTypeId = activityTypeId,
            startAt = clippedStart,
            endAt = clippedEnd,
            // M18.62-FIX: Pausen abziehen — vorher wurde die volle
            // Wanduhrzeit (Ende − Start) gezeigt, obwohl pausiert wurde.
            durationMs = activeDurationInWindow(start, end, now),
            isCurrent = endAt == null
        )
    }

    private fun buildNarrative(
        totalMs: Long,
        openMs: Long,
        top: DashboardCategorySlice?,
        reviewCount: Int,
        current: ActivitySession?
    ): DailyNarrative {
        if (totalMs <= 0 && reviewCount == 0) {
            return DailyNarrative(
                headline = "Dein Tag ist noch eine leere Seite.",
                body = "Erfasse einen ersten Abschnitt oder prüfe später automatische Vorschläge. Aevum wird mit jedem Eintrag klarer."
            )
        }
        if (totalMs <= 0 && reviewCount > 0) {
            return DailyNarrative(
                headline = "Aevum hat etwas für dich vorbereitet.",
                body = "${reviewCount.reviewText()} warten ruhig auf deine Bestätigung. Erst danach zählen sie als Teil deines Tages."
            )
        }
        val topText = top?.let { "Vor allem ${it.label.lowercase()} (${TimeFormatting.formatDuration(it.durationMs)})" } ?: "Mehrere kleine Abschnitte"
        val reviewText = if (reviewCount > 0) " ${reviewCount.reviewText()} sind noch offen." else ""
        val openText = if (openMs > 90 * 60_000L) " ${TimeFormatting.formatDuration(openMs)} sind noch nicht erzählt." else ""
        // M12.2: "läuft … (Auto)" statt "läuft …" für automatisch gestartete Sessions.
        val currentAuto = current?.sourceType in com.d_drostes_apps.aevum.ui.screens.timeline.AUTO_SOURCES
        val currentSuffix = if (currentAuto) " (Auto)" else ""
        val currentText = current?.let { " Gerade läuft: ${it.title}$currentSuffix." }.orEmpty()
        return DailyNarrative(
            headline = "Das war bisher dein Tag.",
            body = "$topText prägt deinen Tagesfluss.$currentText$reviewText$openText".trim()
        )
    }

    private fun buildInsights(
        distribution: List<DashboardCategorySlice>,
        totalMs: Long,
        openMs: Long,
        reviewCount: Int
    ): List<DashboardInsight> {
        val insights = mutableListOf<DashboardInsight>()
        val top = distribution.firstOrNull()
        if (top != null) {
            val share = ((top.durationMs.toFloat() / totalMs.coerceAtLeast(1).toFloat()) * 100).toInt()
            insights += DashboardInsight("Größter Block", "${top.label} macht $share% deiner erfassten Zeit aus.", "◷")
        }
        if (reviewCount > 0) {
            insights += DashboardInsight("Kurz prüfen", "${reviewCount.reviewText()} warten auf deine Entscheidung.", "✓")
        }
        if (openMs > 2 * 60 * 60_000L) {
            insights += DashboardInsight("Offene Zeit", "${TimeFormatting.formatDuration(openMs)} sind noch frei oder nicht erfasst.", "○")
        }
        if (distribution.size >= 3) {
            insights += DashboardInsight("Vielfalt", "Dein Tag verteilt sich auf ${distribution.size} Bereiche.", "✦")
        }
        if (insights.isEmpty()) {
            insights += DashboardInsight("Ruhiger Start", "Ein erster Eintrag reicht, damit Aevum deinen Tag sichtbar macht.", "✧")
        }
        return insights.take(3)
    }

    private fun estimateBalanceScore(distribution: List<DashboardCategorySlice>, totalMs: Long, openMs: Long): Int {
        if (totalMs <= 0) return 0
        val dominantShare = distribution.firstOrNull()?.durationMs?.toFloat()?.div(totalMs.toFloat()) ?: 0f
        val variety = (distribution.size.coerceAtMost(4) / 4f) * 35f
        val coverage = (totalMs.toFloat() / DAY_MS.toFloat()).coerceIn(0f, 1f) * 35f
        val dominancePenalty = if (dominantShare > 0.72f) 18f else 0f
        val openPenalty = if (openMs > 12 * 60 * 60_000L) 10f else 0f
        return (30f + variety + coverage - dominancePenalty - openPenalty).toInt().coerceIn(0, 100)
    }

    private fun Int.reviewText(): String = if (this == 1) "1 Vorschlag" else "$this Vorschläge"

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

private data class ClippedSession(
    val id: String,
    val title: String,
    val categoryId: String?,
    // M18.65-FIX 2 (User: "nicht die Kategorie, sondern die Activity-Zeit
    // anzeigen"): Die Dashboard-Verteilung gruppiert nach ActivityType.
    val activityTypeId: String?,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long,
    val isCurrent: Boolean
)

data class DailyNarrative(
    val headline: String,
    val body: String
)

data class DashboardUiState(
    val headline: String = "Dein Tag ist noch eine leere Seite.",
    val narrative: String = "Erfasse einen ersten Abschnitt oder prüfe später automatische Vorschläge.",
    val currentActivity: String = "Noch nichts erfasst",
    val currentDuration: String = "0m",
    val balanceScore: Int = 0,
    val totalTracked: String = "0m",
    // M18.60: Numerische Werte fuer die Zaehl-Animation beim Tag-Wechsel.
    val totalTrackedMs: Long = 0L,
    val openMsValue: Long = 24 * 60 * 60 * 1000L,
    val openTime: String = "24h",
    val sessionCount: Int = 0,
    val reviewCount: Int = 0,
    val distribution: List<DashboardCategorySlice> = emptyList(),
    val timeline: List<DashboardTimelineRow> = emptyList(),
    val flowSegments: List<DashboardFlowSegment> = emptyList(),
    val flowGaps: List<FlowGap> = emptyList(),
    val currentMinute: Int = 0,
    val insights: List<DashboardInsight> = emptyList(),
    val topCategory: String = "Noch offen",
    val topCategoryDuration: String = "0m",
    val hasData: Boolean = false,
    val dayProgress: Float = 0f,
    // M7: Automation capture
    val capturedTodayCount: Int = 0,
    val candidateCount: Int = 0,
    val acceptedTodayCount: Int = 0,
    // M8: Sleep & Digital
    val sleepCandidateCount: Int = 0,
    val digitalScreenTimeMs: Long = 0L,
    val digitalScreenTimeFormatted: String = "0m",
    val digitalTopApp: String = "—",
    // M9: Activity types for live activity picker
    val activityTypes: List<com.d_drostes_apps.aevum.data.model.ActivityType> = emptyList(),
    // M10: today's sleep summary for the dashboard card
    val lastSleepSession: ActivitySession? = null,
    val lastSleepDurationMs: Long = 0L,
    // M18: Zeitqualität — gewichtete Summe aus (Dauer × Positivität).
    // qualityScore 0..100, qualityBreakdown pro Aktivität für die Balken.
    val qualityScore: Int = 0,
    val qualityBreakdown: List<QualitySlice> = emptyList(),
    // M18.37: Kompakte Todo-Summary fuer die Dashboard-Karte.
    val todoDoneCount: Int = 0,
    val todoOpenCount: Int = 0,
    val todoTotalCount: Int = 0,
    // M18.37: Pauschalen explizit sichtbar (Titel, Minuten/Tag).
    // M18.60: (id, name, effektive Minuten) + Override-Flag.
    val allowanceSummary: List<Pair<Triple<String, String, Int>, Boolean>> = emptyList(),
    // M18.60: Tages-Overrides (allowanceId → Override) fuer Popup-Anzeige.
    val allowanceOverrides: Map<String, com.d_drostes_apps.aevum.data.model.AllowanceDayOverride> = emptyMap(),
    // M18.60: Angezeigtes Datum (Tages-Navigation).
    val displayedDate: LocalDate = LocalDate.now(),
    // AEVUM-3: true, wenn mindestens eine Session des angezeigten Tages
    // eine manuelle Güte-Anpassung (Override) hat.
    val hasDayQualityOverride: Boolean = false
)

data class QualitySlice(
    val activityTypeId: String,
    val label: String,
    val durationMs: Long,
    val score: Int,
    val color: androidx.compose.ui.graphics.Color,
    // M18.66-FIX16: Icon der Aktivität (wie Insights "Top Aktivitäten")
    val icon: String = "•",
    // M18.66-FIX16: Prozent-Anteil an der Gesamtzeit (wie Insights)
    val percent: Int = 0
)

data class DashboardCategorySlice(
    val categoryId: String,
    val label: String,
    val durationMs: Long
)

data class DashboardTimelineRow(
    val id: String,
    val time: String,
    val title: String,
    val categoryName: String,
    val duration: String,
    val source: String,
    val isCurrent: Boolean,
    val isCandidate: Boolean = false
)

data class DashboardFlowSegment(
    val id: String,
    val title: String,
    val categoryName: String,
    val categoryId: String,
    val startMinute: Int,
    val endMinute: Int,
    val timeRange: String,
    val duration: String,
    val isCurrent: Boolean,
    val isCandidate: Boolean = false,
    // M12.2: Auto-Sessions tragen einen isAuto-Flag, damit der Dashboard-
    // Flow sie konsistent markieren kann (gleiche Konstante wie Timeline).
    val isAuto: Boolean = false
)

data class FlowGap(
    val startMinute: Int,
    val endMinute: Int,
    val durationMs: Long
)

data class DashboardInsight(
    val title: String,
    val message: String,
    val icon: String
)

/**
 * M18.58: Ein Punkt im Güte-Verlauf — der gewichtete Quality-Score
 * (0..100) eines Kalendertags plus erfasste Dauer. Wird für den
 * 7/30/365-Tage-Verlaufs-Chart im Dashboard genutzt.
 */
data class DailyQualityPoint(
    val date: String,
    val score: Int,
    val durationMs: Long
)
