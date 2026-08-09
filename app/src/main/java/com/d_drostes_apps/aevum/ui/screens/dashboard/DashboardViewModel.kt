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
import com.d_drostes_apps.aevum.data.repository.GoalRepository
import com.d_drostes_apps.aevum.domain.analytics.GoalProgressAnalytics
import com.d_drostes_apps.aevum.domain.digital.UsageStatsCollector
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityState
import com.d_drostes_apps.aevum.domain.seed.EnsureDefaultDataUseCase
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.screens.goals.GoalWithProgress
import com.d_drostes_apps.aevum.ui.screens.goals.toGoalWithProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val application: Application,
    activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    candidateRepository: ActivityCandidateRepository,
    private val goalRepository: GoalRepository,
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
    private val garminRepository: com.d_drostes_apps.aevum.data.repository.GarminRepository
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    // M18.43-FIX (Root Cause "abgehakte wiederkehrende Todo zeigt im
    // Dashboard noch offen"): `today`/`start`/`end` wurden EINMAL beim
    // ViewModel-Init berechnet. Wenn das Dashboard über Mitternacht
    // offen blieb (oder das ViewModel vor Mitternacht erstellt wurde),
    // zeigte es den VORTAG — die Completion von HEUTE (neues Datum)
    // wurde nie geladen -> "1 offen" obwohl abgehakt. Jetzt: frisches
    // Datum bei jedem State-Build (der Minuten-Tick erzwingt den Rebuild).
    private val today: LocalDate get() = LocalDate.now()
    private val start: Long get() = TimeFormatting.startOfDayMillis(today, zoneId)
    private val end: Long get() = TimeFormatting.endOfDayMillis(today, zoneId)

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
                val wakeMs = try {
                    usageWakeDetector.firstUsageSince(start)
                } catch (_: Exception) { null }
                val now = System.currentTimeMillis()
                val capMs = if (wakeMs != null) (now - wakeMs).coerceAtLeast(0L) else Long.MAX_VALUE
                _screenTimeMs.value = apps.sumOf { it.durationMs.coerceAtMost(capMs) }
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
    val uiState: StateFlow<DashboardUiState> = combine(
        activityRepository.getOverlappingRange(start, end),
        categoryRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        goalRepository.getByStatus("ACTIVE"),
        activityTypeRepository.getAll(),
        // M10: today's sleep — used for the "Guten Morgen" summary card.
        // M16.3: 36h-Overlap-Fenster, damit über-Mitternacht-Schlaf erscheint.
        activityRepository.getOverlappingRange(sleepWindowStart, sleepWindowEnd),
        // M16: Bildschirmzeit aus topApps — vorher nie in buildState() gesetzt
        _screenTimeMs,
        // M18.33: Tagespauschalen — on-the-fly in die Statistik einrechnen.
        dailyAllowanceRepository.getAll(),
        // M18.37: Todos fuer die kompakte Dashboard-Karte.
        todoRepository.getAll(),
        // M18.43-FIX: ALLE Completions laden — buildState filtert selbst
        // auf das AKTUELLE Datum. Vorher wurde getByDate(today) beim
        // combine-Setup einmal abonniert; über Mitternacht blieb der Flow
        // auf dem alten Datum -> abgehakte Todos von HEUTE erschienen
        // nicht als erledigt.
        todoRepository.getAllCompletions(),
        // M18.42: Minuetlicher Tick fuer Live-Updates.
        minuteTick
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val sessions = values[0] as List<ActivitySession>
        val categories = values[1] as List<com.d_drostes_apps.aevum.data.model.Category>
        val candidates = values[2] as List<ActivityCandidate>
        val activeGoals = values[3] as List<com.d_drostes_apps.aevum.data.model.Goal>
        val types = values[4] as List<com.d_drostes_apps.aevum.data.model.ActivityType>
        val allSleepSessions = values[5] as List<ActivitySession>
        val screenMs = values[6] as Long
        val allowances = values[7] as List<com.d_drostes_apps.aevum.data.model.DailyAllowance>
        val allTodos = values[8] as List<com.d_drostes_apps.aevum.data.model.Todo>
        val allCompletions = values[9] as List<com.d_drostes_apps.aevum.data.model.TodoCompletion>
        // values[10] = minuteTick — nur Trigger, kein Inhalt noetig.
        // M18.43: Auf das AKTUELLE Datum filtern (frischer Getter).
        val todayCompletions = allCompletions.filter { it.date == today.toString() }
        // values[10] = minuteTick — nur Trigger, kein Inhalt noetig.
        // M16.3: Auf "den heutigen Tag überlappend" filtern — eine reine
        // today-Query würde Mitternacht-Schlaf verfehlen.
        // M18.21-FIX (Root Cause): Der Filter prüfte NUR die Zeitüberlappung,
        // aber NIE die Aktivität — dadurch landeten ALLE heutigen Sessions
        // (z.B. die laufende Studium-Session) in sleepSessionsToday und
        // lastSleepDurationMs zeigte die Dauer der letzten Session des Tages
        // als "Schlaf" (User-Bug: "1:16 Erfasst" = "1:16 Schlaf").
        val nowApprox = System.currentTimeMillis()
        val sleepSessionsToday = allSleepSessions.filter { session ->
            val s = session.startAt
            val e = session.endAt ?: nowApprox
            // NUR echte Schlaf-Sessions (activityTypeId "sleep" ODER
            // Kategorie "sleep" — deckt custom Schlaf-Typen ab).
            val isSleep = session.activityTypeId == "sleep" || session.categoryId == "sleep"
            // Session überlappt mit [start, end], wenn start < endUnd end > startOfDay.
            isSleep && s < end && e > start
        }
        buildState(
            sessions = sessions,
            categories = categories,
            candidates = candidates.filter { it.startAt < end && it.endAt > start },
            activeGoals = activeGoals,
            typeMap = types.associateBy { it.id },
            allTypes = types,
            sleepSessions = sleepSessionsToday,
            screenTimeMs = screenMs,
            // M18.33: Pauschalen sofort einrechnen
            allowances = allowances,
            // M18.37: Todos fuer die Dashboard-Karte
            todos = allTodos,
            todayCompletions = todayCompletions
        )
    }
        // M12.0.2: Defensive Programmierung — keine Exception darf bis zur UI
        // propagieren. Wenn ein der 6 Flows fehlschlägt (z.B. Room-Schema-Mismatch,
        // DB-Corruption, unzulässige Query), wird der Fehler geloggt und der
        // Default-State (leeres DashboardUiState) beibehalten. Die App bleibt
        // stabil, das Dashboard ist sichtbar — nur eben ohne Daten.
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

    // M18.58: Güte-Verlauf — Quality-Score pro Tag für die letzten 365 Tage.
    // Wird on-the-fly aus allen Sessions berechnet (keine neue Aggregations-
    // Infrastruktur). Die UI wählt das Fenster (7/30/365 Tage) und zeichnet
    // den Verlauf als animierten Area-Chart.
    private val _qualityTrend = MutableStateFlow<List<DailyQualityPoint>>(emptyList())
    val qualityTrend: StateFlow<List<DailyQualityPoint>> = _qualityTrend.asStateFlow()

    init {
        // M18.58: Alle Sessions beobachten (deletedAt==null via DAO-Filter)
        // und pro Tag den gewichteten Positivitäts-Score berechnen.
        viewModelScope.launch {
            activityRepository.getAll().collect { sessions ->
                val types = activityTypeRepository.getAll().firstOrNull() ?: emptyList()
                val typeMap = types.associateBy { it.id }
                _qualityTrend.value = computeDailyQuality(sessions, typeMap, zoneId)
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
     */
    private fun computeDailyQuality(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        zoneId: ZoneId
    ): List<DailyQualityPoint> {
        val dayStartMs = 24L * 60 * 60 * 1000
        val byDay = mutableMapOf<String, MutableList<ActivitySession>>()
        sessions.forEach { session ->
            val day = java.time.Instant.ofEpochMilli(session.startAt).atZone(zoneId).toLocalDate().toString()
            byDay.getOrPut(day) { mutableListOf() }.add(session)
        }
        return byDay.map { (day, daySessions) ->
            var totalWeight = 0L
            var weighted = 0.0
            daySessions.forEach { session ->
                val duration = (session.endAt ?: System.currentTimeMillis()) - session.startAt
                if (duration <= 0L) return@forEach
                val score = typeMap[session.activityTypeId]?.positivityScore ?: 50
                totalWeight += duration
                weighted += duration * score
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
        activeGoals: List<com.d_drostes_apps.aevum.data.model.Goal>,
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
        todayCompletions: List<com.d_drostes_apps.aevum.data.model.TodoCompletion> = emptyList()
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
        val allowanceSummary = allowances
            .filter { it.enabled }
            .map { it.name to it.minutesPerDay }
        // M18.33: Pauschalen-Minuten addieren (nur enabled)
        val allowanceMs = allowances.filter { it.enabled }.sumOf { it.minutesPerDay * 60_000L }
        val totalMsWithAllowances = totalMs + allowanceMs
        val openMs = (DAY_MS - totalMsWithAllowances).coerceAtLeast(0L)
        val distribution = clippedSessions
            .groupBy { it.categoryId ?: "unknown" }
            .map { (categoryId, values) ->
                DashboardCategorySlice(
                    categoryId = categoryId,
                    label = categoryMap[categoryId]?.name ?: "Sonstiges",
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
        val goalProgressList = activeGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, activeSessionsList, today, zoneId, typeMap)
        }.sortedByDescending { it.progress }.take(3).map { it.toGoalWithProgress() }

        return DashboardUiState(
            headline = narrative.headline,
            narrative = narrative.body,
            currentActivity = current?.title ?: "Noch nichts erfasst",
            currentDuration = current?.let { TimeFormatting.formatDuration(((it.endAt ?: now).coerceAtMost(end) - it.startAt.coerceAtLeast(start)).coerceAtLeast(0)) } ?: "0m",
            balanceScore = estimateBalanceScore(distribution, totalMsWithAllowances, openMs),
            totalTracked = TimeFormatting.formatDuration(totalMsWithAllowances),
            openTime = TimeFormatting.formatDuration(openMs),
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
            goalProgress = goalProgressList,
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
            qualityScore = computeQualityScore(activeSessions, typeMap),
            qualityBreakdown = computeQualityBreakdown(activeSessions, typeMap, allowances),
            // M18.37: Todos fuer die Dashboard-Karte
            todoDoneCount = todoDoneCount,
            todoOpenCount = todoOpenCount,
            todoTotalCount = activeTodos.size,
            // M18.37: Pauschalen explizit sichtbar
            allowanceSummary = allowanceSummary
        )
    }

    // M18: Zeitqualitäts-Score 0..100 — gewichtetes Mittel über alle
    // heute erfassten Sessions (nur abgeschlossene + laufende mit Dauer).
    // Score 0 = alles schlecht, 100 = alles gut.
    private fun computeQualityScore(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>
    ): Int {
        var totalWeight = 0L
        var weighted = 0.0
        sessions.forEach { session ->
            val duration = (session.endAt ?: System.currentTimeMillis()) - session.startAt
            if (duration <= 0L) return@forEach
            val score = typeMap[session.activityTypeId]?.positivityScore ?: 50
            totalWeight += duration
            weighted += duration * score
        }
        if (totalWeight <= 0L) return 0
        return (weighted / totalWeight).toInt().coerceIn(0, 100)
    }

    // M18: Pro-Aktivitäts-Breakdown für die Positivitäts-Balken.
    // M18.38: Pauschalen werden als eigene Balken ergänzt — aber nur,
    // wenn die Tageszeit die Pauschaldauer schon überschritten hat
    // (User-Logik: "30 min Pauschale gilt ab 00:30"). So erscheint
    // "Fertig machen 30m" sofort, wenn der Tag schon weiter ist.
    private fun computeQualityBreakdown(
        sessions: List<ActivitySession>,
        typeMap: Map<String, com.d_drostes_apps.aevum.data.model.ActivityType>,
        allowances: List<com.d_drostes_apps.aevum.data.model.DailyAllowance> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): List<QualitySlice> {
        val slices = sessions
            .filter { it.activityTypeId != null }
            .groupBy { it.activityTypeId!! }
            .map { (typeId, typeSessions) ->
                val type = typeMap[typeId]
                val score = type?.positivityScore ?: 50
                val duration = typeSessions.sumOf { (it.endAt ?: now) - it.startAt }
                QualitySlice(
                    activityTypeId = typeId,
                    label = type?.name ?: typeId,
                    durationMs = duration,
                    score = score,
                    color = com.d_drostes_apps.aevum.ui.components.positivityColor(score)
                )
            }
            .toMutableList()
        // M18.38: Pauschalen-Balken — nur wenn die Tageszeit die
        // Pauschaldauer erreicht hat (z.B. 30 min ab 00:30).
        val currentMinute = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)
        allowances.filter { it.enabled }.forEach { allowance ->
            if (currentMinute >= allowance.minutesPerDay) {
                val type = typeMap[allowance.activityTypeId]
                val score = type?.positivityScore ?: 50
                slices.add(
                    QualitySlice(
                        activityTypeId = "allowance_${allowance.id}",
                        label = allowance.name,
                        durationMs = allowance.minutesPerDay * 60_000L,
                        score = score,
                        color = com.d_drostes_apps.aevum.ui.components.positivityColor(score)
                    )
                )
            }
        }
        return slices
            .sortedByDescending { it.durationMs }
            .take(6) // M18.38: 6 statt 5, damit die Pauschale nicht verdrängt wird
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
            startAt = clippedStart,
            endAt = clippedEnd,
            durationMs = (clippedEnd - clippedStart).coerceAtLeast(0L),
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
    val goalProgress: List<GoalWithProgress> = emptyList(),
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
    val allowanceSummary: List<Pair<String, Int>> = emptyList()
)

data class QualitySlice(
    val activityTypeId: String,
    val label: String,
    val durationMs: Long,
    val score: Int,
    val color: androidx.compose.ui.graphics.Color
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
