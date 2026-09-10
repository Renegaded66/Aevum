package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.117: Motion-Gate + Cadence-Veto (Audit docs/activity-detection.md).
 *
 * User-Bug (Kanban t_50a4847b): „Joggen 16 km/h wird als Autofahren
 * aufgezeichnet" + „Spazieren in der 30er-Zone wird als Autofahren
 * aufgezeichnet". Die Engine kennt NUR GPS-Speed; das fehlende Signal
 * ist die Handy-Bewegung (AR-Typ + Schrittfrequenz).
 *
 * Kern-Regeln:
 *  - motionContext = ON_FOOT (AR: WALKING/RUNNING/ON_FOOT) hebt die
 *    Auto-Schwelle auf 12 m/s (43,2 km/h), die Konsekutiv-Kette auf 3
 *    und den Fenster-Schnitt auf 6 m/s. Joggen 16 km/h (4,44 m/s) +
 *    2 Multipath-Spikes bleiben damit weit unter allen Gates.
 *  - motionContext = IN_VEHICLE oder UNKNOWN (kein AR-Signal): 8 m/s
 *    unverändert — die 30er-Zonen-Erkennung bleibt erhalten.
 *  - Cadence-Veto: Schrittfrequenz im Jogging-Band (2,2–3,2 Hz, stabil
 *    ≥ 60 %) + Fenster-Schnitt 2,5–8,0 m/s → NotDriving, UNabhängig vom
 *    AR-Kontext (Sensor braucht keine AR-Permission).
 */
class DriveMotionGateTest {

    private val t0 = 1_000_000L

    /** Probe mit REALISTISCHER Positions-Distanz: der lat-Schritt wird
     *  aus der Speed abgeleitet (dt=120s), damit Speed und Position
     *  konsistent sind — außer bei Spikes, die bewusst WIDERSPRÜCHLICH
     *  gesetzt werden (hohe Speed, Positions-Distanz bleibt im Lauf-
     *  Bereich). */
    private fun probe(
        index: Int,
        speedMps: Float?,
        accuracy: Float = 20f,
        latitude: Double?,
        longitude: Double? = 8.0
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = t0 + index * 120_000L,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = null,
        latitude = latitude,
        longitude = longitude
    )

    // ── Fall 1: Joggen 16 km/h + 2 GPS-Multipath-Spikes ────────────

