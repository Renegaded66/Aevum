package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.133-REGRESSION: Struktur-Checks der Android-/Sensor-gebundenen Pfade
 * des Step-Walk-Stops (Datei-Scan-Technik wie WalkStopStopPathRegressionTest,
 * DriveStopNotificationRegressionTest, StickyGuardInitRegressionTest).
 *
 * Kanban/User-Report: „Die Aufzeichnung läuft nach dem Ende der Autofahrt
 * noch ~10 Minuten weiter. Sobald ich aussteige und gehe, muss sie stoppen."
 *
 * Gesichert wird:
 *  1. SENSOR-VERDRAHTUNG: Der Step-Detector-Listener im DriveDetectionService
 *     ruft die Ausstiegs-Prüfung auf, BEVOR er in den Cadence-Tracker
 *     zurückkehrt (sonst wäre der Pfad tot, wenn kein Tracker existiert).
 *  2. STOP-VERDRAHTUNG: Das Signal schedult den bewährten DriveStopWorker
 *     (sauberes Stop-Paket) und nicht irgendeinen Direkt-Stop.
 *  3. GATES: Es wird nur bei laufender Fahrt (isDriveActive) und aktivem
 *     Setting (isStepWalkStopEnabled) geprüft.
 *  4. PERMISSION: Der Step-Detector wird ohne ACTIVITY_RECOGNITION nicht
 *     registriert (API-29-Vertrag), der Accelerometer-Fallback bleibt
 *     permissionfrei für die Cadence (M18.118).
 *  5. WATCHDOG-LATENZ: 200 m/2 Min sind 6 km/h (Geh-Tempo!) — der GPS-Check
 *     darf die Fahrt deshalb nur verlängern, wenn KEINE Schritte erkannt
 *     wurden bzw. Fahrzeug-Tempo vorliegt.
 *  6. SESSION-GRENZEN: Der Reset verwirft BEIDE Detektoren (AR + Schritte).
 */
class StepWalkStopWiringRegressionTest {

    private val base =
        File("src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")

    private fun source(name: String): String {
        val f = File(base, name)
        assertWithMessage("Quelldatei fehlt: ${f.absolutePath}").that(f.exists()).isTrue()
        return f.readText()
    }

    // ── 1) Sensor-Verdrahtung ────────────────────────────────────

    @Test
    fun `Step-Detector-Listener ruft die Ausstiegs-Pruefung VOR dem Cadence-Tracker`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("private val stepDetectorListener = object : SensorEventListener")
        assertWithMessage("stepDetectorListener nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("private val stepListener = object", idx)
        val block = src.substring(idx, end)

        val checkIdx = block.indexOf("onStepForWalkStop(nowMs)")
        val trackerIdx = block.indexOf("val tracker = cadenceTracker ?: return")
        assertWithMessage("onStepForWalkStop-Aufruf fehlt im Step-Detector-Listener")
            .that(checkIdx).isAtLeast(0)
        assertWithMessage("Der Ausstiegs-Check muss VOR dem Tracker-Zugriff stehen " +
            "(sonst ist er bei fehlendem Tracker tot)")
            .that(checkIdx).isLessThan(trackerIdx)
        // Der Check nutzt WALL-CLOCK-Zeit — die Probe-Zeitstempel der Bridge
        // sind Wall-Clock; ein Mix mit elapsedRealtimeNanos wäre sinnlos.
        assertThat(block.contains("System.currentTimeMillis()")).isTrue()
    }

    @Test
    fun `Step-Detector wird ohne ACTIVITY_RECOGNITION nicht als Schritte-Quelle genutzt`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("private fun registerStepDetectorIfAvailable()")
        assertWithMessage("registerStepDetectorIfAvailable nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("private fun unregisterStepDetector()", idx)
        val block = src.substring(idx, end)
        // Gate vorhanden…
        assertThat(block.contains("ActivityRecognitionPermission.isGranted(this)")).isTrue()
        assertThat(block.contains("stepDetectorUsable")).isTrue()
        // …und der Accelerometer-Fallback läuft WEITER (M18.118: permissionfrei,
        // trägt das Cadence-Veto auch im AR-losen Fallback-Pfad).
        assertThat(block.contains("TYPE_ACCELEROMETER")).isTrue()
    }

    // ── 2) Stop-Verdrahtung ─────────────────────────────────────

