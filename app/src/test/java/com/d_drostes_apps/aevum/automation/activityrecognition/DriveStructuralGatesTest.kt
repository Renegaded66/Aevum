package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.84: Regressionstests für die strukturellen Fahrt-Gates — die
 * User-Fälle vom 30.08.2026 als wiederholbare Szenarien.
 *
 *  1. Gym-Phantom: 5h im Geofence (korrekt aufgezeichnet als Gym), dann
 *     wird TROTZDEM "Autofahren 16–19 Uhr" parallel gestartet — Indoor-
 *     Multipath-Speed-Spikes + langsame Drift ≥150 m erfüllten alle
 *     Speed-Gates. Fix: Geofence-Veto + Inside-Geofence-Cap.
 *  2. Post-Stop-Re-Trigger: Nach der echten Heimfahrt startet erneut
 *     "Autofahren 19:00–19:10" — der 15-Min-Probe-Puffer klassifiziert
 *     Park-/Aussteige-Drift sofort wieder als Fahrt. Fix: Restart-
 *     Cooldown 3 Min + Probe-Drain beim Stop.
 *  3. Walking-Phantom: "Spazieren 19:05–19:17" beim 100-m-Gang zur
 *     Wohnung — AR-WALKING-Echos während der Fahrt luden die Phase auf,
 *     der Vorlauf reichte in die Fahrt zurück. Fix: effectiveSince +
 *     recordingStartTime-Clamp (WalkingDetectionEngineTest).
 */
class DriveStructuralGatesTest {

    private val t0 = 1_000_000L

    /** Gym-Zentrum für die GeoCircle-Tests (250 m — realistische Geofence-
     *  Größe; die Indoor-Drift von ~200 m bleibt darin, der Netto-
     *  Displacement-Gate (150 m) wird trotzdem erfüllt — genau die
     *  Konstellation, die das Phantom ermöglichte). */
    private val gym = DriveDetectionEngine.GeoCircle(
        id = "gym-1",
        name = "Gym",
        latitude = 50.0,
        longitude = 8.0,
        radiusMeters = 250.0
    )

    private val home = DriveDetectionEngine.GeoCircle(
        id = "home-1",
        name = "Zuhause",
        latitude = 50.05, // ~5,5 km vom Gym entfernt
        longitude = 8.0,
        radiusMeters = 100.0
    )

    // ── 1) GEOFENCE-VETO: Gym-Phantom ──────────────────────────────

    @Test
    fun `Gym-Phantom — alle Probes im Gym-Kreis, Speed-Gates erfüllt, kein Driving`() {
        // Nachbau des echten Falls: Der User sitzt 5h im Gym. Indoor-GPS
        // liefert Multipath-Speed-Spikes (8-15 m/s) und die Position
        // driftet langsam (Netto ~166 m über 6 Min — über dem 150-m-
        // Displacement-Gate, aber INNERHALB des 250-m-Gym-Kreises).
        // OHNE Veto wäre das Driving (alle klassischen Gates erfüllt)
        // — genau der Bug vom 30.08.
        val probes = listOf(
            probe(0, 9.0f, latitude = 50.0000),   // ~0 m vom Zentrum
            probe(1, 12.0f, latitude = 50.0005),  // ~55 m
            probe(2, 8.5f, latitude = 50.0010),   // ~111 m
            probe(3, 14.0f, latitude = 50.0015)   // ~166 m — Netto ≥ 150 m Gate,
                                             // aber < 250 m Kreis-Radius
        )
        // Sanity: Ohne Geofence-Kontext wäre das (fälschlich) Driving —
        // genau der Bug vom 30.08.
        val withoutVeto = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(withoutVeto).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)

