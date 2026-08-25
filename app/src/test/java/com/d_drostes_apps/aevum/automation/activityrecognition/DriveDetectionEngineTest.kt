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

    // ── M18.71: Sensiblere Schwellen ──────────────────────────────

    @Test
    fun `30er-Zone Stadtfahrt wird erkannt — 8 ms Schwelle`() {
        // 30 km/h = 8,3 m/s: Die häufigste Stadt-Geschwindigkeit. Mit der
        // alten 9-m/s-Schwelle wurde diese Fahrt NIE erkannt (User-Bug).
        val probes = listOf(
            probe(0, 8.3f), probe(1, 8.5f), probe(2, 8.2f), probe(3, 8.4f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `vier konsekutive schnelle Probes reichen — 4er-Kette`() {
        // M18.71: MIN_CONSECUTIVE_FAST 5 -> 4. Nach einer Ampel-Phase
        // (30-60s Stillstand) muss die Kette neu aufgebaut werden.
        val probes = listOf(
            probe(0, 9.0f), probe(1, 9.5f), probe(2, 9.2f), probe(3, 9.4f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Genauigkeit bis 50m wird akzeptiert — Stadt-Canyon`() {
        // M18.71: MAX_ACCURACY_M 30 -> 50m. In Häuserschluchten liefert
        // GPS oft 30-50m Genauigkeit — vorher wurden alle Probes verworfen.
        val probes = listOf(
            probe(0, 12.0f, accuracy = 45f),
            probe(1, 13.0f, accuracy = 48f),
            probe(2, 12.5f, accuracy = 42f),
            probe(3, 13.5f, accuracy = 47f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 4 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Radfahrer mit kurzen Spikes wird NICHT als Auto erkannt`() {
        // 8,5 m/s-Spikes, aber nie konsekutiv (dazwischen 5 m/s) —
        // die 4er-Kette bricht. Netto-Displacement wäre erfüllt, aber
        // die Kette ist das harte Gate.
        val probes = listOf(
            probe(0, 8.5f), probe(1, 5.0f), probe(2, 8.5f),
            probe(3, 5.0f), probe(4, 8.5f), probe(5, 5.0f)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 6 * 120_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    // ── M18.75: Robustheit gegen spärliche/kaputte Fixes ───────────

    @Test
    fun `Fahrt mit spärlichen Fixes (60s Abstand) und einem ungenauen Fix dazwischen wird erkannt`() {
        // Hintergrund-Fix-Rate 60s (Doze/Battery-Saver) + ein Fix mit
        // accuracy 80m (Tunnel/Stadt-Canyon), der gefiltert wird. Die
        // 4er-Kette wäre hier an dem ungenauen Fix gebrochen — mit
        // MIN_FAST_PROBES=3 + MIN_CONSECUTIVE_FAST=2 wird die Fahrt
        // trotzdem erkannt.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 22.0f, 20f, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 60_000L, 22.0f, 20f, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, 22.0f, 80f, latitude = 50.009, longitude = 8.000), // gefiltert
            DriveDetectionEngine.DriveProbe(t0 + 180_000L, 22.0f, 20f, latitude = 50.0135, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 240_000L, 22.0f, 20f, latitude = 50.018, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 240_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Ampel-Stopp bricht die Erkennung nicht`() {
        // 22, 22, 0 (Ampel), 22, 22 m/s — die 0 setzt die Kette zurück,
        // aber fastCount=4 und maxConsecutive=2 reichen für Driving.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 22.0f, 20f, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 30_000L, 22.0f, 20f, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 60_000L, 0.0f, 20f, latitude = 50.009, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 90_000L, 22.0f, 20f, latitude = 50.0135, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, 22.0f, 20f, latitude = 50.018, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `5-Minuten-Fahrt bei max 40 kmh mit 60s-Fixes wird erkannt — Stadtfahrt mit Anfahren, Ampel, Einparken`() {
        // User-Bug „5-Minuten-Fahrt (max 40 km/h) wird gar nicht aufgezeichnet".
        // Reale Hintergrund-Fix-Rate (Doze/OEM): 60s statt 5s → eine 5-Minuten-
        // Fahrt ergibt nur ~5 Fixes. Stadt-Typisch: Anfahren (3 m/s), 30er-Zone
        // (8,3 m/s), 40er (11,1 m/s), Ampel (0 m/s), Einparken (1,5 m/s).
        // → fastCount = 2, maxConsecutive = 2 (8,3 + 11,1 direkt aufeinander),
        // avgSpeed = 4,78 m/s.
        // Mit MIN_FAST_PROBES = 3 (bisher) und avgSpeed >= 5 wurde diese
        // Fahrt NIE erkannt — die Schwelle muss auf 2 schnelle Probes und
        // 4,5 m/s Durchschnitt (M18.78).
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 3.0f, 20f, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 60_000L, 8.3f, 20f, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, 11.1f, 20f, latitude = 50.009, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 180_000L, 0.0f, 20f, latitude = 50.0135, longitude = 8.000), // Ampel
            DriveDetectionEngine.DriveProbe(t0 + 240_000L, 1.5f, 20f, latitude = 50.018, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 240_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `5-Minuten-Fahrt mit spärlichen 90s-Fixes wird erkannt`() {
        // Extremere Hintergrund-Drosselung: nur 4 Fixes in 5 Minuten.
        // 3,0 (Anfahren) / 11,1 (40 km/h) / 8,3 (30er) / 2,0 (Einparken).
        // → fastCount = 2, maxConsecutive = 2, avg = 6,35, Spread = 270s,
        // Netto-Displacement ~2000 m. Muss mit MIN_FAST_PROBES = 2 Driving sein.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 3.0f, 20f, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 90_000L, 11.1f, 20f, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 180_000L, 8.3f, 20f, latitude = 50.009, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 270_000L, 2.0f, 20f, latitude = 50.0135, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 270_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `10-Minuten-Fahrt bei 80 kmh mit 30s-Fixes wird erkannt`() {
        // 20 Probes à 22 m/s (~80 km/h), 30s Abstand = 10 Minuten Fahrt.
        // Mit der alten 4er-Kette wäre die Erkennung erst nach ~2 Min
        // angesprungen; der Test sichert ab, dass die komplette Fahrt
        // (inkl. aller Probes im 15-Min-Fenster) als Driving klassifiziert.
        val probes = (0 until 20).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + i * 30_000L,
                speedMps = 22.0f,
                accuracyMeters = 20f,
                latitude = 50.0 + i * 0.0045, // ~500m pro Schritt
                longitude = 8.0
            )
        }
        val result = DriveDetectionEngine.classify(probes, t0 + 20 * 30_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    // ── M18.77: Speed-Fallback (Geschwindigkeit aus Distanz) ────────

    @Test
    fun `10-Minuten-Fahrt ohne GPS-Speed-Feld — Geschwindigkeit aus Distanz abgeleitet`() {
        // User-Bug „10-Minuten-Fahrten werden nicht erkannt": Hintergrund-
        // Fixes (Doze/OEM, 30-120s Lücken) liefern KEIN hasSpeed() →
        // speedMps = null. distanceFromLastM = 1000m bei 120s Abstand
        // ergibt 8,33 m/s (30 km/h) — über der Auto-Schwelle von 8 m/s.
        // Der Fallback muss MIN_FAST_PROBES = 3 + MIN_CONSECUTIVE_FAST = 2
        // aus den abgeleiteten Werten erreichen.
        val probes = (0 until 5).map { i ->
            DriveDetectionEngine.DriveProbe(
                timestampMs = t0 + i * 120_000L,
                speedMps = null,
                accuracyMeters = 20f,
                distanceFromLastM = 1000.0, // 1000 m / 120 s = 8,33 m/s
                latitude = 50.0 + i * 0.0045, // ~500m pro Schritt
                longitude = 8.0
            )
        }
        val result = DriveDetectionEngine.classify(probes, t0 + 5 * 120_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Kurze Fahrt mit gemischten Fixes — teils Speed, teils abgeleitet`() {
        // 60s-Fix-Rate (Doze): zwei Fixes mit Speed (22 m/s), zwei ohne
        // Speed aber mit Distanz 600 m → 600 m / 60 s = 10 m/s abgeleitet.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 22.0f, 20f, distanceFromLastM = null, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 60_000L, null, 20f, distanceFromLastM = 600.0, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, null, 20f, distanceFromLastM = 600.0, latitude = 50.009, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 180_000L, 22.0f, 20f, distanceFromLastM = null, latitude = 50.0135, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 180_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Stillstand ohne Speed — Null-Distanz fühlt nicht als Fahrt`() {
        // 4 Probes, speedMps = null, distanceFromLastM = 5 m (GPS-Drift),
        // Koordinaten konstant → Netto-Displacement = 0 < 150 m → das
        // Netto-Displacement-Gate muss NotDriving liefern, auch wenn die
        // abgeleitete Geschwindigkeit (5 m / 60 s = 0,08 m/s) völlig
        // unbedeutend ist.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, null, 20f, distanceFromLastM = 5.0, latitude = 50.0, longitude = 8.0),
            DriveDetectionEngine.DriveProbe(t0 + 60_000L, null, 20f, distanceFromLastM = 5.0, latitude = 50.0, longitude = 8.0),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, null, 20f, distanceFromLastM = 5.0, latitude = 50.0, longitude = 8.0),
            DriveDetectionEngine.DriveProbe(t0 + 180_000L, null, 20f, distanceFromLastM = 5.0, latitude = 50.0, longitude = 8.0)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 240_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Inferierte Speed mit zu kleinem dt wird ignoriert — Fallback greift nicht unter 2s`() {
        // Die ersten beiden Probes liegen 1s auseinander mit 12 m Distanz —
        // OHNE die dt-Untergrenze (2s) ergäben sie 12 m/s und zählten als
        // „schnell". Zusammen mit dem dritten Probe (780 m / 93 s = 8,4 m/s,
        // legitim abgeleitet) käme der Bug auf fastCount = 3 + maxConsecutive
        // = 3 → fälschlich Driving. Mit MIN_INFERRED_DT_MS = 2000 zählen nur
        // der letzte Probe → fastCount = 1 → NotDriving.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, null, 20f, distanceFromLastM = null, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 1_000L, null, 20f, distanceFromLastM = 12.0, latitude = 50.0001, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 2_000L, null, 20f, distanceFromLastM = 12.0, latitude = 50.0002, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 95_000L, null, 20f, distanceFromLastM = 780.0, latitude = 50.0045, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 95_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }

    @Test
    fun `Fahrt mit Jitter-Fixes ohne Speed bleibt erkannt — Jitter bricht die Kette nicht`() {
        // Das reale 10-Minuten-Fahrt-Muster: 3 Fixes mit Speed (8,3 m/s =
        // 30er-Zone) und 2 Fixes OHNE Speed, deren Distanz nur Positions-
        // Jitter ist (30 m / 120 s = 0,25 m/s). OHNE den M18.77-Jitter-
        // Filter würden die 0,25-m/s-Ableitungen als „langsam" zählen und
        // die Kette jedes Mal auf 0 setzen → maxConsecutive = 1 → die
        // Fahrt würde NIE erkannt (exakt der gemeldete User-Bug). Mit
        // MIN_INFERRED_SPEED_MPS werden die Jitter-Ableitungen verworfen:
        // Kette = 8,3 / (verworfen) / 8,3 / (verworfen) / 8,3 →
        // fastCount = 3, maxConsecutive = 2, avgSpeed = 8,3 → Driving.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 8.3f, 20f, distanceFromLastM = null, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 120_000L, null, 20f, distanceFromLastM = 30.0, latitude = 50.0022, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 240_000L, 8.3f, 20f, distanceFromLastM = 900.0, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 360_000L, null, 20f, distanceFromLastM = 30.0, latitude = 50.0067, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 480_000L, 8.3f, 20f, distanceFromLastM = 900.0, latitude = 50.009, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 480_000L)
        assertThat(result).isInstanceOf(DriveDetectionEngine.Classification.Driving::class.java)
    }

    @Test
    fun `Inferierte Speed mit zu grossem dt wird ignoriert — Park-Luecke zaehlt nicht`() {
        // M18.77: Probe 1 liegt 8 Minuten nach Probe 0 (Park-Phase / Lücke):
        // 4000 m / 480 s = 8,3 m/s — OHNE die dt-Obergrenze (5 Min) wäre
        // das ein fiktiver „schneller" Fix.
        // M18.78: Mit MIN_FAST_PROBES = 2 müssen die zwei echten schnellen
        // Fixes (p0, p2) allein NICHT reichen — die drei langsamen Fixes
        // (p3-p5, Einparken/Feierabend) drücken den Schnitt auf 4,04 m/s
        // (< 4,5). Nur wenn die Park-Ableitung MITZÄHLTE (fastCount = 3,
        // avg 4,73 >= 4,5) entstünde ein False-Positive → der Test beweist,
        // dass die dt-Obergrenze weiterhin greift.
        val probes = listOf(
            DriveDetectionEngine.DriveProbe(t0, 8.3f, 20f, distanceFromLastM = null, latitude = 50.000, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 480_000L, null, 20f, distanceFromLastM = 4000.0, latitude = 50.0045, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 600_000L, 8.3f, 20f, distanceFromLastM = null, latitude = 50.009, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 720_000L, 1.2f, 20f, distanceFromLastM = null, latitude = 50.0135, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 840_000L, 1.2f, 20f, distanceFromLastM = null, latitude = 50.018, longitude = 8.000),
            DriveDetectionEngine.DriveProbe(t0 + 960_000L, 1.2f, 20f, distanceFromLastM = null, latitude = 50.0225, longitude = 8.000)
        )
        val result = DriveDetectionEngine.classify(probes, t0 + 960_000L)
        assertThat(result).isEqualTo(DriveDetectionEngine.Classification.NotDriving)
    }
}
