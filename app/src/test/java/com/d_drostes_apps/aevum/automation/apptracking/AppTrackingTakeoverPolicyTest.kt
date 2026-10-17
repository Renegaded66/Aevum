package com.d_drostes_apps.aevum.automation.apptracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.151: Übergabe-Regeln App-Aufzeichnung ↔ Digital — pure Logik,
 * JVM-getestet.
 *
 * Deckt die User-Spezifikation ab:
 *  - „… stoppt die Aufzeichnung von Digital und die neue Aufzeichnung von
 *    der zugewiesenen [Activity wird] gestartet." (TAKE_OVER_DIGITAL)
 *  - „… dann soll die alte Aufzeichnung stoppen und die neue beginnen.
 *    Und zwar direkt." (STOP_AND_START)
 *  - Rangfolge: Geofence/Sport/manuell/Autofahrt haben immer Vorrang
 *    (KEEP_FOREIGN — NICHTS anfassen).
 */
class AppTrackingTakeoverPolicyTest {

    // ── Grundfälle ────────────────────────────────────────────────

    @Test
    fun `nichts laeuft - App-Activity starten`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = null,
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.START_APP)
    }

    @Test
    fun `eigene App-Session laeuft - Wechsel stoppen und starten`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING,
                isOwnSession = true
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.STOP_AND_START)
    }

    @Test
    fun `Digital laeuft - App-Aufzeichnung uebernimmt sofort`() {
        // Der gemeldete Bug: „… stoppt leider auch nicht die Aufzeichnung
        // von Digital … sondern fälschlicherweise wird einfach weiterhin
        // nur Digital aufgezeichnet."
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO,
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.TAKE_OVER_DIGITAL)
    }

    // ── Rangfolge: fremde Aufzeichnungen werden NIE angetastet ────

    @Test
    fun `Geofence hat Vorrang`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "GEOFENCE_AUTO",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    @Test
    fun `Autofahrt hat Vorrang`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "ACTIVITY_RECOGNITION_AUTO",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    @Test
    fun `sportliche Aufzeichnung hat Vorrang`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "WALKING_AUTO",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    @Test
    fun `manuell gestartete Aufzeichnung hat Vorrang`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "LIVE",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    @Test
    fun `Kalender-Fallback hat Vorrang (Nicht-Smartphone-Aufzeichnung)`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "CALENDAR_AUTO",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    @Test
    fun `unbekannte Quelle wird konservativ NIE angefasst`() {
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "SOME_FUTURE_SOURCE",
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.KEEP_FOREIGN)
    }

    // ── Vorrang von isOwnSession vor dem Quellen-Typ ───────────────

    @Test
    fun `eigene Session schlaegt jede Quellen-Heuristik`() {
        // Eigene Sessions tragen APP_TRACKING — selbst wenn ein Aufrufer
        // einmal etwas anderes übergeben würde, bleibt der Wechsel korrekt.
        assertThat(
            AppTrackingTakeoverPolicy.onTrackedForeground(
                liveSourceType = "SCREEN_AUTO",
                isOwnSession = true
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.STOP_AND_START)
    }

    // ── Konstanten ────────────────────────────────────────────────

    @Test
    fun `Quellen-Konstanten matchen die produktiven sourceTypes`() {
        assertThat(AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO).isEqualTo("SCREEN_AUTO")
        assertThat(AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING).isEqualTo("APP_TRACKING")
    }

    // ── M19.3: Nachfassen gegen die beobachtete Unzuverlässigkeit ─

    @Test
    fun `Wiederholungs-Check - Digital laeuft trotz aktueller App - uebernehmen`() {
        // Der M19.3-Report: „Oft zeichnet der einfach weiter digital auf
        // obwohl ich etwas bestimmtes geöffnet habe." — die getrackte App
        // ist bereits „aktuell", Digital ist aber wieder angelaufen.
        assertThat(
            AppTrackingTakeoverPolicy.onRepeatedForeground(
                liveSourceType = AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO,
                liveIsLive = true,
                isOwnSession = false
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.Action.TAKE_OVER_DIGITAL)
    }

    @Test
    fun `Wiederholungs-Check - eigene Session laeuft - nichts tun`() {
        assertThat(
            AppTrackingTakeoverPolicy.onRepeatedForeground(
                liveSourceType = AppTrackingTakeoverPolicy.SOURCE_APP_TRACKING,
                liveIsLive = true,
                isOwnSession = true
            )
        ).isNull()
    }

    @Test
    fun `Wiederholungs-Check - gar nichts live - nichts tun (kein Auto-Neustart)`() {
        // Wichtig: Ein bewusst/manuell gestoppter Zustand darf auf keinen
        // Fall automatisch neu starten — sonst kämpfte der Nutzer alle 2s
        // gegen den Service.
        assertThat(
            AppTrackingTakeoverPolicy.onRepeatedForeground(
                liveSourceType = null,
                liveIsLive = false,
                isOwnSession = false
            )
        ).isNull()
    }

    @Test
    fun `Wiederholungs-Check - fremde Quelle laeuft - Rangfolge, nichts tun`() {
        assertThat(
            AppTrackingTakeoverPolicy.onRepeatedForeground(
                liveSourceType = "GEOFENCE_AUTO",
                liveIsLive = true,
                isOwnSession = false
            )
        ).isNull()
    }

    @Test
    fun `Stopp-Race - nichts mehr live - Ziel bereits erreicht`() {
        assertThat(
            AppTrackingTakeoverPolicy.onStopRace(freshSourceType = null, freshIsLive = false)
        ).isEqualTo(AppTrackingTakeoverPolicy.StopRace.OBJECTIVE_MET)
    }

    @Test
    fun `Stopp-Race - wieder Digital angelaufen - einmal nachfassen`() {
        assertThat(
            AppTrackingTakeoverPolicy.onStopRace(
                freshSourceType = AppTrackingTakeoverPolicy.SOURCE_SCREEN_AUTO,
                freshIsLive = true
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.StopRace.RETRY_STOP)
    }

    @Test
    fun `Stopp-Race - Hoeherpriores hat uebernommen - Rangfolge`() {
        assertThat(
            AppTrackingTakeoverPolicy.onStopRace(
                freshSourceType = "ACTIVITY_RECOGNITION_AUTO",
                freshIsLive = true
            )
        ).isEqualTo(AppTrackingTakeoverPolicy.StopRace.KEEP_FOREIGN)
    }
}
