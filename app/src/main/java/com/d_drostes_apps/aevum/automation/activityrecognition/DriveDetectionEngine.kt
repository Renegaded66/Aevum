package com.d_drostes_apps.aevum.automation.activityrecognition

/**
 * M18.64: Pure Erkennungslogik für Autofahrten auf Basis von
 * GPS-Geschwindigkeits-Probes.
 *
 * WARUM (Root-Cause-Analyse M18.64): Die bisherige Fahrterkennung hing
 * KOMPLETT an Googles Activity-Recognition-Transitions (IN_VEHICLE-ENTER)
 * + einem 2-Fix-GPS-Muster im DriveConfirmWorker. Wenn Google kein
 * IN_VEHICLE-Event liefert (App im Hintergrund, Fahrt begann vor dem
 * App-Start, Sensor-Spring), wurde NIE eine Fahrt erkannt — es gab
 * keinen unabhängigen Geschwindigkeits-Pfad.
 *
 * Diese Engine ist der unabhängige Fallback: Sie klassifiziert eine
 * Serie von GPS-Geschwindigkeits-Probes (Speed aus dem Location-Fix)
 * und bestätigt eine Fahrt erst nach MEHREREN aufeinanderfolgenden
 * Messungen über einen Zeitraum — robust gegen einzelne Ausreißer,
 * schnelles Gehen/Laufen und Fahrradfahren.
 *
 * Bewusst Android-frei (JVM-Unit-Tests ohne Robolectric) — die Tests
 * in DriveDetectionEngineTest sind die Regression-Absicherung.
 */
object DriveDetectionEngine {

