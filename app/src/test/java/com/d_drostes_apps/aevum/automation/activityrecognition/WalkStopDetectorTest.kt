package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.127: Regression-Tests für die Walk-Stop-Erkennung
 * (Autofahrt → „Gehen erkannt" → sofortiger Stop).
 *
 * Szenarien (Kanban-Spec + Design t_e2a5c5c6):
 *  - WALKING mit Confidence ≥ 60 über die 75-s-Gnadenfrist → Stop
 *    (Ausstieg nach dem Parken; Samples real ~30 s auseinander)
 *  - 1 Sample + Ampel-Pause → KEIN Stop (M18.84: Stop&Go ist kein Ausstieg)
 *  - WALKING + frischer Fahrzeug-Tempo-Probe → KEIN Stop, Reset
 *    (M18.117: 8 m/s kann kein Fußgänger)
 *  - IN_VEHICLE/ON_BICYCLE dazwischen → Reset (Fahrt lebt)
 *  - Confidence < 60 → KEIN Stop (verrauschte Einzel-Samples)
 *
 * Timing: Gnadenfrist = WALK_STOP_GRACE_MS = 75 s; der 30-s-Sampling-Takt
 * liefert Samples bei +30 s, +60 s, +90 s → der Stop fällt auf das Sample
 * ≥ 75 s nach dem ersten (real das 3. Sample, ~90 s).
 */
class WalkStopDetectorTest {

    private val t0 = 1_000_000L
    private val detector = WalkStopDetector()

    private fun probe(
        timestampMs: Long,
        speedMps: Float?,
        accuracy: Float = 20f,
        distanceFromLastM: Double? = null
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = timestampMs,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = 50.0,
        longitude = 8.0
    )

    private fun walking(
        now: Long,
        confidence: Int = 80,
        probes: List<DriveDetectionEngine.DriveProbe> = emptyList()
    ) = detector.onSample(
        type = WalkStopDetector.TYPE_WALKING,
        confidence = confidence,
        nowMs = now,
        latestProbes = probes
    )

    // ── Stop-Szenarien ───────────────────────────────────────────

    @Test
    fun `WALKING Confidence 80 ueber 75s Gnadenfrist - Stop`() {
        // Sample 1 startet die Evidenz; +30s und +60s noch innerhalb der
        // Frist; das Sample bei +90s (Takt 3) überschreitet sie → Stop.
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 30_000L)).isFalse()
        assertThat(walking(t0 + 60_000L)).isFalse()
        assertThat(walking(t0 + 90_000L)).isTrue()
    }

    @Test
    fun `RUNNING und ON_FOOT zaehlen wie WALKING`() {
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_RUNNING,
            confidence = 90,
            nowMs = t0,
            latestProbes = emptyList()
        )).isFalse()
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_ON_FOOT,
            confidence = 90,
            nowMs = t0 + 30_000L,
            latestProbes = emptyList()
        )).isFalse()
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_WALKING,
            confidence = 90,
            nowMs = t0 + 90_000L,
            latestProbes = emptyList()
        )).isTrue()
    }

    @Test
    fun `Sample bei 76s ueberschreitet die Frist - Stop`() {
        // Grenzfall: 76s ≥ 75s → Stop, auch wenn das Sample nicht auf dem
        // exakten 30s-Takt liegt (GPS-/GMS-Latenz).
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 76_000L)).isTrue()
    }

    @Test
    fun `nach Stop-Signal + reset beginnt die Kette neu`() {
        // Der Receiver ruft nach einem Stop-Trigger resetWalkStopEvidence()
        // → die Evidenz der beendeten Fahrt zündet nicht erneut.
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 90_000L)).isTrue()
        detector.reset()
        assertThat(walking(t0 + 120_000L)).isFalse() // frischer Start
        assertThat(walking(t0 + 210_000L)).isTrue() // +90s → wieder Stop
    }

    // ── Kein-Stop-Szenarien ───────────────────────────────────────

    @Test
    fun `1 Sample + kurze Pause - kein Stop`() {
        // M18.93v9-Lektion: ein einzelnes AR-Signal ist kein Beweis.
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 20_000L)).isFalse()
    }

    @Test
    fun `Confidence unter 60 - kein Stop`() {
        // Verrauschte Einzel-Samples (Google: „individual predictions
        // may be noisy") zählen nicht.
        assertThat(walking(t0, confidence = 40)).isFalse()
        assertThat(walking(t0 + 30_000L, confidence = 40)).isFalse()
        assertThat(walking(t0 + 90_000L, confidence = 55)).isFalse()
    }

    @Test
    fun `STILL und UNKNOWN - kein Stop und kein Reset`() {
        // Ampel-Ruhe kostet die Evidenz nicht (kein Reset).
        assertThat(walking(t0)).isFalse()
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_ON_FOOT, // nicht STILL — siehe unten
            confidence = 80,
            nowMs = t0 + 20_000L,
            latestProbes = emptyList()
        )).isFalse()
        // STILL (3) und UNKNOWN (4): kein Einfluss.
        assertThat(detector.onSample(
            type = 3,
            confidence = 100,
            nowMs = t0 + 40_000L,
            latestProbes = emptyList()
        )).isFalse()
        assertThat(detector.onSample(
            type = 4,
            confidence = 100,
            nowMs = t0 + 50_000L,
            latestProbes = emptyList()
        )).isFalse()
        // Evidenz lebt weiter → Stop bei +90s (90 ≥ 75).
        assertThat(walking(t0 + 90_000L)).isTrue()
    }

    @Test
    fun `IN_VEHICLE dazwischen - Reset`() {
        assertThat(walking(t0)).isFalse()
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_IN_VEHICLE,
            confidence = 90,
            nowMs = t0 + 20_000L,
            latestProbes = emptyList()
        )).isFalse()
        // Nach dem Reset zählt die Kette von vorn: erst +90s ab dem
        // neuen ersten Sample (+30s) feuert der Stop (+120s).
        assertThat(walking(t0 + 30_000L)).isFalse()
        assertThat(walking(t0 + 120_000L)).isTrue()
    }

    @Test
    fun `ON_BICYCLE dazwischen - Reset`() {
        assertThat(walking(t0)).isFalse()
        assertThat(detector.onSample(
            type = WalkStopDetector.TYPE_ON_BICYCLE,
            confidence = 90,
            nowMs = t0 + 20_000L,
            latestProbes = emptyList()
        )).isFalse()
        assertThat(walking(t0 + 30_000L)).isFalse()
        assertThat(walking(t0 + 120_000L)).isTrue()
    }

    // ── Fahrzeug-Tempo-Veto ───────────────────────────────────────

    @Test
    fun `frischer 9-m-s-Probe - kein Stop und Reset`() {
        // 9 m/s = 32,4 km/h — kein Fußgänger (M18.117-Argumentation).
        val fast = listOf(probe(t0, 9.0f))
        assertThat(walking(t0 + 30_000L, probes = fast)).isFalse()
        // Veto war aktiv → Evidenz verworfen: nächster Geh-Sample startet
        // die Kette neu, kein Stop.
        assertThat(walking(t0 + 60_000L, probes = fast)).isFalse()
    }

    @Test
    fun `8-m-s-Probe direkt an der Schwelle - Veto`() {
        // Schwelle ist inklusiv (AUTO_SPEED_MPS = 8.0).
        val fast = listOf(probe(t0, 8.0f))
        assertThat(walking(t0 + 30_000L, probes = fast)).isFalse()
        assertThat(walking(t0 + 60_000L, probes = fast)).isFalse()
    }

    @Test
    fun `abgeleitetes Fahrzeugtempo aus Distanz - Veto`() {
        // Probe ohne Direct-Speed, aber 600m Distanz in 60s → 10 m/s
        // abgeleitet (M18.77-Semantik).
        val derived = listOf(
            probe(t0, null, distanceFromLastM = null),
            probe(t0 + 60_000L, null, distanceFromLastM = 600.0)
        )
        assertThat(walking(t0 + 90_000L, probes = derived)).isFalse()
        // Veto aktiv → Reset: neue Kette ohne Veto-Probes → Stop erst
        // nach 75s neuer Evidenz.
        assertThat(walking(t0 + 120_000L, probes = derived)).isFalse()
    }

    @Test
    fun `0-m-s-Probe (Ampel) - kein Veto`() {
        // Stillstand widerlegt Gehen NICHT (asymmetrisches Veto): der
        // Stop soll auch feuern, wenn der Ausstiegs-GPS nichts liefert.
        val stopped = listOf(probe(t0, 0.0f))
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 30_000L, probes = stopped)).isFalse()
        assertThat(walking(t0 + 60_000L, probes = stopped)).isFalse()
        assertThat(walking(t0 + 105_000L, probes = stopped)).isTrue() // ≥75s nach erstem Sample
    }

    @Test
    fun `alter oder ungenauer Probe - kein Veto`() {
        // 100 Min alter 25-m/s-Probe: nicht frisch → ignoriert.
        val staleFast = DriveDetectionEngine.DriveProbe(
            timestampMs = t0 - 6_000_000L,
            speedMps = 25f,
            accuracyMeters = 20f
        )
        // Accuracy 80 m (> MAX_ACCURACY_M = 50): verworfen wie in der
        // Engine → kein Veto.
        val inaccurateFast = probe(t0, 9.0f, accuracy = 80f)
        assertThat(walking(t0 + 30_000L, probes = listOf(staleFast, inaccurateFast))).isFalse()
        assertThat(walking(t0 + 120_000L, probes = listOf(staleFast, inaccurateFast))).isTrue()
    }

    @Test
    fun `Geschwindigkeits-Ausreisser ueber 40 m-s - kein Veto`() {
        // GPS-Sprung-Artefakt (OUTLIER_SPEED_MPS = 40) ist KEINE
        // Fahrzeug-Evidenz.
        val spike = listOf(probe(t0, 45.0f))
        assertThat(walking(t0)).isFalse()
        assertThat(walking(t0 + 105_000L, probes = spike)).isTrue()
    }
}
