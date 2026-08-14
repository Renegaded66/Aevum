package com.d_drostes_apps.aevum.automation.activityrecognition

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.64: Regression-Tests für die GPS-Geschwindigkeits-Fahrterkennung.
 *
 * Deckt die geforderten Szenarien ab:
 *  - Walking → Auto (Geschwindigkeit steigt über die Auto-Schwelle)
 *  - Stillstand → Auto
 *  - Andere Aktivität → Auto
 *  - Auto ohne vorher erkanntes Walking (kein AR-Event nötig)
 *  - Bereits laufende Fahrt beim Start der Erkennung (Probes ab Fahrtbeginn)
 *  - Kurzer GPS-Ausreißer (ein einzelner schneller Fix)
 *  - Schnelles Gehen/Laufen (darf NICHT als Auto gelten)
 *  - Fahrradfahrt (darf NICHT als Auto gelten)
 *  - Längere Fahrt mit gelegentlichen GPS-Aussetzern
 */
class DriveDetectionEngineTest {

    private val t0 = 1_000_000L

    // M18.66-FIX13: probe() jetzt mit lat/lon für das Netto-Displacement-Gate.
    // Fahrt-Szenarien: Probes bewegen sich 500m pro Schritt (120s * 4.17 m/s
    // ≈ 500m — korrespondiert zu 15 m/s ≈ 54 km/h Durchschnitt). Die
    // Netto-Distanz vom ersten zum letzten Probe muss >= 200m sein.
    // Kein-Fahrt-Szenarien: lat/lon bleiben konstant (Stillstand) →
    // Netto-Displacement = 0 → MIN_NET_DISPLACEMENT_M gate greift.
    private fun probe(
        index: Int,
        speedMps: Float?,
        accuracy: Float = 20f,
        distanceFromLastM: Double? = null,
        latitude: Double? = 50.0 + index * 0.0045, // ~500m pro Schritt
        longitude: Double? = 8.0
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = t0 + index * 120_000L, // alle 2 Minuten
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM,
        latitude = latitude,
        longitude = longitude
    )

    // ── Fahrt-Szenarien ───────────────────────────────────────────

