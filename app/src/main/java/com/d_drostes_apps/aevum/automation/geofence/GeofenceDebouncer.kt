package com.d_drostes_apps.aevum.automation.geofence

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M11.2: Zentrale Stabilisierungs-Entprellung für Geofence-Trigger.
 *
 * Jeder Übergang (ENTER/EXIT) wird erst bestätigt, wenn der Zustand
 * für STABILIZATION_MS (2 Minuten) konstant bleibt.
 *
 * Ablauf:
 *   1. ENTER erkannt → wird als "pending" gespeichert
 *   2. Wenn innerhalb der Stabilisierungszeit ein EXIT kommt → ENTER verworfen
 *   3. Nach Ablauf der Stabilisierungszeit → GeofenceStabilizationWorker
 *      ruft confirmPending() auf → ENTER wird emitted
 *
 * Thread-safe via ConcurrentHashMap.
 */
@Singleton
class GeofenceDebouncer @Inject constructor() {

    private val pendingByGeofence = ConcurrentHashMap<String, PendingTransition>()
    // M18.43: confirmedState speichert jetzt auch den Zeitstempel der
    // Bestätigung — der Echo-Schutz braucht ein Zeitfenster (siehe shouldEmit).
    private val confirmedStateByGeofence = ConcurrentHashMap<String, Pair<GeofenceTransition, Long>>()
    // M16.6: Korrelations-Tracker. Wenn mehrere Geofences innerhalb eines
    // engen Zeitfensters einen EXIT feuern, ist das sehr wahrscheinlich
    // GPS-Flattern am Rand mehrerer Geofences (z.B. nachts wenn der User
    // eigentlich schläft). Wir registrieren hier den letzten konsolidierten
    // EXIT-Zeitpunkt pro Geofence und unterdrücken parallele EXITs.
    private val recentExitTimestampsMs = ConcurrentHashMap<String, Long>()
    private val recentExitToGeofence = ArrayDeque<Pair<Long, String>>()

    /**
     * M16.6: Korrelations-Schutz für parallele EXITs.
     *
     * Wenn innerhalb von [CONSOLIDATION_WINDOW_MS] (90s) mehr als
     * [CONSOLIDATION_THRESHOLD] EXIT-Trigger für unterschiedliche Geofences
     * eintreffen, behandeln wir das als "GPS-Flattern-Burst" und unterdrücken
     * alle bis auf den ersten.
     *
     * @return `true` wenn der Trigger konsolidiert (also unterdrückt) wurde.
     */
    fun isConsolidatedExit(geofenceId: String, transition: GeofenceTransition, now: Long): Boolean {
        if (transition != GeofenceTransition.Exit) return false
        // Cleanup: alte Einträge entfernen (alles vor dem Fenster).
        val cutoff = now - CONSOLIDATION_WINDOW_MS
        while (recentExitToGeofence.isNotEmpty() && recentExitToGeofence.first().first < cutoff) {
            val old = recentExitToGeofence.removeFirst()
            recentExitTimestampsMs.remove(old.second)
        }
        // Wenn dieser Geofence schon einen aktiven EXIT hat, ist es ein Echo.
        recentExitTimestampsMs[geofenceId]?.let { existingTs ->
            if (now - existingTs < CONSOLIDATION_WINDOW_MS) return true
        }
        // Wenn bereits mehrere EXITs in diesem Fenster für andere Geofences
        // da sind, ist es GPS-Flattern — diesen unterdrücken.
        val otherExits = recentExitTimestampsMs.size
        if (otherExits >= CONSOLIDATION_THRESHOLD) {
            return true
        }
        // Andernfalls: diesen EXIT registrieren.
        recentExitTimestampsMs[geofenceId] = now
        recentExitToGeofence.addLast(now to geofenceId)
        return false
    }

    /**
     * Wird vom BroadcastReceiver bei jedem Geofence-Event aufgerufen.
     *
     * - Wenn kein pendenter Übergang existiert → neuen pendenten starten,
     *   StabilizationWorker schedulen, false zurückgeben (nicht sofort emit).
     * - Wenn ein pendenter Übergang existiert und der neue übereinstimmt →
     *   false (warten auf StabilizationWorker).
     * - Wenn ein pendenter Übergang existiert und der neue abweicht →
     *   pendenten verwerfen, neuen starten, false zurückgeben.
     */
    fun shouldEmit(geofenceId: String, transition: GeofenceTransition, now: Long): Boolean {
        val pending = pendingByGeofence[geofenceId]

        // GPS-Flattern: pendent widerspricht neuem → verwerfen
        if (pending != null && pending.transition != transition) {
            pendingByGeofence.remove(geofenceId)
        }

        val current = pendingByGeofence[geofenceId]
        if (current == null) {
            // Echo-Schutz: gleicher Übergang wie bereits bestätigt → suppress.
            // M15: Echo-Schutz gilt NUR für den Enter-Enter-Fall. Wenn der User
            // gerade bestätigt "Enter" hatte und kommt sofort wieder raus, ist
            // "Exit" eine echte Aktion und darf nicht unterdrückt werden.
            // Play Services kann in seltenen Fällen bei Boundary-Flattern
            // doppelte EXITs feuern — der zweite wird durch den Echo-Schutz
            // (gleicher confirmedState == Exit) sauber gefangen.
            //
            // M18.43-FIX (Root Cause "Zuhause angekommen wurde nicht als
            // Trigger angemerkt"): Der Echo-Schutz hatte KEIN Zeitfenster.
            // Wenn ein EXIT durch GPS-Flattern verworfen wurde (oder die App
            // den EXIT nie sah), blieb confirmedState ewig "Enter" — der
            // nächste echte ENTER (z.B. nach Stunden draußen) wurde als
            // "Echo" unterdrückt -> "Zuhause angekommen" fehlte. Jetzt:
            // Echo nur innerhalb von ECHO_WINDOW_MS (10 Min). Danach ist
            // ein gleicher Übergang ein echter neuer Besuch.
            val confirmed = confirmedStateByGeofence[geofenceId]
            if (confirmed != null && confirmed.first == transition &&
                now - confirmed.second < ECHO_WINDOW_MS
            ) {
                return false
            }
            pendingByGeofence[geofenceId] = PendingTransition(transition, now)
            return false // Worker übernimmt die Bestätigung
        }

        // Pendenter Übergang stimmt mit neuem überein → weiter warten
        return false
    }