    // ── Schwellen (in m/s) ──────────────────────────────────────────
    /** Auto-Schwelle: ~28,8 km/h. M18.66-FIX12 hatte 8→10 m/s gesetzt
     *  (36 km/h), M18.68 senkt auf 9 m/s (32,4 km/h), M18.71 senkt auf
     *  8 m/s: 30er-Zonen (8,3 m/s = 30 km/h) sind in Deutschland die
     *  häufigste Stadt-Geschwindigkeit — mit 9 m/s wurde dort NIE eine
     *  Fahrt erkannt (User: „keine Autofahrten mehr aufgezeichnet").
     *  8 m/s bleibt ÜBER dem normalen Radfahr-Bereich (15-25 km/h =
     *  4-7 m/s; nur Rennrad-Sprints erreichen 8+ m/s, und die scheitern
     *  an der Konsekutiv-Kette + Netto-Displacement-Gate) und weit über
     *  Lauf-Tempo (~5 m/s). GPS-Drift-False-Positives fängt weiterhin
     *  das Netto-Displacement-Gate (≥ 150 m) ab, einzelne Sprünge die
     *  Konsekutiv-Kette. */
    const val AUTO_SPEED_MPS = 8.0f
    /** Unter dieser Geschwindigkeit ist es nie eine Fahrt (Gehen/Laufen). */
    const val WALK_RUN_MAX_MPS = 5.5f
    /** GPS-Ausreißer: > 144 km/h ist kein reales Fahrzeug-Tempo. */
    const val OUTLIER_SPEED_MPS = 40.0f
    /** Ungenaue Fixes verwerfen (schlechte GPS-Lage).
     *  M18.66-FIX2: 120m -> 30m. Indoor-GPS hat oft 50-100m Genauigkeit
     *  und springt um 10-20m — das erzeugte False-Positive-Fahrten,
     *  weil die Distanz den Heartbeat refreshte obwohl der User still
     *  sitzt. 30m ist streng genug, um Indoor-Fixes zu verwerfen, aber
     *  großzügig genug für echtes Auto-GPS (meist < 10m).
     *  M18.71: 30m -> 50m. In Stadt-Canyons (Häuserschluchten) und bei
     *  bewölktem Himmel liefert GPS oft 30-50m Genauigkeit — mit 30m
     *  wurden dort fast alle Probes verworfen und die Fahrt nie erkannt.
     *  50m verwirft weiterhin Indoor-GPS (50-100m), erfasst aber echte
     *  Stadtfahrten. */
    const val MAX_ACCURACY_M = 50f
    /** Probes älter als 15 Minuten gehören zu einer früheren Fahrt. */
    const val MAX_PROBE_AGE_MS = 15L * 60 * 1000
    /** Mindestanzahl gültiger Probes für eine Entscheidung. */
    const val MIN_VALID_PROBES = 3
    /** Mindestens N aufeinanderfolgende Probes über der Auto-Schwelle.
     *  M18.66-FIX12: 4 -> 5. Vier reichte noch für gelegentliche
     *  False-Positives. Fünf aufeinanderfolgende schnelle Probes bei
     *  5s-Intervall = 25s kontinuierlich >= 32 km/h. Das ist robust
     *  gegen GPS-Bursts und schnelles Radfahren. False-Negative-Risiko
     *  minimal: eine echte Autofahrt hat immer 5+ aufeinanderfolgende
     *  Probes >= 9 m/s.
     *  M18.71: 5 -> 4. Nach einer Ampel-Phase (30-60s Stillstand) muss
     *  die Kette neu aufgebaut werden — 5 schnelle Probes = 25s
     *  Beschleunigung in der Stadt sind oft nicht drin (kurze Grün-
     *  Phasen, Stop&Go). 4 Probes = 20s kontinuierlich >= 28,8 km/h
     *  bleibt robust gegen einzelne GPS-Bursts. */
    const val MIN_CONSECUTIVE_FAST = 4
    /** Probes müssen über mindestens 90 Sekunden verteilt sein.
     *  M18.66-FIX6: 1 Min -> 2 Min. User-Vorschlag: "Durchschnitts-
     *  geschwindigkeit innerhalb von 2 Minuten über 25 km/h". Das
     *  filtert kurze GPS-Bursts zuverlässig heraus.
     *  M18.71: 2 Min -> 90s. Die Erkennung soll schneller ansprechen
     *  (sensibler); 90s Verteilung filtert Bursts weiterhin zuverlässig
     *  (18 Probes bei 5s-Intervall). */
    const val MIN_SPREAD_MS = 90_000L
    /** GPS-Sprung > 2 km zwischen zwei Probes (< 60s auseinander) ist
     *  ein Ausreißer (Tunnel-Sprung, Sensorfehler). */
    const val JUMP_OUTLIER_M = 2000.0
    /** M18.66-FIX13: Mindest-Netto-Displacement (geradlinige Distanz vom
     *  ersten zum letzten Probe). Indoor-GPS-Drift erzeugt Speed-Werte
     *  von 10-30 m/s (Kaltstart, Multipath) — aber die Position springt
     *  nur 10-50m um den selben Punkt. Die Netto-Distanz bleibt klein.
     *  Bei einer echten Fahrt (36 km/h über 2 Min) = 1200m. 200m ist
     *  extrem konservativ (17% der erwarteten Distanz) — selbst enge
     *  Kurvenfahrt übersteigt das. Das ist der wichtigste Filter gegen
     *  False-Positives beim Stillstand, inspiriert von DriveQuant:
     *  "GPS is deliberately not activated while the driver is not moving
     *  or before a trip is confirmed" — wir nutzen die Netto-Displacement
     *  als Bestätigung, dass sich der User BEWEGT HAT, nicht nur dass
     *  GPS Speed meldet.
     *  M18.71: 200m -> 150m. Kurze Stadtfahrten (Supermarkt, Kita,
     *  Stop&Go mit viel Wartezeit) legen in 90s Fenster oft nur
     *  150-250m Netto-Distanz zurück. 150m bleibt weit über der
     *  Indoor-Drift (10-50m) und filtert Stillstand weiterhin ab. */
    const val MIN_NET_DISPLACEMENT_M = 150.0

