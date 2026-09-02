package com.d_drostes_apps.aevum.ui.screens.digitalbalance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.AppLimit
import com.d_drostes_apps.aevum.data.model.BalanceProfile
import com.d_drostes_apps.aevum.data.repository.AppLimitRepository
import com.d_drostes_apps.aevum.data.repository.BalanceProfileRepository
import com.d_drostes_apps.aevum.domain.digital.AppLimitChecker
import com.d_drostes_apps.aevum.domain.digital.AppUsageAggregator
import com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission
import com.d_drostes_apps.aevum.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.graphics.asImageBitmap

data class DigitalAppUi(
    val packageName: String,
    val appLabel: String,
    // M18.62: Nutzung am GEWÄHLTEN Tag (heute oder angeklickter Balken)
    val dayMs: Long,
    val rangeMs: Long,
    val limit: AppLimit?,
    val isBlocked: Boolean,
    val progress: Float,
    val remainingMs: Long?,
    // M18.66-FIX16: Fertige ImageBitmap statt Drawable — wird EINMAL im
    // ViewModel konvertiert und gecacht. Vorher wurde drawableToBitmap()
    // bei JEDER Recomposition aufgerufen (Scrollen, Animationen) — das
    // war die Hauptursache für das Ruckeln der Digital-Balance-Seite.
    val icon: androidx.compose.ui.graphics.ImageBitmap? = null
)

data class DigitalBalanceUiState(
    val hasPermission: Boolean = false,
    val todayTotalMs: Long = 0L,
    val todayAppCount: Int = 0,
    val topAppName: String? = null,
    val topAppMs: Long = 0L,
    val unlockCount: Int = 0,
    val hourlyMs: List<Long> = List(24) { 0L },
    val dailyGoalMs: Long = 5 * 60 * 60 * 1000L, // Tagesziel: 5h (Google-Default)
    val rangeDays: Int = 7,
    val dailyTotals: List<Pair<LocalDate, Long>> = emptyList(),
    // M18.62: Gewählter Tag (Balken-Klick im Diagramm). null = heute.
    val selectedDay: LocalDate? = null,
    // M18.62: Gesamt-Bildschirmzeit des gewählten Tages (für die
    // Anteils-Balken der App-Liste)
    val selectedDayTotalMs: Long = 0L,
    val apps: List<DigitalAppUi> = emptyList(),
    val blockedCount: Int = 0,
    val loading: Boolean = true,
    // M18.61g: Sortierung — "usage" (absteigend nach Nutzung, Default)
    // oder "alpha" (alphabetisch nach App-Name)
    val sortMode: String = "usage"
)

