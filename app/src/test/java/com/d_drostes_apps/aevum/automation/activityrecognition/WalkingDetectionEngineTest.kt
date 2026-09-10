package com.d_drostes_apps.aevum.automation.activityrecognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M18.72: Wanderungen automatisch aufzeichnen — pure Logik-Tests.
 *
 * Abgedeckte Regeln (User-Spec):
 *  - Erst nach 5 Minuten am Stück unterwegs starten (nicht jeder Gang
 *    zum Kühlschrank)
 *  - Die 5 Minuten Vorlaufzeit werden mit aufgezeichnet
 *    (startedAt = now − 5 min)
 *  - Kein Start, wenn nichts anderes aufzeichnet (nie zwei Live-Sessions)
 *  - Kein Start bei deaktivierter Walking-Erkennung
 *  - Stopp erst nach 5 Minuten ohne Walking-Signal (kurze Pausen
 *    beenden die Wanderung nicht)
 */
class WalkingDetectionEngineTest {

    private val now = 1_000_000L

    // ── shouldStartWalking: 5-Minuten-Schwelle ──

    @Test
    fun `does not start before 5 minutes of walking`() {
        assertFalse(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = now - 2 * 60_000L, // erst 2 min unterwegs
                now = now,
                walkingEnabled = true,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `starts exactly at 5 minutes`() {
        assertTrue(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = now - WalkingDetectionEngine.WALKING_THRESHOLD_MS,
                now = now,
                walkingEnabled = true,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `starts after 5 minutes`() {
        assertTrue(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = now - 7 * 60_000L,
                now = now,
                walkingEnabled = true,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `does not start without any walking signal`() {
        assertFalse(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = 0L,
                now = now,
                walkingEnabled = true,
                anythingRecording = false
            )
        )
    }

    // ── Gates ──

    @Test
    fun `does not start when walking detection is disabled`() {
        assertFalse(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = now - 10 * 60_000L,
                now = now,
                walkingEnabled = false,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `does not start when something else is recording`() {
        // Auch nach 5+ Minuten: andere Auto-/manuelle Session aktiv
        // (z. B. Autofahrt oder Digital) → nie zwei Live-Sessions.
        assertFalse(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = now - 10 * 60_000L,
                now = now,
                walkingEnabled = true,
                anythingRecording = true
            )
        )
    }

    // ── Vorlaufzeit (User-Spec (b): startedAt = now − 5 min) ──

    @Test
    fun `recording start time is now minus 5 minutes`() {
        assertEquals(
            now - WalkingDetectionEngine.WALKING_THRESHOLD_MS,
            WalkingDetectionEngine.recordingStartTime(now)
        )
    }

    // ── M18.84: Vorlauf-Clamp an letztes Auto-Ende ──

    @Test
    fun `M18_84 - effective walking since never precedes the last drive end`() {
        // AR-WALKING-Echos während der Fahrt: Signal-Phase startet 10 Min
        // vor dem Auto-Ende. Effektiv gezählt wird erst NACH der Fahrt.
        val driveEnd = now - 4 * 60_000L
        val walkingSince = now - 10 * 60_000L
        assertEquals(
            driveEnd,
            WalkingDetectionEngine.effectiveWalkingSince(walkingSince, driveEnd)
        )
        // Kein Auto-Ende bekannt → Signal-Phase zählt voll.
        assertEquals(
            walkingSince,
            WalkingDetectionEngine.effectiveWalkingSince(walkingSince, null)
        )
        // Signal NACH dem Auto-Ende → unverändert.
        val walkAfterDrive = now - 2 * 60_000L
        assertEquals(
            walkAfterDrive,
            WalkingDetectionEngine.effectiveWalkingSince(walkAfterDrive, driveEnd)
        )
    }

    @Test
    fun `M18_84 - walking threshold uses effective since not raw AR signal`() {
        // User-Fall 30.08.: WALKING-Phase begann während der Fahrt (AR-
        // Echos), Auto endete 19:10, erster ENTER danach 19:12. Rohe
        // Phase wäre ≥5 Min → Start mit Vorlauf in die Fahrt. Mit dem
        // Clamp: effektiv erst 2 Min → KEIN Start.
        val now2 = 1_200_000L
        val driveEnd = now2 - 2 * 60_000L       // Auto endete vor 2 Min
        val walkingSince = now2 - 8 * 60_000L   // AR-Phase begann vor 8 Min (in der Fahrt)
        assertFalse(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = walkingSince,
                now = now2,
                walkingEnabled = true,
                anythingRecording = false,
                lastDriveEndMs = driveEnd
            )
        )
        // Ohne Auto-Ende würde derselbe AR-Stand Start auslösen (alter
        // Zustand, reproduced): 8 Min ≥ 5 Min Schwelle.
        assertTrue(
            WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = walkingSince,
                now = now2,
                walkingEnabled = true,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `M18_84 - walking start time never precedes the last drive end`() {
        // Vorlauf now−5 Min läge VOR dem Auto-Ende → Start wird auf das
        // Auto-Ende geklemmt (keine Überlappung mit der Fahrt).
        val driveEnd = now - 2 * 60_000L
        assertEquals(
            driveEnd,
            WalkingDetectionEngine.recordingStartTime(now, driveEnd)
        )
        // Auto-Ende weit in der Vergangenheit → normaler 5-Min-Vorlauf.
        val oldDriveEnd = now - 60 * 60_000L
        assertEquals(
            now - WalkingDetectionEngine.WALKING_THRESHOLD_MS,
            WalkingDetectionEngine.recordingStartTime(now, oldDriveEnd)
        )
        // Kein Auto → normaler Vorlauf.
        assertEquals(
            now - WalkingDetectionEngine.WALKING_THRESHOLD_MS,
            WalkingDetectionEngine.recordingStartTime(now, null)
        )
    }

    // ── Watchdog: Stopp erst nach 5 Minuten ohne Signal ──

    @Test
    fun `does not stop while signals are fresh`() {
        assertFalse(
            WalkingDetectionEngine.shouldStopWalking(
                lastWalkingSignalMs = now - 60_000L, // 1 Min her
                now = now
            )
        )
    }

    @Test
    fun `stops exactly after 5 minutes without signal`() {
        assertTrue(
            WalkingDetectionEngine.shouldStopWalking(
                lastWalkingSignalMs = now - WalkingDetectionEngine.WALKING_WATCHDOG_NO_SIGNAL_MS,
                now = now
            )
        )
    }

    @Test
    fun `stops after more than 5 minutes without signal`() {
        assertTrue(
            WalkingDetectionEngine.shouldStopWalking(
                lastWalkingSignalMs = now - 8 * 60_000L,
                now = now
            )
        )
    }

    @Test
    fun `never stops without any signal`() {
        assertFalse(
            WalkingDetectionEngine.shouldStopWalking(
                lastWalkingSignalMs = 0L,
                now = now
            )
        )
    }

    // ── M18.110: Fahrzeug-Gates gegen „Spazieren während der Fahrt" ──

    @Test
    fun `vehicle speed fix is detected as vehicle`() {
        assertTrue(WalkingDetectionEngine.isVehicleSpeed(8.3f)) // 30er-Zone
        assertTrue(WalkingDetectionEngine.isVehicleSpeed(15.0f))
        assertFalse(WalkingDetectionEngine.isVehicleSpeed(7.9f)) // Fahrrad
        assertFalse(WalkingDetectionEngine.isVehicleSpeed(1.4f)) // Gehen
        assertFalse(WalkingDetectionEngine.isVehicleSpeed(null)) // kein Speed-Feld
    }

    @Test
    fun `phase exceeding walking speed is rejected — 30kmh car ride`() {
        // User-Fall: 5 Min durch die 30er-Zone = ~2.500 m Netto.
        assertTrue(
            WalkingDetectionEngine.exceedsWalkingSpeed(
                netMeters = 2500.0,
                durationMs = WalkingDetectionEngine.WALKING_THRESHOLD_MS
            )
        )
    }

    @Test
    fun `real walk does not exceed walking speed`() {
        // 5 Min Gehen ≈ 400-450 m Netto — deutlich unter 5 m/s.
        assertFalse(
            WalkingDetectionEngine.exceedsWalkingSpeed(
                netMeters = 450.0,
                durationMs = WalkingDetectionEngine.WALKING_THRESHOLD_MS
            )
        )
    }

    @Test
    fun `displacement veto catches no-speed vehicle fix at walk interval`() {
        // 60s BALANCED-Fixes, kein hasSpeed: 500 m zwischen Fixes =
        // 8,3 m/s Durchschnitt → Fahrzeug-Niveau (M18.117: dist/dt ≥ 5 m/s).
        assertTrue(
            WalkingDetectionEngine.isVehicleDisplacement(
                distMeters = 500.0,
                dtMs = 60_000L
            )
        )
        // Gehen: 90 m/Fix bleibt unter dem Veto (1,5 m/s).
        assertFalse(
            WalkingDetectionEngine.isVehicleDisplacement(
                distMeters = 90.0,
                dtMs = 60_000L
            )
        )
    }

    @Test
    fun `jogging at 16 kmh with 120s fix gap is NOT vehicle displacement`() {
        // M18.117 (Audit §3.4): Die fixe 350-m-Schwelle verwarf Joggen
        // bei 120-s-Fix-Lücken (533 m ≥ 350 m), obwohl 533 m / 120 s =
        // 4,44 m/s = 16 km/h reines Joggen ist. Das geschwindigkeits-
        // basierte Veto (5,0 m/s) lässt Joggen durch.
        assertFalse(
            WalkingDetectionEngine.isVehicleDisplacement(
                distMeters = 533.0,
                dtMs = 120_000L
            )
        )
        // Fahrzeug-Tempo bei gleicher Geometrie (1000 m / 120 s = 8,3 m/s)
        // wird weiterhin verworfen.
        assertTrue(
            WalkingDetectionEngine.isVehicleDisplacement(
                distMeters = 1000.0,
                dtMs = 120_000L
            )
        )
    }

    @Test
    fun `veto dt window excludes stale and jitter fixes`() {
        val minDt = WalkingDetectionEngine.WALKING_DISPLACEMENT_VETO_MIN_DT_MS
        val maxDt = WalkingDetectionEngine.WALKING_DISPLACEMENT_VETO_MAX_DT_MS
        // Jitter (10s) und Stale (5 Min) liegen außerhalb des Veto-Fensters.
        assertFalse(minDt <= 10_000L && 10_000L <= maxDt)
        assertFalse(minDt <= 5 * 60_000L && 5 * 60_000L <= maxDt)
        // Der 15s-Stream-Fix (30s dt) liegt im Fenster.
        assertTrue(minDt <= 30_000L && 30_000L <= maxDt)
    }
}
