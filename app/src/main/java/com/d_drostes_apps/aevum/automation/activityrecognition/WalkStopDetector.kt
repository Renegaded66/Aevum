package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.127: Walk-Stop-Erkennung — pure Entscheidungslogik (Android-/GMS-frei).
 *
 * Nutzt den bereits registrierten 30-s-Continuous-Sampling-Stream
 * (M18.112, `requestActivityUpdates`) als STOPP-Signal für laufende
 * Auto-Sessions: Meldet Google während einer Fahrt 2 aufeinanderfolgende
 * WALKING/RUNNING/ON_FOOT-Samples mit Confidence ≥ [WALK_STOP_CONFIDENCE]
 * innerhalb der Gnadenfrist [WALK_STOP_GRACE_MS] (= ~75 s ≈ 2 Samples à
 * 30 s + Puffer), ist der User wahrscheinlich ausgestiegen → STOPP-Signal.
 *
 * Gegen die bekannten AR-Lehren (docs/activity-detection.md):
 *  - M18.84: Google meldet WALKING durchgehend bei Stop&Go-Fahrten
 *    (Anfahren/Kriechen = „Gehen") → Confidence-Schwelle + Gnadenfrist +
 *    Fahrzeug-Tempo-Veto.
 *  - M18.93v9: Ein einzelnes AR-Signal ist kein Beweis (führte zu
 *    „2 Aufzeichnungen mit Leerraum") → erst 2 Samples bzw. die volle
 *    Gnadenfrist entscheidet.
 *
 * VETO (asymmetrisch, bewusst): Ein FRISCHER GPS-Probe (≤
 * [WALK_STOP_VETO_PROBE_AGE_MS]) mit Fahrzeug-Tempo (≥
 * DriveDetectionEngine.AUTO_SPEED_MPS = 8 m/s, direkt ODER aus
 * Distanz/Zeit abgeleitet) widerlegt Gehen — ein Fußgänger erreicht
 * 8 m/s nie (M18.117-Argumentation). Ein 0-m/s-Probe (rote Ampel) sagt
 * dagegen NICHTS (kein Reset, kein Signal) — der Stop feuert also auch
 * dann, wenn der Ausstiegs-GPS im Parkhaus-Canyon nichts liefert.
 *
 * Zustand: [firstWalkingSampleMs]. Reset: IN_VEHICLE/ON_BICYCLE-Sample,
 * frisches Fahrzeug-Tempo, oder explizit über Session-Grenzen hinweg
 * (Bridge: `resetWalkStopEvidence()`).
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — gleiches
 * Muster wie DriveDetectionEngine (M18.64) und WalkingDetectionEngine
 * (M18.72).
 */
class WalkStopDetector {

    companion object {
        /** Confidence ≥ 60 = „wirklich gehen" (Google liefert bei echtem
         *  Gehen typisch 70–100; rohe Einzel-Samples sind verrauscht —
         *  Doku empfiehlt eigenes Filtern). Unter 60 = verwerfen. */
        const val WALK_STOP_CONFIDENCE = 60

        /** Gnadenfrist: ~2 Samples à 30 s + Puffer. Kurze Ampel-/Stau-
         *  Phasen (M18.84) und kurze Fußwege im Stau überstehen; nach
         *  ~75 s durchgehendem Gehen ist ein Ausstieg wahrscheinlich. */
        const val WALK_STOP_GRACE_MS = 75_000L

        /** Fahrzeug-Tempo-Veto nur mit frischer Evidenz („frisch" =
         *  jünger als dieser Wert). */
        const val WALK_STOP_VETO_PROBE_AGE_MS = 90_000L

        // Google-DetectedActivity-Konstanten inline (GMS-Versionen, damit die
        // Klasse GMS-frei bleibt): IN_VEHICLE=0, ON_BICYCLE=1, ON_FOOT=2,
        // WALKING=7, RUNNING=8.
        const val TYPE_IN_VEHICLE = 0
        const val TYPE_ON_BICYCLE = 1
        const val TYPE_ON_FOOT = 2
        const val TYPE_WALKING = 7
        const val TYPE_RUNNING = 8
    }

    /** Zeitpunkt des ersten verwertbaren Geh-Samples (null = keine
     *  Evidenz). Basis der Gnadenfrist. */
    var firstWalkingSampleMs: Long? = null
        private set

    fun reset() {
        firstWalkingSampleMs = null
    }

    /**
     * Ein neues AR-Sample verarbeiten.
     *
     * @param type DetectedActivity-Typ (Konstanten dieses Detektors)
     * @param confidence DetectedActivity.getConfidence() (0–100)
     * @param nowMs aktuelle Zeit
     * @param latestProbes aktuelle GPS-Probes (Bridge.currentDriveProbes())
     * @return true = STOPP auslösen (Fahrt sofort beenden)
     */
    fun onSample(
        type: Int,
        confidence: Int,
        nowMs: Long,
        latestProbes: List<DriveDetectionEngine.DriveProbe>
    ): Boolean {
        // Regel 1: Fahrzeug bestätigt → Geh-Evidenz verwerfen (Fahrt lebt).
        if (type == TYPE_IN_VEHICLE || type == TYPE_ON_BICYCLE) {
            reset()
            return false
        }
        // Regel 4: STILL / UNKNOWN / TILTING → kein Einfluss (kein Reset,
        // kein Signal) — Zuhause-/Ampel-Ruhe kostet die Evidenz nicht.
        val walking = type == TYPE_WALKING || type == TYPE_RUNNING || type == TYPE_ON_FOOT
        if (!walking) return false
        // Regel 2: Confidence-Schwelle (verrauschte Einzel-Samples).
        if (confidence < WALK_STOP_CONFIDENCE) return false
        // Regel 3: Frisches Fahrzeug-Tempo widerlegt Gehen (M18.84).
        if (hasVehicleSpeedVeto(latestProbes, nowMs)) {
            reset()
            return false
        }
        val first = firstWalkingSampleMs
        if (first == null) {
            firstWalkingSampleMs = nowMs
            return false
        }
        return nowMs - first >= WALK_STOP_GRACE_MS
    }

    /** Frischer, genauer GPS-Probe mit Fahrzeug-Tempo? Direkte Speed-
     *  Angabe ODER aus Distanz/Zeit abgeleitet (M18.77-Semantik, nur
     *  bei fehlender Direkt-Speed). Filter wie die DriveDetectionEngine
     *  (Accuracy ≤ 50 m, kein Ausreißer > 40 m/s). */
    private fun hasVehicleSpeedVeto(
        probes: List<DriveDetectionEngine.DriveProbe>,
        nowMs: Long
    ): Boolean {
        var prev: DriveDetectionEngine.DriveProbe? = null
        for (p in probes) {
            val fresh = p.timestampMs <= nowMs &&
                nowMs - p.timestampMs <= WALK_STOP_VETO_PROBE_AGE_MS
            if (fresh && p.accuracyMeters <= DriveDetectionEngine.MAX_ACCURACY_M) {
                val speed = p.speedMps
                if (speed != null) {
                    // Ausreißer (GPS-Sprung) ist KEINE Fahrzeug-Evidenz.
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
