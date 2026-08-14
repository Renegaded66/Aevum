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

    private fun probe(
        index: Int,
        speedMps: Float?,
        accuracy: Float = 20f,
        distanceFromLastM: Double? = null
    ) = DriveDetectionEngine.DriveProbe(
        timestampMs = t0 + index * 120_000L, // alle 2 Minuten
        speedMps = speedMps,
        accuracyMeters = accuracy,
        distanceFromLastM = distanceFromLastM
    )

    // ── Fahrt-Szenarien ───────────────────────────────────────────

    @Test
    fun `Walking dann Auto — Geschwindigkeit steigt über die Schwelle`() {
        // 3 Probes Gehen (~1,5 m/s), dann 4 Probes Auto (~25 m/s = 90 km/h).
        // M18.66-FIX10: 4 konsekutive schnelle Probes nötig (war 3).
        val probes = listOf(
            probe(0, 1.5f), probe(1, 1.4f), probe(2, 1.6f),
            probe(3, 9.0f), probe(4, 24.0f), probe(5, 25.0f), probe(6, 23.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 7 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Stillstand dann Auto — Fahrt wird erkannt`() {
        // M18.66-FIX10: 4 konsekutive schnelle Probes nötig (war 3).
        val probes = listOf(
            probe(0, 0f), probe(1, 0f), probe(2, 0.2f),
            probe(3, 8.5f), probe(4, 12.0f), probe(5, 15.0f), probe(6, 14.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 7 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `andere Aktivität dann Auto — Fahrt wird erkannt`() {
        // Unbekannte/fehlende Geschwindigkeit (null), dann Auto.
        // M18.66-FIX10: 4 konsekutive schnelle Probes nötig (war 3).
        val probes = listOf(
            probe(0, null), probe(1, null), probe(2, 1.0f),
            probe(3, 10.0f), probe(4, 18.0f), probe(5, 22.0f), probe(6, 20.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 7 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Auto ohne vorher erkanntes Walking — Fahrt wird erkannt`() {
        // Die Erkennung startet mitten in der Fahrt: alle Probes schnell.
        val probes = listOf(
            probe(0, 20.0f), probe(1, 22.0f), probe(2, 19.0f),
            probe(3, 24.0f), probe(4, 25.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 5 * 120_000L)
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
        val probes = listOf(
            probe(0, 2.0f), probe(1, 3.5f), probe(2, 4.2f),
            probe(3, 4.5f), probe(4, 4.0f), probe(5, 3.8f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Fahrradfahrt wird NICHT als Auto erkannt`() {
        // Rennrad: ~7 m/s (25 km/h) — unter der Auto-Schwelle von 8 m/s.
        val probes = listOf(
            probe(0, 6.5f), probe(1, 7.0f), probe(2, 6.8f),
            probe(3, 7.2f), probe(4, 6.9f), probe(5, 7.1f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `einzelner GPS-Ausreißer startet keine Fahrt`() {
        // 5 Probes langsam, EIN Ausreißer mit 30 m/s (108 km/h) — der
        // einzelne schnelle Fix darf keine Fahrt auslösen.
        val probes = listOf(
            probe(0, 1.0f), probe(1, 1.2f), probe(2, 30.0f),
            probe(3, 1.1f), probe(4, 1.3f), probe(5, 1.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `ungenaue GPS-Fixes werden verworfen — keine Fahrt bei schlechter Genauigkeit`() {
        // Alle Fixes mit > 120 m Genauigkeit (Tunnel, Stadt-Canyon).
        val probes = listOf(
            probe(0, 20.0f, accuracy = 200f),
            probe(1, 22.0f, accuracy = 180f),
            probe(2, 21.0f, accuracy = 250f),
            probe(3, 23.0f, accuracy = 300f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `zu wenige Messungen — keine Entscheidung`() {
        // Nur 2 Probes: noch keine Fahrt-Bestätigung (Mindestdauer).
        val probes = listOf(probe(0, 20.0f), probe(1, 22.0f))
        val result = DriveDetectionEngine.classify(probes, t0 + 2 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `Messungen ohne zeitliche Verteilung — keine Fahrt`() {
        // 5 schnelle Probes, aber alle innerhalb von 10 Sekunden
        // (ein Mess-Burst, kein Verlauf).
        val probes = (0 until 5).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + i * 2_000L,
                speedMps = 20.0f,
                accuracyMeters = 20f
            )
        }
        val result = DriveDetectionEngine.classify(probes, t0 + 10_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.InsufficientData)
    }

    @Test
    fun `GPS-Sprung-Ausreißer wird verworfen`() {
        // Probe 2 springt 5 km in 30s (Tunnel-Sprung) — der Sprung wird
        // entfernt; die restlichen schnellen Probes bestätigen die Fahrt.
        val probes = listOf(
            probe(0, 20.0f, distanceFromLastM = null),
            probe(1, 22.0f, distanceFromLastM = 300.0),
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + 2 * 120_000L + 30_000L,
                speedMps = 25.0f,
                accuracyMeters = 20f,
                distanceFromLastM = 5_000.0
            ),
            probe(3, 24.0f, distanceFromLastM = 400.0),
            probe(4, 23.0f, distanceFromLastM = 350.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 5 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `alte Probes außerhalb des Fensters zählen nicht`() {
        // 3 schnelle Probes von vor 20 Minuten (alte Fahrt) + 3 langsame
        // jetzt → keine Fahrt.
        val old = listOf(
            DriveDetectionEngine.DriveProbe(t0 - 20 * 60_000L, 20.0f, 20f),
            DriveDetectionEngine.DriveProbe(t0 - 18 * 60_000L, 22.0f, 20f),
            DriveDetectionEngine.DriveProbe(t0 - 16 * 60_000L, 21.0f, 20f)
        )
        val now = listOf(probe(0, 1.0f), probe(1, 1.2f), probe(2, 1.1f))
        val result = DriveDetectionEngine.classify(old + now, t0 + 3 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }
}
