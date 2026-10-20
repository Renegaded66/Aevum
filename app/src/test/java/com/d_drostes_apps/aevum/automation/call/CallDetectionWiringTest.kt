package com.d_drostes_apps.aevum.automation.call

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M26: Verdrahtungs-Wächter der Anruf-Erkennung.
 *
 * Der Fehler, den diese Klasse verhindert, ist NICHT im reinen Regelwerk zu
 * finden (das deckt [com.d_drostes_apps.aevum.domain.call.CallDetectionPolicyTest]
 * ab), sondern in der Verdrahtung: ein Dienst, der die Policy gar nicht
 * fragt und deshalb blind `start()` aufruft, schneidet jede laufende
 * Aktivität ab — auch die manuell gestartete und die Geofence-Session.
 * Genau das hat die User-Spezifikation verboten.
 *
 * Geprüft wird deshalb quelltextbasiert (kein Emulator nötig, Muster:
 * AppTrackingDigitalWiringTest / LocationDisclosureWiringTest):
 *
 *  - Manifest: Berechtigung + Dienst mit `specialUse` und eigenem Subtyp
 *  - Dienst: Policy-Entscheidung VOR dem Start, `mayRecord` als Gate,
 *    Telefonie-Callback (neuer und alter API-Weg), Ende nur über `stopIfLive`
 *  - Wiederbelebung: App-Start, Boot, Watchdog
 *  - UI: Karte, Zuweisung, Berechtigungszeile, Grant schaltet den Trigger
 *  - Timeline: `CALL_AUTO` als automatische Quelle mit eigenem Label
 */
class CallDetectionWiringTest {

