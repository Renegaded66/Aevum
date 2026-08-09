package com.d_drostes_apps.aevum.ui.screens.digitalbalance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.model.AppLimit
import com.d_drostes_apps.aevum.data.repository.AppLimitRepository
import com.d_drostes_apps.aevum.domain.digital.AppLimitChecker
import com.d_drostes_apps.aevum.domain.digital.AppUsageAggregator
import com.d_drostes_apps.aevum.domain.digital.UsageStatsPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val remainingMs: Long?
)

data class DigitalBalanceUiState(
    val hasPermission: Boolean = false,
    val todayTotalMs: Long = 0L,
    val todayAppCount: Int = 0,
    val topAppName: String? = null,
    val topAppMs: Long = 0L,
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
    private val aggregator: AppUsageAggregator
) : AndroidViewModel(application) {

    private val rangeDays = MutableStateFlow(7)
    private val refreshTick = MutableStateFlow(0L)

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
                remainingMs = AppLimitChecker.remainingMs(limit, usage.durationMs)
            )
        }.sortedByDescending { it.todayMs }

        val todayTotal = todayUsage.sumOf { it.durationMs }

        return DigitalBalanceUiState(
            hasPermission = true,
            todayTotalMs = todayTotal,
            todayAppCount = apps.size,
            topAppName = apps.firstOrNull()?.appLabel,
            topAppMs = apps.firstOrNull()?.todayMs ?: 0L,
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
     * Läuft nur, wenn mindestens ein Limit aktiv ist.
     */
    private suspend fun syncBlockService() {
        val limits = appLimitRepository.getAll().first()
        val anyActive = limits.any { it.enabled && it.exceptionType != AppLimit.EXCEPTION_ALWAYS_ALLOW }
        val app = getApplication<Application>()
        if (anyActive) {
            com.d_drostes_apps.aevum.domain.digital.AppBlockService.start(app)
        } else {
            com.d_drostes_apps.aevum.domain.digital.AppBlockService.stop(app)
        }
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
