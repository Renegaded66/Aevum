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

    /** M18.110: GPS-Fixes mit Fahrzeug-Tempo sind KEIN Wanderungs-Signal.
     *
     *  User-Bug „Spazieren wurde aufgezeichnet, während ich eine Weile
     *  durch eine 30er-Zone gefahren bin“: Die GPS-Walking-Phase im
     *  DriveDetectionService misst Netto-Displacement OHNE Tempo-Gate —
     *  5 Min × 8,3 m/s (30er-Zone) = 2.500 m ≫ 300 m-Gate → die Phase
     *  qualifizierte Fahrzeug-Bewegung als Wanderung. Ursache: Der
     *  alte 24/7-Stream (M18.104 davor) lieferte Fix-Raten, bei denen
     *  Googles WALKING-AR + Tempo-Realität auseinanderliefen; seit dem
     *  Burst-Redesign läuft der GPS-Pfad additionally in CONFIRM-Bursts
     *  (Fahrzeug-Verdacht!), wo Fahrzeug-Tempo die häufigste Evidenz ist.
     *  8 m/s = AUTO_SPEED_MPS: unterhalb beginnt der Radfahr-Bereich —
     *  Joggen (RUNNING-AR, eigener Typ) liegt real ≤ 5,5 m/s. Ein
     *  Fahrzeug-Tempo-Fix verwirft die laufende Phase SOFORT (Neustart
     *  ab 0), statt sie weiterlaufen zu lassen. */
    const val WALKING_VEHICLE_SPEED_MPS = 8.0f

    /** M18.110: Ist dieser Fix mit Fahrzeug-Tempo behaftet? (null = kein
     *  Speed-Feld → keine Aussage, Phase bleibt). */
    fun isVehicleSpeed(speedMps: Float?): Boolean =
        speedMps != null && speedMps >= WALKING_VEHICLE_SPEED_MPS

    /** M18.110: Displacement-Veto für Fixes OHNE Speed-Feld (BALANCED-
     *  Walking-Bursts liefern oft kein hasSpeed). Eine Ortsveränderung
     *  ≥ 350 m zwischen zwei Fixes entspricht ≥ 5,8 m/s Durchschnitt bei
     *  60s-Intervall — über der Lauf-Obergrenze (WALK_RUN_MAX ≈ 5,5 m/s
     *  = 330 m), damit schnelles Joggen (eigener "joggen"-Pfad via
     *  RUNNING-AR) nicht vom Veto getroffen wird. Echte Wanderungs-Fixe
     *  bleiben ≤ 90-150 m (Geh-Tempo). */
    const val WALKING_DISPLACEMENT_VETO_M = 350.0

    /** M18.110: Mindest-dt für das Displacement-Veto (kürzere Abstände
     *  = GPS-Jitter-Sprünge, keine Fortbewegung). */
    const val WALKING_DISPLACEMENT_VETO_MIN_DT_MS = 30_000L

    /** M18.110: Max-dt für das Displacement-Veto — der Vor-Fix muss zur
     *  KONTINUIERLICHEN Stream-Serie gehören. Ein älterer Fix (Stream
     *  war aus, User ist längst woanders hin gegangen) sagt nichts über
     *  die aktuelle Bewegung aus — sonst würde der 400-m-Gang vom
     *  20-Min-alten Parkplatz zum Laden fälschlich als Fahrzeug-
     *  Bewegung verworfen. 2 Min deckt den 15s-Stream (maxUpdateDelay
     *  2×) und den 60s-BALANCED-Stream ab. */
    const val WALKING_DISPLACEMENT_VETO_MAX_DT_MS = 2L * 60 * 1000

    /** M18.110: MAX-Gate der GPS-Walking-Phase — die Struktur-Fix für
     *  „Spazieren während der Fahrt". Eine Wanderung ist per Definition
     *  ≤ Lauf-Tempo: Netto-Displacement / Phasen-Dauer ≥ 5,0 m/s
     *  (18 km/h) ist KEIN Spaziergang mehr — egal was die Einzel-Fixe
     *  für Speed-Felder haben. 5,0 m/s liegt über realen Lauf-Tempo
     *  (5,5 m/s nur für kurze Sprints — nicht 5 Min am Stück) und weit
     *  unter Fahrzeug-Tempo (8,3 m/s in der 30er-Zone = 2.500 m in
     *  5 Min — genau der User-Fall). */
    const val WALKING_MAX_AVG_SPEED_MPS = 5.0f

    /** M18.110: Überschreitet die Phase das Wanderungs-Tempo? (Struktur-
     *  Gate am Start-Entscheidungspunkt.) */
    fun exceedsWalkingSpeed(netMeters: Double, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return (netMeters / (durationMs / 1000.0)) >= WALKING_MAX_AVG_SPEED_MPS
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
