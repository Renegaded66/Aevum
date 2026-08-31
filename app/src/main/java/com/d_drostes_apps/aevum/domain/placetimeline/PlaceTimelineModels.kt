package com.d_drostes_apps.aevum.domain.placetimeline

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession

/*
 * ════════════════════════════════════════════════════════════════════════
 * M18.83 — Place Timeline (Google-Maps-Stil) — USABILITY-REFLEXION
 * M18.87 — PRESENCE-Evidenz (GPS-Zonen-Zustand als Fallback-Spur)
 * M18.88 — LÜCKENLOSE Tag-Story (Tail-Bridge, Live-Zone, Konsolidierung)
 * ════════════════════════════════════════════════════════════════════════
 *
 * M18.88 (User-Report nach M18.87): "Trotzdem sagt die Timeline dass ich
 * heute noch keine Orte besucht hätte. Ich bin ja immer an einem Standort."
 * Drei Designfehler von M18.87 wurden damit aufgedeckt und behoben:
 *
 * 1. KALTSTART-LÜCKE: Presence-Evidenz existiert erst ab dem ersten
 *    Sampler-Check nach Update. Davor blieb "heute" leer. Fix (Quelle 5):
 *    Die Live-Zone (CurrentZoneProvider.currentZone) ist die LETZTE
 *    Instanz — läuft sonst nichts, zeigt der Tag den aktuellen Aufenthalt
 *    ab Tagesbeginn. "Ich bin immer irgendwo" ist ein Axiom, keine Evidenz-
 *    Frage. JEDER Tag hat jetzt mindestens eine Karte.
 *
 * 2. TAIL-LÜCKE (der gemeldete Gestern-Fall): Die Heimfahrt endet in
 *    Zuhause, aber GMS feuert den ENTER nie (Prozess tot, Doze, Filter) →
 *    die Fahrt "hängt in der Luft": Vorletzer Ort + Strecke sichtbar,
 *    Ankunfts-STATION fehlt. Fix (Quelle 4): Liegt der letzte Track-Punkt
 *    des Tages in einem Geofence, wird daraus die Ankunfts-Station
 *    abgeleitet (offen bis Tagesende) — die Karte verbindet automatisch
 *    mit der echten Fahrstrecke, denn die Lücke wird zur normalen Gap.
 *
 * 3. FRAGMENTIERUNG: GMS (EXIT 17:58) und Presence (EXIT 18:01) desselben
 *    Ortes erzeugten zwei Visits. Fix: finale Konsolidierung (gleicher Ort
 *    + Lücke ≤ 2 min → ein Visit).
 *
 * Bestehende Entscheidungen (M18.83, unverändert): Read-only-Derivation,
 * Session-first-Dedup, Ehrlichkeit bei lückenhafter Evidenz, DWELL-Truth.
 * ════════════════════════════════════════════════════════════════════════
 */

/** Ein abgeleiteter Besuch an einem Ort — die atomare Einheit der Place Timeline. */
data class PlaceVisit(
    val id: String,
    /** Geofence-ID falls bekannt, sonst null (benannter Ort). */
    val geofenceId: String?,
    val name: String,
    /** Emoji-Icon (aus dem Geofence). */
    val icon: String,
    /** Hex-Farbe (aus dem Geofence, z.B. "#6366F1"). */
    val color: String,
    /** Geokoordinaten für die Karten-Ansicht (null = nicht kartierbar). */
    val latitude: Double?,
    val longitude: Double?,
    val startAt: Long,
    val endAt: Long,
    val evidence: VisitEvidence,
    /** true = Besuch läuft gerade (nur heute möglich). */
    val isOngoing: Boolean
) {
    val durationMs: Long get() = (endAt - startAt).coerceAtLeast(0L)
}

enum class VisitEvidence {
    /** Trigger-Evidenz, kurzer Besuch (< Long-Stay-Schwelle). */
    GEOFENCE_SHORT,
    /** Bestätigt langer Aufenthalt (≥ 30 min). */
    GEOFENCE_LONG,
    /** Aus Unknown-Place mit User-Namen abgeleitet. */
    NAMED_PLACE,
    /** M18.87: Unbenannter Unknown-Place (GPS-bestätigt, ohne Geofence). */
    UNNAMED_PLACE,
    /** M18.88: Aus der Live-Zone abgeleitet (letzter Ausweis "ich bin DA"). */
    LIVE_ZONE
}

