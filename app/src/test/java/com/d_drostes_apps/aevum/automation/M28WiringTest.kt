package com.d_drostes_apps.aevum.automation

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * M28: Verdrahtungs-Guards für die vier Bugfixes dieses Meilensteins.
 *
 * Die reinen Regeln sind separat getestet:
 *  - [com.d_drostes_apps.aevum.automation.activityrecognition.PendingDrivePolicyTest]
 *  - [com.d_drostes_apps.aevum.ui.screens.timeline.PlannedSessionsFutureFilterTest]
 *  - [com.d_drostes_apps.aevum.domain.addiction.AddictionAnalyticsTest]
 *
 * Hier wird bewiesen, dass die getesteten Regeln auch WIRKLICH an den
 * entscheidenden Stellen benutzt werden — der häufigste Weg, auf dem eine
 * gute Regel wirkungslos bleibt (Regel existiert, Aufrufer fragt sie nie).
 */
class M28WiringTest {

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

    // ── 1) Nutzungszugriff: AppOps statt 1-Sekunden-Query ─────────────

    @Test
    fun `Nutzungszugriff-Pruefung-delegiert-an-den-AppOps-Check`() {
        val collector = source("domain/digital/UsageStatsCollector.kt")
        assertWithMessage(
            "hasPermission muss UsageStatsPermission.isGranted nutzen — die queryUsageStats-Variante " +
                "mit 1-Sekunden-Fenster meldet eine erteilte Berechtigung fälschlich als fehlend"
        ).that(collector).contains("fun hasPermission(): Boolean = UsageStatsPermission.isGranted(context)")

        assertWithMessage("Der alte 1-Sekunden-Query-Check darf nicht zurückkehren")
            .that(collector).doesNotContain("now - 1000")
    }

    @Test
    fun `Trigger-Seite-startet-mit-dem-echten-Berechtigungsstatus`() {
        val src = source("ui/screens/settings/TriggerSettingsScreen.kt")
        assertWithMessage("permissionState muss synchron mit readPermissions() starten")
            .that(src).contains("MutableStateFlow(readPermissions())")
        assertWithMessage("Der UI-Erststand muss die echten Berechtigungen tragen")
            .that(src).contains("usageStatsGranted = permissionState.value.usageStatsGranted")
    }

    // ── 2) Bildschirm-Aufzeichnung: Aktivitäts-Auswahl ────────────────

    @Test
    fun `ScreenRecordingWorker-nutzt-die-gewaehlte-Aktivitaet`() {
        val worker = source("automation/screen/ScreenRecordingWorker.kt")
        assertWithMessage("Der Worker muss die gespeicherte Aktivität lesen")
            .that(worker).contains("settings?.screenRecordingActivityTypeId")
        assertWithMessage("Der Worker muss über ScreenRecordingEngine auflösen")
            .that(worker).contains("ScreenRecordingEngine.recordingActivityTypeId(")
        assertWithMessage("Ein gelöschter Typ muss auf digital zurückfallen")
            .that(worker).contains("getById(ScreenRecordingEngine.DIGITAL_TYPE_ID)")
    }

    @Test
    fun `Einstellungen-bieten-die-Aktivitaets-Auswahl-unter-dem-Slider`() {
        val src = source("ui/screens/settings/TriggerSettingsScreen.kt")
        assertWithMessage("ScreenRecordingCard braucht die Aktivitäts-Auswahl")
            .that(src).contains("selectedTypeId = state.settings.screenRecordingActivityTypeId")
        assertWithMessage("Der Setter muss die Auswahl speichern")
            .that(src).contains("fun setScreenRecordingActivity(typeId: String)")
        assertWithMessage("Der Picker-Dialog muss geöffnet werden")
            .that(src).contains("showTypePicker = true")
    }

    // ── 3) Auto-Fahrt unterbricht keine Aufzeichnung ──────────────────

    @Test
    fun `DriveStartWorker-fragt-die-Defer-Policy-und-merkt-die-Fahrt-vor`() {
        val src = source("automation/activityrecognition/DriveWorkers.kt")
        assertWithMessage("DriveStartWorker muss PendingDrivePolicy.shouldDefer fragen")
            .that(src).contains("PendingDrivePolicy.shouldDefer(")
        assertWithMessage("Bei einer laufenden Aufzeichnung muss die Fahrt vorgemerkt werden")
            .that(src).contains("bridge.markDrivePending(")
    }

    @Test
    fun `PendingDriveStarter-ist-am-App-Start-registriert`() {
        val app = source("AevumApplication.kt")
        assertWithMessage("Der Beobachter muss beim App-Start laufen")
            .that(app).contains("pendingDriveStarter.start()")
        val starter = source("automation/activityrecognition/PendingDriveStarter.kt")
        assertWithMessage("Der Beobachter muss auf liveSession hören")
            .that(starter).contains("liveActivityManager.liveSession.collect")
        assertWithMessage("Er muss die getestete Nachhol-Regel benutzen")
            .that(starter).contains("PendingDrivePolicy.shouldStartAfterBlock(")
        assertWithMessage("Nach dem Start muss der Vormerk gelöscht werden")
            .that(starter).contains("bridge.clearDrivePending()")
    }

    // ── 4) Kalender-Blöcke nur in der Zukunft ─────────────────────────

    @Test
    fun `Timeline-filtert-geplante-Bloecke-auf-die-Zukunft`() {
        val src = source("ui/screens/timeline/TimelineViewModels.kt")
        assertWithMessage("Die 7-Tage-Vorschau muss gefiltert werden")
            .that(src).contains("filterPlannedWeekToFuture(planned7Raw")
        assertWithMessage("Der gewählte Tag muss gefiltert werden")
            .that(src).contains("filterPlannedToFuture(selectedRaw")
    }

    // ── 5) Sucht-Detektor: aktuelle Daten + Refresh beim Öffnen ───────

    @Test
    fun `Sucht-Detektor-nutzt-den-aktuellen-Zeitraum`() {
        val src = source("domain/addiction/AddictionAnalytics.kt")
        assertWithMessage("analyze muss currentWindow verwenden (inkl. heute)")
            .that(src).contains("val window = currentWindow(anchorDate, range)")
    }

    @Test
    fun `Sucht-Detektor-aktualisiert-bei-jedem-Oeffnen`() {
        val page = source("ui/screens/addiction/AddictionDetectorPage.kt")
        assertWithMessage("Die Seite muss auf Sichtbarkeit reagieren")
            .that(page).contains("LaunchedEffect(isActive)")
        val balance = source("ui/screens/digitalbalance/DigitalBalanceScreen.kt")
        assertWithMessage("Der Pager muss die Sichtbarkeit durchreichen")
            .that(balance).contains("isActive = pagerState.currentPage == 1")
    }

    // ── 6) Timeline-Liste: Details per Tap ────────────────────────────

    @Test
    fun `Timeline-Liste-oeffnet-Details-fuer-Trigger-und-Termine`() {
        val src = source("ui/screens/timeline/TimelineScreen.kt")
        assertWithMessage("Trigger-Zeile muss Details öffnen")
            .that(src).contains("onClick = { detailsEntry = entry }")
        assertWithMessage("Details-Dialog muss existieren")
            .that(src).contains("TimelineEntryDetailsDialog(")
        assertWithMessage("Zeilen mit Details brauchen die sichtbare Affordanz")
            .that(src).contains("detailsAffordance = true")
    }
}