    @Test
    fun `erkanntes Gehen schedult den DriveStopWorker und verbraucht die Evidenz`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("private fun onStepForWalkStop(eventMs: Long)")
        assertWithMessage("onStepForWalkStop nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("// ════", idx)
        val block = src.substring(idx, end)

        assertThat(block.contains("bridge.onStepWalkStopStep(now)")).isTrue()
        assertThat(block.contains("bridge.resetWalkStopEvidence()")).isTrue()
        assertThat(block.contains("DriveStopWorker.schedule(this)")).isTrue()
        // Reihenfolge: Detector-Entscheid → Reset → Schedule.
        val detIdx = block.indexOf("val shouldStop = bridge.onStepWalkStopStep(now)")
        val resetIdx = block.indexOf("bridge.resetWalkStopEvidence()")
        val schedIdx = block.indexOf("DriveStopWorker.schedule(this)")
        assertThat(detIdx).isLessThan(resetIdx)
        assertThat(resetIdx).isLessThan(schedIdx)
    }

    @Test
    fun `der Sensor-Pfad kann den Prozess nicht killen`() {
        // M18.126-Lehre: Der Listener läuft im Sensor-System-Callback —
        // ein Wurf dort killt den Prozess (Crash-Loop im Hintergrund).
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("private fun onStepForWalkStop(eventMs: Long)")
        val end = src.indexOf("// ════", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("try {")).isTrue()
        assertThat(block.contains("catch (e: Exception)")).isTrue()
    }

    // ── 3) Gates ────────────────────────────────────────────────

    @Test
    fun `die Pruefung laeuft nur bei aktiver Fahrt und aktivem Setting`() {
        val src = source("DriveDetectionService.kt")
        val idx = src.indexOf("private fun onStepForWalkStop(eventMs: Long)")
        val end = src.indexOf("// ════", idx)
        val block = src.substring(idx, end)
        // M18.135: „aktive Fahrt" umfasst jetzt auch die automatische
        // Rad-Session — die Live-Session wird direkt gelesen, weil
        // `isDriveActive()` bei einer radfahren-Session false ist und das
        // Gate damit geschlossen hielte, obwohl der Detektor feuert.
        val activeIdx = block.indexOf(
            "if (!bridge.isDriveActive() && !isLiveAutoTrackedSession(autoSession)) return"
        )
        val settingIdx = block.indexOf("if (!bridge.isStepWalkStopEnabled()) return")
        assertWithMessage("Session-Gate fehlt").that(activeIdx).isAtLeast(0)
        assertWithMessage("isStepWalkStopEnabled-Gate fehlt").that(settingIdx).isAtLeast(0)
        // Beide Gates stehen VOR dem Detector-Aufruf.
        assertThat(activeIdx).isLessThan(block.indexOf("bridge.onStepWalkStopStep(now)"))
        assertThat(settingIdx).isLessThan(block.indexOf("bridge.onStepWalkStopStep(now)"))
        // Und der Session-Zustand kommt aus der Live-Session, nicht aus
        // einem zweiten Bridge-Flag (M18.75/M18.76-Lehre).
        assertThat(block.contains("liveActivityManager.liveSession.value")).isTrue()
    }

    @Test
    fun `Bridge liefert das Setting aus dem Cache`() {
        val src = source("ActivityRecognitionWorker.kt")
        assertThat(src.contains("fun isStepWalkStopEnabled(): Boolean")).isTrue()
        assertThat(src.contains("cachedStepWalkStop = settings?.walkStopOnStepsEnabled ?: true")).isTrue()
        // Default AN: Ohne DB-Zeile bzw. bei Query-Fehler bleibt der Stop aktiv.
        assertThat(src.contains("@Volatile private var cachedStepWalkStop = true")).isTrue()
    }