    /** Ein einzelner Geschwindigkeits-Probe. */
    data class DriveProbe(
        val timestampMs: Long,
        /** Momentane Geschwindigkeit aus dem Location-Fix (m/s), null wenn
         *  der Fix keine Geschwindigkeit liefert. */
        val speedMps: Float?,
        /** GPS-Genauigkeit in Metern. */
        val accuracyMeters: Float,
        /** Distanz zum vorherigen Probe (m), null wenn unbekannt. */
        val distanceFromLastM: Double? = null,
        /** Koordinaten (für die Distanzberechnung im Worker). */
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    sealed class Classification {
        /** Noch nicht genug verwertbare Messungen. */
        data object InsufficientData : Classification()
        /** Messungen vorhanden, aber keine Fahrt (Gehen/Laufen/Fahrrad/Stand). */
        data object NotDriving : Classification()
        /** Fahrt bestätigt. */
        data class Driving(val confidence: Float) : Classification()
    }

    /**
     * Klassifiziert eine Probe-Serie.
     *
     * Ablauf:
     *  1. Alte Probes (> 15 Min) und ungenaue Fixes (> 50 m) verwerfen.
     *  2. Einzelne Geschwindigkeits-Ausreißer (> 40 m/s) verwerfen.
     *  3. GPS-Sprung-Ausreißer (> 2 km in < 60 s) verwerfen.
     *  4. Fahrt = mindestens [MIN_CONSECUTIVE_FAST] aufeinanderfolgende
     *     Probes >= [AUTO_SPEED_MPS] ODER hohe Durchschnittsgeschwindigkeit
     *     mit mehreren schnellen Probes.
     *  5. Zeitliche Verteilung: Die Probes müssen über [MIN_SPREAD_MS]
     *     verteilt sein — ein einzelner Mess-Burst zählt nicht.
     */
    fun classify(
        probes: List<DriveProbe>,
        nowMs: Long = System.currentTimeMillis()
    ): Classification {
        // 1) Fenster + Genauigkeit + Geschwindigkeits-Ausreißer
        val valid = probes
            .filter { nowMs - it.timestampMs <= MAX_PROBE_AGE_MS }
            .filter { it.accuracyMeters <= MAX_ACCURACY_M }
            .filter { it.speedMps == null || it.speedMps <= OUTLIER_SPEED_MPS }
        if (valid.size < MIN_VALID_PROBES) return Classification.InsufficientData

        // 2) Zeitliche Verteilung (mehrere Messungen über einen Zeitraum)
        val spread = valid.maxOf { it.timestampMs } - valid.minOf { it.timestampMs }
        if (spread < MIN_SPREAD_MS) return Classification.InsufficientData

        // 3) GPS-Sprung-Ausreißer entfernen
        val filtered = valid.filterIndexed { i, p ->
            if (i == 0) return@filterIndexed true
            val prev = valid[i - 1]
            val dt = p.timestampMs - prev.timestampMs
            val dist = p.distanceFromLastM
            !(dist != null && dt in 1..60_000 && dist > JUMP_OUTLIER_M)
        }
        if (filtered.size < MIN_VALID_PROBES) return Classification.InsufficientData

        // M18.66-FIX13: NETTO-DISPLACEMENT-GATE.
        // Die wichtigste Defense gegen False-Positives beim Stillstand.
        // GPS-Kaltstart / Multipath / Indoor-Drift kann speed=10-30 m/s
        // liefern — aber die Position springt nur 10-50m um den selben
        // Punkt. Die geradlinige Distanz vom ersten zum letzten Probe
        // bleibt klein. Bei einer echten Fahrt (36 km/h über 2 Min) =
        // 1200m. Wenn die Netto-Distanz < 200m ist, ist es KEINE Fahrt
        // — egal was speed sagt. DriveQuant: "GPS is not activated while
        // the driver is not moving or before a trip is confirmed."
        val firstProbe = filtered.first()
        val lastProbe = filtered.last()
        val netDisplacement = if (firstProbe.latitude != null && firstProbe.longitude != null &&
            lastProbe.latitude != null && lastProbe.longitude != null) {
            haversineMeters(
                firstProbe.latitude!!, firstProbe.longitude!!,
                lastProbe.latitude!!, lastProbe.longitude!!
            )
        } else {
            // Keine Koordinaten → kann nicht validieren → nicht fahren.
            // Besser False-Negative als False-Positive bei Stillstand.
            0.0
        }
        if (netDisplacement < MIN_NET_DISPLACEMENT_M) {
            return Classification.NotDriving
        }

        // 4) Aufeinanderfolgende schnelle Probes + Durchschnitt
        var consecutive = 0
        var maxConsecutive = 0
        var fastCount = 0
        var speedSum = 0f
        var speedCount = 0
        for (p in filtered) {
            val s = p.speedMps
            if (s != null) {
                speedSum += s
                speedCount++
                if (s >= AUTO_SPEED_MPS) {
                    consecutive++
                    maxConsecutive = maxOf(maxConsecutive, consecutive)
                    fastCount++
                } else {
                    consecutive = 0
                }
            }
        }
        val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f

        // M18.68-FIX (False-Negative-Root-Cause): avgSpeed 9 → 6 m/s.
        // M18.66-FIX12 (8→10 m/s + avg 9 m/s = 32,4 km/h) eliminierte
        // False-Positives, machte die Erkennung aber ZU streng: Eine
        // städtische Fahrt (30er-Zone = 8,3 m/s) oder 50 km/h mit nur
        // einer 60s-Ampel-Phase (Durchschnitt ~8,3 m/s) bleibt UNTER
        // dem 9-m/s-Durchschnitt — die Fahrt wird nie bestätigt, obwohl
        // sie Minuten lang mit realer Geschwindigkeit läuft.
        // 6 m/s = 21,6 km/h ist der Mittelwert über eine 2-Minuten-
        // Fahrt, die mindestens 5 schnelle Probes (>= 8 m/s) enthält
        // (5×8 = 40 m/s über 5 Probes; selbst mit 5 langsamen Probes
        // à 1 m/s im selben Fenster ergibt der Schnitt (40+5)/10 = 4,5
        // → 6 m/s fordert also real gefahrene Strecke). Gehen/Laufen
        // (<= 5,5 m/s) und Radfahren (<= 7,5 m/s Durchschnitt über
        // 2 Min — Spitzen > 8 m/s halten Radfahrer nicht 25s) erreichen
        // die Kombination aus 5×8-m/s-Kette + avg 6 m/s + Netto-
        // Displacement ≥ 200 m nicht. Der False-Positive-Schutz bleibt
        // damit intakt (Netto-Displacement-Gate ist der eigentliche
        // Stillstands-Filter), die False-Negatives sind behoben.
        //
        // M18.71 (sensibler): avgSpeed 6 → 5 m/s (18 km/h). Mit der
        // 4er-Kette (>= 8 m/s) und dem 90s-Fenster ist der Durchschnitt
        // einer echten Stadtfahrt mit Ampel-Phasen oft nur 5-6 m/s.
        // 5 m/s bleibt über Geh-Tempo (1,5 m/s) und über dem Schnitt
        // einer Radfahrt mit 4 schnellen Spikes (4×8 + 4×2 = 40/8 = 5 —
        // die scheitert aber an der Konsekutiv-Kette, weil Radfahrer
        // 8 m/s nicht 20s am Stück halten) und am Netto-Displacement.
        val driving = maxConsecutive >= MIN_CONSECUTIVE_FAST &&
            avgSpeed >= 5.0f && fastCount >= MIN_CONSECUTIVE_FAST
        if (!driving) return Classification.NotDriving

        // 5) Konfidenz: Anteil schneller Probes + Geschwindigkeits-Niveau
        val speedRatio = (maxConsecutive.toFloat() / filtered.size).coerceIn(0f, 1f)
        val speedLevel = (avgSpeed / 30f).coerceIn(0f, 1f)
        val confidence = (0.5f * speedRatio + 0.5f * speedLevel).coerceIn(0.6f, 0.95f)
        return Classification.Driving(confidence)
    }

    /** M18.66-FIX13: Haversine-Distanz in Metern (für Netto-Displacement-Gate). */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /**
     * M18.64: Baut aus einer Probe-Serie einen synthetischen
     * [VehicleCluster] für die bestehende Session-Pipeline. Der Start
     * liegt beim ältesten Probe im Fenster — die Fahrt begann spätestens
     * dort (Fallback für "Fahrt begann vor der Erkennung").
     *
     * M18.64-REVIEW-FIX: Mindestens 2 Probes mit >= 60s Spread — der
     * Cluster ist nur der ZEIT-ANKER der Session (die Bestätigung kommt
     * von der Confirmation/Engine). Der DriveConfirmWorker legt genau
     * 2 Fixes 60s auseinander; wenn der AR-Cluster parallel gedrained
     * wurde, muss daraus trotzdem ein Cluster entstehen, sonst ginge
     * die bestätigte Fahrt verloren.
     */
    fun toVehicleCluster(
        probes: List<DriveProbe>,
        nowMs: Long = System.currentTimeMillis()
    ): VehicleCluster? {
        val valid = probes
            .filter { nowMs - it.timestampMs <= MAX_PROBE_AGE_MS }
            .filter { it.accuracyMeters <= MAX_ACCURACY_M }
        if (valid.size < 2) return null
        val spread = valid.maxOf { it.timestampMs } - valid.minOf { it.timestampMs }
        if (spread < 60_000L) return null
        val start = valid.minOf { it.timestampMs }
        val end = valid.maxOf { it.timestampMs }
        return VehicleCluster(
            startMs = start,
            endMs = end,
            lastMs = end,
            sampleCount = valid.size,
            peakConfidence = 75
        )
    }
}
