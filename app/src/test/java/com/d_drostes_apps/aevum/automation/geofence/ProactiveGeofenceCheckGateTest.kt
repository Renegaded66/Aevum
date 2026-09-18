package com.d_drostes_apps.aevum.automation.geofence

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.130 (t_3ac05e06, Motorrad-Fix): Regression-Tests für das
 * entkoppelte Suspicion-Gate des ProactiveGeofenceCheckWorker.
 *
 * Root-Cause (Katalysator, Review t_977652ea + Reproduktion
 * t_eff2f301): Der Bewegungs-Verdacht — der EINZIGE AR-unabhängige
 * CONFIRM-Burst-Trigger — hing am `geofencingEnabled`-Gate (Default
 * FALSE). Motorrad-User ohne Geofences bekamen NIE einen CONFIRM-
 * Burst; zusammen mit dem M18.117-Motion-Gate entstand der
 * Totalausfall (10 Min Fahrt, nichts aufgezeichnet).
 *
 * Fix: Der Worker läuft, sobald Geofencing ODER Auto-Erkennung ODER
 * Walking-Erkennung aktiv ist — der Verdacht dient der FAHRT- und
 * WALKING-Erkennung, nicht dem Geofencing-Feature.
 */
class ProactiveGeofenceCheckGateTest {

    // ── Kern: Verdacht läuft auch OHNE Geofencing ────────────────

    @Test
    fun `Auto-Erkennung an, Geofencing aus - Worker laeuft (Motorrad-User-Fall)`() {
        assertThat(
            ProactiveGeofenceCheckWorker.shouldRunCheck(
                geofencingEnabled = false,
                drivingEnabled = true,
                walkingEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `Nur Walking-Erkennung an - Worker laeuft`() {
        assertThat(
            ProactiveGeofenceCheckWorker.shouldRunCheck(
                geofencingEnabled = false,
                drivingEnabled = false,
                walkingEnabled = true
            )
        ).isTrue()
    }

    @Test
    fun `Nur Geofencing an - Worker laeuft (bisheriges Verhalten)`() {
        assertThat(
            ProactiveGeofenceCheckWorker.shouldRunCheck(
                geofencingEnabled = true,
                drivingEnabled = false,
                walkingEnabled = false
            )
        ).isTrue()
    }

    // ── Alles aus = Worker sinnlos ────────────────────────────────

    @Test
    fun `Alle drei Erkennungen aus - Worker skippt`() {
        assertThat(
            ProactiveGeofenceCheckWorker.shouldRunCheck(
                geofencingEnabled = false,
                drivingEnabled = false,
                walkingEnabled = false
            )
        ).isFalse()
    }
}
