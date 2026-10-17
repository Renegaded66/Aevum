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
 *  6. EINE SEMANTIK: „Vordergrund“ wird nur über [ForegroundTracker]
 *     gefaltet (Service + Probe).
 *
 * M19.2 (User-Report: „… wird einfach weiterhin nur Digital aufgezeichnet —
 * die zugewiesene Activity startet nicht mehr“): Gesichert wird zusätzlich
 * die WIEDERBELEBUNG der App-Aufzeichnung nach Reboot / App-Update / Kill:
 *  7. REVIVAL: AevumApplication (App-Start) UND BootReceiver (Boot +
 *     MY_PACKAGE_REPLACED, siehe Manifest) starten den Service und planen
 *     den AppTrackingWatchdogWorker ein — der Service hatte vorher nur
 *     Start-Pfade beim App-Prozess-Start und in den Settings, während
 *     „Digital“ über den Manifest-Receiver von selbst weiterlief.
 *  8. SCREEN-ON-NUDGE: Der ScreenEventReceiver belebt beim nächsten
 *     Screen-On sofort (isAlive-Gate + getrackte-Apps-Gate) — vorher
 *     waren es maximal 15 Minuten Watchdog-Takt.
 *  9. WATCHDOG: 15-Minuten-Periodikarbeit mit KEEP; gated auf
 *     getEnabledOnce()-Leere und AppTrackingService.isAlive().
 * 10. LIVENESS: Der Service pflegt alive (onCreate/onDestroy) und
 *     lastPollAtMs (Poll) — Grundlage der Gates der Revival-Pfade.
 *
 * M19.3 (User-Report: „Oft zeichnet der einfach weiter digital auf obwohl
 * ich etwas bestimmtes geöffnet habe. […] dann will ich, dass die
 * Aufzeichnung Handynutzung 5 Sekunden nach dem öffnen auch startet […]
 * Hauptsache nichts anderes wird aufgezeichnet."): Gesichert wird zusätzlich
 *  11. TEMPO: Der Poll-Takt steht auf 2 Sekunden (vorher 15s — bis zu 15s
 *      Latenz; gefordert ist der Start ≤5s nach dem Öffnen).
 *  12. NACHPFASSEN: Der Same-App-Zweig prüft regelmäßig nach
 *      (onRepeatedForeground) — wieder angelaufenes Digital wird innerhalb
 *      eines Takts übernommen, statt die ganze App-Sitzung weiterzulaufen.
 *      Ein ins Leere gegriffener Digital-Stopp fasst EINMAL nach
 *      (onStopRace) und markiert den Zustand NICHT als erledigt
 *      (currentTrackedApp = null → Selbstheilung im nächsten Poll).
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

    // ── M19.2-Quellen ─────────────────────────────────────────────

    private fun watchdogSource() = source("automation/apptracking/AppTrackingWatchdogWorker.kt")
    private fun appSource() = source("AevumApplication.kt")
    private fun bootSource() = source("automation/geofence/BootReceiver.kt")

    /** Manifest liegt (anders als die Kotlin-Sourcen) direkt unter src/main. */
    private fun manifestSource(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertWithMessage("AndroidManifest.xml fehlt (${candidates.joinToString()})")
            .that(file != null).isTrue()
        return file!!.readText()
    }

    // ── 1) Übergabe Digital → App ─────────────────────────────────

    @Test
    fun `Service entscheidet ueber die zentrale take-over-Policy`() {
        val src = serviceSource()
        assertWithMessage("onTrackedForeground-Aufruf fehlt — die Übergabe-Regeln müssen über die getestete Policy laufen")
            .that(src).contains("AppTrackingTakeoverPolicy.onTrackedForeground(")
        assertWithMessage("Der Take-over-Zweig fehlt (TAKE_OVER_DIGITAL)")
            .that(src).contains("AppTrackingTakeoverPolicy.Action.TAKE_OVER_DIGITAL")
        assertWithMessage("Die Familien-Regel fehlt: eine verwaiste App-Tracking-Session (Service-Neustart) muss als 'eigene' gelten, sonst blockiert sie für immer den Wechsel/Stop")
            .that(src).contains("live.sourceType == AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING")
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
        val block = src.substring(idx, (idx + 3_000).coerceAtMost(src.length))
        assertWithMessage("stopOwnSessionIfRunning muss stopIfLive nutzen (Rangfolge: höherpriore Sessions nie stoppen)")
            .that(block).contains("stopIfLive(")
        assertWithMessage("stopOwnSessionIfRunning muss auch die verwaiste App-Tracking-Session als Ziel kennen (Service-Neustart)")
            .that(block).contains("SOURCE_APP_TRACKING")
        assertWithMessage("Der Stop darf NIE über ein unbedingtes liveActivityManager.stop() laufen")
            .that(block).doesNotContain("liveActivityManager.stop()")
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

    // ── 9) M19.2: WIEDERBELEBUNG nach Boot/Update/Kill ────────────

    @Test
    fun `AevumApplication und BootReceiver beleben die App-Aufzeichnung nach Boot und Update`() {
        val app = appSource()
        assertWithMessage("AevumApplication muss den AppTrackingService starten (App-Prozess-Start, M18.67-Pfad muss bleiben)")
            .that(app).contains("AppTrackingService.start(this)")
        assertWithMessage("AevumApplication muss den Watchdog einplanen — sonst bleibt die App-Aufzeichnung nach dem nächsten Kill/Reboot/Update wieder tot (nur Digital läuft weiter)")
            .that(app).contains("AppTrackingWatchdogWorker.schedule(this)")
        val boot = bootSource()
        assertWithMessage("BootReceiver muss den AppTrackingService starten — Boot und MY_PACKAGE_REPLACED sind die einzigen System-Anlässe, wenn die App selbst nie geöffnet wird")
            .that(boot).contains("AppTrackingService.start(context)")
        assertWithMessage("BootReceiver muss den Watchdog einplanen (Takt nach Boot/Update neu anstoßen, KEEP macht es idempotent)")
            .that(boot).contains("AppTrackingWatchdogWorker.schedule(context)")
        assertWithMessage("Die Wiederbelebung muss IM handleBoot-Pfad stehen (onReceive leitet nur weiter)")
            .that(boot.indexOf("private fun handleBoot(context: Context)") < boot.indexOf("AppTrackingService.start(context)")).isTrue()
        assertWithMessage("Sofort-Start muss VOR dem Watchdog-Einplan stehen (erst Service beleben, dann Takt)")
            .that(boot.indexOf("AppTrackingService.start(context)") < boot.indexOf("AppTrackingWatchdogWorker.schedule(context)")).isTrue()
        assertWithMessage("Der bewusste Verzicht auf den Location-FGS-Start nach Boot darf nicht entfernt werden (Android-15-Richtlinie)")
            .that(boot).contains("Location-FGS nach Boot bewusst übersprungen")
    }

    @Test
    fun `Manifest meldet MY_PACKAGE_REPLACED fuer den BootReceiver an`() {
        val manifest = manifestSource()
        val idx = manifest.indexOf(".automation.geofence.BootReceiver")
        assertWithMessage("BootReceiver-Eintrag im Manifest fehlt").that(idx).isAtLeast(0)
        val block = manifest.substring(idx, (idx + 800).coerceAtMost(manifest.length))
        assertWithMessage("BOOT_COMPLETED fehlt im Intent-Filter des BootReceivers (bestehendes Verhalten)")
            .that(block).contains("android.intent.action.BOOT_COMPLETED")
        assertWithMessage("MY_PACKAGE_REPLACED fehlt — nach App-Update stirbt der Prozess, und ohne diese Action belebt nichts die App-Aufzeichnung neu (der Code im BootReceiver verarbeitet die Action bereits)")
            .that(block).contains("android.intent.action.MY_PACKAGE_REPLACED")
    }

    @Test
    fun `ScreenEventReceiver nudged beim Screen-On sofort — Wiederbelebung mit isAlive-Guard`() {
        val src = receiverSource()
        assertWithMessage("Deps-Zugang zum AppTrackingEntryRepository fehlt (Gate: nur bei getrackten Apps beleben)")
            .that(src).contains("fun appTrackingEntryRepository(): com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository")
        assertWithMessage("isAlive-Guard fehlt — jeder Screen-On würde im Normalbetrieb den laufenden Service doppelt anstoßen")
            .that(src).contains("!com.d_drostes_apps.aevum.automation.apptracking.AppTrackingService.isAlive()")
        assertWithMessage("Der Nudge muss getrackte Apps als Gate prüfen (getEnabledOnce) — sonst startet er den Service, der sich im Gate sofort wieder selbst beendet")
            .that(src).contains("deps.appTrackingEntryRepository().getEnabledOnce()")
        assertWithMessage("Der Nudge muss den Service STARTEN (AppTrackingService.start(appContext))")
            .that(src).contains("AppTrackingService.start(appContext)")
        val scheduleIdx = src.indexOf(".schedule(appContext, minutes")
        val offIdx = src.indexOf("} else if (type == \"OFF\") {")
        val nudgeIdx = src.indexOf("AppTrackingService.isAlive()")
        assertWithMessage("Der Nudge muss im ON/UNLOCK-Zweig der Bildschirm-Aufzeichnung liegen (nach dem Digital-Einplan, vor dem OFF-Zweig) — genau dort weiß das System, dass der Screen gerade an ging")
            .that(scheduleIdx in 0 until nudgeIdx && nudgeIdx < offIdx).isTrue()
    }

    @Test
    fun `Watchdog-Worker ist 15-Minuten-Periodikarbeit mit KEEP und gated auf getrackte Apps und isAlive`() {
        val src = watchdogSource()
        assertWithMessage("Der UniqueWork-Name app_tracking_watchdog fehlt")
            .that(src).contains("const val WORK_NAME = \"app_tracking_watchdog\"")
        assertWithMessage("Periodikarbeit fehlt — der Watchdog muss enqueueUniquePeriodicWork nutzen")
            .that(src).contains("enqueueUniquePeriodicWork")
        assertWithMessage("15-Minuten-Takt fehlt (Mindestintervall von WorkManager)")
            .that(src).contains("15, java.util.concurrent.TimeUnit.MINUTES")
        assertWithMessage("KEEP-Policy fehlt — erneutes Einstecken (App-Start + Boot) würde sonst den laufenden Takt zurücksetzen")
            .that(src).contains("ExistingPeriodicWorkPolicy.KEEP")
        assertWithMessage("schedule(context) fehlt — der zentrale Einplan-Punkt für AevumApplication und BootReceiver")
            .that(src).contains("fun schedule(context: Context)")
        assertWithMessage("Gate 1 fehlt: getEnabledOnce() als Quelle der getrackten Apps")
            .that(src).contains("getEnabledOnce()")
        assertWithMessage("Gate 1 fehlt: ohne getrackte Apps muss der Worker ein No-Op sein (if (tracked.isEmpty()))")
            .that(src).contains("if (tracked.isEmpty())")
        assertWithMessage("Gate 2 fehlt: lebt der Service schon (isAlive), darf nicht doppelt gestartet werden")
            .that(src).contains("AppTrackingService.isAlive()")
        assertWithMessage("Die Wiederbelebung muss den Service STARTEN")
            .that(src).contains("AppTrackingService.start(applicationContext)")
        assertWithMessage("Gate-Reihenfolge falsch: erst der Leere-Check (getrackte Apps), dann isAlive")
            .that(src.indexOf("if (tracked.isEmpty())") < src.indexOf("AppTrackingService.isAlive()")).isTrue()
    }

    @Test
    fun `Service setzt alive in onCreate und onDestroy und pflegt lastPollAtMs im Poll`() {
        val src = serviceSource()
        assertWithMessage("alive-Flag fehlt im Companion (@Volatile private var alive) — Grundlage des isAlive-Gates der Revival-Pfade")
            .that(src).contains("@Volatile private var alive")
        assertWithMessage("lastPollAtMs-Flag fehlt (@Volatile private var lastPollAtMs)")
            .that(src).contains("@Volatile private var lastPollAtMs")
        assertWithMessage("fun isAlive(): Boolean fehlt")
            .that(src).contains("fun isAlive(): Boolean")
        assertWithMessage("fun lastPollAtMs(): Long fehlt")
            .that(src).contains("fun lastPollAtMs(): Long")
        val createIdx = src.indexOf("override fun onCreate()")
        val commandIdx = src.indexOf("override fun onStartCommand")
        assertWithMessage("onCreate muss alive = true setzen — sonst hält der Watchdog den Service für Leichen im Normalbetrieb")
            .that(src.substring(createIdx, commandIdx)).contains("alive = true")
        val destroyIdx = src.indexOf("override fun onDestroy()")
        assertWithMessage("onDestroy muss alive = false setzen — sonst würde eine bewusst beendete App-Aufzeichnung sofort wiederbelebt")
            .that(src.substring(destroyIdx)).contains("alive = false")
        val pollIdx = src.indexOf("private suspend fun pollForegroundApp()")
        val pollBlock = src.substring(pollIdx, (pollIdx + 400).coerceAtMost(src.length))
        assertWithMessage("pollForegroundApp muss lastPollAtMs pflegen (Watchdog/Nudge erkennen daran einen wirklich lebenden Service)")
            .that(pollBlock).contains("lastPollAtMs = System.currentTimeMillis()")
        assertWithMessage("lastPollAtMs muss als ERSTE Anweisung im Poll gepflegt werden (auch ein Abbruch danach ist dann sichtbar)")
            .that(pollBlock.indexOf("lastPollAtMs = System.currentTimeMillis()") < pollBlock.indexOf("processForegroundEvents()")).isTrue()
    }

    // ── 11) M19.3: TEMPO — der 2-Sekunden-Takt ───────────────────

    @Test
    fun `Service pollt im Zwei-Sekunden-Takt fuer die Fuenf-Sekunden-Anforderung`() {
        val src = serviceSource()
        assertWithMessage("POLL_INTERVAL_MS = 2_000L fehlt — der 15-s-Takt erkannte eine frisch geöffnete App erst nach durchschnittlich 7,5s (Nutzeranforderung: Start ≤5s nach dem Öffnen)")
            .that(src).contains("POLL_INTERVAL_MS = 2_000L")
        assertWithMessage("Der alte 15-s-Takt darf nicht zurückkehren").that(src).doesNotContain("POLL_INTERVAL_MS = 15_000L")
    }

    // ── 12) M19.3: NACHPFASSEN — Digital nie die App-Sitzung überlassen

    @Test
    fun `Same-App-Nachpruefung - spaeter angelaufenes Digital wird aus der Sitzung entfernt`() {
        val src = serviceSource()
        assertWithMessage("onRepeatedForeground fehlt im Service — der Same-App-Zweig kehrt sonst blind zurück und ein später angelaufenes Digital läuft die KOMPLETTE App-Sitzung weiter")
            .that(src).contains("AppTrackingTakeoverPolicy.onRepeatedForeground(")
        val policy = policySource()
        assertWithMessage("Die reine Nachprüf-Regel fehlt in der Policy (JVM-getestet in AppTrackingTakeoverPolicyTest)")
            .that(policy).contains("fun onRepeatedForeground(")
        assertWithMessage("Der Nachprüf-Zweig muss vor dem gleichen Übergabe-Code liegen wie eine neu geöffnete App (durchfallen, nicht eigener Start)")
            .that(src.indexOf("onRepeatedForeground(") < src.indexOf("AppTrackingTakeoverPolicy.onTrackedForeground(")).isTrue()
    }

    @Test
    fun `Digital-Stopp-Race - frisch nachsehen, einmal nachfassen, Zustand nicht als erledigt markieren`() {
        val src = serviceSource()
        assertWithMessage("onStopRace fehlt — ein ins Leere gegriffener Digital-Stopp verlor die Übergabe sonst still für die ganze Sitzung")
            .that(src).contains("AppTrackingTakeoverPolicy.onStopRace(")
        assertWithMessage("Die reine Race-Regel fehlt in der Policy (JVM-getestet in AppTrackingTakeoverPolicyTest)")
            .that(policySource()).contains("fun onStopRace(")
        val abortIdx = src.indexOf("Digital-Übergabe abgebrochen")
        assertWithMessage("Der Abbruch-Log fehlt im Stopp-Pfad").that(abortIdx).isAtLeast(0)
        val abortBlock = src.substring(abortIdx, (abortIdx + 700).coerceAtMost(src.length))
        assertWithMessage("Nach dem endgültigen Fehlschlag muss currentTrackedApp = null gesetzt werden — der nächste 2-s-Poll heilt den Zustand sonst nie (früher: = foreground, Digital lief bis zum nächsten App-Wechsel weiter)")
            .that(abortBlock).contains("currentTrackedApp = null")
        assertWithMessage("Der alte Fehlpfad (currentTrackedApp = foreground unmittelbar nach dem Abbruch) darf nicht zurückkehren")
            .that(abortBlock).doesNotContain("currentTrackedApp = foreground")
    }

    @Test
    fun `Digital-Stopp-Race - Rangfolge bleibt gewahrt (fremde Quelle wird nie angefasst oder uebernommen)`() {
        val policy = policySource()
        assertWithMessage("StopRace-KEEP_FOREIGN fehlt — eine zwischenzeitlich gestartete Fahrt/Sport-Session darf der Nachfass nie stoppen").that(policy).contains("StopRace.KEEP_FOREIGN")
        assertWithMessage("Der Nachfass-Stopp muss exakt die frisch gelesene Session treffen (stopIfLive mit frischer ID), nie ein unbedingtes stop()")
            .that(serviceSource()).contains("liveActivityManager.stopIfLive(fresh.id)")
    }
}
