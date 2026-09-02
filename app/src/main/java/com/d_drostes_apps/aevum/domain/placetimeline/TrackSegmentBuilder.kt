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

    /** Zeitlücke, die ein Segment zerschneidet (GPS-Ausfall/Tunnel/Doze).
     *  M18.94: 5 Min → 30 Min. Eine bewusste Pause (M18.62-Session-Split,
     *  User steht still, z.B. am Handy) dauert real 5–30 Min und ist KEIN
     *  GPS-Ausfall — seit M18.94 schreibt der Service während PAUSED
     *  keine Punkte mehr, die Lücke ist also eine echte Bewegungspause.
     *  Erst ab 30 Min (Tunnel/Doze/Prozess-Tod) wird zerschnitten, damit
     *  die Karte keine erfundenen Geraden über echte Ausfälle zeichnet. */
    const val SEGMENT_BREAK_MS = 30L * 60 * 1000

    /**
     * Baut die zeichenbaren Segmente für EINE Unterwegs-Lücke.
     *
     * @param points alle Track-Punkte (ungefiltert, chronologisch)
     * @param fromMs Ende des vorherigen Visits (Ankunft)
     * @param toMs Beginn des nächsten Visits (Abfahrt)
     * @param anchorStartLat/Lon Koordinaten des Start-Visits (optional) —
     *        die Karte verbindet den Ort mit der Strecke per gerader Linie,
     *        damit auch der Weg VOR dem ersten Track-Punkt sichtbar ist
     *        (User: "wie ich überhaupt dahin gekommen bin — gerade
     *        Verbindungslinien sind ok").
     * @param anchorEndLat/Lon Koordinaten des Ziel-Visits (optional)
     * @return leere Liste = kein Track für diese Lücke (Fallback-Luftlinie)
     */
    fun buildSegments(
        points: List<LocationTrackPoint>,
        fromMs: Long,
        toMs: Long,
        anchorStartLat: Double? = null,
        anchorStartLon: Double? = null,
        anchorEndLat: Double? = null,
        anchorEndLon: Double? = null
    ): List<TrackSegment> {
        if (points.isEmpty() || toMs <= fromMs) return emptyList()

        // Regel 1+2: Nur Punkte in der Lücke mit brauchbarer Genauigkeit.
        val inWindow = points
            .filter { it.recordedAt in fromMs..toMs }
            .filter { it.accuracyMeters == null || it.accuracyMeters <= MAX_DRAW_ACCURACY_M }
            .sortedBy { it.recordedAt }

        // M18.93v10: Weniger als 2 echte Punkte, aber Anker bekannt?
        // Dann trotzdem die Verbindungslinie bauen (Anker + Einzelpunkte) —
        // der User will keine Lücken zwischen Orten ("man kann ja nicht
        // springen"). Nur wenn GAR KEIN Punkt existiert, bleibt es beim
        // Fallback (dezentere Luftlinie in Visit-Farbe).
        if (inWindow.size < 2) {
            val connectors = mutableListOf<TrackPoint>()
            anchorStartLat?.let { lat ->
                anchorStartLon?.let { lon ->
                    connectors += TrackPoint(lat, lon, fromMs, null)
                }
            }
            inWindow.forEach { p ->
                connectors += TrackPoint(p.latitude, p.longitude, p.recordedAt, p.speedMps)
            }
            anchorEndLat?.let { lat ->
                anchorEndLon?.let { lon ->
                    connectors += TrackPoint(lat, lon, toMs, null)
                }
            }
            if (connectors.size >= 2) {
                return listOf(TrackSegment(points = connectors, startsAfterGap = false))
            }
            return emptyList()
        }

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
        val built = segments.filter { it.size >= 2 }
        // M18.93v10 (User: "der Weg dorthin fehlt"): Auch ohne 2 Punkte
        // in der Lücke gibt es eine Verbindung, wenn Anker bekannt sind —
        // als gerade Linie vom Start-Visit zum ersten Track-Punkt bzw.
        // vom letzten Track-Punkt zum Ziel-Visit. Der User hat explizit
        // gesagt: unexakte Teilabschnitte als gerade Verbindungslinien
        // sind in Ordnung ("man kann ja nicht springen").
        if (built.isEmpty()) {
            val connectors = mutableListOf<TrackPoint>()
            anchorStartLat?.let { lat ->
                anchorStartLon?.let { lon ->
                    connectors += TrackPoint(lat, lon, fromMs, null)
                }
            }
            inWindow.forEach { p ->
                connectors += TrackPoint(p.latitude, p.longitude, p.recordedAt, p.speedMps)
            }
            anchorEndLat?.let { lat ->
                anchorEndLon?.let { lon ->
                    connectors += TrackPoint(lat, lon, toMs, null)
                }
            }
            if (connectors.size >= 2) {
                return listOf(TrackSegment(points = connectors, startsAfterGap = false))
            }
            return emptyList()
        }
        // M18.93v10: Bestehende Segmente um die Anker ergänzen — die
        // Strecke beginnt/endet dann exakt am Visit (kein sichtbarer
        // Sprung zwischen Ort und ersten Track-Punkt mehr).
        val first = built.first()
        val last = built.last()
        val startMissing = anchorStartLat != null && anchorStartLon != null &&
            (first.points.first().latitude != anchorStartLat || first.points.first().longitude != anchorStartLon)
        val endMissing = anchorEndLat != null && anchorEndLon != null &&
            (last.points.last().latitude != anchorEndLat || last.points.last().longitude != anchorEndLon)
        if (!startMissing && !endMissing) return built
        return built.mapIndexed { i, seg ->
            var pts = seg.points
            // Ein einzelnes Segment: beide Anker anhängen/voranstellen.
            if (built.size == 1) {
                if (startMissing) pts = listOf(TrackPoint(anchorStartLat!!, anchorStartLon!!, fromMs, null)) + pts
                if (endMissing) pts = pts + TrackPoint(anchorEndLat!!, anchorEndLon!!, toMs, null)
                TrackSegment(points = pts, startsAfterGap = seg.startsAfterGap)
            } else if (i == 0 && startMissing) {
                TrackSegment(
                    points = listOf(TrackPoint(anchorStartLat!!, anchorStartLon!!, fromMs, null)) + pts,
                    startsAfterGap = seg.startsAfterGap
                )
            } else if (i == built.size - 1 && endMissing) {
                TrackSegment(
                    points = pts + TrackPoint(anchorEndLat!!, anchorEndLon!!, toMs, null),
                    startsAfterGap = seg.startsAfterGap
                )
            } else seg
        }
    }
}