package com.d_drostes_apps.aevum.domain.call

import com.d_drostes_apps.aevum.automation.apptracking.AppTrackingTakeoverPolicy
import com.d_drostes_apps.aevum.domain.call.CallDetectionPolicy.Action
import com.d_drostes_apps.aevum.domain.call.CallDetectionPolicy.EndAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * M26: Wächter für die Rangfolge der Anruf-Erkennung.
 *
 * Die User-Spezifikation ist eine Rangfolge, keine Sammlung von Einzelfällen:
 *
 *   „… es soll auch aktuelle Aktivitäten unterbrechen, die durch
 *    Digital-Nutzung stattfinden, also Bildschirmaufzeichnung oder
 *    App-Aufzeichnung, auch PC digital, aber mehr darf nicht unterbrochen
 *    werden, also keine manuell gestartete Aktivität und auch keine
 *    Geofences und Kalender etc."
 *
 * Getestet wird deshalb BEIDES: die vier erlaubten Übernahmen UND die
 * Tabu-Liste — plus die Gegenprobe über eine unbekannte, künftige Quelle,
 * damit eine neue Aufzeichnungsart nicht versehentlich vom Anruf
 * übernommen wird (Allowlist statt Blockliste).
 */
class CallDetectionPolicyTest {

    // ── Beginn des Gesprächs ──────────────────────────────────────────────

    @Test
    fun `nichts laeuft - Anruf startet die zugeordnete Aktivitaet`() {
        assertThat(CallDetectionPolicy.onCallActive(true, null, false))
            .isEqualTo(Action.START_CALL)
    }

    @Test
    fun `Digital-Nutzung wird unterbrochen - alle vier Quellen`() {
        // Bildschirmaufzeichnung, App-Aufzeichnung, PC-Tracker, PC-Fernstart.
        for (source in listOf("SCREEN_AUTO", "APP_TRACKING", "PC_TRACKER", "PC_REMOTE")) {
            assertWithMessage("Quelle $source muss der Anruf übernehmen dürfen")
                .that(CallDetectionPolicy.onCallActive(true, source, true))
                .isEqualTo(Action.TAKE_OVER_DIGITAL)
        }
    }

    @Test
    fun `eigene Anruf-Aufzeichnung laeuft - kein zweiter Start`() {
        // Call-Waiting/Anklopfen feuert mehrere OFFHOOK-Callbacks. Ein
        // zweiter start() würde die laufende Session zerschneiden.
        assertThat(CallDetectionPolicy.onCallActive(true, "CALL_AUTO", true))
            .isEqualTo(Action.ALREADY_TRACKING)
    }

    @Test
    fun `manuell gestartete Aktivitaet wird NIEMALS unterbrochen`() {
        for (source in listOf("MANUAL", "LIVE")) {
            assertWithMessage("Quelle $source ist Nutzerabsicht und tabu")
                .that(CallDetectionPolicy.onCallActive(true, source, true))
                .isEqualTo(Action.KEEP_FOREIGN)
        }
    }

    @Test
    fun `Geofence und Kalender werden NIEMALS unterbrochen`() {
        for (source in listOf("GEOFENCE_AUTO", "CALENDAR_AUTO")) {
            assertWithMessage("Quelle $source ist tabu")
                .that(CallDetectionPolicy.onCallActive(true, source, true))
                .isEqualTo(Action.KEEP_FOREIGN)
        }
    }

    @Test
    fun `Fahrt Wanderung Garmin und Ping werden NIEMALS unterbrochen`() {
        val tabu = listOf(
            "ACTIVITY_RECOGNITION_AUTO",
            "WALKING_AUTO",
            "GARMIN_AUTO",
            "GARMIN_SLEEP_AUTO",
            "HEALTH_SLEEP_AUTO",
            "PING_AUTO"
        )
        for (source in tabu) {
            assertWithMessage("Quelle $source ist tabu")
                .that(CallDetectionPolicy.onCallActive(true, source, true))
                .isEqualTo(Action.KEEP_FOREIGN)
        }
    }

    @Test
    fun `unbekannte kuenftige Quelle ist tabu - Allowlist statt Blockliste`() {
        // Eine neue Aufzeichnungsart (künftiger Milestone) darf NICHT
        // automatisch vom Anruf übernommen werden. Genau dieser Fehler ist
        // in der Geofence-Policy (M18.144) und der Digital-Übergabe
        // (M18.151) je einmal passiert.
        assertThat(CallDetectionPolicy.onCallActive(true, "BRAND_NEW_SOURCE", true))
            .isEqualTo(Action.KEEP_FOREIGN)
    }

