package com.d_drostes_apps.aevum.domain.digital

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.61: Digital Balance — Nutzungs-Aggregation pro App.
 *
 * Zwei Quellen:
 *  - HEUTE: Event-API (MOVE_TO_FOREGROUND / ACTIVITY_RESUMED) — präzise,
 *    weil `totalTimeInForeground` auf vielen Geräten über mehrere Tage
 *    kumuliert (OEM-Bug) und Screen-off-Zeit mitzählt.
 *  - ZEITRAUM (7/30 Tage): queryUsageStats(INTERVAL_DAILY) — pro Tag
 *    aggregiert, für Trends/Durchschnitte ausreichend genau.
 */
@Singleton
class AppUsageAggregator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val usageStats by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    data class AppUsage(
        val packageName: String,
        val appLabel: String,
        val durationMs: Long
    )

    data class DailyTotal(
        val date: LocalDate,
        val totalMs: Long
    )

    data class TodayDetail(
        val totalMs: Long,
        val unlockCount: Int,
        val hourlyMs: List<Long> // 24 Einträge, ms pro Stunde
    )

    /**
     * M18.61: Detaillierte Heute-Statistik: Gesamtzeit, Unlocks
     * (Anzahl der MOVE_TO_FOREGROUND-Events) und Stunden-Breakdown
     * (24 Balken — Google-Digital-Wellbeing-Muster).
     */
    suspend fun todayDetail(): TodayDetail = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val events = usageStats.queryEvents(startOfDay, now) ?: return@withContext TodayDetail(0, 0, List(24) { 0L })

            val foregroundSince = HashMap<String, Long>()
            val totals = HashMap<String, Long>()
            val hourly = LongArray(24)
            var unlocks = 0
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundSince[event.packageName] = event.timeStamp
                        unlocks++
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        foregroundSince.remove(event.packageName)?.let { start ->
                            val dur = (event.timeStamp - start).coerceAtLeast(0L)
                            totals[event.packageName] = (totals[event.packageName] ?: 0L) + dur
                            // Stunden-Bucket: Start-Stunde der Nutzung
                            val hour = java.time.Instant.ofEpochMilli(start).atZone(zone).hour
                            hourly[hour.coerceIn(0, 23)] += dur
                        }
                    }
                }
            }
            foregroundSince.forEach { (pkg, start) ->
                val dur = (now - start).coerceAtLeast(0L)
                totals[pkg] = (totals[pkg] ?: 0L) + dur
                val hour = java.time.Instant.ofEpochMilli(start).atZone(zone).hour
                hourly[hour.coerceIn(0, 23)] += dur
            }

            TodayDetail(
                totalMs = totals.values.sum(),
                unlockCount = unlocks,
                hourlyMs = hourly.toList()
            )
        } catch (_: Exception) {
            TodayDetail(0, 0, List(24) { 0L })
        }
    }

    /**
     * Nutzung pro App HEUTE (seit Mitternacht) via Event-API.
     * Liefert nur Apps mit > 0 Nutzung, sortiert absteigend.
     */
    suspend fun todayUsageByApp(): List<AppUsage> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val events = usageStats.queryEvents(startOfDay, now) ?: return@withContext emptyList()

            // Pro App: letzter Foreground-Zeitpunkt
            val foregroundSince = HashMap<String, Long>()
            val totals = HashMap<String, Long>()
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundSince[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        foregroundSince.remove(event.packageName)?.let { start ->
                            totals[event.packageName] = (totals[event.packageName] ?: 0L) +
                                (event.timeStamp - start).coerceAtLeast(0L)
                        }
                    }
                }
            }
            // Noch im Vordergrund → bis jetzt zählen
            foregroundSince.forEach { (pkg, start) ->
                totals[pkg] = (totals[pkg] ?: 0L) + (now - start).coerceAtLeast(0L)
            }

            totals
                .filter { it.value > 1_000 }
                .map { (pkg, ms) -> AppUsage(pkg, labelFor(pkg), ms) }
                .sortedByDescending { it.durationMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Nutzung pro App über die letzten [days] Tage (inkl. heute).
     * Aggregiert queryUsageStats(INTERVAL_DAILY) pro Package.
     */
    suspend fun rangeUsageByApp(days: Int): List<AppUsage> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).minusDays((days - 1).toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val stats = usageStats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                ?: return@withContext emptyList()

            val totals = HashMap<String, Long>()
            stats.forEach { stat ->
                if (stat.totalTimeInForeground > 0) {
                    totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
                }
            }

            totals
                .filter { it.value > 1_000 }
                .map { (pkg, ms) -> AppUsage(pkg, labelFor(pkg), ms) }
                .sortedByDescending { it.durationMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Gesamt-Bildschirmzeit pro Tag für die letzten [days] Tage
     * (für die 30-Tage-Balken-Statistik).
     */
    suspend fun dailyTotals(days: Int): List<DailyTotal> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val start = today.minusDays((days - 1).toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val stats = usageStats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                ?: return@withContext emptyList()

            // queryUsageStats liefert pro Tag+App einen Eintrag — wir
            // gruppieren nach Tag (firstTimeStamp = Beginn des Tages-Intervalls)
            // und summieren.
            val byDay = HashMap<LocalDate, Long>()
            stats.forEach { stat ->
                if (stat.totalTimeInForeground > 0) {
                    val day = java.time.Instant.ofEpochMilli(stat.firstTimeStamp)
                        .atZone(zone).toLocalDate()
                    byDay[day] = (byDay[day] ?: 0L) + stat.totalTimeInForeground
                }
            }

            (0 until days).map { i ->
                val date = today.minusDays(i.toLong())
                DailyTotal(date, byDay[date] ?: 0L)
            }.reversed()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Nutzung einer einzelnen App heute (für die Sperr-Logik).
     */
    suspend fun usageTodayFor(packageName: String): Long {
        return todayUsageByApp().firstOrNull { it.packageName == packageName }?.durationMs ?: 0L
    }

    private fun labelFor(packageName: String): String {
        return try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
