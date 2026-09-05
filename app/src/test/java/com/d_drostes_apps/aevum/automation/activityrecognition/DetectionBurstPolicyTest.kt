package com.d_drostes_apps.aevum.automation.activityrecognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M18.104: Unit-Tests der ereignisgetriebenen GPS-Burst-Politik
 * (Akku-Redesign). JVM-pure — gleiche Konvention wie DriveDetectionEngineTest
 * / WalkingDetectionEngineTest (Android-frei, keine Robolectric-Abhängigkeit).
 *
 * Die Tests sichern die Kern-Invarianten des Redesigns:
 *  1. Burst-Fenster sind BEGRENZT (kein Dauerzustand wie der alte
 *     24/7-Stream).
 *  2. Cooldowns blocken Burst-Kaskaden nach ergebnislosen Bursts
 *     (AR-Flapping, Raumwechsel-Rauschen).
 *  3. Frische Trigger nach Cooldown kommen IMMER durch (Zuverlässigkeit).
 */
class DetectionBurstPolicyTest {

    // ── Fenster-Größen ──────────────────────────────────────────────

    @Test
    fun `CONFIRM-Fenster ist begrenzt (6 Min, kein Dauerzustand)`() {
        assertEquals(6L * 60 * 1000, DetectionBurstPolicy.CONFIRM_WINDOW_MS)
    }

    @Test
    fun `WALKING-Fenster ist begrenzt (8 Min) und deckt die 5-Min-Schwelle`() {
        assertEquals(8L * 60 * 1000, DetectionBurstPolicy.WALKING_CHECK_WINDOW_MS)
        assertTrue(
            "WALKING-Fenster muss die 5-Min-Schwelle der Engine decken",
            DetectionBurstPolicy.WALKING_CHECK_WINDOW_MS > WalkingDetectionEngine.WALKING_THRESHOLD_MS
        )
    }

    @Test
    fun `CONFIRM-Fenster deckt GPS-Warmup plus Engine-Mindestfenster`() {
        // 60s Warmup + MIN_SPREAD (30s) müssen in das CONFIRM-Fenster passen,
        // sonst kann eine Kaltstart-Fahrt nie bestätigt werden.
        assertTrue(
            DetectionBurstPolicy.CONFIRM_WINDOW_MS > 60_000L + DriveDetectionEngine.MIN_SPREAD_MS
        )
    }

    @Test
    fun `TRACK-Tick ist kurz genug fuer sauberes Session-Ende (2 Min)`() {
        assertEquals(2L * 60 * 1000, DetectionBurstPolicy.TRACK_TICK_MS)
    }

    // ── CONFIRM-Cooldown ─────────────────────────────────────────────

    @Test
    fun `CONFIRM erlaubt nach ergebnislosem Burst erst nach Cooldown`() {
        val lastEnd = 1_000_000L
        // 1 Min nach ergebnislosem Ende -> Cooldown aktiv (3 Min)
        assertFalse(
            DetectionBurstPolicy.confirmBurstAllowed(lastEnd + 60_000L, lastEnd)
        )
        // Genau 3 Min -> Cooldown abgelaufen
        assertTrue(
            DetectionBurstPolicy.confirmBurstAllowed(
                lastEnd + DetectionBurstPolicy.BURST_COOLDOWN_MS,
                lastEnd
            )
        )
        // Lange danach -> frei
        assertTrue(
            DetectionBurstPolicy.confirmBurstAllowed(lastEnd + 3600_000L, lastEnd)
        )
    }

    @Test
    fun `CONFIRM ohne vorherigen Burst ist immer erlaubt`() {
        // lastResultlessConfirmEndMs == 0 (Prozessstart, nur erfolgreiche
        // Bursts bisher) -> kein Cooldown. Frischer Verdacht = frischer
        // Burst (Zuverlässigkeit).
        assertTrue(DetectionBurstPolicy.confirmBurstAllowed(System.currentTimeMillis(), 0L))
    }

    // ── WALKING-Cooldown ─────────────────────────────────────────────

    @Test
    fun `WALKING-Cooldown ist laenger als CONFIRM-Cooldown (Raumwechsel-Rauschen)`() {
        assertTrue(
            DetectionBurstPolicy.WALKING_BURST_COOLDOWN_MS > DetectionBurstPolicy.BURST_COOLDOWN_MS
        )
    }

    @Test
    fun `WALKING erlaubt nach ergebnislosem Burst erst nach 10 Min`() {
        val lastEnd = 1_000_000L
        assertFalse(
            DetectionBurstPolicy.walkingBurstAllowed(lastEnd + 9L * 60 * 1000, lastEnd)
        )
        assertTrue(
            DetectionBurstPolicy.walkingBurstAllowed(
                lastEnd + DetectionBurstPolicy.WALKING_BURST_COOLDOWN_MS,
                lastEnd
            )
        )
    }

