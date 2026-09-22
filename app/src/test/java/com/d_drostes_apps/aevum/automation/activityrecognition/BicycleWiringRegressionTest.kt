package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.134-REGRESSION (Kanban t_a860c07f): Struktur-Checks für die
 * Android-/GMS-gebundenen Rad-Pfade, die als reine JVM-Unit-Tests nicht
 * instanziierbar sind (BroadcastReceiver, GMS-Client, FGS). Gleiche
 * Datei-Scan-Technik wie FastStartWiringRegressionTest (M18.128) und
 * StepWalkStopWiringRegressionTest (M18.133).
 *
 * Der gemeldete Bug war ein ZWEI-Zeilen-Fehler: der ON_BICYCLE-Zweig
 * des Continuous-Receivers setzte bewusst keinen Motion-Kontext. Genau
 * solche Verdrahtungen kann ein Verhaltenstest nicht sichern — deshalb
 * hier die Quelltext-Prüfung der drei Empfangspfade.
 *
 * Gesichert wird:
 *  1. CONTINUOUS-RECEIVER: ON_BICYCLE setzt Kontext + Rad-Evidence +
 *     Rad-Setting-Gate — der Kern des Fixes.
 *  2. TRANSITION-RECEIVER: ON_BICYCLE-ENTER setzt Kontext + Evidence,
 *     EXIT nimmt den Kontext zurück.
 *  3. BOOT-SNAPSHOT: ON_BICYCLE nach dem Neustart setzt Kontext+Evidence.
 *  4. ENGINE: das ON_BICYCLE-Gate existiert mit den vier Konstanten,
 *     und der Pace-Override wird NUR für ON_FOOT ausgewertet.
 *  5. STOP-PFADE: Watchdog/Stop matchen auch `radfahren`.
 */
class BicycleWiringRegressionTest {

    private val base =
        File("src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")

    private fun source(name: String): String {
        val f = File(base, name)
        assertWithMessage("Quelldatei fehlt: ${f.absolutePath}").that(f.exists()).isTrue()
        return f.readText()
    }

    // ── 1) Continuous-Receiver (der eigentliche Bug) ───────────────

    @Test
    fun `ON_BICYCLE-Zweig setzt den Motion-Kontext`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.ON_BICYCLE ->")
        assertWithMessage("ON_BICYCLE-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("else -> Unit", idx)
        val block = src.substring(idx, end)
        assertWithMessage("Der ON_BICYCLE-Zweig MUSS den Kontext setzen — ohne ihn gilt die 8-m/s-Schwelle und 25-km/h-Radfahrten werden als Autofahrt aufgezeichnet")
            .that(block.contains("updateMotionContext(DriveDetectionEngine.MotionContext.ON_BICYCLE)"))
            .isTrue()
    }