/**
 * Reine Abgleichungs-Engine: Aus Sessions + Triggern + Geofences + benannten
 * Orten (+ M18.87 Presence, + M18.88 Track-Tail & Live-Zone) wird die
 * Visit-Liste eines Tages gebaut. Pure Funktion → JVM-testbar.
 */
object PlaceTimelineEngine {

    /** Mindestdauer für "langer Aufenthalt" (Badge "länger geblieben"). */
    const val LONG_STAY_MS: Long = 30 * 60 * 1000L

    /** Mindestdauer, damit ein Roh-Trigger-Paar als Besuch zählt. */
    const val MIN_MERGED_DURATION_MS: Long = 60 * 1000L

    /** M18.87: Mindestdauer für unbenannte Unknown-Places in der Timeline
     *  (identisch zur Persistenz-Schwelle des UnknownPlaceDetectorWorkers). */
    const val UNKNOWN_PLACE_MIN_DURATION_MS: Long = 15 * 60 * 1000L

    /** M18.88: Konsolidierungs-Lücke — Visits desselben Ortes mit weniger
     *  Abstand werden zu einem zusammengezogen (cross-source Fragmentierung). */
    const val CONSOLIDATE_GAP_MS: Long = 2 * 60 * 1000L

    /** M18.88: Toleranz der Tail-Bridge gegen Zeit-Überlappungen mit dem
     *  letzten bekannten Visit (GPS/Start-Zeitjitter). */
    const val BRIDGE_OVERLAP_TOLERANCE_MS: Long = 10 * 60 * 1000L

