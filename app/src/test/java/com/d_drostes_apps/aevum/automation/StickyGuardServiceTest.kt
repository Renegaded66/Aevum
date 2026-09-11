package com.d_drostes_apps.aevum.automation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.121 (Kanban t_fe3e99da): Regressionstests für die Sticky-Guards —
 * der Loop-Breaker gegen die START_STICKY-Restart-Amplifikation des
 * Hintergrund-Crash-Loops.
 *
 * Szenario (Diagnose t_2edf98d8 §4.2): Alle 5 FGS laufen mit
 * START_STICKY. Stirbt der Prozess (Crash oder System-Kill), startet
 * Android jeden dieser Services automatisch neu — ohne Action-Intent.
 * Ein Service, der beim Start sofort stopSelf() zurückgibt (keine
 * Live-Session, keine trackbare Session, kein aktiver Geofence, keine
 * getrackte App), wird vom System in Sekundenabständen wiederbelebt:
 * "crasht alle paar Sekunden im Hintergrund".
 *
 * Der Guard muss: (1) einen normalen Sticky-Restart nach langem
 * Betrieb erlauben, (2) den Kill→Sofort-Rebirth-Loop brechen,
 * (3) auch nach gebrochenen Restarts die nächsten echten Starts
 * normal behandeln.
 */
class StickyGuardServiceTest {

    private val realtimeBase = 1_000_000L

    @Test
    fun `normaler Sticky-Restart ohne vorherigen Start ist erlaubt`() {
        // Frischer Prozess, noch nie ein echter Start markiert.
        assertThat(
            stickyRestartAllowed(
                lastCommandReceivedAt = 0L,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + 5_000L
            )
        ).isTrue()
    }

    @Test
    fun `Kill-Rebirth sofort nach echtem Start wird gebrochen`() {
        // Prozess startete, App startete den Service (t+1s), Prozess
        // stirbt, Android belebt den Service 3s später wieder (Sticky).
        val processStart = realtimeBase
        val lastCommand = realtimeBase + 1_000L
        val now = realtimeBase + 4_000L

        assertThat(stickyRestartAllowed(lastCommand, processStart, now)).isFalse()
    }

    @Test
    fun `Sticky-Restart nach Cooldown-Fenster ist erlaubt`() {
        // Letzter echter Start liegt > 5 Min zurück — der Restart ist
        // kein Wiederbelebungs-Loop mehr.
        val processStart = realtimeBase
        val lastCommand = realtimeBase + 1_000L
        val now = realtimeBase + 5L * 60 * 1000 + 1_000L

        assertThat(stickyRestartAllowed(lastCommand, processStart, now)).isTrue()
    }

    @Test
    fun `alter Prozess mit spaetem Sticky-Restart ist erlaubt`() {
        // Der Prozess lebt seit 10 Minuten, erst jetzt kommt ein
        // Sticky-Restart an (z.B. System-Stop und sofortiger Restart
        // nach langem Lauf) — kein Sofort-Rebirth-Loop.
        val processStart = realtimeBase
        val lastCommand = realtimeBase + 60_000L
        val now = realtimeBase + 10L * 60 * 1000

        assertThat(stickyRestartAllowed(lastCommand, processStart, now)).isTrue()
    }

    @Test
    fun `GuardService markiert Starts und bricht Rebirths im Cooldown`() {
        val guard = StickyGuardService()
        val processStart = realtimeBase

        // Erster echter Start (App-Code, mit oder ohne Action).
        guard.markCommandReceived(realtimeBase + 1_000L)
        assertThat(
            guard.shouldBreakStickyRestart(realtimeBase + 4_000L, processStart)
        ).isTrue()
        assertThat(
            guard.shouldBreakStickyRestart(realtimeBase + 6L * 60 * 1000, processStart)
        ).isFalse()

        // Ein neuer echter Start (z.B. Session beginnt) setzt das Fenster
        // zurück. In einem ALTEN Prozess bleibt der Sticky-Restart trotzdem
        // erlaubt (kein Kill-Rebirth-Loop — der nächste Test zeigt den
        // Break im frischen Prozess).
        guard.markCommandReceived(realtimeBase + 10L * 60 * 1000)
        assertThat(
            guard.shouldBreakStickyRestart(realtimeBase + 10L * 60 * 1000 + 3_000L, processStart)
        ).isFalse()
    }

    @Test
    fun `neuer echter Start im frischen Prozess setzt das Guard-Fenster zurueck`() {
        val guard = StickyGuardService()
        // Prozess erst vor 5s gestartet — der Kill-Rebirth-Kontext.
        val processStart = realtimeBase + 10L * 60 * 1000 - 5_000L

        guard.markCommandReceived(realtimeBase + 60_000L) // alter Start
        guard.markCommandReceived(realtimeBase + 10L * 60 * 1000) // neuer echter Start

        // Rebirth 3s nach dem neuen Start im frischen Prozess → brechen.
        assertThat(
            guard.shouldBreakStickyRestart(realtimeBase + 10L * 60 * 1000 + 3_000L, processStart)
        ).isTrue()
    }

    @Test
    fun `markCommandReceived ersetzt den letzten Startzeitpunkt`() {
        val guard = StickyGuardService()
        val processStart = realtimeBase

        guard.markCommandReceived(realtimeBase + 1_000L)
        guard.markCommandReceived(realtimeBase + 2_000L)

        // Maßgeblich ist der ZWEITE Start (t+2s) — ein Rebirth bei
        // t+3s ist noch im Fenster.
        assertThat(
            guard.shouldBreakStickyRestart(realtimeBase + 3_000L, processStart)
        ).isTrue()
    }
}
