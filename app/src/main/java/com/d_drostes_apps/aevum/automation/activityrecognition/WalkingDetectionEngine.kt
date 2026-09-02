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

    /** Watchdog: 8 Minuten ohne Walking-Signal = Wanderung vorbei.
     *  M18.93v10 (User: "Spaziergänge dürfen ruhig noch 3 Minuten länger
     *  laufen wenn keine Bewegung erkannt wird, bspw. an einer roten
     *  Ampel"): 5 Min war zu knapp — Ampel-/Kreuzungs-Stopps (30-90s)
     *  plus GPS-Lücken beendeten die Session, obwohl der User weitergeht.
     *  8 Min deckt 3 zusätzliche Minuten Stillstand ab, ohne dass eine
     *  beendete Wanderung (echtes Stehenbleiben > 8 Min) endlos weiter-
     *  läuft. Analog zur Drive-5-Minuten-Regel (User-Spec-Familie M18.66). */
    const val WALKING_WATCHDOG_NO_SIGNAL_MS = 8L * 60 * 1000

    /**
     * Soll jetzt eine Wanderung gestartet werden?
     *
     * M18.84: [lastDriveEndMs] — Ende der letzten Auto-Session (null wenn
     * keine bekannt). Die 5-Minuten-Schwelle wird auf die EFFEKTIVE
     * Walking-Zeit angewendet (nach der Fahrt), nicht auf die rohe
     * Signal-Phase: Googles AR meldet WALKING auch während Stop&Go-Fahrten,
     * und ohne diesen Cut reichte der erste ENTER nach dem Aussteigen
     * (now − walkingSince ≥ 5 min), um "Spazieren" mit Vorlauf in die
     * Fahrt hinein zu starten.
     *
     * @param walkingSinceMs Zeitpunkt, seit dem der User ununterbrochen
     *        geht (System.currentTimeMillis), 0 wenn kein Signal vorliegt
     * @param now aktuelle Zeit
     * @param walkingEnabled Gate aus den Trigger-Settings
     * @param anythingRecording true, wenn bereits eine andere Session live ist
     * @param lastDriveEndMs Ende der letzten Auto-Session oder null
     */
    fun shouldStartWalking(
        walkingSinceMs: Long,
        now: Long,
        walkingEnabled: Boolean,
        anythingRecording: Boolean,
        lastDriveEndMs: Long? = null
    ): Boolean {
        if (!walkingEnabled) return false
        if (anythingRecording) return false
        if (walkingSinceMs <= 0L) return false
        val effectiveSince = effectiveWalkingSince(walkingSinceMs, lastDriveEndMs)
        return now - effectiveSince >= WALKING_THRESHOLD_MS
    }

    /**
     * M18.84: Effektiver Walking-Beginn — die Signal-Phase beginnt nie vor
     * dem Ende der letzten Fahrt (AR-WALKING-Echos während der Fahrt
     * zählen nicht als Wanderungszeit).
     */
    fun effectiveWalkingSince(walkingSinceMs: Long, lastDriveEndMs: Long?): Long =
        if (lastDriveEndMs != null && lastDriveEndMs > walkingSinceMs) lastDriveEndMs
        else walkingSinceMs

    /**
     * Startzeit der Wanderung: now − 5 Minuten (Vorlaufzeit). Die
     * vergangenen 5 Minuten fallen rückwirkend in die Aufzeichnung
     * (User-Spec (b): startedAt = now − 5min, analog Screen-Vorlauf M18.70).
     *
     * M18.84: Der Vorlauf wird NIE vor das Ende der letzten Auto-Session
     * zurückdatiert — sonst überlappt die Wanderung die Fahrt, die sie
     * gerade beendet hat (User-Fall: Spazieren 19:05–19:17 begann
     * optisch VOR dem Auto-Stop 19:10). Der Vorlauf entfällt dann
     * einfach (Start = Auto-Ende), die Schwelle selbst bleibt 5 Min.
     */
    fun recordingStartTime(now: Long, lastDriveEndMs: Long? = null): Long {
        val withLead = now - WALKING_THRESHOLD_MS
        return if (lastDriveEndMs != null && lastDriveEndMs > withLead) lastDriveEndMs
        else withLead
    }

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