    fun buildVisits(
        dayStart: Long,
        dayEnd: Long,
        sessions: List<ActivitySession>,
        triggers: List<TriggerEvent>,
        geofences: List<PlaceGeofence>,
        namedPlaces: List<UnknownPlaceSession>,
        nowMs: Long,
        // M18.88: Track-Punkte für die Ankunfts-Bridge (letzte GPS-Position
        // des Tages → Ankunfts-Station, wenn GMS den ENTER nie lieferte).
        trackPoints: List<com.d_drostes_apps.aevum.data.model.LocationTrackPoint> = emptyList(),
        // M18.88: Aktuelle Live-Zone (CurrentZoneProvider) — der Tag zeigt
        // IMMER mindestens den aktuellen Aufenthalt, auch frisch nach
        // Update/Kaltstart ohne Presence-Historie.
        currentZoneGeofenceId: String? = null,
        // M18.88: Wann wurde die Zone zuletzt bestätigt (ZoneInfo.updatedAt)?
        // Startzeit der Live-Zone-Station — ohne sie würde der Startpunkt
        // bei jedem 60s-Ticker mitwandern (Antipattern aus M18.87-Review).
        currentZoneSinceMs: Long? = null
    ): List<PlaceVisit> {
        // Auch deaktivierte Geofences können historische Visits erklären.
        // (Soft-gelöschte Geofences sind bewusst ausgeschlossen — wer den
        // Ort entfernt hat, will ihn nicht mehr sehen.)
        val geofenceById = geofences.associateBy { it.id }
        val visits = mutableListOf<PlaceVisit>()
        // M18.87: Tatsächlich emittierte GMS-Trigger-Intervalle — die
        // Presence-Quelle darf Zeiträume, die GMS bereits erklärt, nicht
        // doppelt belegen (sie subtrahiert sie stattdessen).
        val emittedTriggerIntervals = mutableListOf<Pair<Long, Long>>()

        // ── Quelle 1: aufgezeichnete Sessions mit Trigger-Evidenz ──
        // session.sourceTriggerId → TriggerEvent (geofenceId). Das ist der
        // autoritative Link; wir raten NICHT anhand von ID-Formaten.
        //
        // M18.83.1 STALE-GUARD: Eine Session mit endAt=null, die VOR diesem
        // Tag gestartet ist, ist eine VERLORENE Session (verpasster EXIT)
        // und KEINE heutige Anwesenheits-Evidenz — sie gehört in den
        // Start-Tag.
        val triggerById = triggers.associateBy { it.id }
        val sessionCovered = mutableListOf<Pair<Long, Long>>()
        for (s in sessions) {
            val triggerId = s.sourceTriggerId ?: continue
            val trigger = triggerById[triggerId] ?: continue
            val geofenceId = trigger.geofenceId ?: continue
            val geo = geofenceById[geofenceId] ?: continue
            val isOngoingSession = s.endAt == null && s.startAt >= dayStart
            val endAt = s.endAt ?: (if (s.startAt < dayStart) continue else minOf(nowMs, dayEnd))
            if (endAt <= dayStart || s.startAt >= dayEnd) continue
            val clippedStart = s.startAt.coerceAtLeast(dayStart)
            val clippedEnd = endAt.coerceAtMost(dayEnd)
            visits += PlaceVisit(
                id = "session_${s.id}",
                geofenceId = geo.id,
                name = geo.name,
                icon = geo.icon,
                color = geo.color,
                latitude = geo.latitude,
                longitude = geo.longitude,
                startAt = clippedStart,
                endAt = clippedEnd,
                evidence = visitEvidence(endAt - s.startAt),
                isOngoing = isOngoingSession
            )
            sessionCovered += clippedStart to clippedEnd
        }

        // ── Quelle 2a: PRESENCE-Trigger (M18.87) — GPS-Zonen-Zustand ──
        // Der Presence-Sampler (CurrentZoneProvider, ~2-min-Cadence)
        // persistiert die GPS-Wahrheit "User ist in Zone X" als
        // PRESENCE_ENTER/EXIT-Paare. Deckt die Lücke reiner Event-Analyse.
        val presenceIntervals =
            derivePresenceIntervals(dayStart, dayEnd, triggers, geofenceById, nowMs)

        // ── Quelle 2: Trigger-Paare GLOBAL über einen Zustandsautomaten ──
        // Der User ist immer an GENAU EINEM Ort (oder unterwegs). Ein
        // GLOBALES Event beendet den vorherigen Aufenthalt implizit.
        // Intervalle werden über die GESAMTE Trigger-Chronik gebaut und
        // erst am Ende auf den Tag geclippt — ENTER vom Vortag + EXIT
        // heute funktioniert damit natürlich.
        val coveredBySession: (Long, Long) -> Boolean = { start, end ->
            sessionCovered.any { (cs, ce) -> start < ce && end > cs }
        }
        data class OpenVisit(val geofenceId: String, val enterAt: Long)
        data class ClosedVisit(
            val geofenceId: String,
            val startAt: Long,
            val endAt: Long,
            /** true = durch einen EXIT des GLEICHEN Ortes beendet. */
            val explicitClose: Boolean
        )

        val closed = mutableListOf<ClosedVisit>()
        var current: OpenVisit? = null
        // M18.87: Presence-Trigger NICHT in den GMS-Automaten — sie sind
        // bereits als Intervalle abgeleitet (Quelle 2a) und würden hier
        // Echo-Doppel-Visits erzeugen. GMS/Ping-Typen bleiben.
        val allEvents = triggers.filter {
            it.geofenceId != null &&
                it.source != com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.TRIGGER_SOURCE
        }.sortedBy { it.occurredAt }
        for (t in allEvents) {
            val isEnter = t.type.contains("ENTER") || t.type.contains("DWELL") || t.type.contains("ARRIVED")
            val isExit = t.type.contains("EXIT") || t.type.contains("LEFT")
            if (!isEnter && !isExit) continue
            if (isEnter) {
                val cur = current
                if (cur != null && cur.geofenceId != t.geofenceId) {
                    closed += ClosedVisit(cur.geofenceId, cur.enterAt, t.occurredAt, explicitClose = false)
                } else if (cur != null && cur.geofenceId == t.geofenceId) {
                    // ENTER desselben Ortes ohne EXIT: stiller Merge (GPS-Drift).
                    continue
                }
                current = OpenVisit(t.geofenceId!!, t.occurredAt)
            } else if (current != null && t.occurredAt > current!!.enterAt) {
                closed += ClosedVisit(
                    current.geofenceId, current.enterAt, t.occurredAt,
                    explicitClose = t.geofenceId == current.geofenceId
                )
                current = null
            }
            // EXIT VOR dem aktuellen ENTER (verzögertes Echo): ignoriert.
        }
        current?.let { cur ->
            if (nowMs >= cur.enterAt && nowMs <= dayEnd) {
                closed += ClosedVisit(cur.geofenceId, cur.enterAt, minOf(nowMs, dayEnd), explicitClose = false)
            }
        }

        for (c in closed) {
            val geo = geofenceById[c.geofenceId] ?: continue
            val displayStart = c.startAt.coerceAtLeast(dayStart)
            val displayEnd = c.endAt.coerceAtMost(dayEnd)
            if (displayEnd <= dayStart || c.startAt >= dayEnd) continue
            // ⭐ PHANTOM-GUARD: Ein Vortags-Intervall wird nur bei sauberer
            // Selbstbeendigung (EXIT desselben Ortes) für heute gezeigt.
            if (c.startAt < dayStart && !c.explicitClose) continue
            if (displayEnd - displayStart < MIN_MERGED_DURATION_MS && c.startAt >= dayStart) continue
            if (coveredBySession(c.startAt, c.endAt)) continue
            emittedTriggerIntervals += displayStart to displayEnd
            visits += PlaceVisit(
                id = "trigger_${c.startAt}_${c.geofenceId}",
                geofenceId = c.geofenceId,
                name = geo.name,
                icon = geo.icon,
                color = geo.color,
                latitude = geo.latitude,
                longitude = geo.longitude,
                startAt = displayStart,
                endAt = displayEnd,
                evidence = visitEvidence(c.endAt - c.startAt),
                isOngoing = c.endAt >= minOf(nowMs, dayEnd) && nowMs in c.startAt..c.endAt
            )
        }

        // ── Quelle 2b: PRESENCE-Visits emittieren (M18.87) ─────────────
        // Presence-Intervalle füllen die LÜCKEN, die präzisere Evidenz
        // (Sessions, GMS-Trigger) liegen lässt — SUBTRAHIEREN statt
        // verwerfen, angrenzende Segmente desselben Ortes mergen.
        val subtract = { start: Long, end: Long, blocks: List<Pair<Long, Long>> ->
            var segments = listOf(start to end)
            for ((bs, be) in blocks) {
                segments = segments.flatMap { (s, e) ->
                    when {
                        be <= s || bs >= e -> listOf(s to e)       // keine Überlappung
                        bs <= s && be >= e -> emptyList()          // voll abgedeckt
                        bs <= s -> listOf(be to e)                 // Kopf abgeschnitten
                        be >= e -> listOf(s to bs)                 // Fuß abgeschnitten
                        else -> listOf(s to bs, be to e)           // Mitte gelocht
                    }
                }.filter { (s, e) -> e - s >= MIN_MERGED_DURATION_MS }
            }
            segments
        }
        val presenceBlocks: List<Pair<Long, Long>> =
            sessionCovered + emittedTriggerIntervals
        for ((pStart, pEnd, pGeoId) in presenceIntervals) {
            if (pEnd <= dayStart || pStart >= dayEnd) continue
            val geo = geofenceById[pGeoId] ?: continue
            val clipStart = pStart.coerceAtLeast(dayStart)
            val clipEnd = pEnd.coerceAtMost(dayEnd)
            val segments = subtract(clipStart, clipEnd, presenceBlocks)
            if (segments.isEmpty()) continue
            val merged = mutableListOf<Pair<Long, Long>>()
            for (seg in segments.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && seg.first - last.second < 2 * 60_000L) {
                    merged[merged.size - 1] = last.first to seg.second
                } else {
                    merged += seg
                }
            }
            for ((s, e) in merged) {
                visits += PlaceVisit(
                    id = "presence_${s}_${pGeoId}",
                    geofenceId = pGeoId,
                    name = geo.name,
                    icon = geo.icon,
                    color = geo.color,
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    startAt = s,
                    endAt = e,
                    evidence = visitEvidence(e - s),
                    isOngoing = e >= minOf(nowMs, dayEnd) && nowMs in s..e
                )
            }
        }

