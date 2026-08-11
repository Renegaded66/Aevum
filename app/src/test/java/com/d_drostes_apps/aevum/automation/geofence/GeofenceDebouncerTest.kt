package com.d_drostes_apps.aevum.automation.geofence

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.64: Regression-Tests für den Geofence-Debouncer.
 *
 * Sichert die Kern-Invariante ab: \"Ein Geofence-ENTER muss den
 * TransitionProcessor erreichen, auch wenn er ein Echo ist (App-Neustart
 * im Geofence, Neuregistrierung) — sonst startet die konfigurierte
 * Aktivität nach einem App-Neustart nie wieder.\"
 *
 * Der Processor dedupliziert Trigger selbst (skipTriggerCreation) und
 * refresht/startet die Session — der Debouncer darf ENTER-Echos nicht
 * komplett verschlucken (vorher: kein pending → StabilizationWorker
 * fand nichts → AlreadyEmitted → kein Auto-Start).
 */
class GeofenceDebouncerTest {

    private val t0 = 1_000_000L

    @Test
    fun `ENTER-Echo im Fenster wird als pending registriert — Worker erreicht den Processor`() {
        val debouncer = GeofenceDebouncer()

        // Erster ENTER: pending starten.
        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0)
        // Stabilisierung bestätigt.
        assertThat(debouncer.confirmPending("gym", GeofenceTransition.Enter, t0 + 8_000L))
            .isEqualTo(GeofenceDebouncer.ConfirmationResult.Confirmed)

        // M18.64: ENTER-Echo 2 Minuten später (App-Neustart im Geofence,
        // INITIAL_TRIGGER_ENTER / Neuregistrierung) — muss als pending
        // registriert werden, damit der StabilizationWorker den Processor
        // erreicht (Auto-Start-Refresh).
        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0 + 120_000L)
        assertThat(debouncer.currentlyPendingGeofenceIds()).contains("gym")

        // Der Worker bestätigt das Echo → Confirmed (nicht AlreadyEmitted).
        val result = debouncer.confirmPending("gym", GeofenceTransition.Enter, t0 + 128_000L)
        assertThat(result).isEqualTo(GeofenceDebouncer.ConfirmationResult.Confirmed)
    }

    @Test
    fun `EXIT-Echo im Fenster bleibt hart unterdrückt — kein pending`() {
        val debouncer = GeofenceDebouncer()

        debouncer.shouldEmit("home", GeofenceTransition.Exit, t0)
        debouncer.confirmPending("home", GeofenceTransition.Exit, t0 + 5_000L)

        // EXIT-Echo: wird unterdrückt UND es entsteht kein pending
        // (dort gibt es nichts zu refreshen — kein Auto-Start).
        debouncer.shouldEmit("home", GeofenceTransition.Exit, t0 + 60_000L)
        assertThat(debouncer.currentlyPendingGeofenceIds()).doesNotContain("home")
    }

    @Test
    fun `ENTER nach EXIT ist ein echter neuer Besuch — pending wird registriert`() {
        val debouncer = GeofenceDebouncer()

        debouncer.shouldEmit("gym", GeofenceTransition.Exit, t0)
        debouncer.confirmPending("gym", GeofenceTransition.Exit, t0 + 5_000L)

        // Nächster Besuch: ENTER (auch innerhalb des Echo-Fensters) ist
        // ein anderer Übergang → pending.
        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0 + 60_000L)
        assertThat(debouncer.currentlyPendingGeofenceIds()).contains("gym")
        assertThat(debouncer.confirmPending("gym", GeofenceTransition.Enter, t0 + 68_000L))
            .isEqualTo(GeofenceDebouncer.ConfirmationResult.Confirmed)
    }

    @Test
    fun `widersprüchlicher pendenter Übergang wird verworfen`() {
        val debouncer = GeofenceDebouncer()

        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0)
        // EXIT widerspricht dem pendenten ENTER (GPS-Flattern) → ENTER verworfen.
        debouncer.shouldEmit("gym", GeofenceTransition.Exit, t0 + 3_000L)
        assertThat(debouncer.confirmPending("gym", GeofenceTransition.Enter, t0 + 8_000L))
            .isEqualTo(GeofenceDebouncer.ConfirmationResult.Cancelled)
    }

    @Test
    fun `ENTER nach Ablauf des Echo-Fensters ist ein neuer Besuch`() {
        val debouncer = GeofenceDebouncer()

        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0)
        debouncer.confirmPending("gym", GeofenceTransition.Enter, t0 + 8_000L)

        // 11 Minuten später: Echo-Fenster (10 Min) abgelaufen → neuer Besuch.
        debouncer.shouldEmit("gym", GeofenceTransition.Enter, t0 + 11 * 60_000L)
        assertThat(debouncer.currentlyPendingGeofenceIds()).contains("gym")
    }
}