    /**
     * Wird vom GeofenceStabilizationWorker nach Ablauf der
     * Stabilisierungszeit aufgerufen.
     */
    fun confirmPending(
        geofenceId: String,
        expectedTransition: GeofenceTransition,
        now: Long
    ): ConfirmationResult {
        val pending = pendingByGeofence[geofenceId]
        if (pending == null) {
            return ConfirmationResult.AlreadyEmitted
        }
        if (pending.transition != expectedTransition) {
            pendingByGeofence.remove(geofenceId)
            return ConfirmationResult.Cancelled
        }

        pendingByGeofence.remove(geofenceId)
        confirmedStateByGeofence[geofenceId] = pending.transition to now
        return ConfirmationResult.Confirmed
    }

    fun markEmitted(geofenceId: String, now: Long) {
        // Kompatibilität — Bestätigung erfolgt in confirmPending
    }

    /**
     * M16.7: Liefert die Geofence-IDs, die aktuell in der pending-Map sind.
     * Wird vom Stabilization-Worker genutzt, um Burst-Events zu erkennen, die
     * noch nicht in der DB persistiert sind (z.B. ein zweiter Geofence-EXIT
     * kam 5s nach dem ersten und ist gerade erst pending).
     */
    fun currentlyPendingGeofenceIds(): Set<String> = pendingByGeofence.keys.toSet()

    fun reset() {
        pendingByGeofence.clear()
        confirmedStateByGeofence.clear()
        recentExitTimestampsMs.clear()
        recentExitToGeofence.clear()
    }

    private data class PendingTransition(
        val transition: GeofenceTransition,
        val startedAt: Long
    )

    enum class ConfirmationResult {
        Confirmed,
        Cancelled,
        AlreadyEmitted
    }

    companion object {
        // M17: ENTER-Stabilisierung auf 8 Sekunden reduziert. Der User
        // erwartet, dass die Live-Session SOFORT startet, wenn er den
        // Geofence betritt. 8s reicht, um den häufigsten GPS-Drift-
        // Spike am Boundary (1-2 Samples) sicher zu filtern, ohne den
        // "direkt automatisch"-Use-Case zu zerstören.
        // GPS-Sprung-Schutz: wenn die Auto-Session nach 8s noch läuft
        // UND kein bestätigender zweiter Enter-Trigger in den letzten
        // 60s gekommen ist → wird sie vom LiveActivityManager selbst
        // discarded (siehe LiveActivityManager.startAutoAndScheduleDiscard).
        val STABILIZATION_MS = 8_000L
        // M17: EXIT-Stabilisierung auf 5 Sekunden. Beim Verlassen gibt
        // es keinen Loitering-Schutz, und der User erwartet direkten
        // Stop. 5s reicht für die meisten GPS-Drift-Echos.
        val EXIT_STABILIZATION_MS = 5_000L
        // M16.6: Multi-EXIT-Konsolidierung. Wenn innerhalb von 90 Sekunden
        // mehrere EXITs für verschiedene Geofences eintreffen, ist es
        // GPS-Flattern (z.B. nachts wenn der User eigentlich schläft und
        // mehrere Geofence-Ränder touchen). Wir unterdrücken alle EXITs
        // ab dem zweiten — sie würden sonst als parallele Reise-Starts
        // interpretiert.
        val CONSOLIDATION_WINDOW_MS = 90_000L
        // Schwelle: wenn schon 2 EXITs im Fenster für andere Geofences sind,
        // behandeln wir den dritten als Burst. 2 reicht, weil im echten
        // Leben kaum 3 Geofences gleichzeitig verlassen werden.
        const val CONSOLIDATION_THRESHOLD = 2
        // M18.43: Echo-Schutz-Zeitfenster. Ein gleicher Übergang (Enter nach
        // Enter) wird nur innerhalb von 10 Minuten als Echo gewertet. Danach
        // ist es ein echter neuer Besuch — verpasste EXITs (GPS-Flattern,
        // App-Kill) dürfen den nächsten ENTER nicht blockieren.
        val ECHO_WINDOW_MS = 10 * 60 * 1000L

        fun workName(geofenceId: String, transition: GeofenceTransition): String =
            "${GeofenceStabilizationWorker.WORK_PREFIX}${geofenceId}_${transition.name}"
    }
}