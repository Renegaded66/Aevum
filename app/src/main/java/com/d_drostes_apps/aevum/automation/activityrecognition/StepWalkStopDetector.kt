package com.d_drostes_apps.aevum.automation.activityrecognition

import java.util.ArrayDeque

/**
 * M18.133: Step-Walk-Stop — „ausgestiegen und geht" beendet die Fahrt (pure Logik).
 *
 * USER-SPEZIFIKATION: „Die Aufzeichnung läuft nach dem Ende der Autofahrt noch
 * ~10 Minuten weiter. Sobald ich aus dem Auto aussteige und gehe, bin ich
 * offensichtlich nicht mehr am Autofahren — die Aufzeichnung kann gestoppt
 * werden. Falls die Berechtigung erteilt ist, soll die Aufzeichnung automatisch
 * stoppen, sobald Schritte bzw. Gehen erkannt wird."
 *
 * WARUM EIN EIGENER PFAD (Root-Cause M18.133):
 * Der bestehende [WalkStopDetector] (M18.127) hängt AUSSCHLIESSLICH an Googles
 * AR-Samples. Zwei Lücken in der Praxis:
 *  1. Google liefert im Hintergrund oft gar keine WALKING-Samples („The latency
 *     of event detection might vary by device") — dann entsteht nie Geh-Evidenz.
 *  2. Der 5-Min-Watchdog verlängert die Session, wenn der GPS-Bewegungs-Check
 *     ≥ 200 m/2 Min sieht (DriveWorkers.DRIVE_MIN_PROBE_MOVEMENT_M). Ein
 *     zügiger Fußgänger schafft 1,7 m/s = 204 m/2 Min und passiert die Schwelle
 *     physikalisch — die Session läuft weiter (User-Befund: ~10 Min Nachlauf).
 *
 * Dieser Detektor nutzt den HARDWARE-Step-Detector (android.hardware.Sensor.
 * TYPE_STEP_DETECTOR), der im TRACK_DRIVE-Modus ohnehin schon für die
 * Schrittfrequenz läuft (M18.118/M18.126). Ein Step-Event ist ein echter
 * Schritt (Android-Garantie) — Schritte gibt es im Fahrzeug nicht, sie sind
 * damit ein Google-unabhängiges, physikalisches Ausstiegs-Signal. Zusätzliche
 * Sensor-/GPS-Kosten: NULL (der Stream läuft für das Cadence-Veto bereits).
 *
 * ABGRENZUNG zu M18.117/M18.118 (Cadence-Veto): Das Cadence-Veto verhindert,
 * dass JOGGEN als Fahrt STARTET (2,2–3,2 Hz). Dieser Detektor ist ein STOP-
 * Signal für eine LAUFENDE Fahrt — bewusst mit niedrigerer Schwelle
 * ([MIN_STEPS_IN_WINDOW] = 0,67 Hz ≈ 40 Schritte/Min): Aussteigen und ein paar
 * Schritte zum Kofferraum oder zur Haustür sollen schon reichen.
 *
 * SCHUTZREGELN (evidenzbasiert aus den M18.84/M18.126-Lehren):
 *  - Regel 1 — Fahrzeug-Veto (Frischer Herzschlag ODER Fahrzeug-Tempo):
 *    Der M18.126-Bericht belegt Vibrations-Fehlzählungen des Step-Detectors
 *    (Crash-Zeitpunkte mehrheitlich in Fahrten). Solange das Fahrzeug
 *    nachweislich lebt, sind Schritte nicht verwertbar.
 *      a) [vehicleHeartbeatFresh]: Der Fahrth Herzschlag der Bridge
 *         (`lastVehicleSample()`) ist frisch. Er wird im TRACK-Stream alle
 *         15 s erneuert, solange der Fix ≥ 2 m/s (7,2 km/h) zeigt — ein
 *         fahrendes Auto hat also immer einen frischen Herzschlag. Nach dem
 *         Parken altert er aus (Gehen ist 1,4 m/s → kein Refresh), die Sperre
 *         endet damit von selbst.
 *      b) Ein frischer, genauer GPS-Probe mit ≥ 8 m/s (direkt ODER aus
 *         Distanz/Zeit abgeleitet) widerlegt Gehen — ein Fußgänger erreicht
 *         8 m/s nie (M18.117-Argumentation).
 *    BEWUSST NICHT als Veto: AR-Motion-Context IN_VEHICLE. Google meldet nach
 *    dem Parken oft minutenlang weiter IN_VEHICLE (genau das ist der
 *    ~10-Minuten-Nachlauf) — ein Veto darauf würde den Stop dauerhaft
 *    blockieren und den Zweck des Features zerstören.
 *  - Regel 2 — Artefakt-Grenze: Ein defekter Step-Detector zählt Vibration als
 *    Schritte mit pathologischer Rate. Fenster über [MAX_STEPS_IN_WINDOW]
 *    (4 Hz) werden verworfen und die Kette geleert (M18.126-Muster).
 *  - Regel 3 — Kette: [MIN_STEPS_IN_WINDOW] Schritte im
 *    [STEP_WINDOW_MS]-Fenster = STOPP.
 *
 * Nur während einer LAUFENDEN Fahrt: Der Aufrufer (DriveDetectionService)
 * füttert den Detektor nur bei `isDriveActive` und aktivem Setting
 * (`walk_stop_on_steps_enabled`).
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — gleiches Muster wie
 * DriveDetectionEngine (M18.64), WalkStopDetector (M18.127), CadenceTracker (M18.118).
 */
