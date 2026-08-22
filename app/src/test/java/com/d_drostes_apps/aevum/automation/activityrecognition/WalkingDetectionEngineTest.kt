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
}
