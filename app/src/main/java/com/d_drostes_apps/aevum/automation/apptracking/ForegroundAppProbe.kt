package com.d_drostes_apps.aevum.automation.apptracking

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * M20: Einmalige Vordergrund-Abfrage per UsageStats-Events — für
 * Konsumenten AUSSERHALB des [AppTrackingService]. Hauptnutzer:
 * [com.d_drostes_apps.aevum.automation.screen.ScreenRecordingWorker], der
 * beim Feuern prüfen muss, ob gerade eine getrackte App im Vordergrund ist
 * und „Digital" deshalb NICHT starten darf.
 *
 * Die Event-Faltung ist identisch zu der des Services und liegt in
 * [ForegroundTracker] (unit-getestet, seit M20 auf Activity-Ebene).
 *
 * Zwei Fenster ([resolve]):
 *  - [LOOKBACK_MS] (2 min): Übergänge — erkennt eine gerade geöffnete oder
 *    gerade verlassene App sofort.
 *  - [WIDE_LOOKBACK_MS] (12 h): Baseline — findet den Vordergrund auch,
 *    wenn der letzte Übergang lange zurückliegt (App „ruhig offen", der
 *    klassische Fall aus dem Nutzer-Report „Digital startet statt App").
 *
 * Rückgabe null heißt: KEINE Übergangs-Events im Fenster bzw. keine
 * belastbare Aussage — der Aufrufer kombiniert mit dem letzten
 * Poll-Schnappschuss des Service ([AppTrackingService.currentSnapshot]),
 * die Kombinations-Regel liegt in [AppForegroundResolution].
 */
object ForegroundAppProbe {

    /** Fenster der schnellen Event-Faltung: Übergänge der letzten 2 Minuten. */
    const val LOOKBACK_MS = 2 * 60_000L

    /**
     * M20: Weites Baseline-Fenster — deckt „App seit Stunden ruhig im
     * Vordergrund" ab, wo es in 2 Minuten keine Übergangs-Events gibt.
     * 12 h reichen (über Nacht bleibt der Screen aus; der Wake-Vorgang
     * selbst erzeugt frische Events, die schon das kurze Fenster sieht).
     */
    const val WIDE_LOOKBACK_MS = 12 * 60 * 60 * 1000L

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

        var state = ForegroundState.EMPTY
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            state = ForegroundTracker.next(
                state = state,
                packageName = event.packageName ?: "",
                className = event.className,
                isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED,
                isBackgroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            )
        }
        return state.current?.packageName
    }

    /**
     * M20: Zweistufige Auflösung — kurzes Fenster zuerst, bei leerem
     * Ergebnis die weite 12-h-Baseline. (Vor M20 gab es nur die 2-min-Abfrage;
     * die lieferte im „ruhig offen"-Fall null und der Digital-Start entschied
     * fälschlich gegen die App-Aufzeichnung.)
     */
    fun resolve(context: Context): String? =
        currentForeground(context) ?: currentForeground(context, WIDE_LOOKBACK_MS)

    private const val TAG = "ForegroundAppProbe"
}