        // ── Quelle 3: benannte + unbenannte Orte (unknown_place_session) ──
        for (u in namedPlaces) {
            if (u.endAt <= dayStart || u.startAt >= dayEnd) continue
            if (u.name.isNullOrBlank()) {
                // M18.87: Unbenannte Unknown-Places ZEIGEN (Google-Timeline-
                // Verhalten: Orte ohne gespeicherten Geofence sichtbar).
                // ≥ 15 min schaltet Detector-Rauschen still.
                if (u.endAt - u.startAt < UNKNOWN_PLACE_MIN_DURATION_MS) continue
                val displayStart = u.startAt.coerceAtLeast(dayStart)
                val displayEnd = u.endAt.coerceAtMost(dayEnd)
                if (displayEnd - displayStart < MIN_MERGED_DURATION_MS) continue
                visits += PlaceVisit(
                    id = "unknown_${u.id}",
                    geofenceId = null,
                    name = "",
                    icon = "📍",
                    color = "#64748B",
                    latitude = u.latitude,
                    longitude = u.longitude,
                    startAt = displayStart,
                    endAt = displayEnd,
                    evidence = VisitEvidence.UNNAMED_PLACE,
                    isOngoing = false
                )
                continue
            }
            visits += PlaceVisit(
                id = "unknown_${u.id}",
                geofenceId = null,
                name = u.name,
                icon = "📌",
                color = "#6366F1",
                latitude = u.latitude,
                longitude = u.longitude,
                startAt = u.startAt.coerceAtLeast(dayStart),
                endAt = u.endAt.coerceAtMost(dayEnd),
                evidence = VisitEvidence.NAMED_PLACE,
                isOngoing = false
            )
        }