    @Test
    fun `WALKING ohne vorherigen Burst ist immer erlaubt`() {
        assertTrue(DetectionBurstPolicy.walkingBurstAllowed(System.currentTimeMillis(), 0L))
    }

    // ── Bewegungs-Verdacht (Fallback-Pfad ohne AR) ───────────────────

    @Test
    fun `Fahrzeug-Verdachtsschwelle liegt ueber Geh-Tempo-Displacement`() {
        // Gehen: ~1,4 m/s * 5 Min = 420 m Netto — darf KEINEN CONFIRM-Burst
        // auslösen (sonst würde jeder Spaziergang einen 6-Min-HIGH-GPS-
        // Burst starten).
        assertTrue(
            DetectionBurstPolicy.DRIVE_SUSPICION_MIN_DISPLACEMENT_M > 420.0
        )
        // 1500 m in 5 Min = 18 km/h Durchschnitt — Fahrrad-Tempo. Der
        // CONFIRM-Burst selbst entscheidet über die Engine-Gates (8 m/s),
        // ob es eine Fahrt ist; der Verdacht darf ruhig großzügig sein.
    }

    @Test
    fun `Walking-Verdachtsschwelle liegt ueber Indoor-Drift`() {
        // Indoor-Drift pendelt ±10-50 m um denselben Punkt (Netto ~0-50 m).
        // 200 m Netto ist nachhaltige Ortsveränderung.
        assertTrue(
            DetectionBurstPolicy.WALK_SUSPICION_MIN_DISPLACEMENT_M >= 200.0
        )
        // Und unter der echten Walking-Erkennungsschwelle (300 m) — der
        // Burst prüft die 300 m selbst.
        assertTrue(
            DetectionBurstPolicy.WALK_SUSPICION_MIN_DISPLACEMENT_M < 300.0
        )
    }

    @Test
    fun `Verdachtsfenster schliesst Jitter und stale Baselines aus`() {
        // < 2 Min: Positions-Jitter zwischen zwei Checks (Worker-Takt kann
        // schwanken) — keine Aussagekraft.
        assertEquals(2L * 60 * 1000, DetectionBurstPolicy.SUSPICION_MIN_DT_MS)
        // > 15 Min: Drift-Baseline veraltet (User kann längst wieder zu
        // Hause sein — Netto wäre irreführend).
        assertEquals(15L * 60 * 1000, DetectionBurstPolicy.SUSPICION_MAX_DT_MS)
    }

    // ── Bewegungs-Erneuerung (Stop&Go-Schutz) ────────────────────────

    @Test
    fun `CONFIRM-Verlangerungen sind gedeckelt (kein Dauerzustand durch Flapping)`() {
        assertTrue(
            "Max 2 Verlängerungen — Flapping darf keinen Dauer-Stream erzeugen",
            DetectionBurstPolicy.MAX_CONFIRM_EXTENSIONS in 1..3
        )
    }

    @Test
    fun `Bewegungs-Gate-Schwelle liegt ueber Geh-Tempo`() {
        // 2 m/s = 7,2 km/h — Gehen (1,0-1,5 m/s) darf das CONFIRM-Fenster
        // niemals verlängern (sonst: Dauer-Burst beim Spazierengehen).
        assertTrue(DetectionBurstPolicy.EXTENSION_MIN_AVG_SPEED_MPS > 1.5f)
        // Aber unter Stadtverkehr-Stau-Tempo (5-15 m/s).
        assertTrue(DetectionBurstPolicy.EXTENSION_MIN_AVG_SPEED_MPS < 5.0f)
    }

    // ── windowMsFor-Konsistenz ───────────────────────────────────────

    @Test
    fun `windowMsFor liefert die richtigen Fenster je Modus`() {
        assertEquals(
            DetectionBurstPolicy.CONFIRM_WINDOW_MS,
            DetectionBurstPolicy.windowMsFor(DetectionBurstPolicy.Mode.CONFIRM)
        )
        assertEquals(
            DetectionBurstPolicy.WALKING_CHECK_WINDOW_MS,
            DetectionBurstPolicy.windowMsFor(DetectionBurstPolicy.Mode.WALKING_CHECK)
        )
        assertEquals(
            DetectionBurstPolicy.TRACK_TICK_MS,
            DetectionBurstPolicy.windowMsFor(DetectionBurstPolicy.Mode.TRACK)
        )
        assertEquals(0L, DetectionBurstPolicy.windowMsFor(DetectionBurstPolicy.Mode.IDLE))
    }
}