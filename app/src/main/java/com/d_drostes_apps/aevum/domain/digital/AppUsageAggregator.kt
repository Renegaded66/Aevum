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
) : com.d_drostes_apps.aevum.domain.digital.ScreenTimeProvider {
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
    ) {
        val durationMs: Long get() = (end - start).coerceAtLeast(0L)
    }

    /**
     * M18.62: Alle Vordergrund-Phasen im Zeitraum [start, end] via
     * Event-API. Offene Phasen (App noch im Vordergrund) werden bis
     * [end] gezählt. Gemeinsame Basis für Heute, Tages- und
     * Zeitraum-Aggregation — ersetzt die kumulativen
     * totalTimeInForeground-Werte (OEM-Bug).
     *
     * M21: Faltung über [UsageEventFolding] — Activity-Ebene mit
     * Paket-Vereinigung. Der frühere Paket-Schlüssel beendete die Phase
     * beim PAUSED der SPLASH-Activity (App-Start) und verlor damit die
     * komplette Sitzung („Duolingo fehlt in Digital Balance").
     */
    private fun foregroundPhases(start: Long, end: Long): List<ForegroundPhase> {
        val events = usageStats.queryEvents(start, end) ?: return emptyList()
        val folder = UsageEventFolding.Folder(clipStart = start)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    folder.onForeground(event.packageName ?: "", event.className, event.timeStamp)
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    folder.onBackground(event.packageName ?: "", event.className, event.timeStamp)
            }
        }
        return folder.finish(end).map { ForegroundPhase(it.packageName, it.start, it.end) }
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
            // M21: gemeinsame Faltung (Activity-Ebene, Paket-Vereinigung).
            val phases = foregroundPhases(startOfDay, now)
            val hourly = LongArray(24)
            phases.forEach { phase ->
                // M21: Stundenzuordnung stückweise — eine 3-Stunden-Sitzung
                // zählt jetzt in jede ihrer Stunden statt komplett in die
                // Startstunde (ehrlicheres Balken-Diagramm).
                var cursor = phase.start
                while (cursor < phase.end) {
                    // Stundengrenze in der LOKALEN Zeitzone (nicht UTC —
                    // sonst wären die Balken in Halbstunden-Zonen verschoben).
                    val zoned = java.time.Instant.ofEpochMilli(cursor).atZone(zone)
                    val hourEnd = zoned.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .plusHours(1).toInstant().toEpochMilli()
                    val segEnd = minOf(phase.end, hourEnd)
                    hourly[zoned.hour.coerceIn(0, 23)] += (segEnd - cursor).coerceAtLeast(0L)
                    cursor = segEnd
                }
            }
            TodayDetail(
                totalMs = phases.sumOf { it.durationMs },
                unlockCount = foregroundEventCount(startOfDay, now),
                hourlyMs = hourly.toList()
            )
        } catch (_: Exception) {
            TodayDetail(0, 0, List(24) { 0L })
        }
    }

    /** M21: Foreground-Transitionen (Unlocks) im Zeitraum. */
    private fun foregroundEventCount(start: Long, end: Long): Int {
        val events = usageStats.queryEvents(start, end) ?: return 0
        var count = 0
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                count++
            }
        }
        return count
    }

    /**
     * M18.93v7-FIX (User: "Dashboard 33 Min vs Balance 25 Min vs System-App
     * 31 Min"): GESAMTE App-Vordergrund-Zeit HEUTE via Event-API.
     *
     * Das Dashboard nutzte bisher UsageStatsCollector.screenTimeTodayMs()
     * (SCREEN_INTERACTIVE/SCREEN_NON_INTERACTIVE = Display-an-Zeit inkl.
     * Lock-Screen/Standby) — das ist eine ANDERE Größe als die
     * App-Foreground-Zeit (MOVE_TO_FOREGROUND/BACKGROUND), die der
     * Digital-Balance-Tab und Google Digital Wellbeing anzeigen. Dadurch
     * wichen Dashboard- und Balance-Wert strukturell voneinander ab.
     *
     * Diese Funktion liefert die Summe ALLER Vordergrund-Phasen ohne den
     * >1s-Filter von todayUsageByApp() (Kurz-Phasen sind echte Nutzung).
     * Konsistent mit todayUsageByApp/todayDetail: gleiche Events, gleiche
     * Mitternachtsgrenze.
     */
    suspend fun todayForegroundTotalMs(): Long = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            // M21: gemeinsame Faltung — identische Basis wie die App-Liste.
            foregroundPhases(startOfDay, now).sumOf { it.durationMs }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Nutzung pro App heute (seit Mitternacht) via Event-API.
     * Liefert nur Apps mit > 0 Nutzung, sortiert absteigend.
     */
    suspend fun todayUsageByApp(): List<AppUsage> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()

            // M21: gemeinsame Faltung (Activity-Ebene) — eine App, deren
            // Splash-Activity ein PAUSED schickt, bleibt vollständig gezählt.
            val totals = HashMap<String, Long>()
            foregroundPhases(startOfDay, now).forEach { phase ->
                totals[phase.packageName] = (totals[phase.packageName] ?: 0L) + phase.durationMs
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
     * M18.119: Gesamt-Bildschirmzeit an einem BESTIMMTEN Tag via Event-API
     * (gleiche Phase-Logik wie dailyTotals, inkl. Mitternachts-Clipping
     * und ohne >1s-Filter — identische Basis wie dailyTotals/heute).
     * Für die Bildschirmzeit-Kachel im Dashboard: Der Tag-Swipe soll die
     * Bildschirmzeit des GEWÄHLTEN Tags zeigen, nicht immer die von heute
     * (todayForegroundTotalMs bleibt für „jetzt"-Sichten bestehen).
     */
    suspend fun foregroundTotalForDay(date: LocalDate): Long = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Zukünftige Tage haben (noch) keine Daten:
            val effectiveEnd = minOf(end, System.currentTimeMillis())
            if (effectiveEnd <= start) return@withContext 0L
            foregroundTotalForRange(start, effectiveEnd)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * M18.138: Bildschirmzeit über einen freien Zeitraum [start, end).
     *
     * Grundlage für die Statistik-Zeiträume (Insights Woche/Monat, Weekly
     * Review): Die Aufzeichnungs-Sessions (SCREEN_AUTO) sind aus der
     * Statistik ausgeschlossen — an ihrer Stelle zählt genau dieser
     * Digital-Balance-Wert. EIN Aufruf statt einer Abfrage pro Tag.
     */
    suspend fun foregroundTotalForRange(start: Long, end: Long): Long = withContext(Dispatchers.IO) {
        try {
            val effectiveEnd = minOf(end, System.currentTimeMillis())
            if (effectiveEnd <= start) return@withContext 0L
            foregroundPhases(start, effectiveEnd).sumOf { it.end - it.start }.coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * M18.138: Bildschirmzeit je Tag für einen beliebigen Zeitraum.
     *
     * Grundlage für Statistik-Sichten, die die Aufzeichnungs-Sessions
     * (SCREEN_AUTO) durch die echte Bildschirmzeit ersetzen — sie brauchen
     * den Balance-Wert PRO TAG, damit Heatmap und Tagesdurchschnitte den
     * Betrag dem richtigen Tag zuordnen.
     *
     * Gleiche Phase-Logik und Mitternachts-Clipping wie [dailyTotals].
     *
     * @param startDate erster Tag (inklusive)
     * @param endDateExclusive Tag nach dem letzten
     * @return Map Tag → Bildschirmzeit in ms. 0-Tage sind enthalten.
     */
    override suspend fun dailyTotalsForRange(
        startDate: LocalDate,
        endDateExclusive: LocalDate
    ): Map<LocalDate, Long> = withContext(Dispatchers.IO) {
        try {
            val zone = ZoneId.systemDefault()
            val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = endDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            val effectiveEnd = minOf(end, System.currentTimeMillis())

            val byDay = HashMap<LocalDate, Long>()
            var cursor = startDate
            while (cursor.isBefore(endDateExclusive)) {
                byDay[cursor] = 0L
                cursor = cursor.plusDays(1)
            }
            if (effectiveEnd <= start) return@withContext byDay

            foregroundPhases(start, effectiveEnd).forEach { phase ->
                var phaseCursor = phase.start
                while (phaseCursor < phase.end) {
                    val day = java.time.Instant.ofEpochMilli(phaseCursor).atZone(zone).toLocalDate()
                    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val segEnd = minOf(phase.end, dayEnd)
                    if (byDay.containsKey(day)) {
                        byDay[day] = (byDay[day] ?: 0L) + (segEnd - phaseCursor).coerceAtLeast(0L)
                    }
                    phaseCursor = segEnd
                }
            }
            byDay
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Nutzung pro App an einem BESTIMMTEN Tag.
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