@HiltViewModel
class DigitalBalanceViewModel @Inject constructor(
    application: Application,
    private val appLimitRepository: AppLimitRepository,
    private val aggregator: AppUsageAggregator,
    // M18.61f: Profile (Lern-Profil sperrt Social Media)
    private val balanceProfileRepository: BalanceProfileRepository
) : AndroidViewModel(application) {

    private val rangeDays = MutableStateFlow(7)
    private val refreshTick = MutableStateFlow(0L)
    // M18.93v7-FIX: Live-Tick alle 60s — der Balance-Tab aktualisierte
    // nur bei onResume/Refresh (User: "System-App zeigt 31 Min, Balance
    // 25 Min"). System-Apps wie Digital Wellbeing aktualisieren live;
    // Aevum-Balance blieb bis zum nächsten Refresh hinter dem echten
    // Wert zurück.
    private val minuteTick = MutableStateFlow(0L)
    // M18.62: Gewählter Tag (Balken-Klick im Diagramm). null = heute.
    private val selectedDay = MutableStateFlow<LocalDate?>(null)
    // M18.61g: Sortierung — "usage" (absteigend nach Nutzung) oder "alpha"
    private val sortMode = MutableStateFlow("usage")

    // M18.66-FIX16: Icon-Cache (packageName → ImageBitmap). Das Laden +
    // Konvertieren von App-Icons ist teuer. Vorher wurde bei jedem
    // State-Refresh getApplicationIcon() und bei jeder Recomposition
    // drawableToBitmap() aufgerufen — die Hauptursache für das Ruckeln.
    // Jetzt: einmal konvertieren, dauerhaft wiederverwenden.
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    // M18.61f: Profile-Flows für die Profile-Karte
    val profiles: StateFlow<List<BalanceProfile>> = balanceProfileRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeProfile: StateFlow<BalanceProfile?> = balanceProfileRepository.getActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun appContext(): Application = getApplication()

    /** M18.66-FIX16: Drawable → ImageBitmap (einmalig, gecacht). */
    private fun drawableToImageBitmap(drawable: android.graphics.drawable.Drawable): androidx.compose.ui.graphics.ImageBitmap {
        return try {
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            // Fallback: 1x1 transparentes Bitmap
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    /** 1×1 transparentes Bitmap als Cache-Marker für nicht ladbare Icons. */
    private fun emptyIcon(): androidx.compose.ui.graphics.ImageBitmap =
        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

    /**
     * M18.62: Pakete eines Profils (für den Edit-Dialog, um die
     * App-Auswahl vorzubefüllen).
     */
    suspend fun getProfilePackages(profileId: String): List<String> {
        return balanceProfileRepository.getAppPackagesOnce(profileId)
    }

    val uiState: StateFlow<DigitalBalanceUiState> = combine(
        rangeDays,
        refreshTick,
        sortMode,
        selectedDay,
        appLimitRepository.getAll(),
        minuteTick
    ) { values ->
        // M18.93v7: 6 Flows → Array-Form (kotlinx combine hat typisierte
        // Überladungen nur bis 5); Casts wie im DashboardViewModel.
        @Suppress("UNCHECKED_CAST")
        val days = values[0] as Int
        @Suppress("UNCHECKED_CAST")
        val sort = values[2] as String
        @Suppress("UNCHECKED_CAST")
        val selDay = values[3] as LocalDate?
        @Suppress("UNCHECKED_CAST")
        val limits = values[4] as List<AppLimit>
        val permission = UsageStatsPermission.isGranted(getApplication())
        if (!permission) {
            DigitalBalanceUiState(hasPermission = false, loading = false)
        } else {
            buildState(days, limits, sort, selDay)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DigitalBalanceUiState())

    init {
        // M18.93v7-FIX: Der Balance-Tab soll live mitlaufen (System-
        // Wellbeing-Muster) und nicht erst beim manuellen Refresh.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                minuteTick.value = System.currentTimeMillis()
            }
        }
    }

    private suspend fun buildState(days: Int, limits: List<AppLimit>, sort: String = "usage", selDay: LocalDate? = null): DigitalBalanceUiState {
        val today = LocalDate.now()
        val effectiveDay = selDay ?: today
        val isToday = effectiveDay == today

        // Performance-Fix: Die 4 UsageStats-Queries laufen jetzt PARALLEL
        // (coroutineScope + async). Vorher sequentiell → jeder Klick/Refresh
        // wartete 4× die Query-Latenz (100-500ms pro Query) → UI hakte.
        val results = coroutineScope {
            val dayUsageDeferred = async {
                if (isToday) aggregator.todayUsageByApp() else aggregator.usageByAppForDay(effectiveDay)
            }
            val rangeUsageDeferred = async { aggregator.rangeUsageByApp(days) }
            val dailyDeferred = async { aggregator.dailyTotals(days) }
            val detailDeferred = async { aggregator.todayDetail() }
            awaitAll(dayUsageDeferred, rangeUsageDeferred, dailyDeferred, detailDeferred)
        }
        @Suppress("UNCHECKED_CAST")
        val dayUsage = results[0] as List<AppUsageAggregator.AppUsage>
        @Suppress("UNCHECKED_CAST")
        val rangeUsage = results[1] as List<AppUsageAggregator.AppUsage>
        @Suppress("UNCHECKED_CAST")
        val daily = results[2] as List<AppUsageAggregator.DailyTotal>
        @Suppress("UNCHECKED_CAST")
        val detail = results[3] as AppUsageAggregator.TodayDetail
        val now = System.currentTimeMillis()

        val limitMap = limits.associateBy { it.packageName }
        val rangeMap = rangeUsage.associateBy { it.packageName }

        // Icon-Ladung: NICHT im UI-Refresh-Pfad blockieren. Die Icons
        // werden asynchron nachgeladen und in den Cache geschrieben —
        // der nächste Refresh zeigt sie dann. Vorher blockierte
        // getApplicationIcon() + Bitmap-Konvertierung den State-Build
        // (bei vielen Apps mehrere hundert ms) → hängende Klicks.
        val apps = dayUsage.map { usage ->
            val limit = limitMap[usage.packageName]
            val rangeMs = rangeMap[usage.packageName]?.durationMs ?: usage.durationMs
            DigitalAppUi(
                packageName = usage.packageName,
                appLabel = usage.appLabel,
                dayMs = usage.durationMs,
                rangeMs = rangeMs,
                limit = limit,
                isBlocked = AppLimitChecker.isBlocked(limit, usage.durationMs, now),
                progress = AppLimitChecker.progress(limit, usage.durationMs),
                remainingMs = AppLimitChecker.remainingMs(limit, usage.durationMs),
                // M18.66-FIX16: Icon aus dem Cache (einmalige Konvertierung).
                icon = iconCache[usage.packageName]
            )
        }.let { list ->
            // M18.61g: Sortierung — "usage" (absteigend nach Nutzung) oder
            // "alpha" (alphabetisch nach App-Name, case-insensitive)
            when (sort) {
                "alpha" -> list.sortedBy { it.appLabel.lowercase(Locale.getDefault()) }
                else -> list.sortedByDescending { it.dayMs }
            }
        }

        // Icons asynchron nachladen (nicht blockierend für die UI).
        viewModelScope.launch {
            val pm = getApplication<Application>().packageManager
            dayUsage.forEach { usage ->
                if (!iconCache.containsKey(usage.packageName)) {
                    try {
                        val drawable = pm.getApplicationIcon(usage.packageName)
                        val bitmap = drawableToImageBitmap(drawable)
                        iconCache[usage.packageName] = bitmap
                    } catch (_: Exception) {
                        // Icon nicht ladbar (deinstallierte App o. ä.) —
                        // Cache-Marker setzen, damit wir nicht bei jedem
                        // Refresh erneut versuchen.
                        iconCache[usage.packageName] = emptyIcon()
                    }
                }
            }
        }

        // M18.93v7-FIX (User: "Balance 25 Min vs System-App 31 Min"):
        // todayUsageByApp() filterte Phasen < 1s heraus (`> 1_000`) —
        // viele kurze App-Wechsel (z.B. Notification-Shade, Schnell-
        // Umschalter) verschwanden dadurch aus der Summe. Der System-
        // Ring (Google Digital Wellbeing) zählt ALLE Vordergrund-Phasen
        // ohne Schwellwert. Der Hero-Ring nutzt daher jetzt die
        // unfilterte detail.totalMs (identische Event-API, kein Filter);
        // die App-Liste behält den 1s-Filter (Rauschen ausblenden).
        val dayTotal = detail.totalMs

        return DigitalBalanceUiState(
            hasPermission = true,
            todayTotalMs = dayTotal,
            todayAppCount = apps.size,
            topAppName = apps.firstOrNull()?.appLabel,
            topAppMs = apps.firstOrNull()?.dayMs ?: 0L,
            unlockCount = detail.unlockCount,
            hourlyMs = detail.hourlyMs,
            rangeDays = days,
            dailyTotals = daily.map { it.date to it.totalMs },
            selectedDay = if (isToday) null else effectiveDay,
            selectedDayTotalMs = dayTotal,
            apps = apps,
            blockedCount = apps.count { it.isBlocked },
            loading = false,
            sortMode = sort
        )
    }

    fun setSortMode(mode: String) {
        sortMode.value = mode
    }

    fun setRangeDays(days: Int) {
        rangeDays.value = days
    }

    /**
     * M18.62: Tag im Balken-Diagramm antippen → App-Liste zeigt die Werte
     * dieses Tages. Nochmal antippen (oder null) → zurück zu heute.
     */
    fun selectDay(date: LocalDate?) {
        selectedDay.value = date
    }

    fun refresh() {
        // Performance-Fix: Refresh-Deduplizierung. ON_RESUME feuert bei
        // jedem Resume (auch nach Permission-Dialogen, App-Switcher) —
        // ohne Guard würde der komplette State-Build (4 UsageStats-Queries)
        // mehrfach pro Sekunde laufen → hängende Klicks.
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < 1_000) return
        lastRefreshMs = now
        refreshTick.value = now
    }

    private var lastRefreshMs = 0L

    fun setLimit(packageName: String, minutes: Int, enabled: Boolean) {
        viewModelScope.launch {
            val existing = appLimitRepository.getByPackageOnce(packageName)
            appLimitRepository.upsert(
                AppLimit(
                    packageName = packageName,
                    limitMinutes = minutes,
                    enabled = enabled,
                    exceptionType = existing?.exceptionType ?: AppLimit.EXCEPTION_NONE,
                    windowStartMin = existing?.windowStartMin ?: 0,
                    windowEndMin = existing?.windowEndMin ?: 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
            refresh()
            syncBlockService()
        }
    }

    fun setException(packageName: String, exceptionType: String, windowStartMin: Int = 0, windowEndMin: Int = 0) {
        viewModelScope.launch {
            val existing = appLimitRepository.getByPackageOnce(packageName)
            appLimitRepository.upsert(
                AppLimit(
                    packageName = packageName,
                    limitMinutes = existing?.limitMinutes ?: 60,
                    enabled = existing?.enabled ?: true,
                    exceptionType = exceptionType,
                    windowStartMin = windowStartMin,
                    windowEndMin = windowEndMin,
                    updatedAt = System.currentTimeMillis()
                )
            )
            refresh()
            syncBlockService()
        }
    }

    fun removeLimit(packageName: String) {
        viewModelScope.launch {
            appLimitRepository.delete(packageName)
            refresh()
            syncBlockService()
        }
    }

    /**
     * M18.61: Startet/stoppt den Sperr-Service je nach aktiven Limits.
     * Läuft nur, wenn mindestens ein Limit aktiv ist ODER ein Profil aktiv.
     */
    private suspend fun syncBlockService() {
        val limits = appLimitRepository.getAll().first()
        val anyActive = limits.any { it.enabled && it.exceptionType != AppLimit.EXCEPTION_ALWAYS_ALLOW }
        val activeProfile = balanceProfileRepository.getActiveOnce()
        val app = getApplication<Application>()
        if (anyActive || activeProfile != null) {
            com.d_drostes_apps.aevum.domain.digital.AppBlockService.start(app)
        } else {
            com.d_drostes_apps.aevum.domain.digital.AppBlockService.stop(app)
        }
    }

    // ===================== M18.61f: PROFILE =====================

    fun createProfile(name: String, icon: String, color: String, packageNames: List<String>) {
        viewModelScope.launch {
            balanceProfileRepository.create(name, icon, color, packageNames)
            refresh()
            syncBlockService()
        }
    }

    // M18.66-FIX14: Profil mit Zeitplan erstellen
    fun createProfileWithSchedule(
        name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int
    ) {
        viewModelScope.launch {
            balanceProfileRepository.createWithSchedule(
                name, icon, color, packageNames,
                scheduleEnabled, scheduleDays, scheduleStartMin, scheduleEndMin
            )
            refresh()
            syncBlockService()
        }
    }

    fun updateProfileApps(profileId: String, packageNames: List<String>) {
        viewModelScope.launch {
            balanceProfileRepository.updateApps(profileId, packageNames)
            refresh()
        }
    }

    /**
     * M18.62: Profil bearbeiten — Name/Icon/Farbe + App-Auswahl ändern.
     */
    fun updateProfile(profileId: String, name: String, icon: String, color: String, packageNames: List<String>) {
        viewModelScope.launch {
            balanceProfileRepository.update(profileId, name, icon, color, packageNames)
            refresh()
            syncBlockService()
        }
    }

    // M18.66-FIX14: Profil mit Zeitplan bearbeiten
    fun updateProfileWithSchedule(
        profileId: String, name: String, icon: String, color: String, packageNames: List<String>,
        scheduleEnabled: Boolean, scheduleDays: Int, scheduleStartMin: Int, scheduleEndMin: Int
    ) {
        viewModelScope.launch {
            balanceProfileRepository.updateWithSchedule(
                profileId, name, icon, color, packageNames,
                scheduleEnabled, scheduleDays, scheduleStartMin, scheduleEndMin
            )
            refresh()
            syncBlockService()
        }
    }

    fun setProfileActive(profileId: String) {
        viewModelScope.launch {
            balanceProfileRepository.setActive(profileId)
            refresh()
            syncBlockService()
        }
    }

    fun deactivateProfile() {
        viewModelScope.launch {
            balanceProfileRepository.deactivate()
            refresh()
            syncBlockService()
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            balanceProfileRepository.delete(profileId)
            refresh()
            syncBlockService()
        }
    }

    // ===================== M18.61f: POMODORO =====================

    /** Pomodoro-Phasen: Fokus / Kurzpause / Lange Pause */
    enum class PomodoroPhase(val labelRes: Int, val defaultMinutes: Int) {
        FOCUS(R.string.balance_pomodoro_focus, 25),
        SHORT_BREAK(R.string.balance_pomodoro_short_break, 5),
        LONG_BREAK(R.string.balance_pomodoro_long_break, 15)
    }

    data class PomodoroState(
        val phase: PomodoroPhase = PomodoroPhase.FOCUS,
        val totalSeconds: Int = 25 * 60,
        val remainingSeconds: Int = 25 * 60,
        val running: Boolean = false,
        val completedFocusSessions: Int = 0,
        val customMinutes: Int = 25
    )

    private val _pomodoro = MutableStateFlow(PomodoroState())
    val pomodoro: StateFlow<PomodoroState> = _pomodoro.asStateFlow()

    private var pomodoroJob: kotlinx.coroutines.Job? = null

    fun setPomodoroPhase(phase: PomodoroPhase) {
        pomodoroJob?.cancel()
        val total = phase.defaultMinutes * 60
        _pomodoro.value = _pomodoro.value.copy(
            phase = phase,
            totalSeconds = total,
            remainingSeconds = total,
            running = false
        )
    }

    fun setPomodoroMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(1, 120)
        pomodoroJob?.cancel()
        _pomodoro.value = _pomodoro.value.copy(
            customMinutes = clamped,
            totalSeconds = clamped * 60,
            remainingSeconds = clamped * 60,
            running = false
        )
    }

    fun togglePomodoro() {
        val current = _pomodoro.value
        if (current.running) {
            pomodoroJob?.cancel()
            _pomodoro.value = current.copy(running = false)
        } else {
            if (current.remainingSeconds <= 0) {
                // Abgelaufen → zurücksetzen auf volle Dauer
                _pomodoro.value = current.copy(remainingSeconds = current.totalSeconds)
            }
            pomodoroJob = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val s = _pomodoro.value
                    if (s.remainingSeconds <= 1) {
                        // Phase abgeschlossen
                        val completed = if (s.phase == PomodoroPhase.FOCUS) s.completedFocusSessions + 1 else s.completedFocusSessions
                        val nextPhase = when (s.phase) {
                            PomodoroPhase.FOCUS -> if (completed % 4 == 0) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
                            else -> PomodoroPhase.FOCUS
                        }
                        val nextTotal = nextPhase.defaultMinutes * 60
                        _pomodoro.value = s.copy(
                            phase = nextPhase,
                            totalSeconds = nextTotal,
                            remainingSeconds = nextTotal,
                            running = false,
                            completedFocusSessions = completed
                        )
                        break
                    }
                    _pomodoro.value = s.copy(remainingSeconds = s.remainingSeconds - 1)
                }
            }
        }
    }

    fun resetPomodoro() {
        pomodoroJob?.cancel()
        val s = _pomodoro.value
        _pomodoro.value = s.copy(remainingSeconds = s.totalSeconds, running = false)
    }

    fun openUsageAccessSettings() {
        com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission.openSettings(getApplication())
    }

    /**
     * M18.62: Nutzungszugriff widerrufen (Akku sparen, wenn Digital
     * Balance nicht benötigt wird). Öffnet die System-Settings, wo der
     * User den Schalter ausschalten kann. Zusätzlich wird der Sperr-
     * Service gestoppt, damit er nicht weiter im Hintergrund läuft.
     */
    fun revokeUsageAccess() {
        viewModelScope.launch {
            // Sperr-Service stoppen (kein aktives Limit mehr relevant,
            // da die Nutzungsdaten nicht mehr verfügbar sind)
            com.d_drostes_apps.aevum.domain.digital.AppBlockService.stop(getApplication())
            com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission.openSettings(getApplication())
        }
    }

    companion object {
        fun formatDuration(ms: Long): String {
            val hours = ms / 3_600_000
            val minutes = (ms % 3_600_000) / 60_000
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
        }

    }
}
