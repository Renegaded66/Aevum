package com.d_drostes_apps.aevum.domain.placetimeline

import com.d_drostes_apps.aevum.data.model.LocationTrackPoint

/*
 * ════════════════════════════════════════════════════════════════════════
 * M18.86 — TRACK-SEGMENTE für die Orts-Timeline-Karte (ADR-0030)
 * ════════════════════════════════════════════════════════════════════════
 *
 * WAS: Aus den verdichteten GPS-Track-Punkten (location_track_point,
 * aufgezeichnet während Auto-Fahrten/Wanderungen) werden pro
 * "Unterwegs"-Lücke zwischen zwei Visits die Strecken-Segmente abgeleitet,
 * die die Karte zeichnet — echte Fahrtstrecke statt Luftlinie.
 *
 * REGELN (ehrlich wie M18.83):
 *  1. NUR Punkte in der LÜCKE (zwischen visitEndAt und nextVisitStartAt)
 *     zählen — ein Punkt VOR der Ankunft gehört zur vorherigen Strecke.
 *  2. Punkte mit accuracy > 200 m werden verworfen (Multipath-Auspäser
 *     zeichnen sonst Fantasie-Sprünge über die Karte).
 *  3. Längere Zeitlücken (> 5 Min zwischen zwei Punkten) ZERSCHNEIDEN das
 *     Segment (GPS-Ausfall während Tunnel/Doze) — die Karte springt dann
 *     bewusst nicht, sondern zeigt zwei Teilstrecken. Ein Segment über
 *     eine 10-Min-Lücke wäre eine erfundene Gerade.
 *  4. Fehlt der Track komplett (alte Tage vor M18.86, Session nie
 *     trackbar), bleibt die Lücke OHNE Segment — die Karte zeigt dann
 *     die bewusste Luftlinien-Verbindung als dezente Fallback-Linie
 *     (Screen-Entscheidung, nicht Engine: Engine liefert nur Evidenz).
 */

/** Ein gezeichnetes Strecken-Segment zwischen zwei Punkten (bereits
 *  gefiltert und zerschnitten — direkt zeichenbar). */
data class TrackSegment(
    val points: List<TrackPoint>,
    /** true, wenn das Segment durch eine Zeitlücke zerschnitten wurde
     *  (Anfang eines neuen Teilstücks nach GPS-Ausfall). */
    val startsAfterGap: Boolean
) {
    val size: Int get() = points.size
}

/** Ein einzelner Strecken-Punkt für die Karte. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long,
    val speedMps: Float?
)

object TrackSegmentBuilder {

    /** Punkte mit schlechterer Genauigkeit verwerfen (200 m — großzügiger
     *  als das 50-m-Recording-Gate, weil ältere Aufzeichnungen und Doze-
     *  Phasen schlechtere Fixes haben können; 200 m liegt aber unter
     *  jeder echten Strecken-Abweichung, die die Karte verzerren würde). */
    const val MAX_DRAW_ACCURACY_M = 200f

    /** Zeitlücke, die ein Segment zerschneidet (GPS-Ausfall/Tunnel/Doze). */
    const val SEGMENT_BREAK_MS = 5L * 60 * 1000

    /**
     * Baut die zeichenbaren Segmente für EINE Unterwegs-Lücke.
     *
     * @param points alle Track-Punkte (ungefiltert, chronologisch)
     * @param fromMs Ende des vorherigen Visits (Ankunft)
     * @param toMs Beginn des nächsten Visits (Abfahrt)
     * @return leere Liste = kein Track für diese Lücke (Fallback-Luftlinie)
     */
    fun buildSegments(
        points: List<LocationTrackPoint>,
        fromMs: Long,
        toMs: Long
    ): List<TrackSegment> {
        if (points.isEmpty() || toMs <= fromMs) return emptyList()

        // Regel 1+2: Nur Punkte in der Lücke mit brauchbarer Genauigkeit.
        val inWindow = points
            .filter { it.recordedAt in fromMs..toMs }
            .filter { it.accuracyMeters == null || it.accuracyMeters <= MAX_DRAW_ACCURACY_M }
            .sortedBy { it.recordedAt }
        if (inWindow.size < 2) return emptyList()

        // Regel 3: An Zeitlücken zerschneiden.
        val segments = mutableListOf<TrackSegment>()
        var current = mutableListOf<TrackPoint>()
        var currentStartedAfterGap = false
        var previous: LocationTrackPoint? = null
        for (p in inWindow) {
            val gapBreak = previous != null &&
                p.recordedAt - previous!!.recordedAt > SEGMENT_BREAK_MS
            if (gapBreak && current.isNotEmpty()) {
                segments += TrackSegment(points = current, startsAfterGap = currentStartedAfterGap)
                current = mutableListOf()
                currentStartedAfterGap = true
            } else if (previous == null) {
                currentStartedAfterGap = false
            }
            current += TrackPoint(
                latitude = p.latitude,
                longitude = p.longitude,
                recordedAt = p.recordedAt,
                speedMps = p.speedMps
            )
            previous = p
        }
        if (current.size >= 2) {
            segments += TrackSegment(points = current, startsAfterGap = currentStartedAfterGap)
        }
        return segments.filter { it.size >= 2 }
    }
}