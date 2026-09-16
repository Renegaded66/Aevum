package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.127-REGRESSION (Kanban t_ad14b740): Struktur-Checks für die
 * Android-/GMS-gebundenen Pfade der Walk-Stop-Erkennung, die als
 * reine JVM-Unit-Tests nicht instanziierbar sind (WorkManager,
 * BroadcastReceiver, GMS-Client). Gleiche Datei-Scan-Technik wie
 * DriveStopNotificationRegressionTest (M18.124b) und
 * StickyGuardInitRegressionTest (M18.123).
 *
 * Gesichert wird:
 *  1. PERMISSION-GATE: register()/unregister() der Continuous-Samples
 *     sind ohne ACTIVITY_RECOGNITION-Grant No-Ops — der Receiver
 *     bricht OHNE Grant sofort ab. (Dieselbe Prüfung, die der
 *     ActivityTransitionReceiver seit M18.112 hat.)
 *  2. STOP-VERDRAHTUNG: Ein Walk-Stop-Signal (Detector true) während
 *     einer aktiven Fahrt schedult den bestehenden DriveStopWorker
 *     (sofortiger sauberer Stop) und resetet die Evidenz — der
 *     uralte M18.93v9-Fehler „2 Aufzeichnungen mit Leerraum" (nur
 *     ein Signal, kein sauberer Stop) darf nicht zurückkehren.
 *  3. 5-MIN-FALLBACK INTACT: Die User-Spezifikation („endet wenn
 *     5 Minuten keine Fahrt erkannt wird") bleibt der unangetastete
 *     Fallback — DRIVE_WATCHDOG_NO_SIGNAL_MS ist unverändert 5 Min,
 *     der Watchdog wird beim Fahrt-Start (DriveStartWorker) UND beim
 *     Refresh laufender Fahrten (Heartbeat-Pfad) schedult, und der
 *     Walk-Stop-Pfad darf NIE einen Weg einschlagen, der eine Fahrt
 *     verlängert.
 */
class WalkStopStopPathRegressionTest {

    private val base =
        File("src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/d_drostes_apps/aevum/automation/activityrecognition")

    private fun source(name: String): String {
        val f = File(base, name)
        assertWithMessage("Quelldatei fehlt: ${f.absolutePath}").that(f.exists()).isTrue()
        return f.readText()
    }

    // ── 1) Permission-Denial-Gate ────────────────────────────────

    @Test
    fun `Continuous-Samples register ohne Permission - No-Op`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("fun register(context: Context)")
        assertWithMessage("register() nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, src.indexOf("private fun continuousPendingIntent", idx))
        assertThat(block.contains("if (!ActivityRecognitionPermission.isGranted(context))")).isTrue()
        // No-Op-Pfad: ohne Grant gibt es KEINEN GMS-Call.
        assertThat(block.substring(block.indexOf("isGranted(context)"), block.indexOf("val pendingIntent")))
            .contains("return")
    }

    @Test
    fun `Continuous-Samples unregister ohne Permission - No-Op`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("fun unregister(context: Context)")
        assertWithMessage("unregister() nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, src.indexOf("val pendingIntent = continuousPendingIntent(context)", idx))
        assertThat(block.contains("if (!ActivityRecognitionPermission.isGranted(context))")).isTrue()
        // Kein removeActivityUpdates im No-Op-Pfad (der GMS-Call steht
        // erst NACH dem Grant-Check — idempotenter Remove nur mit Grant).
        assertThat(block.substring(block.indexOf("isGranted(context)"), block.length))
            .contains("return")
        assertThat(block).doesNotContain("removeActivityUpdates")
    }

    @Test
    fun `Receiver verwirft Samples ohne Permission`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("override fun onReceive(context: Context, intent: Intent)")
        assertWithMessage("onReceive nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, src.indexOf("val result = com.google.android.gms.location.ActivityRecognitionResult"))
        assertThat(block.contains("if (!ActivityRecognitionPermission.isGranted(context)) return")).isTrue()
    }

    // ── 2) Stop-Verdrahtung (WalkStopDetector → DriveStopWorker) ──

    @Test
    fun `Walk-Stop wahrend aktiver Fahrt schedult DriveStopWorker sofort`() {
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("if (bridge.isDriveActive())")
        assertWithMessage("Walk-Stop-Block (isDriveActive) nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("else if (bridge.isWalkingEnabled())", idx)
        val block = src.substring(idx, end)

        // Detector-Signal + sauberer Stop:
        assertThat(block.contains("if (bridge.onWalkStopSample(top.type, top.confidence))")).isTrue()
        assertThat(block.contains("bridge.resetWalkStopEvidence()")).isTrue()
        assertThat(block.contains("DriveStopWorker.schedule(context)")).isTrue()

        // M18.93v9-Wächter: Die Geh-Samples müssen über den DETECTOR
        // (Confidence ≥ 60 + Gnadenfrist) laufen — ein direktes
        // „bei jedem WALKING sofort stoppen" (Stop&Go-Gefahr, M18.84)
        // ist verboten. Der Stop-Trigger steht NACH dem Detector-Erfolg.
        val detIdx = src.indexOf("if (bridge.onWalkStopSample(top.type, top.confidence))")
        val schedIdx = src.indexOf("DriveStopWorker.schedule(context)")
        assertThat(schedIdx).isGreaterThan(detIdx)
    }

    @Test
    fun `DriveStopWorker wird NUR ueber den Detector schedult - kein Direkt-Stop bei WALKING`() {
        // Exakt EIN Schedule-Aufruf im Continuous-Samples-Receiver — und
        // der steht im Detector-Erfolgsblock. Ein Direkt-Stop unterhalb
        // des `when` wäre der alte M18.93/v9-Leerraum-Fehler.
        val src = source("ActivityContinuousSamples.kt")
        val count = Regex("DriveStopWorker\\.schedule\\(context\\)").findAll(src).count()
        assertThat(count).isEqualTo(1)
        // Der eine Aufruf liegt im Walk-Stop-Block (zwischen Detector-If
        // und Walking-else-Zweig).
        val walkIdx = src.indexOf("if (bridge.isDriveActive())")
        val elseIdx = src.indexOf("else if (bridge.isWalkingEnabled())", walkIdx)
        val schedIdx = src.indexOf("DriveStopWorker.schedule(context)")
        assertThat(schedIdx).isGreaterThan(walkIdx)
        assertThat(schedIdx).isLessThan(elseIdx)
    }

    @Test
    fun `Walk-Stop-Block reseted die Evidenz NACH dem Stop-Trigger`() {
        // Reihenfolge: Erst der Detector-Entscheid (onWalkStopSample),
        // dann der Reset — der Reset VOR dem Trigger würde das gerade
        // gezündete Signal für den nächsten Sample neu aufbauen.
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("if (bridge.onWalkStopSample(top.type, top.confidence))")
        val end = src.indexOf("DriveStopWorker.schedule(context)", idx)
        assertWithMessage("Trigger-Block nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, end)
        assertThat(block.contains("resetWalkStopEvidence()")).isTrue()
    }

    @Test
    fun `nur Wahrend aktiver Fahrt - Walking ohne Fahrt startet weiterhin Walking-Check`() {
        // Regression: Der ELSE-Zweig (keine aktive Fahrt) muss den
        // bestehenden Walking-Start-Pfad beibehalten — der Walk-Stop
        // darf die Wanderungs-Erkennung nicht brechen.
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("else if (bridge.isWalkingEnabled())")
        assertWithMessage("Walking-else-Zweig nicht gefunden").that(idx).isAtLeast(0)
        val block = src.substring(idx, idx + 400)
        assertThat(block.contains("ACTION_WALKING_CHECK")).isTrue()
    }

    // ── 3) 5-Minuten-Fallback intact (User-Spezifikation) ─────────

    @Test
    fun `5-Min-Watchdog-Konstante unveraendert`() {
        val src = source("DriveWorkers.kt")
        assertThat(src.contains("private const val DRIVE_WATCHDOG_NO_SIGNAL_MS = 5L * 60 * 1000")).isTrue()
    }

    @Test
    fun `Watchdog wird bei jedem Fahrt-Start und Heartbeat-Refresh schedult`() {
        val src = source("DriveWorkers.kt")
        // Beim Neustart einer laufenden Session (Duplikat-Schutz-Pfad)…
        assertThat(src.contains("bridge.refreshDriveHeartbeat(now)\n            DriveWatchdogWorker.schedule(applicationContext)")).isTrue()
        // …und beim eigentlichen Session-Start (DriveStartWorker).
        assertThat(src.contains("DriveWatchdogWorker.schedule(applicationContext)\n            triggerRepo.insert(")).isTrue()
        // Watchdog-Worker selbst verlängert bei frischem Signal (REPLACE).
        assertThat(src.contains("last > 0 && now - last < DRIVE_WATCHDOG_NO_SIGNAL_MS")).isTrue()
    }

    @Test
    fun `Walk-Stop-Pfad verlaengert keine Fahrt - kein zusaetzlicher Heartbeat-Refresh`() {
        // Der Walk-Stop ist ausschließlich ein STOP-Signal: Er darf den
        // 5-Min-Watchdog nie refreshen (das würde die Fahrt über den
        // Fallback hinaus VERLÄNGERN — „kein Code-Pfad verlängert eine
        // Fahrt", Integrations-Acceptance t_a92bf972).
        val src = source("ActivityContinuousSamples.kt")
        val idx = src.indexOf("if (bridge.isDriveActive())")
        assertWithMessage("Walk-Stop-Block nicht gefunden").that(idx).isAtLeast(0)
        val end = src.indexOf("else if (bridge.isWalkingEnabled())", idx)
        val block = src.substring(idx, end)
        assertThat(block.contains("refreshDriveHeartbeat")).isFalse()
        assertThat(block.contains("lastVehicleSample")).isFalse()
    }

    @Test
    fun `Watchdog-Stopp-Paket reseted die Walk-Stop-Evidenz`() {
        // Beide Stop-Pfade (Sofort-Stop + 5-Min-Watchdog) müssen die
        // Evidenz über Session-Grenzen verwerfen, sonst zündet die
        // ausgestiegene Kette die NÄCHSTE Fahrt an.
        val src = source("DriveWorkers.kt")
        // DriveStopWorker (M18.127-Block).
        assertThat(src.contains("bridge.resetWalkStopEvidence()\n            live.stop()")).isTrue()
        // DriveWatchdogWorker.stopDrivingSession.
        assertThat(src.contains("bridge.resetWalkStopEvidence()\n            live.stop()")).isTrue()
    }

    @Test
    fun `DriveStartWorker reseted die Walk-Stop-Evidenz beim Start`() {
        val src = source("DriveWorkers.kt")
        // Losgehen → Auto: Ein Geh-Sample kurz vor dem Start darf den
        // Stop-Mechanismus der neuen Fahrt nicht sofort auslösen.
        assertThat(src.contains("bridge.resetWalkStopEvidence()\n            // Foreground-Service")).isTrue()
        assertThat(src.contains("// M18.127: Auch die Walk-Stop-Evidenz gehört nicht in die neue\n            // Fahrt hinein")).isTrue()
    }
}