        // ── Quelle 4: TAIL-BRIDGE (M18.88) — Ankunft aus dem letzten ──
        // Track-Punkt des Tages. Reported Fall: Die Heimfahrt ENDET in
        // Zuhause, aber GMS feuert den ENTER nie (Prozess tot, Doze,
        // Stabilisierungsfilter) → die Fahrt "hängt in der Luft". Liegt
        // der tagesletzte Track-Punkt in einem Geofence und NACH dem
        // letzten bekannten Visit(-Ende), entsteht daraus die Ankunfts-
        // Station (offen bis Tagesende — Anwesenheit hält real an).
        if (trackPoints.isNotEmpty()) {
            val lastPoint = trackPoints.maxByOrNull { it.recordedAt }
            // Nur Punkte INNERHALB des Tages bridgen (der VM lädt ±30 min
            // Puffer — ein Vortags-Punkt am Tagesanfang ist keine Ankunft
            // an DIESEM Tag, und künftige Tage haben keine Tracks).
            if (lastPoint != null && lastPoint.recordedAt >= dayStart) {
                val tailGeo = geofences.firstOrNull { g ->
                    g.deletedAt == null &&
                        distanceMeters(
                            lastPoint.latitude, lastPoint.longitude,
                            g.latitude, g.longitude
                        ) <= g.radiusMeters
                }
                if (tailGeo != null) {
                    val bridgeStart = lastPoint.recordedAt.coerceIn(dayStart, dayEnd)
                    val bridgeEnd = minOf(nowMs, dayEnd)
                    val coveredByVisits = visits.any { v ->
                        bridgeStart < v.endAt + BRIDGE_OVERLAP_TOLERANCE_MS &&
                            bridgeEnd > v.startAt - BRIDGE_OVERLAP_TOLERANCE_MS
                    }
                    if (!coveredByVisits && bridgeEnd - bridgeStart >= MIN_MERGED_DURATION_MS) {
                        visits += PlaceVisit(
                            id = "tailbridge_${bridgeStart}_${tailGeo.id}",
                            geofenceId = tailGeo.id,
                            name = tailGeo.name,
                            icon = tailGeo.icon,
                            color = tailGeo.color,
                            latitude = tailGeo.latitude,
                            longitude = tailGeo.longitude,
                            startAt = bridgeStart,
                            endAt = bridgeEnd,
                            evidence = visitEvidence(bridgeEnd - bridgeStart),
                            isOngoing = nowMs in bridgeStart..bridgeEnd && bridgeEnd < dayEnd
                        )
                    }
                }
            }
        }

