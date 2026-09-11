package com.d_drostes_apps.aevum.automation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.121 (Kanban t_fe3e99da) + M18.122 (Kanban t_55c14376):
 * Regressionstests für die Sticky-Guards — der Loop-Breaker gegen die
 * START_STICKY-Restart-Amplifikation des Hintergrund-Crash-Loops.
 *
 * Szenario (Diagnose t_2edf98d8 §4.2): Alle 5 FGS laufen mit
 * START_STICKY. Stirbt der Prozess (Crash oder System-Kill), startet
 * Android jeden dieser Services automatisch neu — ohne Action-Intent.
 * Ein Service, der beim Start sofort stopSelf() zurückgibt (keine
 * Live-Session, keine trackbare Session, kein aktiver Geofence, keine
 * getrackte App), wird vom System in Sekundenabständen wiederbelebt:
 * "crasht alle paar Sekunden im Hintergrund".
 *
 * M18.122 (D2): Der Guard-Zustand ist PERSISTIERT (SharedPreferences,
 * pro Service ein Key) — die Entscheidung bei null-Action:
 *   · persistierter Eintrag < 5 Min alt UND Prozess frisch
 *     (< 30 s elapsedRealtime-Delta) → Kill-Rebirth → BREAK
 *   · kein Eintrag / zu alt → ALLOW (normal starten)
 * M18.122 (D3): App-interne Starts tragen ACTION_INTERNAL_START —
 * der Break passiert NUR bei action == null (Tests: Action-Intent
 * → nie Break).
 */
class StickyGuardServiceTest {

    private val wallBase = 10_000_000L
    private val realtimeBase = 1_000_000L

    // ── Pure Entscheidungsfunktion: Persistenz-Fälle (D2) ──────────────

    @Test
    fun `kein persistierter Eintrag - normal starten`() {
        // Frischer Prozess ohne vorherigen echten Start (z.B. nach
        // App-Update oder Datenlöschung): nie brechen.
        assertThat(
            stickyRebirthShouldBreak(
                lastLegitStartWallClockMs = 0L,
                nowWallClockMs = wallBase,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + 5_000L
            )
        ).isFalse()
    }

    @Test
    fun `frischer Kill-Rebirth im neuen Prozess wird gebrochen`() {
        // M18.122-Kernfall (D2): Prozess wurde gekillt, Android belebt
        // den Service im NEUEN Prozess wieder — der persistierte
        // Eintrag (vor dem Kill geschrieben, vor 4s) ist noch frisch,
        // der Prozess ist 2s alt → Kill→Rebirth-Loop → brechen.
        assertThat(
            stickyRebirthShouldBreak(
                lastLegitStartWallClockMs = wallBase,
                nowWallClockMs = wallBase + 4_000L,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + 2_000L
            )
        ).isTrue()
    }

    @Test
    fun `frischer Eintrag nach Cooldown-Fenster ist erlaubt`() {
        // Der letzte echte Start liegt > 5 Min zurück — der Rebirth
        // ist kein Wiederbelebungs-Loop mehr (auch im frischen Prozess).
        assertThat(
            stickyRebirthShouldBreak(
                lastLegitStartWallClockMs = wallBase,
                nowWallClockMs = wallBase + STICKY_GUARD_BREAK_WINDOW_MS + 1_000L,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + 2_000L
            )
        ).isFalse()
    }

    @Test
    fun `frischer Eintrag bei altem Prozess ist erlaubt`() {
        // Prozess lebt seit 10 Min, erst jetzt kommt ein Sticky-Restart
        // an (System-Stop + Restart nach langem Lauf) — kein
        // Sofort-Rebirth-Loop.
        assertThat(
            stickyRebirthShouldBreak(
                lastLegitStartWallClockMs = wallBase,
                nowWallClockMs = wallBase + 60_000L,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + 10L * 60 * 1000
            )
        ).isFalse()
    }

    @Test
    fun `genau an der Prozess-Frische-Grenze wird nicht mehr gebrochen`() {
        // 30s: Prozess nicht mehr "frisch" → kein Kill-Rebirth-Muster.
        assertThat(
            stickyRebirthShouldBreak(
                lastLegitStartWallClockMs = wallBase,
                nowWallClockMs = wallBase + 2_000L,
                processStartRealtime = realtimeBase,
                nowRealtime = realtimeBase + STICKY_GUARD_PROCESS_FRESH_MS
            )
        ).isFalse()
    }

