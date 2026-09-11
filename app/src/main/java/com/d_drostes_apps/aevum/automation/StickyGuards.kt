package com.d_drostes_apps.aevum.automation

// ══════════════════════════════════════════════════════════════════════
// M18.121 (Kanban t_fe3e99da) + M18.122 (Kanban t_55c14376):
// STICKY-GUARDS — Loop-Breaker gegen die START_STICKY-Restart-
// Amplifikation des Hintergrund-Crash-Loops.
//
// PROBLEM (analysiert in t_2edf98d8 §4.2 + Code-Trace): Alle 5
// Foreground-Services laufen mit START_STICKY. Stirbt der Prozess
// (uncaught Crash ODER System-Kill), startet Android JEDEN dieser
// Services automatisch neu. Ein Service, dessen Start-Gate sofort
// stopSelf() zurückgibt (z. B. LiveActivityService ohne Live-Session,
// DriveDetectionService ohne trackbare Session, AppTrackingService
// ohne getrackte Apps, GeofenceFGS ohne aktive Geofences), wird vom
// System in Sekundenabständen wiederbelebt — "crasht alle paar
// Sekunden im Hintergrund".
//
// M18.122-NACHBESSERUNG (Root-Review von e8928e3, 3 Defekte):
//   D2: Der M18.121-Zustand war ein Instanz-Feld → nach Prozess-Kill
//       (NEUE Service-Instanz im NEUEN Prozess) war lastCommand=0
//       und der Kill→Rebirth-Loop wurde NIE gebrochen. Jetzt ist der
//       Guard-Zustand PERSISTIERT (SharedPreferences, pro Service ein
//       Key): der Wall-Clock des letzten echten Starts überlebt den
//       Prozess-Kill. Entscheidung bei null-Action:
//         · persistierter Eintrag < 5 Min alt UND Prozess frisch
//           (< 30 s elapsedRealtime-Delta) → Kill-Rebirth → brechen
//         · kein Eintrag / zu alt → normal starten
//   D3: App-interne Starts bauen jetzt IMMER einen Action-Intent
//       (ACTION_INTERNAL_START). Guard-Break NUR bei action == null —
//       null-Action ist damit wirklich nur System-Rebirth.
//
// Reine JVM-Logik (testbar); die Services nutzen sie über
// [StickyGuardService] + [SharedPrefsStickyGuardPersistence].
// ══════════════════════════════════════════════════════════════════════

/** Fenster, in dem ein persistierter Start-Eintrag als "frisch" gilt:
 *  5 Minuten. Ein Rebirth innerhalb dieses Fensters nach einem echten
 *  Start wird als Kill→Rebirth-Loop gebrochen (wenn der Prozess frisch
 *  ist). Danach ist jeder Sticky-Restart legitim. */
const val STICKY_GUARD_BREAK_WINDOW_MS = 5L * 60 * 1000

/** Prozess-Frische-Schwelle: Ein frischer persistierter Eintrag bricht
 *  NUR, wenn der Prozess jünger als dieser Wert ist (Kill-Rebirth
 *  geschieht in Sekunden — ein alter Prozess, der erst jetzt einen
 *  Sticky-Restart bekommt, ist kein Wiederbelebungs-Loop). */
const val STICKY_GUARD_PROCESS_FRESH_MS = 30_000L

/**
 * Reine Entscheidung (M18.122, D2): Soll dieser Sticky-Rebirth
 * (Intent OHNE Action = System-Wiederbelebung nach Prozess-Kill)
 * gebrochen werden?
 *
 * @param lastLegitStartWallClockMs Persistierter Wall-Clock
 *   (System.currentTimeMillis) des letzten ECHTEN Starts (App-intern
 *   mit Action oder ACTION_INTERNAL_START ausgelöst), 0 = kein
 *   Eintrag. Überlebt den Prozess-Kill (SharedPreferences).
 * @param nowWallClockMs aktuelle Wall-Clock-Zeit.
 * @param processStartRealtime Realtime-Millis des Prozess-Starts
 *   (SystemClock.elapsedRealtime() in onCreate erfasst).
 * @param nowRealtime aktuelle Realtime-Millis.
 * @return true = Kill-Rebirth, brechen (Service beendet sich mit
 *   START_NOT_STICKY). false = normal starten (kein Eintrag, Eintrag
 *   zu alt, oder Prozess lebt schon zu lange).
 */
fun stickyRebirthShouldBreak(
    lastLegitStartWallClockMs: Long,
    nowWallClockMs: Long,
    processStartRealtime: Long,
    nowRealtime: Long,
    breakWindowMs: Long = STICKY_GUARD_BREAK_WINDOW_MS,
    processFreshMs: Long = STICKY_GUARD_PROCESS_FRESH_MS
): Boolean {
    // Kein Eintrag (noch nie echt gestartet) → normal starten.
    if (lastLegitStartWallClockMs <= 0L) return false
    // Eintrag älter als das Fenster → kein Loop mehr → normal starten.
    if (nowWallClockMs - lastLegitStartWallClockMs >= breakWindowMs) return false
    // Frischer Eintrag: Nur brechen, wenn der Prozess gerade erst
    // gestartet ist (Kill→Sofort-Rebirth-Muster: neuer Prozess,
    // Wiederbelebung Sekunden nach dem echten Start). Ein Prozess,
    // der schon lange lebt, ist kein Wiederbelebungs-Loop.
    return nowRealtime - processStartRealtime < processFreshMs
}