    @Test
    fun `Herzschlag-Veto ist verdrahtet`() {
        // Ohne das Veto würden Vibrations-Fehlzählungen (M18.126) die Fahrt
        // mitten in der Fahrt stoppen. Der Herzschlag (15-s-Refresh im
        // TRACK-Stream) ist der Anker.
        val src = source("ActivityRecognitionWorker.kt")
        val idx = src.indexOf("fun onStepWalkStopStep(")
        assertWithMessage("onStepWalkStopStep nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, idx + 1200)
        assertThat(block.contains("lastVehicleSampleMs")).isTrue()
        assertThat(block.contains("StepWalkStopDetector.VEHICLE_HEARTBEAT_VETO_MS")).isTrue()
    }

    // ── 4) Session-Grenzen ──────────────────────────────────────

    @Test
    fun `Session-Grenzen resetten BEIDE Walk-Stop-Detektoren`() {
        val src = source("ActivityRecognitionWorker.kt")
        val idx = src.indexOf("fun resetWalkStopEvidence()")
        assertWithMessage("resetWalkStopEvidence nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, idx + 200)
        assertThat(block.contains("walkStopDetector.reset()")).isTrue()
        assertThat(block.contains("stepWalkStopDetector.reset()")).isTrue()
    }

    @Test
    fun `alle Stop- und Start-Pfade rufen resetWalkStopEvidence`() {
        // Start + beide Stop-Pfade (Sofort-Stop, Watchdog) — Evidenz darf
        // keine Session-Grenze überleben (M18.127-Muster).
        val src = source("DriveWorkers.kt")
        val count = Regex("bridge\\.resetWalkStopEvidence\\(\\)").findAll(src).count()
        assertWithMessage("Start + DriveStopWorker + DriveWatchdogWorker erwarten 3 Aufrufe, gefunden: $count")
            .that(count).isAtLeast(3)
    }

    // ── 5) Watchdog-Latenz (die Ursache des ~10-Minuten-Nachlaufs) ──

    @Test
    fun `GPS-Bewegungs-Check verlaengert nur ohne Geh-Schritte oder mit Fahrzeug-Tempo`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("if (distance >= DRIVE_MIN_PROBE_MOVEMENT_M)")
        assertWithMessage("GPS-Bewegungs-Check nicht gefunden").that(idx).isAtLeast(0)
        // Blockende = der Stop-Aufruf, der NACH dem Check folgt (robust
        // gegen Längenänderungen der Log-Strings).
        // M18.134: Der Aufruf trägt jetzt zusätzlich isBikeRide — das
        // Fenster wird deshalb über den Funktionsnamen gesucht.
        val stopIdx = src.indexOf("stopDrivingSession(live, triggerRepo, now", idx)
        assertWithMessage("stopDrivingSession-Aufruf nach dem Check nicht gefunden")
            .that(stopIdx).isAtLeast(0)
        val block = src.substring(idx, stopIdx)

        assertThat(block.contains("bridge.hasStepWalkingEvidence(now)")).isTrue()
        assertThat(block.contains("hasFreshVehiclePace(bridge.currentDriveProbes(), now)")).isTrue()
        // Die Verlängerung darf NUR im else-Zweig passieren (walked &&
        // !vehiclePace → durchfallen zum Stop).
        assertThat(block.contains("if (walked && !vehiclePace)")).isTrue()
        val walkedIdx = block.indexOf("if (walked && !vehiclePace)")
        val schedIdx = block.indexOf("schedule(applicationContext)")
        assertWithMessage("Watchdog-Verlängerung fehlt im Block").that(schedIdx).isAtLeast(0)
        assertThat(schedIdx).isGreaterThan(walkedIdx)
    }

    @Test
    fun `Fahrzeug-Tempo-Pruefung im Watchdog nutzt die Engine-Schwellen`() {
        val src = source("DriveWorkers.kt")
        val idx = src.indexOf("private fun hasFreshVehiclePace(")
        assertWithMessage("hasFreshVehiclePace nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, idx + 1600)
        assertThat(block.contains("DriveDetectionEngine.AUTO_SPEED_MPS")).isTrue()
        assertThat(block.contains("DriveDetectionEngine.OUTLIER_SPEED_MPS")).isTrue()
        assertThat(block.contains("DriveDetectionEngine.MAX_ACCURACY_M")).isTrue()
    }

    @Test
    fun `5-Minuten-Watchdog-Konstante bleibt nach der User-Spezifikation`() {
        val src = source("DriveWorkers.kt")
        assertThat(src.contains("private const val DRIVE_WATCHDOG_NO_SIGNAL_MS = 5L * 60 * 1000")).isTrue()
    }

    // ── 6) UI-/DB-Verdrahtung ───────────────────────────────────

    @Test
    fun `Settings-UI bietet den Toggle mit Permission-Gate`() {
        val src = File("src/main/java/com/d_drostes_apps/aevum/ui/screens/settings/TriggerSettingsScreen.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/ui/screens/settings/TriggerSettingsScreen.kt")
        val text = src.readText()
        assertThat(text.contains("fun setStepWalkStop(enabled: Boolean)")).isTrue()
        assertThat(text.contains("R.string.settings_triggers_step_walk_stop")).isTrue()
        // Permission-Gate: derselbe Launcher wie die anderen Bewegungs-Trigger.
        assertThat(text.contains("\"step_walk_stop\" -> viewModel.setStepWalkStop(true)")).isTrue()
    }

    @Test
    fun `DB-Migration v43 fuegt die Spalte mit Default 1 hinzu`() {
        val src = File("src/main/java/com/d_drostes_apps/aevum/data/db/AppDatabase.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/data/db/AppDatabase.kt")
        val text = src.readText()
        assertThat(text.contains("val MIGRATION_42_43 = object : Migration(42, 43)")).isTrue()
        assertThat(text.contains("ADD COLUMN `walk_stop_on_steps_enabled` INTEGER NOT NULL DEFAULT 1")).isTrue()
        assertThat(text.contains("version = 43")).isTrue()
    }

    @Test
    fun `Migration ist in der DatabaseModule registriert`() {
        val src = File("src/main/java/com/d_drostes_apps/aevum/di/DatabaseModule.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/di/DatabaseModule.kt")
        assertThat(src.readText().contains("AppDatabase.MIGRATION_42_43")).isTrue()
    }
}
