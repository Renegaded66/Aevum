package com.d_drostes_apps.aevum.automation.geofence

// ══════════════════════════════════════════════════════════════════════
// M18.121 (Kanban t_fe3e99da): GEOFENCE-RE-START-DROSSEL — Loop-Breaker
// gegen den Hintergrund-Crash-Loop nach dem Tracking-Szenario.
//
// PROBLEM (analysiert in t_2edf98d8 §4.2 + Code-Trace): Nach einer
// durch Bug 1 korrumpierten Session (startAt in der Vergangenheit,
// endAt null) kollidiert der DriveEndGeofenceRestarter mit dem
// DriveStartWorker/GeofenceTransitionProcessor. Jeder Drive-Stop ruft
// schedule() auf (REPLACE — nur der letzte zählt), der Worker startet
// die Geofence-Session neu; stoppt die nächste Automatik sie sofort
// (Überlappungs-Trim, Watchdog), feuert der nächste Stop-Pfad erneut →
// Session-Flackern + LiveActivityService-Restore-Race in Sekunden-
// Abständen. Zusammen mit der START_STICKY-Amplifikation (Prozess-Kill
// → alle 5 FGS werden vom System neu gestartet) entsteht der
// "crasht alle paar Sekunden im Hintergrund"-Loop.
//
// LÖSUNG: Reine Drossel-Entscheidung (JVM-testbar, keine Android-
// Abhängigkeiten). Pro Geofence gibt es maximal EINEN Auto-Re-Start
// innerhalb [cooldownMs]. Wird die Schwelle [maxStartsInWindowMs]
// überschritten, pausiert der Re-Start für [cooldownMs] komplett.
// Die Drossel ist IN-MEMORY (Singleton im Hilt-Graph): Sie bricht
// das Flackern innerhalb einer Prozess-Lebensdauer — die Kette, die
// den Crash-Loop speist, läuft komplett im selben Prozess
// (WorkManager + Services). Nach einem Prozess-Kill beginnt der
// Zustand frisch; die Cooldowns der Erkennung (M18.84) und die
// Sticky-Guards verhindern dort das Wiederanfachen.
// ══════════════════════════════════════════════════════════════════════

/**
 * Reine Drossel-Entscheidung für den Geofence-Auto-Re-Start nach
 * Fahrt-Ende. Thread-sicher, JVM-testbar.
 */
class GeofenceRestartThrottle(
    /** Zeitfenster für die Start-Zählung (ms). */
    private val windowMs: Long = GeofenceRestartThrottle.DEFAULT_WINDOW_MS,
    /** Maximale Restarts pro Geofence im Fenster. */
    private val maxStartsInWindow: Int = GeofenceRestartThrottle.DEFAULT_MAX_STARTS,
    /** Vollständige Pause, wenn die Schwelle erreicht wurde (ms). */
    private val cooldownMs: Long = GeofenceRestartThrottle.DEFAULT_COOLDOWN_MS,
    /** Clock-Provider für Tests (default: System.currentTimeMillis). */
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val startsByGeofence = HashMap<String, ArrayDeque<Long>>()
    private val suppressedUntilByGeofence = HashMap<String, Long>()

    /**
     * Darf für [geofenceId] jetzt ein Auto-Re-Start ausgelöst werden?
     * Nebenwirkung: Ein `true` registriert den Start (Zählung).
     */
    @Synchronized
    fun allowRestart(geofenceId: String): Boolean {
        val now = clock()
        val suppressedUntil = suppressedUntilByGeofence[geofenceId]
        if (suppressedUntil != null && now < suppressedUntil) {
            return false
        }
        val starts = startsByGeofence.getOrPut(geofenceId) { ArrayDeque() }
        // Fenster-Cleanup: Starts außerhalb des Fensters verfallen.
        while (starts.isNotEmpty() && starts.first() <= now - windowMs) {
            starts.removeFirst()
        }
        if (starts.size >= maxStartsInWindow) {
            // Schwelle erreicht → Cooldown setzen, Zähler zurücksetzen.
            suppressedUntilByGeofence[geofenceId] = now + cooldownMs
            starts.clear()
            return false
        }
        starts.addLast(now)
        return true
    }

    /** Prüft, ob [geofenceId] aktuell gedrosselt ist (ohne Zählung). */
    @Synchronized
    fun isSuppressed(geofenceId: String): Boolean {
        val until = suppressedUntilByGeofence[geofenceId] ?: return false
        return clock() < until
    }

    fun reset() {
        synchronized(this) {
            startsByGeofence.clear()
            suppressedUntilByGeofence.clear()
        }
    }

    companion object {
        /** Fenster: 10 Minuten — deckt das Flacker-Fenster der
         *  Stop→Restart-Kaskade ab (Watchdog 5 Min + Worker-Latenz). */
        const val DEFAULT_WINDOW_MS = 10L * 60 * 1000
        /** 2 Starts pro Fenster pro Geofence — der erste Re-Start nach
         *  einer echten Fahrt ist legitim, der zweite in < 10 Min ist
         *  praktisch immer Flackern. */
        const val DEFAULT_MAX_STARTS = 2
        /** Vollständige Pause nach überschrittener Schwelle: 30 Minuten. */
        const val DEFAULT_COOLDOWN_MS = 30L * 60 * 1000
    }
}