        // MIT Veto: alle Probes innerhalb Gym (250 m) → keine Fahrt.
        val withVeto = DriveDetectionEngine.classify(
            probes, t0 + 4 * 120_000L, listOf(gym)
        )
        assertThat(withVeto).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
    }

    @Test
    fun `Veto hebt sich auf, wenn EIN Probe den Orts-Kreis verlässt (echte Fahrt)`() {
        // Echte Heimfahrt: Die ersten Probes liegen noch im Gym-Kreis
        // (losgefahren), dann verlässt die Fahrt den Kreis ZWANGSLÄUFIG.
        // Ein einzelner Probe außerhalb hebt das Veto auf → Driving.
        val probes = listOf(
            probe(0, 9.0f, latitude = 50.0000),   // noch im Gym
            probe(1, 12.0f, latitude = 50.0010),  // noch im Gym
            probe(2, 8.5f, latitude = 50.0100),   // ~1,1 km entfernt — echte Fahrt
            probe(3, 14.0f, latitude = 50.0200)   // ~2,2 km entfernt
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 4 * 120_000L, listOf(gym)
        )
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Veto über mehrere Orte — Probes in Gym UND Zuhause sind trotzdem keine Fahrt`() {
        // GPS-Echos zwischen zwei nahen Orten: Der User ist drinnen
        // (Ein-Ort-Axiom), die Probes springen zwischen den Kreisen.
        // "In irgendeinem Kreis" reicht fürs Veto — kein Phantom-Auto
        // für einen Ortwechsel zwischen zwei benannten Orten.
        val probes = listOf(
            probe(0, 9.0f, latitude = 50.0000),   // Gym
            probe(1, 12.0f, latitude = 50.0008),  // Gym-Rand
            probe(2, 8.5f, latitude = 50.0495),   // Zuhause-Rand
            probe(3, 14.0f, latitude = 50.0500)   // Zuhause
        )
        val result = DriveDetectionEngine.classify(
            probes, t0 + 4 * 120_000L, listOf(gym, home)
        )
        // Beide Orte sind benannt → Veto greift (keine Fahrt zwischen
        // zwei Orten, die der User beide besucht hat).
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
    }

    @Test
    fun `Veto blockiert nur positives Driving — NotDriving bleibt NotDriving`() {
        // Stillstand (keine Drift, Speed-Spikes): Ohne Veto NotDriving
        // (Netto-Displacement-Gate). Mit Veto ebenfalls NotDriving —
        // das Veto darf ein NotDriving nie in Driving verwandeln.
        val probes = listOf(
            probe(0, 0f, latitude = 50.0000),
            probe(1, 0.3f, latitude = 50.0001),
            probe(2, 0f, latitude = 50.0000)
        )
        val withoutVeto = DriveDetectionEngine.classify(probes, t0 + 3 * 120_000L)
        assertThat(withoutVeto).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
        val withVeto = DriveDetectionEngine.classify(probes, t0 + 3 * 120_000L, listOf(gym))
        assertThat(withVeto).isInstanceOf(DriveDetectionEngine.Classification.NotDriving::class.java)
    }

    // ── 2) RESTART-COOLDOWN: Post-Stop-Re-Trigger ──────────────────

    @Test
    fun `Cooldown blockiert Neustart innerhalb von 3 Minuten nach Stop`() {
        // Fahrt endete um 19:00 (watchdog_5min_or_exit). Um 19:02 feuert
        // der weiterlaufende Probe-Puffer erneut "Driving" (Park-Drift).
        // Der Cooldown muss den Start blocken.
        val stopAt = t0
        val retryAt = t0 + 2 * 60_000L // 2 Min später
        assertThat(DriveDetectionEngine.isWithinCooldown(retryAt, stopAt)).isTrue()
    }

    @Test
    fun `Cooldown läuft nach 3 Minuten ab — echte Folgefahrt startet frei`() {
        val stopAt = t0
        val retryAt = t0 + 3 * 60_000L // exakt 3 Min
        assertThat(DriveDetectionEngine.isWithinCooldown(retryAt, stopAt)).isFalse()
        val laterRetry = t0 + 10 * 60_000L // 10 Min später (Rückweg)
        assertThat(DriveDetectionEngine.isWithinCooldown(laterRetry, stopAt)).isFalse()
    }

    @Test
    fun `kein Cooldown ohne vorherige Fahrt (first run nach Prozessstart)`() {
        assertThat(DriveDetectionEngine.isWithinCooldown(t0, null)).isFalse()
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun probe(
        index: Int,
        speedMps: Float?,
        accuracy: Float = 20f,
        latitude: Double
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = t0 + index * 120_000L, // 2-Min-Takt (Hintergrund-Fixrate)
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = null,
        latitude = latitude,
        longitude = 8.0
    )
}