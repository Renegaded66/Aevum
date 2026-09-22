package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.135: Zweirad-Kontext-Gate (pure Logik, Android-/GMS-frei).
 *
 * PROBLEM (Kanban t_8e2889cd, gemessen in t_88146697): Der ON_BICYCLE-EXIT
 * des Transition-Receivers nahm den Motion-Kontext sofort zurück (2x
 * UNKNOWN für die Bridge-Hysterese). Ist dieser EXIT ein Google-Artefakt
 * (dieselbe Fehlerklasse wie die M18.93v9-Lehre „Google liefert bei
 * Stop&Go regelmäßig EXIT-Artefakte"; auf dem Rad entstehen EXITs
 * zusätzlich beim Schieben an der Ampel und beim Abstellen) und fährt der
 * User weiter, gilt ~60 s lang wieder die 8-m/s-Schwelle (28,8 km/h):
 * Gemessen waren 29 km/h → `classify = Driving` 15 s nach dem EXIT →
 * Auto-Session über/neben der laufenden Rad-Session (`inserted =
 * [radfahren, driving]`, bei 29 km/h beendet `trimOverlappingForNewSession`
 * die Rad-Session); 32 km/h dasselbe; 25 km/h bleibt korrekt (unter der
 * Schwelle).
 *
 * DER FIX: Das 12-m/s-Gate des Rad-Kontexts hängt nicht mehr allein am
 * rohen Kontext-Feld, sondern zusätzlich an der FRISCHE eines BESTÄTIGTEN
 * Rad-Signals:
 *
 *   bikeGate(now)  ⇔  rawContext == ON_BICYCLE
 *                  ODER  (bestätigtes ON_BICYCLE-Sample jünger als
 *                         [BIKE_GATE_HOLD_MS])
 *
 * Ein EXIT rührt das bestätigte Rad-Signal NICHT an — das Gate bleibt also
 * über das Artefakt-Fenster hinweg offen, solange der weiterlaufende
 * AR-Stream (30-s-Takt) Rad-Samples liefert. Erst wenn die Samples
 * ausbleiben (echtes Ende der Radfahrt: abgestellt, losgegangen), verfällt
 * die Frische und die 8-m/s-Schwelle ist wieder da.
 *
 * WIDERLEGUNG durch das bestätigte Konkurrenz-Signal: Ein IN_VEHICLE-Sample
 * mit Confidence ≥ [MIN_CONTRADICT_CONFIDENCE] löscht die Frische sofort
 * („das Handy sitzt im Fahrzeug"). Das ist der legitime Übergang
 * „Rad abgestellt, ins Auto gestiegen" — der Auto-Start erfolgt dann ohne
 * künstliche Verzögerung.
 *
 * BEWUSST NICHT als Widerlegung: `UNKNOWN` und `STILL`. Genau diese
 * Zustände erzeugt ein EXIT-Artefakt selbst — sie würden das Fenster
 * sofort wieder öffnen (der gemessene Bug).
 *
 * WARUM DIE 60-s-HYSTERESE DER BRIDGE UNANGETASTET BLEIBT: Der rohe
 * Kontext ([ActivityRecognitionBridge.updateMotionContext]) wechselt
 * weiterhin erst nach 2 aufeinanderfolgenden Samples desselben Typs
 * (M18.117, Schutz vor Einzel-Sample-Flackern). Dieses Gate ergänzt ihn
 * nur um die Frische-Regel für den Zweirad-Kontext; der Übergang
 * ENTER→EXIT ist damit kein Kontext-Reset mehr, sondern ein Ablaufen.
 *
 * Zustand lebt in der [ActivityRecognitionBridge] (überlebt die flüchtigen
 * Receiver-Instanzen) — gleiches Muster wie WalkStopDetector (M18.127),
 * VehicleEvidence (M18.128) und BicycleEvidence (M18.134).
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — gleiches Muster
 * wie DriveDetectionEngine (M18.64) und StepWalkStopDetector (M18.133).
 */
class BikeContextGuard {

    companion object {
        /** Haltedauer des Rad-Gates nach dem letzten BESTÄTIGTEN
         *  ON_BICYCLE-Sample.
         *
         *  90 s = drei 30-s-Takte des Continuous-Streams. Die Größe ist
         *  bewusst identisch zu
         *  [DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS]: Beide
         *  beantworten dieselbe Frage („ist das Rad-Signal noch frisch
         *  genug, um für diese Fahrt zu gelten?") und müssen deshalb
         *  dieselbe Zeitbasis haben — sonst hätte die Rad-Session eine
         *  andere Lebensdauer als das Gate, das sie vor Auto-Starts
         *  schützt.
         *
         *  Wirkung: Das gemessene ~60-s-Fenster nach einem EXIT-Artefakt
         *  ist vollständig geschlossen (der weiterlaufende Stream liefert
         *  innerhalb von 30 s das nächste Sample). Bleiben die Samples
         *  aus (echtes Ende der Radfahrt), ist die 8-m/s-Schwelle
         *  spätestens nach 90 s wieder aktiv. */
        const val BIKE_GATE_HOLD_MS = DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS

        /** Confidence-Schwelle eines Rad-Samples, das die Frische setzen
         *  darf. Identisch zu [DriveDetectionEngine.BIKE_CONTEXT_MIN_CONFIDENCE]
         *  (60) — dieselbe Schwelle, die auch die Rad-Session qualifiziert
         *  (Verrauschte Einzel-Samples liegen unter 60). */
        const val MIN_CONFIRM_CONFIDENCE = DriveDetectionEngine.BIKE_CONTEXT_MIN_CONFIDENCE

        /** Confidence-Schwelle eines FAHRZEUG-Samples, das den Rad-Kontext
         *  widerlegen darf. Gleiche Größenordnung wie
         *  [MIN_CONFIRM_CONFIDENCE]: Google liefert bei echter
         *  Fahrzeug-Aktivität typisch 70-100; ein verrauschtes Sample
         *  (Bus vor dem Fenster) darf das Gate nicht reißen. */
        const val MIN_CONTRADICT_CONFIDENCE = 60
    }

    /** Zeitpunkt des letzten BESTÄTIGTEN ON_BICYCLE-Samples (0 = keins). */
    var lastConfirmedBikeSampleMs: Long = 0L
        private set

    /** Zeitpunkt der letzten Widerlegung durch ein Fahrzeug-Sample
     *  (0 = keine) — Diagnose/Log. */
    var contradictedAtMs: Long = 0L
        private set

    /** Zeitpunkt des letzten ON_BICYCLE-EXIT (0 = keiner) — Diagnose/Log.
     *  Der EXIT selbst ändert das Gate NICHT (siehe Klassen-Kommentar). */
    var lastExitAtMs: Long = 0L
        private set

    /** Ein ON_BICYCLE-Sample melden. Nur ein BESTÄTIGTES Sample setzt die
     *  Frische — unter [MIN_CONFIRM_CONFIDENCE] ist das Signal Rauschen
     *  und darf das Gate nicht offen halten. */
    fun onBicycleSample(nowMs: Long, confidence: Int) {
        if (confidence < MIN_CONFIRM_CONFIDENCE) return
        lastConfirmedBikeSampleMs = nowMs
    }

    /** Ein IN_VEHICLE-Sample melden. Ein BESTÄTIGTES Fahrzeug-Sample
     *  widerlegt den Rad-Kontext sofort (Rad abgestellt, ins Auto
     *  gestiegen) — das Gate fällt, der Auto-Pfad ist frei. */
    fun onVehicleSample(nowMs: Long, confidence: Int) {
        if (confidence < MIN_CONTRADICT_CONFIDENCE) return
        lastConfirmedBikeSampleMs = 0L
        contradictedAtMs = nowMs
    }

    /** Ein ON_BICYCLE-EXIT wurde gemeldet (Transition-Receiver) — nur
     *  Buchführung für Logs. Der rohe Kontext wird vom Aufrufer weiterhin
     *  auf UNKNOWN zurückgesetzt (M18.117-Hysterese); dieses Gate hält
     *  ihn für die Klassifikation, solange das Rad-Signal frisch ist. */
    fun onBicycleExit(nowMs: Long) {
        lastExitAtMs = nowMs
    }

    /** Ist ein bestätigtes Rad-Signal innerhalb von [BIKE_GATE_HOLD_MS]
     *  gemeldet worden? */
    fun hasFreshConfirmedBikeSample(nowMs: Long): Boolean =
        lastConfirmedBikeSampleMs > 0L &&
            nowMs - lastConfirmedBikeSampleMs <= BIKE_GATE_HOLD_MS

    /**
     * Der Kontext, der für die KLASSIFIKATION gilt.
     *
     * @param nowMs aktuelle Zeit
     * @param rawContext der rohe Motion-Kontext der Bridge (M18.117)
     * @return ON_BICYCLE, wenn der rohe Kontext ON_BICYCLE ist ODER ein
     *   frisches bestätigtes Rad-Sample vorliegt — sonst der rohe
     *   Kontext unverändert. Die Abfrage ist zustandsarm und heilt sich
     *   selbst (M18.75/M18.76-Lehre: kein Flag, das ein Stop-Pfad
     *   verpassen kann — die Frische läuft von allein ab).
     */
    fun effectiveContext(
        nowMs: Long,
        rawContext: DriveDetectionEngine.MotionContext
    ): DriveDetectionEngine.MotionContext =
        if (rawContext == DriveDetectionEngine.MotionContext.ON_BICYCLE ||
            hasFreshConfirmedBikeSample(nowMs)
        ) {
            DriveDetectionEngine.MotionContext.ON_BICYCLE
        } else {
            rawContext
        }

    /** Diagnose: Ist das Gate gerade offen? (identisch zum effektiven
     *  Kontext = ON_BICYCLE, als eigene Abfrage für Log/Tests). */
    fun isBikeGateActive(nowMs: Long, rawContext: DriveDetectionEngine.MotionContext): Boolean =
        effectiveContext(nowMs, rawContext) == DriveDetectionEngine.MotionContext.ON_BICYCLE
}