    // ── Persistenz-Interface (D2): Eintrag überlebt die Instanz ─────────

    @Test
    fun `persistenter Speicher liefert den Eintrag einer anderen Instanz`() {
        // Simuliert den Kill→Rebirth: EINE Instanz schreibt, eine
        // NEUE Instanz (neuer Prozess) liest denselben Eintrag.
        val store = FakeStickyGuardPersistence()
        val firstInstance = StickyGuardService(store)
        firstInstance.markLegitStart(wallBase + 1_000L)

        val newInstance = StickyGuardService(store)
        assertThat(newInstance.shouldBreakStickyRebirth(
            processStartedAtRealtime = realtimeBase,
            nowRealtime = realtimeBase + 3_000L,
            nowWallClockMs = wallBase + 4_000L
        )).isTrue()
    }

    @Test
    fun `neuer echter Start setzt das Persistenz-Fenster zurueck`() {
        val store = FakeStickyGuardPersistence()
        val guard = StickyGuardService(store)
        guard.markLegitStart(wallBase + 60_000L) // alter Start
        // Neuer echter Start (z.B. App öffnen) aktualisiert den Eintrag.
        guard.markLegitStart(wallBase + 5L * 60 * 1000)
        // Rebirth 3s nach dem neuen Start im frischen Prozess → brechen.
        assertThat(guard.shouldBreakStickyRebirth(
            processStartedAtRealtime = realtimeBase,
            nowRealtime = realtimeBase + 3_000L,
            nowWallClockMs = wallBase + 5L * 60 * 1000 + 3_000L
        )).isTrue()
    }

    @Test
    fun `alter persistierter Eintrag - normal starten`() {
        // Eintrag vorhanden, aber > 5 Min alt: der Rebirth darf laufen
        // (z.B. App lief den ganzen Tag, System killte sie einmal).
        val store = FakeStickyGuardPersistence()
        store.markLegitStart(wallBase)
        val guard = StickyGuardService(store)
        assertThat(guard.shouldBreakStickyRebirth(
            processStartedAtRealtime = realtimeBase,
            nowRealtime = realtimeBase + 2_000L,
            nowWallClockMs = wallBase + 10L * 60 * 1000
        )).isFalse()
    }

    // ── Action-Konvention (D3): Action-Intent → nie Break ───────────────

    @Test
    fun `Action-Intent wird nie als Rebirth gebrochen - Konvention D3`() {
        // D3: App-interne Starts tragen ACTION_INTERNAL_START. In allen
        // 5 Services ist der Break an `intent?.action == null &&`
        // gekoppelt — mit Action wird shouldBreakStickyRebirth gar nicht
        // aufgerufen. Dieser Test hält die Persistenz-Seite der
        // Konvention fest: ein per Action markierter Start ist der neue
        // Persistenz-Anker, kein Break-Kandidat.
        val store = FakeStickyGuardPersistence()
        store.markLegitStart(wallBase) // Anker aus früherem Start
        val guard = StickyGuardService(store)

        // Interner Start (Service-Code: markLegitStart, keine Break-Frage).
        guard.markLegitStart(wallBase + 2_000L)
        assertThat(store.lastLegitStartMs()).isEqualTo(wallBase + 2_000L)
    }

    @Test
    fun `markLegitStart aktualisiert den Zeitstempel`() {
        val store = FakeStickyGuardPersistence()
        val guard = StickyGuardService(store)
        guard.markLegitStart(wallBase + 1_000L)
        guard.markLegitStart(wallBase + 2_000L)
        // Maßgeblich ist der ZWEITE Start (t+2s) — ein Rebirth bei
        // t+3s ist noch im Fenster.
        assertThat(guard.shouldBreakStickyRebirth(
            processStartedAtRealtime = realtimeBase,
            nowRealtime = realtimeBase + 3_000L,
            nowWallClockMs = wallBase + 3_000L
        )).isTrue()
    }

    // ── In-Memory-Persistence-Fake (Android-frei, JVM) ─────────────────

    private class FakeStickyGuardPersistence : StickyGuardPersistence {
        private var last: Long = 0L
        override fun lastLegitStartMs(): Long = last
        override fun markLegitStart(nowWallClockMs: Long) {
            last = nowWallClockMs
        }
    }
}
