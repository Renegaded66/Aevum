package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M18.151-REGRESSION: Struktur-Checks der Übergabe „App-Aufzeichnung ↔
 * Digital" (Datei-Scan-Technik wie StepWalkStopWiringRegressionTest,
 * WalkStopStopPathRegressionTest, DriveStopNotificationRegressionTest).
 *
 * User-Report: „… dann wird einfach weiterhin die ganze Zeit Digital
 * aufgezeichnet. […] stoppt leider auch nicht die Aufzeichnung von Digital
 * und die neue Aufzeichnung von der zugewiesenen [App wird] gestartet […]
 * Falls dann 2 Minuten das Display an ist ohne eine ausgewählte App, dann
 * soll Digital wieder starten."
 *
 * Gesichert wird:
 *  1. ÜBERGABE: Der AppTrackingService entscheidet über die zentrale
 *     [AppTrackingTakeoverPolicy] (unit-getestete Regeln) und stoppt das
 *     Digital NUR über den Race-sicheren [LiveActivityManager.stopIfLive].
 *  2. RANGFOLGE: fremde Sessions (Geofence/Fahrt/Sport/manuell) werden nie
 *     angefasst — der Stop-Pfad der eigenen Session nutzt ebenfalls
 *     stopIfLive (kein unbedingtes stop()).
 *  3. SCREEN-OFF: beendet auch die App-Aufzeichnung SOFORT.
 *  4. NEUANSATZ: Nach dem App-Ende wird der Digital-Start erneut
 *     eingeplant — über EINEN zentralen Einplan-Punkt
 *     ([ScreenRecordingWorker.schedule]), den auch der ScreenEventReceiver
 *     nutzt.
 *  5. WORKER-GUARD: Der ScreenRecordingWorker startet kein Digital, wenn
 *     eine getrackte App im Vordergrund ist (Probe + Poll-Schnappschuss).
 *  6. EINE SEMANTIK: „Vordergrund" wird nur über [ForegroundTracker]
 *     gefaltet (Service + Probe).
 */
class AppTrackingDigitalWiringTest {

    private val roots = listOf(
        File("src/main/java/com/d_drostes_apps/aevum"),
        File("app/src/main/java/com/d_drostes_apps/aevum")
    )

    private fun source(relative: String): String {
        val candidates = roots.map { File(it, relative) }
        val file = candidates.firstOrNull { it.exists() }
        assertWithMessage("Quelldatei fehlt: $relative (gesucht: ${candidates.joinToString()})")
            .that(file != null).isTrue()
        return file!!.readText()
    }

    private fun serviceSource() = source("automation/apptracking/AppTrackingService.kt")
    private fun workerSource() = source("automation/screen/ScreenRecordingWorker.kt")
    private fun receiverSource() = source("automation/sleep/ScreenEventReceiver.kt")
    private fun managerSource() = source("domain/liveactivity/LiveActivityManager.kt")
    private fun policySource() = source("automation/apptracking/AppTrackingTakeoverPolicy.kt")
    private fun probeSource() = source("automation/apptracking/ForegroundAppProbe.kt")

    // ── 1) Übergabe Digital → App ─────────────────────────────────

    @Test
    fun `Service entscheidet ueber die zentrale take-over-Policy`() {
        val src = serviceSource()
        assertWithMessage("onTrackedForeground-Aufruf fehlt — die Übergabe-Regeln müssen über die getestete Policy laufen")
            .that(src).contains("AppTrackingTakeoverPolicy.onTrackedForeground(")
        assertWithMessage("Der Take-over-Zweig fehlt (TAKE_OVER_DIGITAL)")
            .that(src).contains("AppTrackingTakeoverPolicy.Action.TAKE_OVER_DIGITAL")
    }

    @Test
    fun `Digital-Stopp laeuft nur ueber den Race-sicheren stopIfLive`() {
        val src = serviceSource()
        assertWithMessage("stopIfLive(digital.id) fehlt — Digital darf nur gestoppt werden, wenn es noch DIESE Session ist (sonst könnte eine zwischenzeitlich gestartete Fahrt-Session erwischt werden)")
            .that(src).contains("stopIfLive(digital.id)")
        val manager = managerSource()
        assertWithMessage("LiveActivityManager.stopIfLive fehlt")
            .that(manager).contains("suspend fun stopIfLive(sessionId: String): Boolean")
        assertWithMessage("stopIfLive muss die Session-ID vergleichen")
            .that(manager).contains("session.id != sessionId")
    }

    // ── 2) Rangfolge ──────────────────────────────────────────────

    @Test
    fun `eigener Session-Stop nutzt ebenfalls stopIfLive — nichts Fremdes wird angefasst`() {
        val src = serviceSource()
        val idx = src.indexOf("private suspend fun stopOwnSessionIfRunning")
        assertWithMessage("stopOwnSessionIfRunning fehlt").that(idx).isAtLeast(0)
        val block = src.substring(idx, idx + 700)
        assertWithMessage("stopOwnSessionIfRunning muss stopIfLive nutzen (Rangfolge: höherpriore Sessions nie stoppen)")
            .that(block).contains("stopIfLive(sessionId)")
    }

    // ── 3) Screen-OFF beendet die App-Aufzeichnung sofort ─────────