class StepWalkStopDetector {

    companion object {
        /** So viele Schritte im [STEP_WINDOW_MS]-Fenster beenden die Fahrt.
         *  10 Schritte / 15 s = 0,67 Hz ≈ 40 Schritte/Min — deutlich unter
         *  normalem Gehtempo (100–120 Schritte/Min = 1,7–2 Hz). Wenige
         *  Schritte (Aussteigen, Kofferraum, Tür) sollen reichen. */
        const val MIN_STEPS_IN_WINDOW = 10

        /** Beobachtungsfenster für die Schrittzählung. 15 s deckt das Gehen
         *  direkt nach dem Aussteigen ab. */
        const val STEP_WINDOW_MS = 15_000L

        /** Artefakt-Obergrenze: 4 Hz × 15 s — absolute Notbremse für einen
         *  Zähler, der dauerhaft weit über der Physiologie liegt. Die
         *  EIGENTLICHE Artefakt-Erkennung ist die Rate-Regel
         *  ([MAX_PLAUSIBLE_STEP_HZ]) — sie greift schon beim 10. Schritt
         *  (der M18.126-Beleg: Vibrations-Fehlzählung ist real). */
        const val MAX_STEPS_IN_WINDOW = 60

        /** Physiologische Obergrenze der Schrittfrequenz — bewusst IDENTISCH
         *  zum [CadenceTracker.MAX_PLAUSIBLE_HZ] (3,5 Hz; Sprint ≈ 3,1 Hz,
         *  3,5 liegt bewusst über dem Jogging-Band 3,2 Hz). Kommen
         *  [MIN_STEPS_IN_WINDOW] Schritte DICHTER, zählt der Sensor Vibration
         *  statt Schritte (M18.126) → verwerfen statt stoppen. Ohne diese
         *  Regel hätte ein 5-Hz-Fehlzähler (Vibration) nach 2 s einen
         *  Falsch-Stop ausgelöst — die absolute Obergrenze [MAX_STEPS_IN_WINDOW]
         *  hätte erst nach 12 s gegriffen. */
        const val MAX_PLAUSIBLE_STEP_HZ = 3.5

        /** Fahrzeug-Tempo-Veto nur mit frischer Evidenz — ein Fix von vor
         *  10 Minuten darf einen echten Ausstieg nicht blockieren. */
        const val VETO_PROBE_AGE_MS = 90_000L

        /** Fahrzeug-Veto über den Fahrt-Herzschlag: „frisch" = jünger als
         *  dieser Wert. Basiert auf dem 15-s-Fix-Intervall des TRACK-Streams
         *  (DriveDetectionService.TRACK_DRIVE_INTERVAL_MS): 45 s = 3 Intervalle,
         *  deckt also auch eine verpasste Fix-Lücke ab. Bewusst NICHT größer:
         *  Jede Sekunde dieses Fensters ist eine Sekunde, in der ein echter
         *  Ausstieg nicht erkannt wird (User: „Aufzeichnung soll sofort
         *  stoppen, sobald ich gehe"). Die starke Fahrzeug-Evidenz liefert
         *  ohnehin das ≥-8-m/s-Speed-Veto (siehe [hasVehicleSpeedVeto]);
         *  der Herzschlag deckt zusätzlich das langsame Rollen im Parkhaus ab. */
        const val VEHICLE_HEARTBEAT_VETO_MS = 45_000L

        /** Mindest-Abstand zwischen zwei gezählten Schritten. Der
         *  Hardware-Detector liefert pro Schritt EIN Event; Doppel-Events
         *  (Echo) innerhalb von 150 ms zählen nicht als zweiter Schritt —
         *  das ist die halbe Schritt-Periode bei 3,3 Hz (Sprint). */
        const val MIN_STEP_GAP_MS = 150L
    }

