package de.devondroste.aevum.domain.digital

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.data.model.AppUsageSample
import de.devondroste.aevum.data.repository.AppUsageSampleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Collects digital balance data from Android UsageStatsManager.
 *
 * M8: Analytics-only. No candidates generated from digital data.
 * Reads: total screen time, per-app usage.
 */
class UsageStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appUsageRepository: AppUsageSampleRepository
) {
    private val usageStats by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    fun hasPermission(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 1000,
                now
            )
            !stats.isNullOrEmpty()
        } catch (_: Exception) { false }
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Collects usage stats for a specific day and stores top apps.
     * Returns summary for dashboard display.
     */
    suspend fun collectDayUsage(date: LocalDate): DigitalDayStats {
        if (!hasPermission()) return DigitalDayStats.empty()

        return withContext(Dispatchers.IO) {
            val zoneId = ZoneId.systemDefault()
            val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                endOfDay
            )

            if (stats.isNullOrEmpty()) return@withContext DigitalDayStats.empty()

            val appSamples = stats
                .filter { it.totalTimeInForeground > 1000 }
                .sortedByDescending { it.totalTimeInForeground }
                .map { stat ->
                    val label = try {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(stat.packageName, 0)
                        ).toString()
                    } catch (_: Exception) { stat.packageName }

                    AppUsageSample(
                        id = "${date}_${stat.packageName}",
                        packageName = stat.packageName,
                        appLabel = label,
                        startAt = stat.lastTimeUsed.takeIf { it > 0 } ?: startOfDay,
                        endAt = stat.lastTimeUsed.takeIf { it > 0 } ?: endOfDay,
                        durationMs = stat.totalTimeInForeground
                    )
                }
                .take(20)

            appUsageRepository.insertAll(appSamples)

            val totalMs = appSamples.sumOf { it.durationMs }
            val topApp = appSamples.firstOrNull()

            DigitalDayStats(
                date = date,
                totalScreenTimeMs = totalMs,
                appCount = appSamples.size,
                topAppName = topApp?.appLabel,
                topAppDurationMs = topApp?.durationMs ?: 0L
            )
        }
    }
}

data class DigitalDayStats(
    val date: LocalDate = LocalDate.now(),
    val totalScreenTimeMs: Long = 0L,
    val appCount: Int = 0,
    val topAppName: String? = null,
    val topAppDurationMs: Long = 0L
) {
    companion object {
        fun empty() = DigitalDayStats()
    }

    val totalScreenTimeFormatted: String
        get() {
            val hours = totalScreenTimeMs / 3_600_000
            val minutes = (totalScreenTimeMs % 3_600_000) / 60_000
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

    val topAppFormatted: String
        get() {
            if (topAppName == null) return "—"
            val mins = topAppDurationMs / 60_000
            return "$topAppName · ${mins}min"
        }
}
