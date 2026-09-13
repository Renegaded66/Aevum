package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.126-Regression (Crash t_9b1a4b9a): DriveDetectionService crashte
 * mit ArrayIndexOutOfBoundsException (length=1; index=1) in
 * DriveDetectionService$stepListener$1.onSensorChanged, weil EIN
 * gemeinsamer Listener für TYPE_STEP_DETECTOR UND Accelerometer die
 * 3-Achsen-Magnitude event.values[0..2] las — der Step-Detector
 * liefert aber values.length == 1 (ein Event = ein Schritt, values[0]
 * = Konfidenz). Jedes Schritt-Event riss den Prozess.
 *
 * Diese Tests scannen die Service-Quelle und erzwingen das M18.126-
 * Muster:
 *   - Ein Listener mit event.values[N]-Zugriff darf NUR im
 *     Accelerometer-Pfad (stepListener) stehen.
 *   - Der Step-Detector-Listener (stepDetectorListener) darf
 *     values NIE lesen — er ruft ausschließlich tracker.addStep(...).
 * Ein Rückfall auf den gemeinsamen Magnitude-Listener failt hier
 * sofort, ohne Emulator/Device.
 */
class DriveDetectionServiceSensorScanTest {

    private val source: String by lazy {
        val root = File(".").absoluteFile
        val candidates = listOf(
            File(".").resolve(
                "src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveDetectionService.kt"
            ),
            File("..").resolve(
                "app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition/DriveDetectionService.kt"
            )
        )
        candidates.firstOrNull { it.exists() }
            ?.readText()
            ?: error("DriveDetectionService.kt nicht gefunden (CWD=${root.path})")
    }

    @Test
    fun `Step-Detector-Listener liest NIE event values`() {
        val stepDetectorListenerBlock = source.substringAfter(
            "private val stepDetectorListener"
        ).substringBefore("private val stepListener")
        // Der Schritt-Pfad darf keine Magnitude-Formel enthalten.
        assertWithMessage("stepDetectorListener darf event.values NICHT lesen (Step-Detector: length==1)")
            .that(stepDetectorListenerBlock.contains("event.values[")).isFalse()
        // Er muss den Schritt-Zähler füttern.
        assertWithMessage("stepDetectorListener muss tracker.addStep aufrufen")
            .that(stepDetectorListenerBlock.contains("tracker.addStep(")).isTrue()
    }

    @Test
    fun `Magnitude-Zugriff existiert nur im Accelerometer-Fallback-Listener`() {
        val magnitudeBlock = source.substringAfter("private val stepListener")
            .substringBefore("private lateinit var fusedClient")
        assertWithMessage("Accelerometer-Listener muss die Magnitude-Formel enthalten")
            .that(magnitudeBlock.contains("event.values[2]")).isTrue()
        // Er darf den Schritt-Zähler NICHT nutzen (addSample-Pfad).
        assertWithMessage("stepListener (Accelerometer) muss tracker.addSample aufrufen")
            .that(magnitudeBlock.contains("tracker.addSample(")).isTrue()
    }

    @Test
    fun `Registrierung nutzt fuer den Step-Detector den eigenen Listener`() {
        val registerBlock = source.substringAfter("private fun registerStepDetectorIfAvailable")
            .substringBefore("private fun unregisterStepDetector")
        assertWithMessage("Step-Detector-Registrierung muss stepDetectorListener nutzen")
            .that(registerBlock.contains("sm.registerListener(stepDetectorListener, detector,")).isTrue()
        assertWithMessage("Accelerometer-Fallback muss stepListener nutzen")
            .that(registerBlock.contains("sm.registerListener(stepListener, accel,")).isTrue()
        // Kein gemeinsamer Listener mehr für beide Sensoren.
        assertWithMessage("stepListener darf NICHT für den Step-Detector registriert sein")
            .that(registerBlock.contains("sm.registerListener(stepListener, detector,")).isFalse()
    }
}