/**
 * Persistenz-Schnittstelle für den Guard-Zustand — Android-frei,
 * damit die Entscheidungslogik rein JVM-testbar bleibt.
 *
 * M18.122 (D2): Der Zustand MUSS Prozess-Kills überleben, sonst
 * erkennt der Guard den Kill→Rebirth-Loop nie (Instanzfeld wurde im
 * neuen Prozess immer mit 0 initialisiert).
 */
interface StickyGuardPersistence {
    /** Wall-Clock des letzten echten Starts (0 = noch nie). */
    fun lastLegitStartMs(): Long

    /** Wall-Clock des letzten echten Starts persistieren. */
    fun markLegitStart(nowWallClockMs: Long)
}

/**
 * SharedPreferences-Implementierung. Pro Service ein eigener Prefs-Name
 * → pro Service ein eigener Key (M18.122: "pro Service ein
 * SharedPreferences-Key"). Schreiben asynchron via apply() (persistiert
 * garantiert vor dem nächsten Prozess-Kill, der Rebirth liest synchron).
 */
class SharedPrefsStickyGuardPersistence(
    context: android.content.Context,
    serviceKey: String
) : StickyGuardPersistence {

    private val prefs =
        context.getSharedPreferences("sticky_guard_$serviceKey", android.content.Context.MODE_PRIVATE)

    override fun lastLegitStartMs(): Long = prefs.getLong(KEY_LAST_LEGIT_START_MS, 0L)

    override fun markLegitStart(nowWallClockMs: Long) {
        prefs.edit().putLong(KEY_LAST_LEGIT_START_MS, nowWallClockMs).apply()
    }

    private companion object {
        const val KEY_LAST_LEGIT_START_MS = "sticky_guard_last_legit_start_ms"
    }
}

/**
 * Helper für die Services: persistiert den letzten echten Start und
 * stellt die Sticky-Entscheidung bereit.
 *
 * Verwendung in einem Service — WICHTIG (M18.123-Regression): Die
 * SharedPrefs-Implementierung NIE in einer Property-Initialisierung
 * konstruieren! Android erzeugt Service-Instanzen via newInstance()
 * und ruft attach(context, ...) ERST NACH dem Konstruktor auf — ein
 * getSharedPreferences(this, ...) im Property-Init crasht mit NPE
 * (ContextWrapper.mBase == null) in ActivityThread.handleCreateService,
 * d.h. der Prozess stirbt beim Service-Start (App-Start-Crash).
 * Stattdessen lateinit-Feld + Init in onCreate (Context ist dort
 * garantiert attacht):
 *   private lateinit var stickyGuard: StickyGuardService
 *   // onCreate:
 *   stickyGuard = StickyGuardService(
 *       SharedPrefsStickyGuardPersistence(this, "<serviceKey>")
 *   )
 *   processStartedAtRealtime = SystemClock.elapsedRealtime()
 *   // onStartCommand — REIHENFOLGE (M18.122, D1): ZUERST den
 *   // FGS-Vertrag erfüllen (startForeground), DANN den Guard-Break,
 *   // DANN markLegitStart(). Die Break-ENTSCHEIDUNG kann vorher
 *   // berechnet werden (reiner Lesezugriff), der stopSelf()/Return
 *   // selbst MUSS aber nach startForeground stehen:
 *   val isStickyRebirth = intent?.action == null &&
 *       stickyGuard.shouldBreakStickyRebirth(
 *           processStartedAtRealtime,
 *           SystemClock.elapsedRealtime()
 *       )
 *   startForeground(...)  // FGS-Vertrag zuerst
 *   if (isStickyRebirth) { stopSelf(); return START_NOT_STICKY }
 *   // Bei jedem echten Start (Action oder ACTION_INTERNAL_START):
 *   stickyGuard.markLegitStart()
 */
class StickyGuardService(
    private val persistence: StickyGuardPersistence
) {
    /** Wall-Clock des letzten echten Starts persistieren. */
    fun markLegitStart(nowWallClockMs: Long = System.currentTimeMillis()) {
        persistence.markLegitStart(nowWallClockMs)
    }

    /**
     * Soll dieser Sticky-Rebirth (Intent ohne Action) gebrochen werden?
     * @see [stickyRebirthShouldBreak]
     */
    fun shouldBreakStickyRebirth(
        processStartedAtRealtime: Long,
        nowRealtime: Long,
        nowWallClockMs: Long = System.currentTimeMillis()
    ): Boolean =
        stickyRebirthShouldBreak(
            lastLegitStartWallClockMs = persistence.lastLegitStartMs(),
            nowWallClockMs = nowWallClockMs,
            processStartRealtime = processStartedAtRealtime,
            nowRealtime = nowRealtime
        )
}
