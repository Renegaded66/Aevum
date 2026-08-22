package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.72: Wanderungen automatisch erkennen — pure Entscheidungslogik.
 *
 * Regel (User-Spec, analog M18.70 ScreenRecordingEngine: Schwelle + Vorlauf):
 *  - Erst wenn man 5 Minuten am Stück unterwegs ist (Bewegung — nicht jeder
 *    Gang zum Kühlschrank), wird die Wanderung automatisch aufgezeichnet.
 *  - Die 5 Minuten Vorlaufzeit müssen mit aufgezeichnet werden:
 *    startedAt = now − 5 min (die ersten 5 Minuten Bewegung fallen
 *    rückwirkend in die Aufzeichnung).
 *  - Nur wenn gerade nichts anderes aufzeichnet (nie zwei Auto-Sessions;
 *    M18.71-Overlap-Regeln gelten im Start-Pfad weiter).
 *  - Stopp: erst wenn 5 Minuten lang KEIN Walking-Signal mehr kam
 *    (Google-Transition ODER GPS-Bewegung) — kurze Pausen (Blick aufs
 *    Handy, Ampel, Schnürsenkel) beenden die Wanderung nicht.
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — gleiches
 * Muster wie ScreenRecordingEngine (M18.70) und DriveDetectionEngine
 * (M18.64).
 */
object WalkingDetectionEngine {

    /** Schwelle: 5 Minuten am Stück unterwegs, bevor die Wanderung
     *  automatisch gestartet wird (User-Spec). */
    const val WALKING_THRESHOLD_MS = 5L * 60 * 1000

    /** Watchdog: 5 Minuten ohne Walking-Signal = Wanderung vorbei.
     *  Analog zur Drive-5-Minuten-Regel (User-Spec-Familie M18.66). */
    const val WALKING_WATCHDOG_NO_SIGNAL_MS = 5L * 60 * 1000

    /**
     * Soll jetzt eine Wanderung gestartet werden?
     *
     * @param walkingSinceMs Zeitpunkt, seit dem der User ununterbrochen
     *        geht (System.currentTimeMillis), 0 wenn kein Signal vorliegt
     * @param now aktuelle Zeit
     * @param walkingEnabled Gate aus den Trigger-Settings
     * @param anythingRecording true, wenn bereits eine andere Session live ist
     */
    fun shouldStartWalking(
        walkingSinceMs: Long,
        now: Long,
        walkingEnabled: Boolean,
        anythingRecording: Boolean
    ): Boolean {
        if (!walkingEnabled) return false
        if (anythingRecording) return false
        if (walkingSinceMs <= 0L) return false
        return now - walkingSinceMs >= WALKING_THRESHOLD_MS
    }

    /**
     * Startzeit der Wanderung: now − 5 Minuten (Vorlaufzeit). Die
     * vergangenen 5 Minuten fallen rückwirkend in die Aufzeichnung
     * (User-Spec (b): startedAt = now − 5min, analog Screen-Vorlauf M18.70).
     */
    fun recordingStartTime(now: Long): Long = now - WALKING_THRESHOLD_MS

    /**
     * Soll die laufende Wanderung gestoppt werden? Erst wenn seit
     * [WALKING_WATCHDOG_NO_SIGNAL_MS] (5 min) kein Walking-Signal mehr
     * kam — kurze Pausen beenden die Aufzeichnung nicht.
     *
     * @param lastWalkingSignalMs Zeitpunkt des letzten Walking-Signals
     *   (Transition ODER GPS-Bewegung), 0 wenn nie ein Signal kam
     * @param now aktuelle Zeit
     */
    fun shouldStopWalking(lastWalkingSignalMs: Long, now: Long): Boolean {
        if (lastWalkingSignalMs <= 0L) return false
        return now - lastWalkingSignalMs >= WALKING_WATCHDOG_NO_SIGNAL_MS
    }
}
