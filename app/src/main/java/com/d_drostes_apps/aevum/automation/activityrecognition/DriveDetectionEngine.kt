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
    /** Auto-Schwelle: ~29 km/h. Deutlich über Lauf- (~5 m/s) und
     *  Fahrrad-Tempo (~7 m/s). */
    const val AUTO_SPEED_MPS = 8.0f
    /** Unter dieser Geschwindigkeit ist es nie eine Fahrt (Gehen/Laufen). */
    const val WALK_RUN_MAX_MPS = 5.5f
    /** GPS-Ausreißer: > 144 km/h ist kein reales Fahrzeug-Tempo. */
    const val OUTLIER_SPEED_MPS = 40.0f
    /** Ungenaue Fixes verwerfen (schlechte GPS-Lage). */
    const val MAX_ACCURACY_M = 120f
    /** Probes älter als 15 Minuten gehören zu einer früheren Fahrt. */
    const val MAX_PROBE_AGE_MS = 15L * 60 * 1000
    /** Mindestanzahl gültiger Probes für eine Entscheidung. */
    const val MIN_VALID_PROBES = 3
    /** Mindestens N aufeinanderfolgende Probes über der Auto-Schwelle. */
    const val MIN_CONSECUTIVE_FAST = 2
    /** Probes müssen über mindestens 1 Minute verteilt sein (kein
     *  Einzel-Sample-Burst). */
    const val MIN_SPREAD_MS = 60_000L
    /** GPS-Sprung > 2 km zwischen zwei Probes (< 60s auseinander) ist
     *  ein Ausreißer (Tunnel-Sprung, Sensorfehler). */
    const val JUMP_OUTLIER_M = 2000.0

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
     *  1. Alte Probes (> 15 Min) und ungenaue Fixes (> 120 m) verwerfen.
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

        val driving = maxConsecutive >= MIN_CONSECUTIVE_FAST ||
            (avgSpeed >= AUTO_SPEED_MPS + 2f && fastCount >= 2)
        if (!driving) return Classification.NotDriving

        // 5) Konfidenz: Anteil schneller Probes + Geschwindigkeits-Niveau
        val speedRatio = (maxConsecutive.toFloat() / filtered.size).coerceIn(0f, 1f)
        val speedLevel = (avgSpeed / 30f).coerceIn(0f, 1f)
        val confidence = (0.5f * speedRatio + 0.5f * speedLevel).coerceIn(0.6f, 0.95f)
        return Classification.Driving(confidence)
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