    @Test
    fun `Walking dann Auto — Geschwindigkeit steigt über die Schwelle`() {
        // 3 Probes Gehen (~1,5 m/s), dann 5 Probes Auto (>= 12 m/s = 43 km/h).
        // M18.66-FIX12: 5 konsekutive schnelle Probes >= 10 m/s nötig.
        val probes = listOf(
            probe(0, 1.5f), probe(1, 1.4f), probe(2, 1.6f),
            probe(3, 12.0f), probe(4, 24.0f), probe(5, 25.0f), probe(6, 23.0f), probe(7, 22.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 8 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Stillstand dann Auto — Fahrt wird erkannt`() {
        // M18.66-FIX12: 5 konsekutive schnelle Probes >= 10 m/s nötig.
        val probes = listOf(
            probe(0, 0f), probe(1, 0f), probe(2, 0.2f),
            probe(3, 12.0f), probe(4, 14.0f), probe(5, 15.0f), probe(6, 14.0f), probe(7, 13.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 8 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `andere Aktivität dann Auto — Fahrt wird erkannt`() {
        // Unbekannte/fehlende Geschwindigkeit (null), dann Auto.
        // M18.66-FIX12: 5 konsekutive schnelle Probes >= 10 m/s nötig.
        val probes = listOf(
            probe(0, null), probe(1, null), probe(2, 1.0f),
            probe(3, 12.0f), probe(4, 18.0f), probe(5, 22.0f), probe(6, 20.0f), probe(7, 21.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 8 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Auto ohne vorher erkanntes Walking — Fahrt wird erkannt`() {
        // Die Erkennung startet mitten in der Fahrt: alle Probes schnell.
        // M18.66-FIX12: 5 konsekutive schnelle Probes >= 10 m/s nötig.
        val probes = listOf(
            probe(0, 20.0f), probe(1, 22.0f), probe(2, 19.0f),
            probe(3, 24.0f), probe(4, 25.0f), probe(5, 21.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `bereits laufende Fahrt beim Start der Erkennung — Cluster-Start liegt am Anfang`() {
        // Fahrt begann VOR der Erkennung: Die Probes decken nur den
        // laufenden Teil ab. Der Cluster-Start muss beim ältesten Probe
        // liegen (nicht bei now).
        val probes = listOf(
            probe(0, 21.0f), probe(1, 23.0f), probe(2, 20.0f), probe(3, 24.0f)
        )
        val now = t0 + 4 * 120_000L
        val cluster = DriveDetectionEngine.toVehicleCluster(probes, now)
        assertThat(cluster).isNotNull()
        assertThat(cluster!!.startMs).isEqualTo(t0)
        assertThat(cluster.endMs).isEqualTo(t0 + 3 * 120_000L)
    }

    @Test
    fun `längere Fahrt mit gelegentlichen GPS-Aussetzern — Fahrt bleibt erkannt`() {
        // 10 Probes: 8 schnell, 2 mit fehlender Geschwindigkeit (Aussetzer).
        val probes = (0 until 10).map { i ->
            probe(i, if (i % 5 == 3) null else 20.0f + i)
        }
        val result = DriveDetectionEngine.classify(probes, t0 + 10 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    // ── Kein-Fahrt-Szenarien ──────────────────────────────────────

    @Test
    fun `schnelles Gehen und Laufen wird NICHT als Auto erkannt`() {
        // Laufen: ~4,5 m/s (16 km/h) — deutlich unter der Auto-Schwelle.
        // M18.66-FIX13: Koordinaten konstant (Stillstand-Drift simuliert).
        val probes = listOf(
            probe(0, 2.0f, latitude = 50.0, longitude = 8.0),
            probe(1, 3.5f, latitude = 50.0, longitude = 8.0),
            probe(2, 4.2f, latitude = 50.0, longitude = 8.0),
            probe(3, 4.5f, latitude = 50.0, longitude = 8.0),
            probe(4, 4.0f, latitude = 50.0, longitude = 8.0),
            probe(5, 3.8f, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Fahrradfahrt wird NICHT als Auto erkannt`() {
        // Rennrad: ~7 m/s (25 km/h) — unter der Auto-Schwelle von 10 m/s.
        // M18.66-FIX12: Schwelle 8→10 m/s, auch schnelles Radfahren
        // (8-9 m/s = 29-32 km/h) wird jetzt korrekt als NICHT Auto erkannt.
        // M18.66-FIX13: Koordinaten konstant (Stillstand-Drift simuliert).
        val probes = listOf(
            probe(0, 6.5f, latitude = 50.0, longitude = 8.0),
            probe(1, 7.0f, latitude = 50.0, longitude = 8.0),
            probe(2, 6.8f, latitude = 50.0, longitude = 8.0),
            probe(3, 7.2f, latitude = 50.0, longitude = 8.0),
            probe(4, 6.9f, latitude = 50.0, longitude = 8.0),
            probe(5, 7.1f, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `einzelner GPS-Ausreißer startet keine Fahrt`() {
        // 5 Probes langsam, EIN Ausreißer mit 30 m/s (108 km/h) — der
        // einzelne schnelle Fix darf keine Fahrt auslösen.
        // M18.66-FIX13: Koordinaten konstant (Stillstand).
        val probes = listOf(
            probe(0, 1.0f, latitude = 50.0, longitude = 8.0),
            probe(1, 1.2f, latitude = 50.0, longitude = 8.0),
            probe(2, 30.0f, latitude = 50.0, longitude = 8.0),
            probe(3, 1.1f, latitude = 50.0, longitude = 8.0),
            probe(4, 1.3f, latitude = 50.0, longitude = 8.0),
            probe(5, 1.0f, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `ungenaue GPS-Fixes werden verworfen — keine Fahrt bei schlechter Genauigkeit`() {
        // Alle Fixes mit > 120 m Genauigkeit (Tunnel, Stadt-Canyon).
        // M18.66-FIX13: Koordinaten irrelevant — Genauigkeit filtert vorher.
        val probes = listOf(
            probe(0, 20.0f, accuracy = 200f, latitude = 50.0, longitude = 8.0),
            probe(1, 22.0f, accuracy = 180f, latitude = 50.0, longitude = 8.0),
            probe(2, 21.0f, accuracy = 250f, latitude = 50.0, longitude = 8.0),
            probe(3, 23.0f, accuracy = 300f, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `zu wenige Messungen — keine Entscheidung`() {
        // Nur 2 Probes: noch keine Fahrt-Bestätigung (Mindestdauer).
        val probes = listOf(
            probe(0, 20.0f, latitude = 50.0, longitude = 8.0),
            probe(1, 22.0f, latitude = 50.004, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 2 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `Messungen ohne zeitliche Verteilung — keine Fahrt`() {
        // 5 schnelle Probes, aber alle innerhalb von 10 Sekunden
        // (ein Mess-Burst, kein Verlauf).
        // M18.66-FIX13: Koordinaten bewegen sich (echo Fahrt) — aber
        // MIN_SPREAD_MS filtert vorher (Spread < 2 Min).
        val probes = (0 until 5).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + i * 2_000L,
                speedMps = 20.0f,
                accuracyMeters = 20f,
                latitude = 50.0 + i * 0.0045,
                longitude = 8.0
            )
        }
        val result = DriveDetectionEngine.classify(probes, t0 + 10_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `GPS-Sprung-Ausreißer wird verworfen`() {
        // Probe 2 springt 5 km in 30s (Tunnel-Sprung) — der Sprung wird
        // entfernt; die restlichen schnellen Probes bestätigen die Fahrt.
        // M18.66-FIX12: 5 konsekutive schnelle Probes nötig.
        // M18.66-FIX13: Koordinaten bewegen sich pro Schritt (~500m).
        val probes = listOf(
            probe(0, 20.0f, distanceFromLastM = null),
            probe(1, 22.0f, distanceFromLastM = 300.0),
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + 2 * 120_000L + 30_000L,
                speedMps = 25.0f,
                accuracyMeters = 20f,
                distanceFromLastM = 5_000.0,
                latitude = 50.0 + 2 * 0.0045,  // bewegt sich weiter
                longitude = 8.0
            ),
            probe(3, 24.0f, distanceFromLastM = 400.0),
            probe(4, 23.0f, distanceFromLastM = 350.0),
            probe(5, 21.0f, distanceFromLastM = 380.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `alte Probes außerhalb des Fensters zählen nicht`() {
        // 3 schnelle Probes von vor 20 Minuten (alte Fahrt) + 3 langsame
        // jetzt → keine Fahrt.
        // M18.66-FIX13: Alte Probes mit Koordinaten, neue konstant (Stillstand).
        val old = listOf(
            DriveDetectionEngine.DriveProbe(t0 - 20 * 60_000L, 20.0f, 20f, latitude = 49.0, longitude = 8.0),
            DriveDetectionEngine.DriveProbe(t0 - 18 * 60_000L, 22.0f, 20f, latitude = 49.005, longitude = 8.0),
            DriveDetectionEngine.DriveProbe(t0 - 16 * 60_000L, 21.0f, 20f, latitude = 49.010, longitude = 8.0)
        )
        val now = listOf(
            probe(0, 1.0f, latitude = 50.0, longitude = 8.0),
            probe(1, 1.2f, latitude = 50.0, longitude = 8.0),
            probe(2, 1.1f, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(old + now, t0 + 3 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    // ── M18.66-FIX13: Netto-Displacement-Gate Tests ──────────────

    @Test
    fun `Stillstand mit GPS-Drift-Speed wird NICHT als Auto erkannt — Netto-Displacement-Gate`() {
        // Kritischer Test: GPS liefert speed >= 10 m/s (Kaltstart-Müll),
        // aber die Position driftet nur 20m um den selben Punkt.
        // Ohne Netto-Displacement-Gate wäre das eine False-Positive.
        // Mit Gate: Netto-Distanz ~20m < 200m → NotDriving.
        val probes = listOf(
            probe(0, 12.0f, latitude = 50.000, longitude = 8.000),
            probe(1, 15.0f, latitude = 50.0001, longitude = 8.0001),  // ~15m
            probe(2, 14.0f, latitude = 50.0002, longitude = 8.0002),  // ~28m
            probe(3, 13.0f, latitude = 50.0001, longitude = 8.0001),  // ~15m
            probe(4, 12.0f, latitude = 50.000, longitude = 8.000),    // zurück
            probe(5, 11.0f, latitude = 50.0001, longitude = 8.0001)   // ~15m
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `echte Fahrt mit ausreichendem Displacement wird erkannt`() {
        // 5 schnelle Probes mit ~500m pro Schritt → Netto ~2500m > 200m.
        val probes = listOf(
            probe(0, 12.0f, latitude = 50.000, longitude = 8.000),
            probe(1, 14.0f, latitude = 50.0045, longitude = 8.000),
            probe(2, 15.0f, latitude = 50.009, longitude = 8.000),
            probe(3, 13.0f, latitude = 50.0135, longitude = 8.000),
            probe(4, 14.0f, latitude = 50.018, longitude = 8.000),
            probe(5, 12.0f, latitude = 50.0225, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }
}