    @Test
    fun `ON_BICYCLE-Zweig registriert die Rad-Evidence mit Confidence`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.ON_BICYCLE ->")
        val end = src.indexOf("else -> Unit", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("bridge.onBicycleSampleWithConfidence(top.confidence)")).isTrue()
        // Die M18.128-Widerlegung der Fahrzeug-Evidence bleibt bestehen.
        assertThat(block.contains("bridge.onBicycleSample()")).isTrue()
    }

    @Test
    fun `ON_BICYCLE-Zweig respektiert das Rad-Setting`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("DetectedActivity.ON_BICYCLE ->")
        val end = src.indexOf("else -> Unit", idx)
        val block = src.substring(idx, end)
        assertWithMessage("Der UI-Toggle „Radfahren\" muss auch im Continuous-Zweig wirken (vorher nur im Transition-Receiver)")
            .that(block.contains("isBicycleEnabled()"))
            .isTrue()
    }

    // ── 2) Transition-Receiver ────────────────────────────────────

    @Test
    fun `Transition-Receiver setzt Kontext und Evidence bei ON_BICYCLE-ENTER`() {
        val src = source("ActivityRecognitionWorker.kt")
        val idx = src.indexOf("if (event.activityType == DetectedActivity.ON_BICYCLE) {")
        assertWithMessage("ON_BICYCLE-Transition-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 4000).coerceAtMost(src.length))
        assertThat(block.contains("bridge.onBicycleSampleWithConfidence(75, now)")).isTrue()
        assertThat(block.contains("MotionContext.ON_BICYCLE")).isTrue()
        // EXIT nimmt den Kontext zurück (8-m/s-Schwelle gilt wieder).
        assertThat(block.contains("MotionContext.UNKNOWN")).isTrue()
        // Der M15-Trigger-Marker bleibt erhalten.
        assertThat(block.contains("enqueueTriggerWorker(context, event.activityType, transitionType)")).isTrue()
    }

    @Test
    fun `Kontext-Setzen ist toggle-frei - nur Session und Marker haengen am Rad-Setting`() {
        // Wichtige Korrektheits-Eigenschaft: Mit ausgeschalteter
        // Rad-Erkennung darf 25 km/h Radfahren NICHT als Autofahrt
        // gelten. Der Toggle steuert die Session/den Marker, nicht die
        // Klassifikation. Deshalb: onBicycleSampleWithConfidence und
        // updateMotionContext stehen VOR jeder bicycleOk-Prüfung.
        val src = source("ActivityRecognitionWorker.kt")
        val idx = src.indexOf("if (event.activityType == DetectedActivity.ON_BICYCLE) {")
        val block = src.substring(idx, (idx + 4000).coerceAtMost(src.length))
        val evidenceIdx = block.indexOf("bridge.onBicycleSampleWithConfidence(75, now)")
        val contextIdx = block.indexOf("MotionContext.ON_BICYCLE")
        val gateIdx = block.indexOf("if (bicycleOk) {")
        assertThat(evidenceIdx).isAtLeast(0)
        assertThat(contextIdx).isAtLeast(0)
        assertThat(gateIdx).isAtLeast(0)
        assertWithMessage("Kontext+Evidence müssen VOR dem Setting-Gate stehen (sonst bleibt die 8-m/s-Schwelle bei deaktivierter Rad-Erkennung aktiv)")
            .that(evidenceIdx).isLessThan(gateIdx)
        assertThat(contextIdx).isLessThan(gateIdx)
    }

    // ── 3) Boot-Snapshot ──────────────────────────────────────────

    @Test
    fun `Boot-Snapshot setzt den Rad-Kontext bei laufendem ON_BICYCLE`() {
        val src = source("InitialActivitySnapshotWorker.kt")
        val idx = src.indexOf("DetectedActivity.ON_BICYCLE ->")
        assertWithMessage("ON_BICYCLE-Zweig im Boot-Snapshot fehlt").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 700).coerceAtMost(src.length))
        assertThat(block.contains("MotionContext.ON_BICYCLE")).isTrue()
        assertThat(block.contains("onBicycleSampleWithConfidence")).isTrue()
    }

    // ── 4) Engine-Gate ────────────────────────────────────────────

    @Test
    fun `Engine kennt den vierten Motion-Kontext und die Rad-Konstanten`() {
        val src = source("DriveDetectionEngine.kt")
        assertThat(src.contains("ON_BICYCLE")).isTrue()
        assertThat(src.contains("const val BIKE_DRIVE_SPEED_MPS = 12.0f")).isTrue()
        assertThat(src.contains("const val BIKE_MIN_CONSECUTIVE_FAST = 3")).isTrue()
        assertThat(src.contains("const val BIKE_MIN_AVG_SPEED_MPS = 12.0f")).isTrue()
        assertThat(src.contains("const val MIN_BIKE_RIDE_AVG_MPS = 4.0f")).isTrue()
        assertThat(src.contains("fun detectBikeRide(")).isTrue()
        assertThat(src.contains("fun isReliableBicycleSignal(")).isTrue()
        assertThat(src.contains("data class BicycleEvidence(")).isTrue()
    }

    @Test
    fun `Pace-Override wird NUR fuer ON_FOOT ausgewertet`() {
        // Der M18.130-Override nimmt die 12-m/s-Schwelle auf 8 m/s
        // zurück. Unter ON_BICYCLE würde er das Rad-Loch wieder öffnen
        // (gemessen: 100 % Driving für 25-km/h-Profile mit Antritten) —
        // er darf deshalb nur an ON_FOOT hängen.
        val src = source("DriveDetectionEngine.kt")
        val idx = src.indexOf("val vehiclePace = if (")
        assertWithMessage("vehiclePace-Block nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 120).coerceAtMost(src.length))
        assertThat(block.contains("motionContext == MotionContext.ON_FOOT")).isTrue()

        // bikeGated ist unabhängig davon und nutzt den eigenen Block.
        val bikeIdx = src.indexOf("val bikeGated = motionContext == MotionContext.ON_BICYCLE")
        assertThat(bikeIdx).isAtLeast(0)
        val bikeBlock = src.substring(bikeIdx, (bikeIdx + 700).coerceAtMost(src.length))
        assertThat(bikeBlock.contains("BIKE_DRIVE_SPEED_MPS")).isTrue()
        assertThat(bikeBlock.contains("BIKE_MIN_CONSECUTIVE_FAST")).isTrue()
        assertThat(bikeBlock.contains("BIKE_MIN_AVG_SPEED_MPS")).isTrue()
    }

    // ── 5) Stop-Pfade kennen die Rad-Session ──────────────────────

    @Test
    fun `Watchdog und Sofort-Stop matchen auch die Rad-Session`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("private fun isAutoTrackedSession(")
        assertWithMessage("isAutoTrackedSession-Helfer fehlt").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 600).coerceAtMost(src.length))
        assertThat(block.contains("\"driving\"")).isTrue()
        assertThat(block.contains("\"radfahren\"")).isTrue()
        // Beide Stop-Pfade nutzen den Helfer.
        val count = Regex("isAutoTrackedSession\\(session\\)").findAll(src).count()
        assertWithMessage("Watchdog + DriveStopWorker müssen beide matchen, gefunden: $count")
            .that(count).isEqualTo(2)
    }

    @Test
    fun `Radfahrt bekommt eigene Trigger-Marker und keinen Geofence-Re-Enter`() {
        val src = source("DriveWorkers.kt")
        assertThat(src.contains("TRIGGER_BICYCLE_ENDED")).isTrue()
        val idx = src.indexOf("if (!isBikeRide) {")
        assertWithMessage("Geofence-Re-Enter muss für Radfahrten ausgeschlossen sein")
            .that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 300).coerceAtMost(src.length))
        assertThat(block.contains("DriveEndGeofenceRestarter.schedule")).isTrue()
    }

    @Test
    fun `Rad-Start-Worker existiert mit allen drei Gates`() {
        val src = source("BicycleWorkers.kt")
        assertThat(src.contains("class BicycleStartWorker")).isTrue()
        assertThat(src.contains("isBicycleEnabled()")).isTrue()
        assertThat(src.contains("isReliableBicycleSignal")).isTrue()
        assertThat(src.contains("detectBikeRide")).isTrue()
        assertThat(src.contains("activityTypeId = \"radfahren\"")).isTrue()
        assertThat(src.contains("sourceType = \"ACTIVITY_RECOGNITION_AUTO\"")).isTrue()
        assertThat(src.contains("TRIGGER_BICYCLE_STARTED")).isTrue()
        // Start-Anker: Rückdatierung auf den ersten bewegten Probe.
        assertThat(src.contains("startedAt = ride.startMs.coerceAtMost(now)")).isTrue()
    }

    @Test
    fun `Bridge bietet die Rad-Evidence synchronisiert`() {
        val src = source("ActivityRecognitionWorker.kt")
        assertThat(src.contains("fun onBicycleSampleWithConfidence(confidence: Int, nowMs: Long = System.currentTimeMillis())")).isTrue()
        assertThat(src.contains("fun resetBicycleEvidence()")).isTrue()
        assertThat(src.contains("fun bicycleEvidence(): DriveDetectionEngine.BicycleEvidence?")).isTrue()
        val count = Regex("@Synchronized\\n    fun (onBicycleSampleWithConfidence|resetBicycleEvidence|bicycleEvidence)\\(")
            .findAll(src).count()
        assertWithMessage("Alle 3 Rad-Evidence-Methoden müssen @Synchronized sein")
            .that(count).isEqualTo(3)
    }

    // ── 6) M18.135: Zweirad-Kontext-Gate (Kanban t_8e2889cd) ──────

    @Test
    fun `EXIT-Pfad markiert den Rad-EXIT und setzt den Roh-Kontext zurueck`() {
        // Der Transition-Receiver nimmt den ROHEN Kontext weiterhin auf
        // UNKNOWN zurück (M18.117-Hysterese, Bestand) — meldet den EXIT
        // aber zusätzlich an das Gate, das die Klassifikation schützt.
        val src = source("ActivityRecognitionWorker.kt")
        val idx = src.indexOf("if (event.activityType == DetectedActivity.ON_BICYCLE) {")
        assertThat(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 4000).coerceAtMost(src.length))
        assertWithMessage("Der ON_BICYCLE-EXIT muss das Zweirad-Gate melden (sonst bleibt das ~60-s-Loch offen)")
            .that(block.contains("bridge.onBicycleExit(now)")).isTrue()
        assertThat(block.contains("MotionContext.UNKNOWN")).isTrue()
    }

    @Test
    fun `die Klassifikation liest den effektiven Kontext - Rohwert bleibt diagnostizierbar`() {
        val src = source("ActivityRecognitionWorker.kt")
        assertThat(src.contains("bikeContextGuard.effectiveContext(System.currentTimeMillis(), motionContext)")).isTrue()
        assertThat(src.contains("fun rawMotionContext(): DriveDetectionEngine.MotionContext")).isTrue()
        assertThat(src.contains("fun isBikeGateActive(nowMs: Long = System.currentTimeMillis()): Boolean")).isTrue()
    }

    @Test
    fun `Start-Seite - DriveStartWorker kennt die laufende Rad-Session`() {
        // Die zweite Schutzebene: Kein Auto-Start über eine laufende
        // radfahren-Session (Abnahmekriterium 2) — mit der dokumentierten
        // Ausnahme „Bewegung auf Fahrzeug-Niveau" (M18.130).
        val src = source("DriveWorkers.kt")
        assertThat(src.contains("isAutoTrackedSession(liveSessionForCooldown)")).isTrue()
        val idx = src.indexOf("liveSession!!.activityTypeId == \"radfahren\"")
        assertWithMessage("Rad-Session-Guard im DriveStartWorker fehlt").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 900).coerceAtMost(src.length))
        assertThat(block.contains("DriveDetectionEngine.isVehicleLevelMovement(")).isTrue()
        assertThat(block.contains("return Result.success()")).isTrue()
    }

    @Test
    fun `Trigger-Seite - die Stop-Gates lesen die Live-Session statt isDriveActive allein`() {
        // Dritte Ebene (dieselbe Wurzel): Die Walk-/Step-Stop-TRIGGER
        // waren für eine Rad-Session geschlossen, weil isDriveActive dort
        // false ist. Jetzt entscheidet der Live-Session-Zustand mit.
        val continuous = source("ActivityContinuousSamples.kt")
        assertThat(continuous.contains("isLiveAutoTrackedSession(autoSession)")).isTrue()
        assertThat(continuous.contains("liveActivityManager.liveSession.value")).isTrue()

        val service = source("DriveDetectionService.kt")
        val idx = service.indexOf("private fun onStepForWalkStop(eventMs: Long)")
        assertThat(idx).isAtLeast(0)
        val block = service.substring(idx, (idx + 2000).coerceAtMost(service.length))
        assertThat(block.contains("!isLiveAutoTrackedSession(autoSession)")).isTrue()
        assertThat(block.contains("liveActivityManager.liveSession.value")).isTrue()
    }

    @Test
    fun `Engine bietet die Fahrzeug-Niveau-Pruefung fuer die Rad-Session-Ablosung`() {
        val src = source("DriveDetectionEngine.kt")
        assertThat(src.contains("fun isVehicleLevelMovement(")).isTrue()
        assertThat(src.contains("fun isReliableVehicleSignal(")).isTrue()
        // Gate-treu: es wird mit dem Zweirad-Kontext klassifiziert.
        val idx = src.indexOf("fun isVehicleLevelMovement(")
        val block = src.substring(idx, (idx + 400).coerceAtMost(src.length))
        assertThat(block.contains("MotionContext.ON_BICYCLE")).isTrue()
    }

    @Test
    fun `das Gate ist pure Logik und Android-frei`() {
        // Wie DriveDetectionEngine/WalkStopDetector/StepWalkStopDetector:
        // keine Android-Imports, damit die JVM-Tests die echten Klassen
        // fahren (kein Robolectric, keine Kopie der Logik).
        val src = source("BikeContextGuard.kt")
        assertThat(src.contains("class BikeContextGuard")).isTrue()
        val androidImports = Regex("^import android", RegexOption.MULTILINE).findAll(src).count()
        assertWithMessage("BikeContextGuard muss Android-frei bleiben (JVM-testbar)")
            .that(androidImports).isEqualTo(0)
        // Die Frische-Grenze ist an die Rad-Evidence gekoppelt (eine
        // Zeitbasis für Gate und Session).
        assertThat(src.contains("DriveDetectionEngine.BICYCLE_EVIDENCE_MAX_AGE_MS")).isTrue()
    }
}