        // ── Quelle 5: LIVE-ZONE (M18.88) — der letzte Ausweis ──────────
        // "Ich bin IMMER an einem Standort" ist ein Axiom, keine Evidenz-
        // Frage. Läuft für HEUTE sonst nichts (Kaltstart nach Update —
        // noch keine Presence-Historie, keine GMS-Events), zeigt der Tag
        // den aktuellen Zonen-Aufenthalt ab Tagesbeginn. Bewusst ehrlich
        // grob: Wir wissen nicht, WANN die Anwesenheit begann — also
        // starten wir nicht mit einer erfundenen Uhrzeit, sondern lassen
        // den Tag-Vorlauf als "Unterwegs/woanders" offen und zeigen
        // "läuft gerade". Sobald der erste echte Presence/GMS-Event kommt,
        // übernimmt der die Präzision.
        if (nowMs in dayStart..dayEnd && currentZoneGeofenceId != null) {
            val liveGeo = geofenceById[currentZoneGeofenceId]
            val ongoingCoversNow = visits.any { v ->
                v.isOngoing && nowMs >= v.startAt && nowMs <= v.endAt + BRIDGE_OVERLAP_TOLERANCE_MS
            }
            if (liveGeo != null && !ongoingCoversNow) {
                visits += PlaceVisit(
                    id = "livezone_${dayStart}_${liveGeo.id}",
                    geofenceId = liveGeo.id,
                    name = liveGeo.name,
                    icon = liveGeo.icon,
                    color = liveGeo.color,
                    latitude = liveGeo.latitude,
                    longitude = liveGeo.longitude,
                    // Ehrlichkeit: Start bei der letzten Zonen-Bestätigung
                    // (erste GPS-Prüfung nach Update) — keine erfundene
                    // Nacht-Retrospektive, Dauer wächst live mit.
                    startAt = if (currentZoneSinceMs != null) {
                        currentZoneSinceMs.coerceIn(dayStart, minOf(nowMs, dayEnd))
                    } else {
                        minOf(nowMs, dayEnd)
                    },
                    endAt = minOf(nowMs, dayEnd),
                    evidence = VisitEvidence.LIVE_ZONE,
                    isOngoing = true
                )
            }
        }

        // ── M18.88 KONSOLIDIERUNG: fragmentierte Visits desselben Ortes ──
        // GMS- und Presence-Evidenz desselben Ortes können minimal versetzt
        // sein (GMS-EXIT 17:58 vs. Presence-EXIT 18:00) → sonst zwei Visits
        // desselben Ortes mit Pseudo-Lücke. Merge: gleicher Geofence +
        // Lücke ≤ 2 min → ein Visit. (Sortierung schützt die Chronologie.)
        val consolidated = consolidateVisits(visits.sortedBy { it.startAt })