    @Test
    fun `SCREEN_OFF stoppt die eigene App-Session sofort`() {
        val src = serviceSource()
        val idx = src.indexOf("Intent.ACTION_SCREEN_OFF -> {")
        assertWithMessage("SCREEN_OFF-Zweig fehlt in registerScreenReceiver").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 1_400).coerceAtMost(src.length))
        assertWithMessage("Der Screen-OFF-Zweig muss stopOwnSessionIfRunning() aufrufen")
            .that(block).contains("stopOwnSessionIfRunning()")
        assertWithMessage("Der Screen-OFF-Zweig muss currentTrackedApp zurücksetzen (sonst wird der Wiedereinstieg nach dem Entsperren verschluckt)")
            .that(block).contains("currentTrackedApp = null")
    }

    // ── 4) Digital-Neuansatz nach App-Ende ────────────────────────

    @Test
    fun `nach dem App-Ende wird der Digital-Start neu eingeplant`() {
        val src = serviceSource()
        assertWithMessage("scheduleDigitalRecheck fehlt — nach dem App-Ende muss Digital nach der Vorlaufzeit wieder starten können („Falls dann 2 Minuten das Display an ist ohne eine ausgewählte App, dann soll Digital wieder starten.“)")
            .that(src).contains("scheduleDigitalRecheck()")
        val idx = src.indexOf("private suspend fun scheduleDigitalRecheck")
        assertWithMessage("scheduleDigitalRecheck-Funktion fehlt").that(idx).isAtLeast(0)
        val block = src.substring(idx, (idx + 800).coerceAtMost(src.length))
        assertWithMessage("scheduleDigitalRecheck muss den zentralen ScreenRecordingWorker.schedule nutzen")
            .that(block).contains("ScreenRecordingWorker.schedule(")
        assertWithMessage("scheduleDigitalRecheck muss die Vorlaufzeit Einstellung respektieren (DEACTIVATED)")
            .that(block).contains("DEACTIVATED")
    }

    @Test
    fun `App-Session startet die Live-Notification`() {
        val src = serviceSource()
        assertWithMessage("LiveActivityService.start fehlt nach dem App-Session-Start — die Session soll sichtbar sein wie jede andere Live-Aufzeichnung")
            .that(src).contains("LiveActivityService.start(applicationContext)")
    }

    // ── 5) Worker-Guard: keine getrackte App im Vordergrund ───────

    @Test
    fun `Worker startet kein Digital wenn eine getrackte App im Vordergrund ist`() {
        val src = workerSource()
        assertWithMessage("ForegroundAppProbe fehlt im Worker").that(src).contains("ForegroundAppProbe")
        assertWithMessage("Poll-Schnappschuss-Fallback fehlt (Fall „App seit Stunden ruhig im Vordergrund“)")
            .that(src).contains("recentPollSnapshot(90_000L)")
        assertWithMessage("Der Tracked-Check muss VOR dem Digital-Start stehen")
            .that(src.indexOf(".currentForeground(applicationContext)"))
            .isLessThan(src.indexOf("val session = deps.liveActivityManager().start("))
        assertWithMessage("Der Worker muss den AppTrackingService anstupsen, wenn er wegen einer getrackten App überspringt")
            .that(src).contains("AppTrackingService")
        assertWithMessage("Der Nudge muss den Service STARTEN (nicht stoppen)")
            .that(src).contains(".start(applicationContext)")
    }

    @Test
    fun `Probe und Service falten den Vordergrund identisch (eine Semantik)`() {
        val service = serviceSource()
        val probe = probeSource()
        assertWithMessage("Service muss ForegroundTracker.next nutzen").that(service).contains("ForegroundTracker.next(")
        assertWithMessage("Probe muss ForegroundTracker.next nutzen").that(probe).contains("ForegroundTracker.next(")
        assertWithMessage("Probe darf keine eigene Event-Faltung duplizieren")
            .that(probe).doesNotContain("MOVE_TO_FOREGROUND ->")
    }

    // ── 6) Zentraler Einplan-Punkt ────────────────────────────────

    @Test
    fun `Digital-Start hat genau EINEN zentralen Einplan-Punkt`() {
        val worker = workerSource()
        assertWithMessage("ScreenRecordingWorker.schedule(context, delayMinutes) fehlt")
            .that(worker).contains("fun schedule(context: Context, delayMinutes: Long)")
        val receiver = receiverSource()
        assertWithMessage("Der ScreenEventReceiver muss den zentralen schedule nutzen (kein zweiter Eigenbau)")
            .that(receiver).contains("ScreenRecordingWorker")
        assertWithMessage("Der ScreenEventReceiver muss den zentralen schedule aufrufen")
            .that(receiver).contains(".schedule(appContext,")
    }

    // ── 7) Policy bleibt rein (JVM-testbar) ───────────────────────

    @Test
    fun `Takeover-Policy bleibt Android-frei und zentral dokumentiert`() {
        val src = policySource()
        assertWithMessage("Die Policy darf KEINE Android-Imports haben (pure, JVM-getestet)")
            .that(src).doesNotContain("import android")
        assertWithMessage("Policy muss den Rangfolge-Fall kennen").that(src).contains("KEEP_FOREIGN")
    }

    // ── 8) Bestehende M18.67-Guards bleiben erhalten ──────────────

    @Test
    fun `Same-App-Guard und Poll-Schnappschuss bleiben erhalten`() {
        val src = serviceSource()
        assertWithMessage("Der Same-App-Guard fehlt — ein Poll ohne App-Wechsel darf nichts auslösen")
            .that(src).contains("currentTrackedApp == foreground")
        assertWithMessage("Der Poll-Schnappschuss (rememberForegroundSnapshot) fehlt")
            .that(src).contains("rememberForegroundSnapshot()")
        assertWithMessage("recentPollSnapshot(maxAgeMs) fehlt")
            .that(src).contains("fun recentPollSnapshot(maxAgeMs: Long): String?")
    }
}
