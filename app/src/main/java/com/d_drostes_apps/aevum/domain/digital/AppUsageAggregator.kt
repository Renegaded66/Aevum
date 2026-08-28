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
 * EINE Quelle für ALLE Zeiträume: die Event-API (MOVE_TO_FOREGROUND /
 * ACTIVITY_RESUMED → MOVE_TO_BACKGROUND / ACTIVITY_PAUSED / ACTIVITY_STOPPED).
 *
 * M18.62-FIX (User: "heute stimmt, aber die letzten Tage zeigt Aevum
 * 7h+ während Digital Wellbeing 2,5h sagt; Balkenhöhen passen nicht"):
 * `totalTimeInForeground` aus queryUsageStats kumuliert auf vielen
 * Geräten über MEHRERE Tage (OEM-Bug) — die Vergangenheits-Werte waren
 * dadurch massiv zu hoch und die Balken inkonsistent. Die Event-API
 * liefert echte Phasen mit Timestamps; pro Tag wird an der
 * Mitternachts-Grenze geclippt. Das ist die Quelle, die auch Google
 * Digital Wellbeing nutzt.
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

    /** Eine Vordergrund-Phase einer App: [start, end) in epochMillis. */
    private data class ForegroundPhase(
        val packageName: String,
        val start: Long,
        val end: Long
    )

    /**
     * M18.62: Alle Vordergrund-Phasen im Zeitraum [start, end] via
     * Event-API. Offene Phasen (App noch im Vordergrund) werden bis
     * [end] gezählt. Gemeinsame Basis für Heute, Tages- und
     * Zeitraum-Aggregation — ersetzt die kumulativen
     * totalTimeInForeground-Werte (OEM-Bug).
     */
    private fun foregroundPhases(start: Long, end: Long): List<ForegroundPhase> {
        val events = usageStats.queryEvents(start, end) ?: return emptyList()
        val foregroundSince = HashMap<String, Long>()
        val phases = mutableListOf<ForegroundPhase>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // WICHTIG (Balkenhöhen-Fix): Manche OEMs liefern Events
                    // mit Timestamps AUSSERHALB des Query-Fensters (z.B. ein
                    // MOVE_TO_FOREGROUND von gestern 23:50 bei einem Query
                    // ab heute 00:00). Ohne Clipping würde die Phase ab
                    // gestern 23:50 bis jetzt gezählt → der heutige Balken
                    // und die App-Liste zeigen massiv zu viel Zeit.
                    foregroundSince[event.packageName] = maxOf(event.timeStamp, start)
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    foregroundSince.remove(event.packageName)?.let { s ->
                        phases += ForegroundPhase(event.packageName, s, event.timeStamp)
                    }
                }
            }
        }
        // Noch im Vordergrund → bis zum Ende des Zeitraums zählen
        foregroundSince.forEach { (pkg, s) ->
            phases += ForegroundPhase(pkg, s, end)
        }
        return phases
    }

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
                        // Clipping wie in foregroundPhases (OEM-Events außerhalb des Fensters)
                        foregroundSince[event.packageName] = maxOf(event.timeStamp, startOfDay)
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
                        // Clipping wie in foregroundPhases (OEM-Events außerhalb des Fensters)
                        foregroundSince[event.packageName] = maxOf(event.timeStamp, startOfDay)
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
     * M18.62: Event-API statt totalTimeInForeground (OEM-Kumulierung).
     */
    suspend fun rangeUsageByApp(days: Int): List<AppUsage> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).minusDays((days - 1).toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()

            val totals = HashMap<String, Long>()
            foregroundPhases(start, now).forEach { phase ->
                val dur = (phase.end - phase.start).coerceAtLeast(0L)
                totals[phase.packageName] = (totals[phase.packageName] ?: 0L) + dur
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
     * (für die 7/30-Tage-Balken-Statistik).
     *
     * M18.62-FIX: Event-API mit Mitternachts-Clipping. Eine Phase, die
     * über Mitternacht läuft (z.B. 23:50–00:20), wird auf beide Tage
     * aufgeteilt. Vorher: totalTimeInForeground kumulierte über mehrere
     * Tage (OEM-Bug) → Werte viel zu hoch, Balkenhöhen inkonsistent.
     */
    suspend fun dailyTotals(days: Int): List<DailyTotal> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val start = today.minusDays((days - 1).toLong())
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()

            val byDay = HashMap<LocalDate, Long>()
            foregroundPhases(start, now).forEach { phase ->
                // Phase auf Tagesgrenzen clippen
                var cursor = phase.start
                while (cursor < phase.end) {
                    val day = java.time.Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val segEnd = minOf(phase.end, dayEnd)
                    byDay[day] = (byDay[day] ?: 0L) + (segEnd - cursor).coerceAtLeast(0L)
                    cursor = segEnd
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

    /**
     * M18.62: Nutzung pro App an einem BESTIMMTEN Tag.
     *
     * Für die App-Liste in Digital Balance, wenn im Balken-Diagramm ein
     * anderer Tag als heute gewählt ist (User: "beim Balken-Diagramm auf
     * die einzelnen Balken klicken und dann in der Liste die Werte der
     * einzelnen Apps für den angeklickten Tag sehen").
     *
     * M18.62-FIX: Event-API mit Mitternachts-Clipping statt
     * totalTimeInForeground (OEM-Kumulierung über mehrere Tage).
     */
    suspend fun usageByAppForDay(date: LocalDate): List<AppUsage> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val totals = HashMap<String, Long>()
            foregroundPhases(start, end).forEach { phase ->
                val dur = (phase.end - phase.start).coerceAtLeast(0L)
                totals[phase.packageName] = (totals[phase.packageName] ?: 0L) + dur
            }

            totals
                .filter { it.value > 1_000 }
                .map { (pkg, ms) -> AppUsage(pkg, labelFor(pkg), ms) }
                .sortedByDescending { it.durationMs }
        } catch (_: Exception) {
            emptyList()
        }
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