    /** Zeitstempel der Schritte im aktuellen Fenster (älteste zuerst). */
    private val stepTimes = ArrayDeque<Long>()

    /** Zeitpunkt des letzten gezählten Schritts (0 = keiner) — Log/Diagnose. */
    var lastStepMs: Long = 0L
        private set

    /** Evidenz verwerfen (Session-Grenzen: Start und jeder Stop-Pfad). */
    fun reset() {
        stepTimes.clear()
    }

    /**
     * EIN Step-Detector-Event verarbeiten.
     *
     * @param nowMs aktuelle Zeit in WALL-CLOCK-Millisekunden (Basis der
     *   Probe-Zeitstempel — NICHT der SensorEvent-Nanosekunden-Wert, der auf
     *   `elapsedRealtimeNanos` basiert und nicht mit Wall-Clock vergleichbar ist)
     * @param vehicleHeartbeatFresh lebt das Fahrzeug nachweislich?
     *   (`lastVehicleSample()` jünger als [VEHICLE_HEARTBEAT_VETO_MS])
     * @param latestProbes aktuelle GPS-Probes (Bridge.currentDriveProbes())
     * @return true = STOPP auslösen (Fahrt beenden)
     */
    fun onStep(
        nowMs: Long,
        vehicleHeartbeatFresh: Boolean = false,
        latestProbes: List<DriveDetectionEngine.DriveProbe> = emptyList()
    ): Boolean {
        // Regel 1: Fahrzeug lebt nachweislich → Schritte nicht verwertbar
        // (Vibration, M18.126). Evidenz verwerfen, nicht ansparen.
        if (vehicleHeartbeatFresh || hasVehicleSpeedVeto(latestProbes, nowMs)) {
            reset()
            return false
        }
        // Echo-Schutz: Doppel-Events innerhalb der halben Sprint-Periode
        // sind keine zweiten Schritte.
        val last = stepTimes.peekLast()
        if (last != null && nowMs - last < MIN_STEP_GAP_MS) return false
        stepTimes.addLast(nowMs)
        lastStepMs = nowMs
        prune(nowMs)
        // Regel 2a: Ratengrenze (die EIGENTLICHE Artefakt-Erkennung).
        // Die Kette ist vollständig, aber sie kam PHYSIKALISCH zu schnell:
        // MIN_STEPS_IN_WINDOW Schritte dichter als MAX_PLAUSIBLE_STEP_HZ
        // bedeutet, dass der Span kürzer ist als die physiologische
        // Mindestdauer. Das ist Vibrations-Fehlzählung (M18.126) — ein
        // Gehender schafft das nicht.
        if (stepTimes.size >= MIN_STEPS_IN_WINDOW && !chainIsPhysiologicallyPossible(nowMs)) {
            reset()
            return false
        }
        // Regel 2b: Absolute Notbremse (dauerhaft pathologische Zählrate).
        if (stepTimes.size > MAX_STEPS_IN_WINDOW) {
            reset()
            return false
        }
        // Regel 3: Geh-Kette vollständig?
        return stepTimes.size >= MIN_STEPS_IN_WINDOW
    }

