package com.d_drostes_apps.aevum.automation.apptracking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * M18.151: Einmalige Vordergrund-Abfrage per UsageStats-Events — für
 * Konsumenten AUSSERHALB des [AppTrackingService]. Hauptnutzer:
 * [com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker], der
 * beim Feuern prüfen muss, ob gerade eine getrackte App im Vordergrund ist
 * und „Digital" deshalb NICHT starten darf (User-Spec: die Aufzeichnung
 * gehört dann der zugewiesenen Activity, nicht dem Standard-Digital).
 *
 * Die Event-Faltung ist identisch zu der des Services und liegt in
 * [ForegroundTracker] (unit-getestet). Rückgabe null heißt NICHT zwingend
 * „nichts im Vordergrund", sondern „keine Übergangs-Events im Fenster"
 * (z. B. App seit Stunden ruhig im Vordergrund) — der Aufrufer fällt dann
 * auf den letzten Poll-Schnappschuss des Service zurück
 * ([AppTrackingService.recentPollSnapshot]) und startet Digital nur, wenn
 * auch der nichts Getracktes meldet.
 */
object ForegroundAppProbe {

    /** Fenster der Event-Faltung: Übergänge der letzten 2 Minuten. */
    const val LOOKBACK_MS = 2 * 60_000L

    fun currentForeground(context: Context, lookbackMs: Long = LOOKBACK_MS): String? {
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = try {
            usageStats.queryEvents(now - lookbackMs.coerceAtLeast(1_000L), now)
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents fehlgeschlagen: ${e.message}")
            null
        } ?: return null

        var current: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            current = ForegroundTracker.next(
                current = current,
                packageName = event.packageName ?: "",
                isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED,
                isBackgroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            )
        }
        return current
    }

    private const val TAG = "ForegroundAppProbe"
}
