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
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class DigitalAppUi(
    val packageName: String,
    val appLabel: String,
    val todayMs: Long,
    val rangeMs: Long,
    val limit: AppLimit?,
    val isBlocked: Boolean,
    val progress: Float,
    val remainingMs: Long?,
    // M18.61f: Echte App-Icons (Bitmap aus dem PackageManager) statt
    // Buchstaben-Kreis. Wird im ViewModel geladen (IO-Dispatcher).
    val icon: android.graphics.drawable.Drawable? = null
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
    val apps: List<DigitalAppUi> = emptyList(),
    val blockedCount: Int = 0,
    val loading: Boolean = true
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

    // M18.61f: Profile-Flows für die Profile-Karte
    val profiles: StateFlow<List<BalanceProfile>> = balanceProfileRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeProfile: StateFlow<BalanceProfile?> = balanceProfileRepository.getActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun appContext(): Application = getApplication()

    val uiState: StateFlow<DigitalBalanceUiState> = combine(
        rangeDays,
        refreshTick,
        appLimitRepository.getAll()
    ) { days, _, limits ->
        val permission = UsageStatsPermission.isGranted(getApplication())
        if (!permission) {
            DigitalBalanceUiState(hasPermission = false, loading = false)
        } else {
            buildState(days, limits)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DigitalBalanceUiState())

    private suspend fun buildState(days: Int, limits: List<AppLimit>): DigitalBalanceUiState {
        val todayUsage = aggregator.todayUsageByApp()
        val rangeUsage = aggregator.rangeUsageByApp(days)
        val daily = aggregator.dailyTotals(days).map { it.date to it.totalMs }
        val detail = aggregator.todayDetail()
        val now = System.currentTimeMillis()

        val limitMap = limits.associateBy { it.packageName }
        val rangeMap = rangeUsage.associateBy { it.packageName }

        val apps = todayUsage.map { usage ->
            val limit = limitMap[usage.packageName]
            val rangeMs = rangeMap[usage.packageName]?.durationMs ?: usage.durationMs
            DigitalAppUi(
                packageName = usage.packageName,
                appLabel = usage.appLabel,
                todayMs = usage.durationMs,
                rangeMs = rangeMs,
                limit = limit,
                isBlocked = AppLimitChecker.isBlocked(limit, usage.durationMs, now),
                progress = AppLimitChecker.progress(limit, usage.durationMs),
                remainingMs = AppLimitChecker.remainingMs(limit, usage.durationMs),
                // M18.61f: Echte App-Icons laden (Drawable, kein Bitmap —
                // Compose kann Drawables direkt via rememberDrawablePainter).
                icon = try {
                    getApplication<Application>().packageManager.getApplicationIcon(usage.packageName)
                } catch (_: Exception) { null }
            )
        }.sortedByDescending { it.todayMs }

        val todayTotal = todayUsage.sumOf { it.durationMs }

        return DigitalBalanceUiState(
            hasPermission = true,
            todayTotalMs = todayTotal,
            todayAppCount = apps.size,
            topAppName = apps.firstOrNull()?.appLabel,
            topAppMs = apps.firstOrNull()?.todayMs ?: 0L,
            unlockCount = detail.unlockCount,
            hourlyMs = detail.hourlyMs,
            rangeDays = days,
            dailyTotals = daily,
            apps = apps,
            blockedCount = apps.count { it.isBlocked },
            loading = false
        )
    }

    fun setRangeDays(days: Int) {
        rangeDays.value = days
    }

    fun refresh() {
        refreshTick.value = System.currentTimeMillis()
    }

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

    fun updateProfileApps(profileId: String, packageNames: List<String>) {
        viewModelScope.launch {
            balanceProfileRepository.updateApps(profileId, packageNames)
            refresh()
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
    enum class PomodoroPhase(val label: String, val defaultMinutes: Int) {
        FOCUS("Fokus", 25),
        SHORT_BREAK("Kurzpause", 5),
        LONG_BREAK("Lange Pause", 15)
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

        fun formatDay(date: LocalDate): String {
            val today = LocalDate.now()
            return when (date) {
                today -> "Heute"
                today.minusDays(1) -> "Gestern"
                else -> date.format(DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN))
            }
        }
    }
}
