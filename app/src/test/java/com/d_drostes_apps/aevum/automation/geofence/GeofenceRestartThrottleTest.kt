package com.d_drostes_apps.aevum.automation.geofence

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.121 (Kanban t_fe3e99da): Regressionstests für die
 * Geofence-Re-Start-Drossel — der Loop-Breaker gegen das
 * Start/Stop-Flackern nach Fahrt-Ende (Hintergrund-Crash-Loop).
 *
 * Szenario (Diagnose t_2edf98d8 §4.2): Nach einer durch Bug 1
 * korrumpierten Session kollidieren Drive-EndGeofenceRestarter und
 * DriveStartWorker/GeofenceTransitionProcessor; jeder Drive-Stop-
 * Pfad schedult den Restarter neu, der Start einer Geofence-Session
 * wird von der nächsten Automatik gestoppt, der nächste Stop-Pfad
 * feuert erneut → Session-Flackern in Sekundenabständen + Race mit
 * dem LiveActivityService-Restore.
 *
 * Die Drossel muss: (1) den ersten legitimen Re-Start nach einer
 * echten Fahrt erlauben, (2) das Flackern nach 2 Starts im Fenster
 * brechen, (3) nach dem Cooldown wieder öffnen, (4) Geofences
 * getrennt zählen.
 */
class GeofenceRestartThrottleTest {

    @Test
    fun `erste zwei Restarts pro Geofence sind erlaubt`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        assertThat(throttle.allowRestart("gym")).isTrue()
        assertThat(throttle.allowRestart("gym")).isTrue()
    }

    @Test
    fun `dritter Restart im Fenster wird geblockt und setzt Cooldown`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        throttle.allowRestart("gym")
        // Dritter Start im 10-Min-Fenster = Flackern → blockiert.
        assertThat(throttle.allowRestart("gym")).isFalse()
        assertThat(throttle.isSuppressed("gym")).isTrue()
    }

    @Test
    fun `nach Ablauf des Cooldowns ist der Re-Start wieder erlaubt`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        throttle.allowRestart("gym")
        assertThat(throttle.allowRestart("gym")).isFalse()

        // Cooldown (30 Min) verstreichen lassen.
        now += GeofenceRestartThrottle.DEFAULT_COOLDOWN_MS + 1

        assertThat(throttle.isSuppressed("gym")).isFalse()
        assertThat(throttle.allowRestart("gym")).isTrue()
    }

    @Test
    fun `verschiedene Geofences werden getrennt gezaehlt`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        throttle.allowRestart("gym")

        // Anderer Geofence (z.B. Zuhause) ist nicht betroffen.
        assertThat(throttle.allowRestart("home")).isTrue()
        // Gym ist nach 2 Starts blockiert, Home noch nicht.
        assertThat(throttle.allowRestart("gym")).isFalse()
        assertThat(throttle.allowRestart("home")).isTrue()
    }

    @Test
    fun `Starts ausserhalb des Fensters verfallen - alte Evidenz zaehlt nicht`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        assertThat(throttle.allowRestart("gym")).isTrue()

        // Fenster (10 Min) verstreichen lassen — die zwei Starts sind
        // verfallen, ein neuer Start ist legitim (echter Folgebesuch).
        now += GeofenceRestartThrottle.DEFAULT_WINDOW_MS + 1
        assertThat(throttle.allowRestart("gym")).isTrue()
        assertThat(throttle.allowRestart("gym")).isTrue()
    }

    @Test
    fun `Flacker-Serie bleibt blockiert bis der Cooldown abgelaufen ist`() {
        // Das Kern-Szenario des Crash-Loops: Die Stop→Restart-Kaskade
        // feuert in Sekundenabständen. Auch 10 weitere Versuche innerhalb
        // des Cooldowns müssen blockiert bleiben (kein Zähler-Reset durch
        // die geblockten Versuche).
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        throttle.allowRestart("gym")
        repeat(10) {
            assertThat(throttle.allowRestart("gym")).isFalse()
            now += 5_000L // Stop-Pfade feuern alle Sekunden
        }
        assertThat(throttle.isSuppressed("gym")).isTrue()

        now += GeofenceRestartThrottle.DEFAULT_COOLDOWN_MS + 1
        assertThat(throttle.allowRestart("gym")).isTrue()
    }

    @Test
    fun `reset raeumt alle Zaehler und Sperren`() {
        var now = 1_000_000L
        val throttle = GeofenceRestartThrottle(clock = { now })

        throttle.allowRestart("gym")
        throttle.allowRestart("gym")
        throttle.allowRestart("gym") // jetzt blockiert + Cooldown

        throttle.reset()

        assertThat(throttle.isSuppressed("gym")).isFalse()
        assertThat(throttle.allowRestart("gym")).isTrue()
    }
}