        return consolidated.sortedBy { it.startAt }
    }

    /** M18.88: Merge angrenzender Visits desselben Ortes (Lücke ≤ 2 min).
     *  Der repräsentierende Visit folgt der Evidenz-Priorität (Session >
     *  GMS-Trigger > Presence > Tail-Bridge > Live-Zone) — ID und Typ
     *  bleiben damit aus der stärksten Quelle, die Zeitachse zeigt die
     *  vereinte Dauer. */
    private fun sourcePriority(visit: PlaceVisit): Int = when {
        visit.id.startsWith("session_") -> 0
        visit.id.startsWith("trigger_") -> 1
        visit.id.startsWith("presence_") -> 2
        visit.id.startsWith("tailbridge_") -> 3
        else -> 4
    }

    private fun consolidateVisits(sorted: List<PlaceVisit>): List<PlaceVisit> {
        if (sorted.size <= 1) return sorted
        val result = mutableListOf<PlaceVisit>()
        for (v in sorted) {
            val last = result.lastOrNull()
            val mergeable = last != null &&
                last.geofenceId != null &&
                last.geofenceId == v.geofenceId &&
                v.startAt - last.endAt <= CONSOLIDATE_GAP_MS
            if (mergeable) {
                // Repräsentant: die Quelle mit der HÖHEREN Priorität
                // (Session schlägt Roh-Trigger schlägt Presence), aber mit
                // der UNION der Zeitintervalle.
                val keep =
                    if (last!!.evidence == VisitEvidence.NAMED_PLACE ||
                        last.evidence == VisitEvidence.UNNAMED_PLACE
                    ) last
                    else if (sourcePriority(last) <= sourcePriority(v)) last else v
                val unionStart = minOf(last.startAt, v.startAt)
                val unionEnd = maxOf(last.endAt, v.endAt)
                result[result.size - 1] = keep.copy(
                    startAt = unionStart,
                    endAt = unionEnd,
                    isOngoing = last.isOngoing || v.isOngoing
                )
            } else {
                result += v
            }
        }
        return result
    }

    /** Haversine-Distanz (m) — nur für das Tail-Bridge-Matching. */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun visitEvidence(durationMs: Long): VisitEvidence =
        if (durationMs >= LONG_STAY_MS) VisitEvidence.GEOFENCE_LONG else VisitEvidence.GEOFENCE_SHORT

    /**
     * M18.87: PRESENCE-Trigger → überlappungsfreie Intervalle. State-Machine
     * über die chronologische Trigger-Chronik: ENTER öffnet, EXIT schließt,
     * ENTER desselben Ortes wird still gemerged. Intervall-Starts VOR dem
     * Tag (Mitternacht) werden geclippt und gelten als selbstkonsistente
     * Nacht (eigene Paare — der EXIT beweist das Ende).
     *
     * @return Liste (startMs, endMs, geofenceId), chronologisch.
     */
    private fun derivePresenceIntervals(
        dayStart: Long,
        dayEnd: Long,
        triggers: List<TriggerEvent>,
        geofenceById: Map<String, PlaceGeofence>,
        nowMs: Long
    ): List<Triple<Long, Long, String>> {
        val presenceTriggers = triggers.filter {
            it.geofenceId != null &&
                it.source == com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.TRIGGER_SOURCE
        }.sortedBy { it.occurredAt }
        if (presenceTriggers.isEmpty()) return emptyList()

        data class OpenPresence(val geofenceId: String, val startAt: Long)
        val intervals = mutableListOf<Triple<Long, Long, String>>()
        var open: OpenPresence? = null

        fun close(at: Long) {
            val cur = open ?: return
            open = null
            // Ehrlichkeit: nur bestätigte Aufenthalte (≥ 60 s) — identisch
            // zum MIN_MERGED_DURATION_MS-Vertrag von Quelle 2.
            if (at - cur.startAt >= MIN_MERGED_DURATION_MS &&
                geofenceById.containsKey(cur.geofenceId)
            ) {
                intervals += Triple(cur.startAt, at, cur.geofenceId)
            }
        }

        for (t in presenceTriggers) {
            val isEnter = t.type == com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.TYPE_ENTER
            val isExit = t.type == com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.TYPE_EXIT
            if (isEnter) {
                val cur = open
                if (cur != null && cur.geofenceId == t.geofenceId) continue // stiller Merge
                if (cur != null) close(t.occurredAt)
                open = OpenPresence(t.geofenceId!!, t.occurredAt)
            } else if (t.type == com.d_drostes_apps.aevum.automation.geofence.CurrentZoneProvider.TYPE_EXIT) {
                val cur = open
                if (cur != null && t.occurredAt > cur.startAt) close(t.occurredAt)
                // EXIT vor openStart (Echo): ignorieren.
            }
        }
        open?.let {
            // Offen geblieben: läuft bis jetzt (kein Ende erfunden).
            close(minOf(nowMs, dayEnd))
        }
        return intervals
    }
}

/** Zusammenfassung eines Tages (Kopf-Karte der Place Timeline). */
data class PlaceDaySummary(
    /** Sortiert nach Dauer absteigend — "hauptsächlich warst du hier". */
    val placeTotals: List<Pair<String, Long>>,
    /** Summe aller Visit-Dauern. */
    val totalVisitedMs: Long,
    /** Tageslänge minus Visits = unterwegs/woanders. */
    val onTheRoadMs: Long,
    val visitCount: Int
)

object PlaceDaySummaryCalculator {
    fun calculate(visits: List<PlaceVisit>, dayStart: Long, dayEnd: Long): PlaceDaySummary {
        val totalVisited = visits.sumOf { it.durationMs }
        val totals = visits
            .groupBy { it.name }
            .map { (name, list) -> name to list.sumOf { it.durationMs } }
            .sortedByDescending { it.second }
        return PlaceDaySummary(
            placeTotals = totals,
            totalVisitedMs = totalVisited,
            onTheRoadMs = (dayEnd - dayStart - totalVisited).coerceAtLeast(0L),
            visitCount = visits.size
        )
    }
}