    @Test
    fun `Joggen 16 kmh mit 2 Spikes und ON_FOOT-Kontext ist KEINE Fahrt`() {
        // Audit-Simulation (docs/activity-detection.md §3.1): Jogger
        // 4,44 m/s (0,004° lat/2 Min ≈ 444 m), 2 Multipath-Spikes à
        // 8,5 m/s. OHNE Motion-Gate: fast=2, maxConsecutive=2, avg=5,79
        // → Driving (FP). MIT ON_FOOT: 12-m/s-Schwelle → Spikes zählen
        // nicht → NotDriving.
        val jogStep = 0.004
        val probes = listOf(
            probe(0, 4.4f, latitude = 50.0),
            probe(1, 8.5f, latitude = 50.0 + jogStep),      // SPIKE
            probe(2, 8.5f, latitude = 50.0 + 2 * jogStep), // SPIKE
            probe(3, 4.4f, latitude = 50.0 + 3 * jogStep),
            probe(4, 4.4f, latitude = 50.0 + 4 * jogStep),
            probe(5, 4.4f, latitude = 50.0 + 5 * jogStep)
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.ON_FOOT
        )
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Joggen 16 kmh mit 2 Spikes und UNKNOWN-Kontext bleibt Fahrt-FP — Cadence-Veto greift`() {
        // OHNE AR-Signal (Permission fehlt) bleibt der Kontext UNKNOWN →
        // 8-m/s-Schwelle → die Spikes erfüllen die Gates (fast=2,
        // maxConsecutive=2, avg=5,79 ≥ 4,5) → Driving. Genau hier greift
        // das Cadence-Veto: Schrittfrequenz 2,4 Hz (Jogging-Band) +
        // Schnitt 5,79 m/s (2,5–8,0) → NotDriving, auch ohne AR.
        val jogStep = 0.004
        val probes = listOf(
            probe(0, 4.4f, latitude = 50.0),
            probe(1, 8.5f, latitude = 50.0 + jogStep),      // SPIKE
            probe(2, 8.5f, latitude = 50.0 + 2 * jogStep), // SPIKE
            probe(3, 4.4f, latitude = 50.0 + 3 * jogStep),
            probe(4, 4.4f, latitude = 50.0 + 4 * jogStep),
            probe(5, 4.4f, latitude = 50.0 + 5 * jogStep)
        )
        // Ohne Cadence: UNKNOWN → Driving (der gemeldete Bug).
        val withoutCadence = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.UNKNOWN
        )
        assertThat(withoutCadence).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
        // Mit Cadence 2,4 Hz (stabil): NotDriving.
        val withCadence = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.UNKNOWN,
            cadenceHz = 2.4f,
            cadenceValidFraction = 0.8f
        )
        assertThat(withCadence).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    // ── Fall 2: Spazieren in der 30er-Zone ─────────────────────────

    @Test
    fun `30er-Zone-Fahrt mit IN_VEHICLE-Kontext bleibt eine Fahrt`() {
        // Regression M18.113: 30 km/h = 8,3 m/s konstant, AR meldet
        // IN_VEHICLE → 8-m/s-Schwelle unverändert → Driving.
        val driveStep = 0.009
        val probes = listOf(
            probe(0, 3.0f, latitude = 50.0),
            probe(1, 8.3f, latitude = 50.0 + driveStep),
            probe(2, 8.3f, latitude = 50.0 + 2 * driveStep),
            probe(3, 11.1f, latitude = 50.0 + 3 * driveStep),
            probe(4, 8.3f, latitude = 50.0 + 4 * driveStep)
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.IN_VEHICLE
        )
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `30er-Zone-Fahrt mit ON_FOOT-Kontext (AR-Flackern) ist KEINE Fahrt`() {
        // Audit §4.5: Google meldet WALKING während Stop&Go-Fahrten.
        // ON_FOOT → 12-m/s-Schwelle → 8,3 m/s zählt nicht → NotDriving.
        // Trade-off akzeptiert: False-Positive (Joggen als Fahrt) ist der
        // gemeldete Bug; die Fahrt heilt der nächste IN_VEHICLE-Sample.
        val driveStep = 0.009
        val probes = listOf(
            probe(0, 3.0f, latitude = 50.0),
            probe(1, 8.3f, latitude = 50.0 + driveStep),
            probe(2, 8.3f, latitude = 50.0 + 2 * driveStep),
            probe(3, 11.1f, latitude = 50.0 + 3 * driveStep),
            probe(4, 8.3f, latitude = 50.0 + 4 * driveStep)
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.ON_FOOT
        )
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `30er-Zone-Fahrt mit UNKNOWN-Kontext bleibt eine Fahrt`() {
        // Kein AR-Signal (Permission fehlt) → UNKNOWN → 8 m/s wie heute.
        // Kein neues False-Negative-Risiko (Audit §4.5).
        val driveStep = 0.009
        val probes = listOf(
            probe(0, 3.0f, latitude = 50.0),
            probe(1, 8.3f, latitude = 50.0 + driveStep),
            probe(2, 8.3f, latitude = 50.0 + 2 * driveStep),
            probe(3, 11.1f, latitude = 50.0 + 3 * driveStep),
            probe(4, 8.3f, latitude = 50.0 + 4 * driveStep)
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.UNKNOWN
        )
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    // ── Echte Fahrt trotz ON_FOOT-Kontext ──────────────────────────

    @Test
    fun `echte Fahrt mit 12-m-s-Spitzen wird auch bei ON_FOOT erkannt`() {
        // ON_FOOT verlangt 3 konsekutive ≥ 12 m/s + Schnitt ≥ 6 m/s.
        // Eine echte Stadt-/Landfahrt (12-25 m/s) erfüllt das locker.
        val driveStep = 0.02 // ~2200 m/2 Min ≈ 18,4 m/s
        val probes = (0..5).map { probe(it, 22.0f, latitude = 50.0 + it * driveStep) }
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.ON_FOOT
        )
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `ON_FOOT mit nur 2 schnellen Probes reicht nicht — 3er-Kette noetig`() {
        // MOTION_GATED_MIN_CONSECUTIVE_FAST = 3: 2 schnelle Probes
        // (Burst) dürfen auch bei ON_FOOT keine Fahrt starten.
        val driveStep = 0.02
        val probes = listOf(
            probe(0, 3.0f, latitude = 50.0),
            probe(1, 22.0f, latitude = 50.0 + driveStep),
            probe(2, 22.0f, latitude = 50.0 + 2 * driveStep),
            probe(3, 3.0f, latitude = 50.0 + 3 * driveStep)
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.ON_FOOT
        )
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    // ── Cadence-Veto (pure Funktion) ───────────────────────────────

    @Test
    fun `Joggen-Cadence 2,4 Hz mit stabiler Schaetzung ist Joggen`() {
        assertThat(
            DriveDetectionEngine.isJoggingCadence(2.4f, 0.8f)
        ).isTrue()
    }

    @Test
    fun `Geh-Cadence 1,5 Hz ist kein Joggen`() {
        assertThat(
            DriveDetectionEngine.isJoggingCadence(1.5f, 0.8f)
        ).isFalse()
    }

    @Test
    fun `Auto-Vibration 12 Hz ist kein Joggen`() {
        // Audit §4.2.2: Fahrzeug-Vibration ist hochfrequent (> 10 Hz)
        // und niederamplitudig — sie erzeugt keine 2,2-Hz-Schrittperiode.
        assertThat(
            DriveDetectionEngine.isJoggingCadence(12.0f, 0.8f)
        ).isFalse()
    }

    @Test
    fun `unstabile Schaetzung (unter 60 Prozent) ist kein Joggen`() {
        assertThat(
            DriveDetectionEngine.isJoggingCadence(2.4f, 0.4f)
        ).isFalse()
    }

    @Test
    fun `keine Cadence-Messung ist kein Joggen`() {
        assertThat(
            DriveDetectionEngine.isJoggingCadence(null, 0.8f)
        ).isFalse()
    }

    @Test
    fun `Cadence-Veto greift nicht bei echter Fahrt-Speed ueber 8 ms`() {
        // 22 m/s (80 km/h) liegt außerhalb des Veto-Bereichs (2,5–8,0):
        // Selbst eine (fehlerhafte) Jogging-Cadence darf eine echte
        // Fahrt nicht blockieren.
        val driveStep = 0.02
        val probes = (0..5).map { probe(it, 22.0f, latitude = 50.0 + it * driveStep) }
        val result = DriveDetectionEngine.classify(
            probes, t0 + 16L * 60 * 1000,
            motionContext = DriveDetectionEngine.MotionContext.UNKNOWN,
            cadenceHz = 2.4f,
            cadenceValidFraction = 0.8f
        )
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }
}
