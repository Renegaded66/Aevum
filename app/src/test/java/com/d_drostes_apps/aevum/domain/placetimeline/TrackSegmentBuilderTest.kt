package com.d_drostes_apps.aevum.domain.placetimeline

import com.d_drostes_apps.aevum.data.model.LocationTrackPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.86: Regressionstests für den Track-Segment-Builder (ADR-0030) —
 * die Regeln, die die Karte ehrlich halten:
 *  1. Nur Punkte INNERHALB der Lücke zählen
 *  2. Schlechte Genauigkeit (> 200 m) fliegt raus
 *  3. Zeitlücken > 5 Min zerschneiden das Segment (keine erfundenen
 *     Geraden über GPS-Ausfall)
 *  4. Kein Track → leere Liste (Karte zeigt Fallback-Luftlinie)
 */
class TrackSegmentBuilderTest {

    private val t0 = 1_000_000L

    private fun point(
        index: Int,
        atMs: Long,
        lat: Double,
        accuracy: Float? = 15f,
        speed: Float? = 12f
    ) = LocationTrackPoint(
        id = "p$index",
        sessionId = "session-1",
        recordedAt = atMs,
        latitude = lat,
        longitude = 8.0,
        accuracyMeters = accuracy,
        speedMps = speed
    )

    @Test
    fun `dichte Strecke wird zu einem Segment`() {
        // 10 Punkte alle 30 s, 100 m auseinander = eine Fahrtstrecke.
        val points = (0 until 10).map { i ->
            point(i, t0 + i * 30_000L, 50.0 + i * 0.0009) // ~100 m/Schritt
        }
        val segments = TrackSegmentBuilder.buildSegments(points, t0, t0 + 10 * 30_000L)
        assertThat(segments).hasSize(1)
        assertThat(segments.first().points).hasSize(10)
        assertThat(segments.first().startsAfterGap).isFalse()
    }

    @Test
    fun `Punkte vor und nach der Lücke zaehlen nicht`() {
        // 3 Punkte VOR der Lücke (gehören zur vorherigen Strecke), 5 in
        // der Lücke, 2 NACH der Lücke (nächste Strecke) → nur 5 zählen.
        val inGap = (0 until 5).map { i -> point(i, t0 + i * 30_000L, 50.0 + i * 0.0009) }
        val before = listOf(point(90, t0 - 60_000L, 49.99), point(91, t0 - 30_000L, 49.995))
        val after = listOf(point(92, t0 + 5 * 30_000L + 1, 50.01))
        val segments = TrackSegmentBuilder.buildSegments(
            before + inGap + after,
            fromMs = t0,
            toMs = t0 + 5 * 30_000L
        )
        assertThat(segments).hasSize(1)
        assertThat(segments.first().points).hasSize(5)
    }

    @Test
    fun `Zeitluecke zerschneidet das Segment`() {
        // 4 Punkte, dann 8 Min GPS-Ausfall (Tunnel/Doze), dann 4 Punkte →
        // 2 Segmente; das zweite startet nach der Lücke.
        val first = (0 until 4).map { i -> point(i, t0 + i * 30_000L, 50.0 + i * 0.0009) }
        val second = (4 until 8).map { i ->
            point(i, t0 + i * 30_000L + 8 * 60_000L, 50.005 + (i - 4) * 0.0009)
        }
        val segments = TrackSegmentBuilder.buildSegments(
            first + second,
            fromMs = t0,
            toMs = t0 + 20 * 60_000L
        )
        assertThat(segments).hasSize(2)
        assertThat(segments[0].startsAfterGap).isFalse()
        assertThat(segments[1].startsAfterGap).isTrue()
        assertThat(segments[0].points).hasSize(4)
        assertThat(segments[1].points).hasSize(4)
    }

    @Test
    fun `unkenna Punkte werden verworfen`() {
        // 5 brauchbare Punkte + 2 mit 400 m Genauigkeit (Multipath-Auspäser
        // mitten in der Fahrt) — die Auspäser fliegen raus, Segment bleibt.
        val good = (0 until 5).map { i -> point(i, t0 + i * 30_000L, 50.0 + i * 0.0009) }
        val bad = listOf(
            point(80, t0 + 35_000L, 50.9, accuracy = 400f), // 90 km Sprung
            point(81, t0 + 65_000L, 49.1, accuracy = 400f)
        )
        val segments = TrackSegmentBuilder.buildSegments(good + bad, t0, t0 + 5 * 30_000L)
        assertThat(segments).hasSize(1)
        assertThat(segments.first().points).hasSize(5)
    }

    @Test
    fun `kein Track fuer die Luecke liefert leere Liste`() {
        // Alte Tage vor M18.86: Punkte existieren, aber nicht in der Lücke.
        val elsewhere = listOf(point(0, t0 - 3_600_000L, 49.0))
        val segments = TrackSegmentBuilder.buildSegments(elsewhere, t0, t0 + 60_000L)
        assertThat(segments).isEmpty()
    }

    @Test
    fun `weniger als 2 Punkte sind kein Segment`() {
        // Ein einzelner Punkt (Herzbeat bei kurzer Lücke) — nichts zeichenbares.
        val single = listOf(point(0, t0, 50.0))
        val segments = TrackSegmentBuilder.buildSegments(single, t0, t0 + 60_000L)
        assertThat(segments).isEmpty()
    }

    @Test
    fun `Stillstand-Punkte erzeugen ein kurzes Segment ohne Spruenge`() {
        // Ampel: 4 Punkte am selben Ort (Heartbeat-Regel beim Recording
        // hat sie gespeichert) → 1 Segment, 4 Punkte, keine Bewegung —
        // die Karte zeichnet einen Punkt-förmigen Verbleib, kein Springen.
        val standing = (0 until 4).map { i -> point(i, t0 + i * 60_000L, 50.0, speed = 0f) }
        val segments = TrackSegmentBuilder.buildSegments(standing, t0, t0 + 4 * 60_000L)
        assertThat(segments).hasSize(1)
        assertThat(segments.first().points).hasSize(4)
    }
}