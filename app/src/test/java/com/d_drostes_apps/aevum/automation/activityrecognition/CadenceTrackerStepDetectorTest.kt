package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.126-Regression (Crash t_9b1a4b9a): DriveDetectionService crashte
 * im Hintergrund mit
 *   java.lang.ArrayIndexOutOfBoundsException: length=1; index=1
 *   at DriveDetectionService$stepListener$1.onSensorChanged (Zeile 124)
 *
 * ROOT CAUSE (evidenzbasiert aus Logcat, 11.-12.09.2026): Der seit
 * M18.118 gemeinsame Sensor-Listener (stepListener) berechnete aus
 * JEDEM SensorEvent die 3-Achsen-Magnitude event.values[0..2]. Auf dem
 * Motorola Edge 50 Pro registriert der Service aber primär den
 * TYPE_STEP_DETECTOR — und dessen Android-Vertrag ist: values.length
 * == 1 (Konfidenz 0..1), ein Event = EIN Schritt. Jedes Schritt-Event
 * crashte mit index=1 → Prozess-Kill → START_STICKY-Rebirth →
 * Track-Restore → Sensor wieder registriert → nächster Schritt →
 * Crash-Loop. Deshalb stürzte die App genau bei Fahrten (Track-Modus
 * hält den Sensor aktiv, Fahrzeug-Erschütterung triggert den
 * Step-Detector) UND Zuhause (echte Schritte in jedem Burst-Fenster).
 *
 * Der Fix: ZWEI Listener — stepDetectorListener (addStep, liest NIE
 * event.values) für den Step-Detector, stepListener (addSample,
 * Magnitude) nur noch für den Accelerometer-Fallback. Diese Tests
 * sichern den CadenceTracker-Teil ab (die Listener selbst sind
 * Android-gebunden und werden per Source-Scan auf den
 * values-Zugriff geprüft, siehe DriveDetectionServiceSensorScanTest
 * im selben Paket).
 */
class CadenceTrackerStepDetectorTest {

    /** Regelmäßige Schrittfolge mit [hz] Schritten pro Sekunde. */
    private fun steps(hz: Double, durationS: Double): List<Long> =
        (0 until (durationS * hz).toInt()).map { (it / hz * 1000.0).toLong() }

    @Test
    fun `addStep 2,4 Hz erkennt Jogging-Cadence wie der Magnitude-Pfad`() {
        val tracker = CadenceTracker()
        steps(hz = 2.4, durationS = 60.0).forEach { tracker.addStep(it) }
        val cadence = tracker.currentCadenceHz()
        assertThat(cadence).isNotNull()
        assertThat(cadence!!).isAtLeast(2.0f)
        assertThat(cadence).isAtMost(2.8f)
        assertThat(tracker.validFraction()).isEqualTo(1.0f)
        assertThat(
            DriveDetectionEngine.isJoggingCadence(cadence, tracker.validFraction())
        ).isTrue()
    }

    @Test
    fun `addStep 1,5 Hz Gehen ist messbar, aber KEIN Joggen`() {
        val tracker = CadenceTracker()
        steps(hz = 1.5, durationS = 60.0).forEach { tracker.addStep(it) }
        val cadence = tracker.currentCadenceHz()
        assertThat(cadence).isNotNull()
        assertThat(cadence!!).isAtLeast(1.2f)
        assertThat(cadence).isAtMost(1.8f)
        assertThat(
            DriveDetectionEngine.isJoggingCadence(cadence, tracker.validFraction())
        ).isFalse()
    }

    @Test
    fun `addStep ohne Schritte erzeugt keine Cadence`() {
        val tracker = CadenceTracker()
        // Zeit vergeht (Fenster-Abschlüsse), aber kein einziger Schritt.
        (0 until 7).forEach { i -> tracker.addStep(i * 10_000L) }
        assertThat(tracker.currentCadenceHz()).isNull()
        assertThat(tracker.validFraction()).isEqualTo(0f)
    }

    @Test
    fun `defekter Detektor mit konstant 12 Hz wird verworfen`() {
        // Auto-Sitz-Sensor-Fehltrigger: 12 Hz (weit über der
        // physiologischen Obergrenze). Nach 3 konsistenten Fenstern
        // wird die Messung verworfen — kein Jogging-Veto aus Vibration.
        val tracker = CadenceTracker()
        steps(hz = 12.0, durationS = 60.0).forEach { tracker.addStep(it) }
        // Historie ist nach dem Verwerfen leer → keine Cadence.
        assertThat(tracker.currentCadenceHz()).isNull()
        assertThat(tracker.validFraction()).isEqualTo(0f)
    }

    @Test
    fun `einzelnes unplausibles Fenster loest KEINEN Artefakt-Discard aus`() {
        val tracker = CadenceTracker()
        // Fenster 1: Zähl-Artefakt (50 Impulse in 1 s → 5 Hz im 10-s-Fenster).
        steps(hz = 50.0, durationS = 1.0).forEach { tracker.addStep(it) }
        // Fenster 2-5: normale 2,4-Hz-Schrittfolge (50 s, versetzt).
        steps(hz = 2.4, durationS = 50.0).map { it + 10_000L }
            .forEach { tracker.addStep(it) }
        // Kein Discard (nur 1 von 5 Fenstern unplausibel) → Messung lebt;
        // nach dem Herausrollen des Artefakt-Fensters ist die Cadence
        // wieder im Jogging-Band.
        val cadence = tracker.currentCadenceHz()
        assertThat(cadence).isNotNull()
        assertThat(
            DriveDetectionEngine.isJoggingCadence(cadence, tracker.validFraction())
        ).isTrue()
    }
}