    @Test
    fun `Feature aus - keine Aktion, auch wenn nichts laeuft`() {
        assertThat(CallDetectionPolicy.onCallActive(false, null, false))
            .isEqualTo(Action.DISABLED)
        assertThat(CallDetectionPolicy.onCallActive(false, "SCREEN_AUTO", true))
            .isEqualTo(Action.DISABLED)
    }

    @Test
    fun `tote Live-Session blockiert den Start nicht`() {
        // liveIsLive = false (z. B. PAUSED/FINISHED-Rest in der DB) heißt:
        // es läuft nichts. Ein Anruf darf dann starten, egal welche Quelle
        // der tote Datensatz trägt.
        assertThat(CallDetectionPolicy.onCallActive(true, "MANUAL", false))
            .isEqualTo(Action.START_CALL)
        assertThat(CallDetectionPolicy.onCallActive(true, "GEOFENCE_AUTO", false))
            .isEqualTo(Action.START_CALL)
    }

    @Test
    fun `mayRecord nur bei START und TAKE_OVER`() {
        assertThat(CallDetectionPolicy.mayRecord(Action.START_CALL)).isTrue()
        assertThat(CallDetectionPolicy.mayRecord(Action.TAKE_OVER_DIGITAL)).isTrue()
        assertThat(CallDetectionPolicy.mayRecord(Action.ALREADY_TRACKING)).isFalse()
        assertThat(CallDetectionPolicy.mayRecord(Action.KEEP_FOREIGN)).isFalse()
        assertThat(CallDetectionPolicy.mayRecord(Action.DISABLED)).isFalse()
    }

    // ── Ende des Gesprächs ────────────────────────────────────────────────

    @Test
    fun `Anruf-Ende beendet nur die eigene Aufzeichnung`() {
        assertThat(CallDetectionPolicy.onCallEnded("CALL_AUTO", true))
            .isEqualTo(EndAction.STOP_OWN)
    }

    @Test
    fun `Anruf-Ende fasst fremde Aufzeichnungen nicht an`() {
        for (source in listOf(
            "MANUAL", "LIVE", "GEOFENCE_AUTO", "CALENDAR_AUTO",
            "ACTIVITY_RECOGNITION_AUTO", "WALKING_AUTO", "SCREEN_AUTO", "APP_TRACKING"
        )) {
            assertWithMessage("Quelle $source darf das Anruf-Ende nicht beenden")
                .that(CallDetectionPolicy.onCallEnded(source, true))
                .isEqualTo(EndAction.NOTHING)
        }
        assertThat(CallDetectionPolicy.onCallEnded(null, false))
            .isEqualTo(EndAction.NOTHING)
    }

    @Test
    fun `tote Anruf-Session wird nicht doppelt beendet`() {
        assertThat(CallDetectionPolicy.onCallEnded("CALL_AUTO", false))
            .isEqualTo(EndAction.NOTHING)
    }

    // ── Digital-Begriff ───────────────────────────────────────────────────

    @Test
    fun `Digital umfasst genau die vier Geraetequellen`() {
        assertThat(CallDetectionPolicy.DIGITAL_SOURCES)
            .containsExactly("SCREEN_AUTO", "APP_TRACKING", "PC_TRACKER", "PC_REMOTE")
        assertThat(CallDetectionPolicy.isDigital("MANUAL")).isFalse()
        assertThat(CallDetectionPolicy.isDigital("GEOFENCE_AUTO")).isFalse()
        assertThat(CallDetectionPolicy.isDigital(null)).isFalse()
    }

    @Test
    fun `Quell-Typen stimmen mit der Digital-Uebergabe ueberein`() {
        // Vier Stellen im Projekt benennen dieselben Quellen. Driftet eine
        // davon, unterbricht der Anruf plötzlich das Falsche (oder nichts).
        assertThat(CallDetectionPolicy.SOURCE_SCREEN_AUTO)
            .isEqualTo(AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO)
        assertThat(CallDetectionPolicy.SOURCE_APP_TRACKING)
            .isEqualTo(AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING)
    }
}