    /** Schritte im aktuellen [STEP_WINDOW_MS]-Fenster (Log/Diagnose). */
    fun stepsInWindow(nowMs: Long): Int {
        prune(nowMs)
        return stepTimes.size
    }

    /** Liegt Geh-Evidenz vor? Der [DriveWatchdogWorker] nutzt das beim
     *  GPS-Bewegungs-Check: ≥ 200 m/2 Min können Gehen ODER Kriechverkehr
     *  sein — Schritte entscheiden.
     *
     *  M18.133-Konsistenz: Es gilt DIESELBE Ratengrenze wie im Stop-Pfad
     *  ([chainIsPhysiologicallyPossible]). Ohne sie würde ein
     *  Vibrations-Fehlzähler (M18.126) den Watchdog zu einem Falsch-Stop
     *  überreden — genau die Evidenz-Basis, die [onStep] schon prüft. */
    fun hasWalkingEvidence(nowMs: Long): Boolean {
        prune(nowMs)
        return stepTimes.size >= MIN_STEPS_IN_WINDOW && chainIsPhysiologicallyPossible(nowMs)
    }

    /** Ist der Span der aktuellen Kette physiologisch möglich? (10 Schritte
     *  dichter als [MAX_PLAUSIBLE_STEP_HZ] = Vibrations-Fehlzählung.) */
    private fun chainIsPhysiologicallyPossible(nowMs: Long): Boolean {
        if (stepTimes.size < MIN_STEPS_IN_WINDOW) return false
        val spanMs = nowMs - stepTimes.first()
        val minSpanMs = ((MIN_STEPS_IN_WINDOW - 1) / MAX_PLAUSIBLE_STEP_HZ * 1000.0).toLong()
        return spanMs >= minSpanMs
    }

    private fun prune(nowMs: Long) {
        while (stepTimes.isNotEmpty() && nowMs - stepTimes.first() > STEP_WINDOW_MS) {
            stepTimes.removeFirst()
        }
    }

    /** Frischer, genauer GPS-Probe mit Fahrzeug-Tempo? Direkte Speed-Angabe
     *  ODER aus Distanz/Zeit abgeleitet (M18.77-Semantik, nur bei fehlender
     *  Direkt-Speed). Filter wie die DriveDetectionEngine (Accuracy ≤ 50 m,
     *  kein Ausreißer > 40 m/s) — identische Semantik wie das Veto des
     *  [WalkStopDetector], damit beide Stop-Pfade dieselbe Evidenz-Basis haben. */
    private fun hasVehicleSpeedVeto(
        probes: List<DriveDetectionEngine.DriveProbe>,
        nowMs: Long
    ): Boolean {
        var prev: DriveDetectionEngine.DriveProbe? = null
        for (p in probes) {
            val fresh = p.timestampMs <= nowMs &&
                nowMs - p.timestampMs <= VETO_PROBE_AGE_MS
            if (fresh && p.accuracyMeters <= DriveDetectionEngine.MAX_ACCURACY_M) {
                val speed = p.speedMps
                if (speed != null) {
                    if (speed <= DriveDetectionEngine.OUTLIER_SPEED_MPS &&
                        speed >= DriveDetectionEngine.AUTO_SPEED_MPS
                    ) {
                        return true
                    }
                } else if (p.distanceFromLastM != null && prev != null) {
                    val dtMs = p.timestampMs - prev.timestampMs
                    if (dtMs >= DriveDetectionEngine.MIN_INFERRED_DT_MS &&
                        dtMs <= DriveDetectionEngine.MAX_INFERRED_DT_MS
                    ) {
                        val derived = (p.distanceFromLastM / (dtMs / 1000.0)).toFloat()
                        if (derived >= DriveDetectionEngine.AUTO_SPEED_MPS) return true
                    }
                }
                prev = p
            }
        }
        return false
    }
}
