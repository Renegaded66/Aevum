package com.d_drostes_apps.aevum.automation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M18.70: Bildschirm-Aufzeichnung — pure Logik-Tests.
 *
 * Abgedeckte Regeln (User-Spec):
 *  - x Minuten am Stück an + nichts anderes zeichnet auf → starten
 *  - x = 0 → sofort bei Screen-ON
 *  - x = -1 (deaktiviert) → nie starten
 *  - Vorlaufzeit: startedAt = now − x min
 *  - Screen-OFF stoppt immer
 */
class ScreenRecordingEngineTest {

    private val now = 1_000_000L
    private val screenOn = now - 10 * 60_000L // vor 10 Minuten

    // ── shouldStartRecording ──

    @Test
    fun `deactivated never starts`() {
        assertFalse(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = screenOn,
                now = now,
                minutes = ScreenRecordingEngine.DEACTIVATED,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `zero minutes starts immediately`() {
        assertTrue(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = now, // gerade erst an
                now = now,
                minutes = 0,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `does not start before threshold`() {
        assertFalse(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = now - 2 * 60_000L, // erst 2 min an
                now = now,
                minutes = 5,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `starts exactly at threshold`() {
        assertTrue(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = now - 5 * 60_000L, // exakt 5 min an
                now = now,
                minutes = 5,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `starts after threshold`() {
        assertTrue(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = now - 7 * 60_000L, // 7 min an
                now = now,
                minutes = 5,
                anythingRecording = false
            )
        )
    }

    @Test
    fun `does not start when something else records`() {
        assertFalse(
            ScreenRecordingEngine.shouldStartRecording(
                screenOnSinceMs = screenOn,
                now = now,
                minutes = 5,
                anythingRecording = true
            )
        )
    }

    // ── recordingStartTime (Vorlaufzeit) ──

    @Test
    fun `start time goes back by configured minutes`() {
        assertEquals(now - 5 * 60_000L, ScreenRecordingEngine.recordingStartTime(now, 5))
    }

    @Test
    fun `start time is now for zero minutes`() {
        assertEquals(now, ScreenRecordingEngine.recordingStartTime(now, 0))
    }

    @Test
    fun `start time is now for deactivated`() {
        assertEquals(now, ScreenRecordingEngine.recordingStartTime(now, ScreenRecordingEngine.DEACTIVATED))
    }

    // ── Slider-Mapping ──

    @Test
    fun `slider max maps to deactivated`() {
        assertEquals(ScreenRecordingEngine.DEACTIVATED, ScreenRecordingEngine.sliderToDb(ScreenRecordingEngine.SLIDER_MAX))
    }

    @Test
    fun `slider zero maps to zero`() {
        assertEquals(0, ScreenRecordingEngine.sliderToDb(0))
    }

    @Test
    fun `slider five maps to five`() {
        assertEquals(5, ScreenRecordingEngine.sliderToDb(5))
    }

    @Test
    fun `deactivated db maps to slider max`() {
        assertEquals(ScreenRecordingEngine.SLIDER_MAX, ScreenRecordingEngine.dbToSlider(ScreenRecordingEngine.DEACTIVATED))
    }

    @Test
    fun `db five maps to slider five`() {
        assertEquals(5, ScreenRecordingEngine.dbToSlider(5))
    }

    // ── Screen-OFF (M18.71: erst nach 30s stoppen) ──

    @Test
    fun `screen off does not stop immediately`() {
        assertFalse(
            ScreenRecordingEngine.shouldStopOnScreenOff(
                screenOffSinceMs = now - 5_000L, // erst 5s aus
                now = now
            )
        )
    }

    @Test
    fun `screen off stops after 30 seconds`() {
        assertTrue(
            ScreenRecordingEngine.shouldStopOnScreenOff(
                screenOffSinceMs = now - 30_000L, // exakt 30s aus
                now = now
            )
        )
    }

    @Test
    fun `screen off stops after more than 30 seconds`() {
        assertTrue(
            ScreenRecordingEngine.shouldStopOnScreenOff(
                screenOffSinceMs = now - 45_000L, // 45s aus
                now = now
            )
        )
    }

    @Test
    fun `screen on never stops`() {
        assertFalse(
            ScreenRecordingEngine.shouldStopOnScreenOff(
                screenOffSinceMs = 0L, // Screen an
                now = now
            )
        )
    }
}
