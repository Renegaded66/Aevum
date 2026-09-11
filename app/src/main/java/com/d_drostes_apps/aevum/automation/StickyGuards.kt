package com.d_drostes_apps.aevum.automation

// ══════════════════════════════════════════════════════════════════════
// M18.121 (Kanban t_fe3e99da): STICKY-GUARDS — Loop-Breaker gegen die
// START_STICKY-Restart-Amplifikation des Hintergrund-Crash-Loops.
//
// PROBLEM (analysiert in t_2edf98d8 §4.2 + Code-Trace): Alle 5
// Foreground-Services laufen mit START_STICKY. Stirbt der Prozess
// (uncaught Crash ODER System-Kill), startet Android JEDEN dieser
// Services automatisch neu. Ein Service, dessen Start-Gate sofort
// stopSelf() zurückgibt (z. B. LiveActivityService ohne Live-Session,
// DriveDetectionService ohne trackbare Session, AppTrackingService
// ohne getrackte Apps, GeofenceFGS ohne aktive Geofences), wird vom
// System in Sekundenabständen wiederbelebt — "crasht alle paar
// Sekunden im Hintergrund", ohne dass der eigentliche Auslöser den
// Prozess je verlassen müsste. Bei einem Crash-Kill (nicht System-
// Kill) ist der Restart der FGS der ZWEITE Loop-Arm neben den
// Workers/Receivern, die den Crash erneut auslösen.
//
// LÖSUNG: Sticky-Guards in jedem FGS. Wenn ein Sticky-Restart (kein
// expliziter Intent mit Action, sondern System-Rebirth nach Kill)
// innerhalb des Cooldown-Fensters ankommt, wird er NICHT ausgeführt —
// der Service beendet sich sofort mit START_NOT_STICKY. Das bricht
// die Wiederbelebungs-Schleife: Der nächste echte Anlass (App-Start,
// AR-/Geofence-Event, Worker) startet den Service regulär.
//
// Reine JVM-Logik (testbar); die Services nutzen sie über die
// statische [StickyGuardService]-Helper.
// ══════════════════════════════════════════════════════════════════════

/**
 * Reine Entscheidung: Darf dieser Sticky-Restart ausgeführt werden?
 *
 * @param lastCommandReceivedAt Zeitstempel des letzten echten
 *   Service-Starts (expliziter Intent mit Action) — 0 = noch nie.
 *   Wird vom Service bei JEDEM onStartCommand mit Action gesetzt
 *   (auch bei null-Action, wenn der Start von der App selbst kam,
 *   siehe [StickyGuardService]).
 * @param processStartRealtime Realtime-Millis des Prozess-Starts
 *   (SystemClock.elapsedRealtime() in onCreate erfasst) — der Sticky-
 *   Restart kommt maximal ~Sekunden nach dem Prozess-Start an.
 * @param nowRealtime aktuelle Realtime-Millis.
 * @param cooldownMs Restart-Cooldown-Fenster.
 * @return true = Sticky-Restart darf laufen (letzter echter Start
 *   liegt lange genug zurück ODER der Prozess läuft schon zu lange,
 *   um ein frischer Kill-Restart zu sein). false = Sticky-Restart
 *   verwerfen (START_NOT_STICKY sofort).
 */
fun stickyRestartAllowed(
    lastCommandReceivedAt: Long,
    processStartRealtime: Long,
    nowRealtime: Long,
    cooldownMs: Long = StickyGuardService.DEFAULT_RESTART_COOLDOWN_MS
): Boolean {
    // Kein echter Start bekannt (frischer Prozess, alles normal).
    if (lastCommandReceivedAt <= 0L) return true
    // Nach dem Cooldown-Fenster ist jeder Sticky-Restart legitim
    // (z. B. App lief den ganzen Tag, System killte sie einmal).
    if (nowRealtime - lastCommandReceivedAt >= cooldownMs) return true
    // Innerhalb des Fensters: Nur brechen, wenn der Prozess gerade
    // erst gestartet ist (Kill→Sofort-Rebirth-Muster). Ein Prozess,
    // der schon lange lebt, aber erst jetzt einen Sticky-Restart
    // bekommt, ist kein Wiederbelebungs-Loop.
    return nowRealtime - processStartRealtime > RESTART_GUARD_PROCESS_AGE_MS
}

/** Prozess-Alters-Schwelle: Ein Sticky-Restart innerhalb des Cooldown-
 *  Fensters bricht NUR, wenn der Prozess jünger als dieser Wert ist
 *  (Kill-Rebirth geschieht in Sekunden). */
const val RESTART_GUARD_PROCESS_AGE_MS = 30_000L

/**
 * Helper für die Services: erfasst [lastCommandReceivedAt] und stellt
 * die Sticky-Entscheidung bereit. Bewusst eine kleine, testbare
 * Bausteine-Klasse ohne Android-Abhängigkeiten (die Services reichen
 * SystemClock-Werte von außen herein).
 *
 * Verwendung in einem Service:
 *   // Feld:
 *   private val stickyGuard = StickyGuardService()
 *   // onCreate:
 *   processStartedAtRealtime = SystemClock.elapsedRealtime()
 *   // onStartCommand (NACH der Action-Verarbeitung, VOR dem Sticky-
 *   // Return):
 *   val isStickyRebirth = intent?.action == null && stickyGuard.shouldBreakStickyRestart(
 *       SystemClock.elapsedRealtime(), processStartedAtRealtime
 *   )
 *   if (isStickyRebirth) return START_NOT_STICKY
 *   // Bei JEDEM echten Start (egal ob Action oder null vom App-Code):
 *   stickyGuard.markCommandReceived(SystemClock.elapsedRealtime())
 */
class StickyGuardService {
    @Volatile
    private var lastCommandReceivedAtRealtime: Long = 0L

    /** Zeitstempel des letzten echten Starts (Realtime) markieren. */
    fun markCommandReceived(nowRealtime: Long) {
        lastCommandReceivedAtRealtime = nowRealtime
    }

    /**
     * Soll dieser Sticky-Restart (Intent ohne Action) gebrochen werden?
     * @see [stickyRestartAllowed]
     */
    fun shouldBreakStickyRestart(
        nowRealtime: Long,
        processStartedAtRealtime: Long,
        cooldownMs: Long = DEFAULT_RESTART_COOLDOWN_MS
    ): Boolean =
        !stickyRestartAllowed(
            lastCommandReceivedAtRealtime,
            processStartedAtRealtime,
            nowRealtime,
            cooldownMs
        )

    companion object {
        /** Fenster, in dem Sticky-Restarts als Kill-Rebirth gebrochen
         *  werden: 5 Minuten ab dem letzten echten Start. Danach ist
         *  jeder Sticky-Restart legitim (kein Loop mehr). */
        const val DEFAULT_RESTART_COOLDOWN_MS = 5L * 60 * 1000
    }
}
