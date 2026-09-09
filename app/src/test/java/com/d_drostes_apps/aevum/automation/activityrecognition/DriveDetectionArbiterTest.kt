package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.113: Speed-Position-Konsistenz-Tests (Arbiter).
 *
 * User-Bugs:
 *  1. „Drive aufgezeichnet obwohl ich spazieren war" — GPS-Multipath-Spikes
 *     (2 konsekutive ≥ 8 m/s) erfüllten die Drive-Kette, obwohl die
 *     POSITION zwischen den Fixes nur Geh-Distanz legte.
 *     → Konsistenz-Check: Speed behauptet, Position widerspricht →
 *       Spike zählt nicht als schnell → Kette bricht.
 *  2. „Spazieren aufgezeichnet während 30er-Zone-Fahrt" — separat im
 *     WalkingVehicleVetoTest (abgeleitetes Tempo als Veto).
 */
class DriveDetectionArbiterTest {

    private val t0 = 1_000_000L

    /** Probe mit REALISTISCHER Positions-Distanz: der lat-Schritt wird aus
     *  der Speed abgeleitet (dt=120s), damit Speed und Position konsistent
     *  sind — außer bei Spikes, die bewusst WIDERSPRÜCHLICH gesetzt werden
     *  (hohe Speed, Positions-Distanz bleibt im Geh-Bereich). */
    private fun probe(
        index: Int,
        speedMps: Float?,
        accuracy: Float = 20f,
        latitude: Double?,
        longitude: Double? = 8.0
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = t0 + index * 120_000L,
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = null,
        latitude = latitude,
        longitude = longitude
    )

    @Test
    fun `Spaziergang mit 2 Multipath-Spikes wird NICHT als Fahrt klassifiziert`() {
        // 40-Minuten-Spaziergang (1,4 m/s ≈ 168 m/2 Min ≈ 0,0015° lat),
        // mitten drin 2 Multipath-Spikes: behaupten 8,5 m/s (= 1020 m/2 Min),
        // aber die Position legt nur Geh-Distanz zurück (0,0015°).
        // Vor M18.113: 2 konsekutive ≥ 8 + avg 4,68 ≥ 4,5 → DRIVING (FP!).
        // Jetzt: beide Spikes positionswidrig → Kette bricht → NotDriving.
        val walkStep = 0.0015
        val probes = listOf(
            probe(0, 1.4f, latitude = 50.0),
            probe(1, 8.5f, latitude = 50.0 + walkStep),      // SPIKE (unrealistisch)
            probe(2, 8.5f, latitude = 50.0 + 2 * walkStep),  // SPIKE
            probe(3, 4.2f, latitude = 50.0 + 3 * walkStep),
            probe(4, 1.3f, latitude = 50.0 + 4 * walkStep),
            probe(5, 4.2f, latitude = 50.0 + 5 * walkStep)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 16L * 60 * 1000)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
    }

    @Test
    fun `Reine Multipath-Spike-Serie mit Geh-Schnitt bleibt NotDriving`() {
        val walkStep = 0.0015
        val probes = listOf(
            probe(0, 1.5f, latitude = 50.0),
            probe(1, 8.5f, latitude = 50.0 + walkStep),      // SPIKE
            probe(2, 8.5f, latitude = 50.0 + 2 * walkStep),  // SPIKE
            probe(3, 1.4f, latitude = 50.0 + 3 * walkStep),
            probe(4, 1.6f, latitude = 50.0 + 4 * walkStep)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 16L * 60 * 1000)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
    }

    @Test
    fun `Echte 30er-Zone-Fahrt bleibt Driving — Speed bestätigt die Position`() {
        // 30 km/h = 8,3 m/s konstant über 5 Probes (996 m/2 Min ≈ 0,009° lat).
        // Speed und Position konsistent → alle schnell-Probes bestätigt → Driving.
        val driveStep = 0.009
        val probes = listOf(
            probe(0, 3.0f, latitude = 50.0),
            probe(1, 8.3f, latitude = 50.0 + driveStep),
            probe(2, 8.3f, latitude = 50.0 + 2 * driveStep),
            probe(3, 11.1f, latitude = 50.0 + 3 * driveStep),
            probe(4, 8.3f, latitude = 50.0 + 4 * driveStep)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 16L * 60 * 1000)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Stop-und-Go Stadtverkehr mit konsistenten Fix-Distanzen bleibt Driving`() {
        // Ampel-Muster mit konsistenter Position: schnelle Probes (8-11) haben
        // passend große Distanzen, Ampel-Nullen kleine.
        // dt-Abstände: 2 Min Fix-Rate; Strecken real passend zur Speed.
        val probes = listOf(
            probe(0, 3.0f,  latitude = 50.000),
            probe(1, 8.3f,  latitude = 50.009),   // 996 m / 120 s = 8,3 ✓
            probe(2, 0.0f,  latitude = 50.017),   // Ampel (Speed 0)
            probe(3, 11.1f, latitude = 50.029),   // 2 Min nach Anfahren: 1332 m ✓
            probe(4, 8.3f,  latitude = 50.038)    // wieder 30er-Tempo ✓
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 16L * 60 * 1000)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Schnelle kurze Fahrt bleibt Driving`() {
        val driveStep = 0.02 // ~2200 m/2 Min ≈ 18,4 m/s
        val probes = (0..5).map { probe(it, 22.0f, latitude = 50.0 + it * driveStep) }
        val result = DriveDetectionEngine.classify(probes, t0 + 16L * 60 * 1000)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }
}
