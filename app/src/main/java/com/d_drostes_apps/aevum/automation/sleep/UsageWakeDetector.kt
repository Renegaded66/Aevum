package com.d_drostes_apps.aevum.automation.sleep

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.45-FIX (User-Feedback: "Ich habe das Handy um 07:50 entsperrt,
 * aber Aevum sagt, ich hätte bis 07:57 geschlafen"):
 *
 * Seit Android 14 werden SCREEN_ON-Broadcasts nicht mehr an
 * Hintergrund-Apps geliefert. Aevum sieht daher nur noch den
 * LIFECYCLE-Fallback (App-Öffnung, hier 07:57) — der echte Wake
 * (erste Bildschirm-Nutzung, 07:50) ging verloren.
 *
 * UsageStatsManager kennt die WAHRHEIT: `lastTimeUsed` pro App.
 * Das früheste `lastTimeUsed` nach dem Screen-Off ist die echte
 * erste Nutzung am Morgen. Diese Quelle wird in
 * [SleepFusionEngine.detectScreenSleepWindow] und
 * [SleepHeuristicEngine] als Wake-Korrektur genutzt, wenn sie
 * VOR dem LIFECYCLE-Zeitpunkt liegt.
 *
 * M18.58 (User: "um 9:00 Handy benutzt, um 9:05 Aevum geöffnet,
 * Aufstehzeit war 9:05 statt 9:00"): `lastTimeUsed` ist auf vielen
 * Geräten nur stunden-/tages-granular und wird von OEMs teils erst
 * beim nächsten App-Fokus aktualisiert. Präziser ist die Event-API:
 * [UsageEvents.queryEvents] liefert echte MOVE_TO_FOREGROUND /
 * ACTIVITY_RESUMED-Timestamps mit Minuten-Genauigkeit. Der Detector
 * nutzt jetzt ZUERST die Events und fällt nur bei leerem Ergebnis
 * auf lastTimeUsed zurück.
 */
@Singleton
class UsageWakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Liefert die früheste echte App-Nutzung nach [fromMs].
     * Null, wenn keine Permission, kein Sample oder keine Nutzung danach.
     */
    fun firstUsageSince(fromMs: Long): Long? {
        return try {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()

            // M18.58: Präziser Pfad über UsageEvents — echte Foreground-
            // Wechsel mit exakten Timestamps (Minuten-Genauigkeit).
            val eventWake = queryFirstForegroundEvent(mgr, fromMs, now)
            if (eventWake != null) return eventWake

            // Fallback: lastTimeUsed pro App (grob, aber besser als nichts).
            val stats = mgr.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                fromMs - 60L * 60 * 1000, // 1h Puffer vor dem Screen-Off
                now
            ) ?: return null
            stats.asSequence()
                .filter { it.totalTimeInForeground > 0 }
                .map { it.lastTimeUsed }
                .filter { it > fromMs && it <= now }
                .minOrNull()
        } catch (_: Exception) {
            null // keine Permission / OEM-Blockade — nie crashen
        }
    }

    /**
     * M18.58: Schlaf-Fenster aus UsageStats-Events.
     *
     * User-Feedback ("die App zeichnet Bildschirmzeiten nur auf, wenn sie
     * geöffnet ist"): Seit Android 14 werden SCREEN_ON/OFF-Broadcasts an
     * Hintergrund-Apps nicht mehr geliefert — wenn Aevum abends geschlossen
     * ist, fehlt das OFF-Event und die Heuristik hat kein Schlaf-Fenster.
     *
     * UsageStats liefert die Wahrheit über App-Nutzung. Die längste Lücke
     * zwischen Foreground-Events im Schlaf-Fenster (Start 20:00–02:00, Ende
     * 04:00–11:59, Dauer 3–14h) ist die Screen-off-Phase = Schlaf.
     */
    fun longestSleepWindow(): UsageSleepWindow? {
        return try {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            val yesterday18 = now.minusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()
            val today13 = now.withHour(13).withMinute(0).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()
            val events = mgr.queryEvents(yesterday18, today13) ?: return null

            // Alle Foreground-Zeitpunkte sammeln (App wurde benutzt = Screen an)
            val foregroundTimes = mutableListOf<Long>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                if (isForeground) foregroundTimes.add(event.timeStamp)
            }
            foregroundTimes.sort()
            if (foregroundTimes.size < 2) return null

            // Längste Lücke zwischen aufeinanderfolgenden Nutzungen
            var bestStart = 0L
            var bestEnd = 0L
            var bestDur = 0L
            for (i in 1 until foregroundTimes.size) {
                val gapStart = foregroundTimes[i - 1]
                val gapEnd = foregroundTimes[i]
                val gapDur = gapEnd - gapStart
                if (gapDur > bestDur) {
                    bestDur = gapDur
                    bestStart = gapStart
                    bestEnd = gapEnd
                }
            }

            // Schlaf-Filter: Start 20:00–02:00, Ende 04:00–11:59, Dauer 3–14h
            val startLocal = java.time.Instant.ofEpochMilli(bestStart).atZone(zone)
            val endLocal = java.time.Instant.ofEpochMilli(bestEnd).atZone(zone)
            val startHour = startLocal.hour
            val endHour = endLocal.hour
            val hours = bestDur / 3_600_000.0
            val validStart = startHour >= 20 || startHour < 2
            val validEnd = endHour in 4..11
            if (!validStart || !validEnd || hours < 3.0 || hours > 14.0) return null

            UsageSleepWindow(bestStart, bestEnd)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFirstForegroundEvent(mgr: UsageStatsManager, fromMs: Long, now: Long): Long? {
        val events = mgr.queryEvents(fromMs - 60L * 60 * 1000, now) ?: return null
        val event = UsageEvents.Event()
        var earliest: Long? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (!isForeground) continue
            val ts = event.timeStamp
            if (ts > fromMs && ts <= now && (earliest == null || ts < earliest!!)) {
                earliest = ts
            }
        }
        return earliest
    }
}

/**
 * M18.58: Ergebnis von [UsageWakeDetector.longestSleepWindow] — die
 * Screen-off-Phase (Schlaf) aus der UsageStats-Wahrheit.
 */
data class UsageSleepWindow(
    val startMs: Long,
    val endMs: Long
)