    private fun file(relative: String): File {
        val candidates = listOf(
            File(".").resolve(relative),
            File("..").resolve("app/$relative"),
            File("../app").resolve(relative),
            File("app").resolve(relative)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $relative (CWD=${File(".").absolutePath})")
    }

    private fun source(relative: String): String = file(relative).readText()

    private val manifest by lazy { source("src/main/AndroidManifest.xml") }
    private val service by lazy { source("src/main/java/com/d_drostes_apps/aevum/automation/call/CallDetectionService.kt") }
    private val watchdog by lazy { source("src/main/java/com/d_drostes_apps/aevum/automation/call/CallDetectionWatchdogWorker.kt") }
    private val appClass by lazy { source("src/main/java/com/d_drostes_apps/aevum/AevumApplication.kt") }
    private val bootReceiver by lazy { source("src/main/java/com/d_drostes_apps/aevum/automation/geofence/BootReceiver.kt") }
    private val triggerScreen by lazy { source("src/main/java/com/d_drostes_apps/aevum/ui/screens/settings/TriggerSettingsScreen.kt") }
    private val timelineVm by lazy { source("src/main/java/com/d_drostes_apps/aevum/ui/screens/timeline/TimelineViewModels.kt") }
    private val disclosure by lazy { source("src/main/java/com/d_drostes_apps/aevum/ui/disclosure/PermissionDisclosure.kt") }
    private val settingsEntity by lazy { source("src/main/java/com/d_drostes_apps/aevum/data/model/AutomationSettings.kt") }
    private val databaseModule by lazy { source("src/main/java/com/d_drostes_apps/aevum/di/DatabaseModule.kt") }

    /** Zeilen ohne Kommentare (Doku darf keine Verdrahtung vortäuschen). */
    private fun codeLines(text: String): List<String> = text.lines().filter { line ->
        val t = line.trimStart()
        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("<!--")
    }

    // ── Manifest ─────────────────────────────────────────────────────────

    @Test
    fun `Manifest deklariert READ_PHONE_STATE`() {
        assertWithMessage("READ_PHONE_STATE fehlt — der Telefonie-Callback wirft SecurityException")
            .that(manifest.contains("android.permission.READ_PHONE_STATE")).isTrue()
        // Gegenprobe: KEINE weitergehenden Telefonie-Rechte. Geprüft werden
        // nur echte <uses-permission>-Deklarationen — ein Kommentar, der die
        // Rechte ausschließt, darf den Test nicht selbst auslösen.
        val declared = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        for (forbidden in listOf("READ_CALL_LOG", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS")) {
            assertWithMessage("$forbidden ist nicht nötig und darf nicht deklariert sein")
                .that(declared.any { it.endsWith(forbidden) }).isFalse()
        }
    }

    @Test
    fun `Dienst ist mit specialUse und eigenem Subtyp registriert`() {
        assertThatServiceBlock()
        assertWithMessage("Ohne Subtyp lehnt Play den specialUse-Dienst ab")
            .that(manifest.contains("call_state_detection")).isTrue()
    }

    private fun assertThatServiceBlock() {
        val block = manifest.substringAfter(".automation.call.CallDetectionService", "")
        assertWithMessage("CallDetectionService ist nicht im Manifest registriert")
            .that(block.isNotEmpty()).isTrue()
        val nextService = block.substringBefore("</service>")
        assertWithMessage("foregroundServiceType specialUse fehlt")
            .that(nextService.contains("specialUse")).isTrue()
        assertWithMessage("exported=false fehlt — der Dienst darf nur intern startbar sein")
            .that(nextService.contains("android:exported=\"false\"")).isTrue()
    }

    // ── Dienst: die entscheidende Reihenfolge ────────────────────────────

    @Test
    fun `Policy-Entscheidung steht VOR dem Start der Aufzeichnung`() {
        val onStarted = service.substringAfter("private suspend fun onCallStarted()")
            .substringBefore("private suspend fun onCallFinished()")
        assertWithMessage("onCallStarted fragt die Policy nicht")
            .that(onStarted.contains("CallDetectionPolicy.onCallActive(")).isTrue()
        assertWithMessage(
            "onCallStarted startet ohne mayRecord-Gate — ein Anruf würde dann auch " +
                "manuelle Aktivitäten und Geofence-Sessions abschneiden."
        ).that(onStarted.contains("CallDetectionPolicy.mayRecord(")).isTrue()
        val policyIndex = onStarted.indexOf("CallDetectionPolicy.onCallActive(")
        val startIndex = onStarted.indexOf("liveActivityManager.start(")
        assertWithMessage("liveActivityManager.start( fehlt im Anruf-Pfad").that(startIndex).isGreaterThan(0)
        assertWithMessage("start() steht VOR der Policy-Entscheidung")
            .that(startIndex).isGreaterThan(policyIndex)
    }

    @Test
    fun `Anruf-Ende beendet nur die eigene Session`() {
        val onFinished = service.substringAfter("private suspend fun onCallFinished()")
        assertWithMessage("onCallFinished fragt die Policy nicht")
            .that(onFinished.contains("CallDetectionPolicy.onCallEnded(")).isTrue()
        assertWithMessage("Anruf-Ende muss stopIfLive nutzen, nie ein blindes stop()")
            .that(onFinished.contains("liveActivityManager.stopIfLive(")).isTrue()
        assertWithMessage("blindes stop() im Anruf-Ende — würde fremde Sessions beenden")
            .that(onFinished.contains("liveActivityManager.stop()")).isFalse()
    }

    @Test
    fun `Telefonie-Callback deckt beide API-Wege ab`() {
        assertWithMessage("registerTelephonyCallback (API 31+) fehlt")
            .that(service.contains("registerTelephonyCallback")).isTrue()
        assertWithMessage("PhoneStateListener-Fallback (API 29/30) fehlt")
            .that(service.contains("PhoneStateListener")).isTrue()
        assertWithMessage("OFFHOOK wird nicht ausgewertet")
            .that(service.contains("CALL_STATE_OFFHOOK")).isTrue()
        assertWithMessage("IDLE wird nicht ausgewertet — die Aufzeichnung liefe ewig")
            .that(service.contains("CALL_STATE_IDLE")).isTrue()
        assertWithMessage("RINGING darf NICHT aufzeichnen (abgelehnter Anruf)")
            .that(codeLines(service).any { it.contains("CALL_STATE_RINGING ->") }).isFalse()
    }

    @Test
    fun `fehlende Berechtigung ist kein stiller Ausfall`() {
        assertWithMessage("Der Dienst prüft READ_PHONE_STATE nicht selbst")
            .that(service.contains("Manifest.permission.READ_PHONE_STATE")).isTrue()
        assertWithMessage("Ohne Logzeile ist „zeichnet nichts auf\" im Feld nicht diagnostizierbar")
            .that(service.contains("READ_PHONE_STATE fehlt")).isTrue()
    }

    @Test
    fun `Dienst startet das Live-Banner mit`() {
        assertWithMessage("Ohne LiveActivityService bleibt die Aufzeichnung im System unsichtbar")
            .that(service.contains("LiveActivityService.start(")).isTrue()
    }

    // ── Wiederbelebung ───────────────────────────────────────────────────

    @Test
    fun `drei Wiederbelebungs-Pfade haengen am Dienst`() {
        assertWithMessage("App-Start belebt den Dienst nicht")
            .that(appClass.contains("CallDetectionService.ensureAlive(")).isTrue()
        assertWithMessage("Boot/Update belebt den Dienst nicht")
            .that(bootReceiver.contains("CallDetectionService.ensureAlive(")).isTrue()
        assertWithMessage("Der 15-Minuten-Watchdog ist nicht eingeplant")
            .that(watchdog.contains("CallDetectionService.ensureAlive(")).isTrue()
        assertWithMessage("Der Watchdog wird nirgends eingeplant")
            .that(appClass.contains("CallDetectionWatchdogWorker.schedule(")).isTrue()
    }

    @Test
    fun `Watchdog respektiert Schalter und Berechtigung`() {
        assertWithMessage("Watchdog prüft den Schalter nicht — er würde dauerhaft wecken")
            .that(watchdog.contains("callDetectionEnabled")).isTrue()
        assertWithMessage("Watchdog prüft die Berechtigung nicht")
            .that(watchdog.contains("READ_PHONE_STATE")).isTrue()
    }

    // ── Einstellungen / UI ───────────────────────────────────────────────

    @Test
    fun `Settings-Screen bietet Berechtigungszeile und Karte`() {
        assertWithMessage("Berechtigungszeile für Anrufe fehlt")
            .that(triggerScreen.contains("settings_triggers_permission_calls")).isTrue()
        assertWithMessage("Die Anruf-Karte fehlt")
            .that(triggerScreen.contains("CallDetectionCard(")).isTrue()
        assertWithMessage("Der Schalter persistiert nicht")
            .that(triggerScreen.contains("fun setCallDetection(")).isTrue()
        assertWithMessage("Die Zuweisung der Aktivität persistiert nicht")
            .that(triggerScreen.contains("fun setCallActivity(")).isTrue()
        assertWithMessage("Der Erklärungsdialog für Anrufe wird nie angefragt")
            .that(triggerScreen.contains("PermissionDisclosureKind.CALLS")).isTrue()
        assertWithMessage("Der Grant schaltet den Trigger nicht scharf (zweite Verdrahtungsstelle fehlt)")
            .that(triggerScreen.contains("pendingTrigger == \"call_detection\"")).isTrue()
        assertWithMessage("Der Grant schaltet den Trigger nicht scharf (setCallDetection fehlt im Launcher-Callback)")
            .that(triggerScreen.contains("viewModel.setCallDetection(true)")).isTrue()
    }

    @Test
    fun `Berechtigungs-Art CALLS existiert mit Offenlegungstext`() {
        assertWithMessage("PermissionDisclosureKind.CALLS fehlt")
            .that(disclosure.contains("CALLS(Manifest.permission.READ_PHONE_STATE")).isTrue()
        assertWithMessage("Dem Enum fehlt der Offenlegungstext")
            .that(disclosure.contains("PermissionDisclosureKind.CALLS ->")).isTrue()
    }

    @Test
    fun `Einstellungen speichern Schalter und Zuweisung`() {
        assertWithMessage("callDetectionEnabled fehlt im AutomationSettings-Entity")
            .that(settingsEntity.contains("call_detection_enabled")).isTrue()
        assertWithMessage("callActivityTypeId fehlt im AutomationSettings-Entity")
            .that(settingsEntity.contains("call_activity_type_id")).isTrue()
        assertWithMessage("Default der Zuweisung ist nicht 'social'")
            .that(settingsEntity.contains("defaultValue = \"social\"")).isTrue()
        assertWithMessage("MIGRATION_48_49 ist nicht in der Datenbank-Registrierung")
            .that(databaseModule.contains("MIGRATION_48_49")).isTrue()
    }

    // ── Timeline ─────────────────────────────────────────────────────────

    @Test
    fun `Anruf-Aufzeichnungen gelten als automatische Quelle`() {
        val autoSources = timelineVm.substringAfter("val AUTO_SOURCES")
            .substringBefore("}")
        assertWithMessage("CALL_AUTO fehlt in AUTO_SOURCES — die Timeline zeigt sie als manuell")
            .that(autoSources.contains("\"CALL_AUTO\"")).isTrue()
    }
}
