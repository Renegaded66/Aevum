package com.d_drostes_apps.aevum.domain.placetimeline

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession

/*
 * ════════════════════════════════════════════════════════════════════════
 * M18.83 — Place Timeline (Google-Maps-Stil) — USABILITY-REFLEXION
 * ════════════════════════════════════════════════════════════════════════
 *
 * WAS DER USER HEUTE SIEHT:
 * Die Frage "Wo war ich eigentlich wann?" ist in Aevum unbeantwortbar.
 * Die Trigger-Liste ist eine Diagnose-Ebene (Roh-Events mit Confidence),
 * die normale Timeline zeigt Aktivitäten, keine Orte. Orte leben als
 * Geofences nur in den Einstellungen.
 *
 * WAS DER USER ERWARTET (Google Maps Timeline als Vorbild):
 * Eine erzählende Tag-Story: Stationen untereinander, farbig codiert,
 * mit Uhrzeiten und Dauer, unterwegs-Phasen zwischen den Orten, eine
 * Zusammenfassung oben ("6h 12m Zuhause · 3h Büro").
 *
 * ENTSCHEIDUNGEN (+ Alternative, + Trade-off):
 *
 * 1. READ-ONLY-DERIVATION statt neuer DB-Tabelle.
 *    Alternative: place_visit-Tabelle, befüllt vom GeofenceTransition-
 *    Processor. Dagegen: M18.40/41/60/61c haben gezeigt, wie leicht
 *    Writer-Pfade still versagen und wie viele Room-Migrations-Crashs
 *    diese App schon hatte. ENTER/EXIT-Paare + Sessions existieren
 *    bereits — wir leiten Visits beim Lesen ab. Kein Migrations-Risiko,
 *    keine zweite Quelle der Wahrheit, sondern die EINE (trigger_event).
 *
 * 2. SESSION-FIRST-DEDUP: Eine aufgezeichnete Session (sourceTriggerId
 *    verweist auf einen Geofence-Trigger, nicht soft-deleted) ist der
 *    stärkste Beweis für Anwesenheit — sie hat den 60s-Auto-Discard
 *    überlebt. Roh-Trigger-Intervalle, die von einer Session abgedeckt
 *    sind, werden verworfen (sonst doppelt).
 *
 * 3. EHRlichkeit bei lückenhafter Evidenz: Ein EXIT ohne ENTER ergibt
 *    KEINEN Visit (Dauer unbekannt → wir erfinden keine Arrival-Zeit).
 *    Ein offener ENTER ohne EXIT wird nur als laufend gezeigt, wenn
 *    "jetzt" in dem Intervall liegt. Manuelle Aktivitäten zählen nicht
 *    als Orte (keine Location-Evidenz), unbenannte Unknown-Places
 *    erscheinen nicht (dafür gibt es die Unknown-Places-Review).
 *
 * 4. SEMANTIK wie der Auto-Tracker: DWELL = bestätigter Anwesenheits-
 *    Beweis (M18.41), Session-Coverage verdrängt Roh-Trigger (die Session
 *    hat den 60s-Auto-Discard überlebt und ist damit Truth).
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
    NAMED_PLACE
}

/**
 * Reine Abgleichungs-Engine: Aus Sessions + Triggern + Geofences + benannten
 * Orten wird die Visit-Liste eines Tages gebaut. Pure Funktion → JVM-testbar.
 */
object PlaceTimelineEngine {

    /** Mindestdauer für "langer Aufenthalt" (Badge "länger geblieben"). */
    const val LONG_STAY_MS: Long = 30 * 60 * 1000L

    /** Mindestdauer, damit ein Roh-Trigger-Paar als Besuch zählt. */
    const val MIN_MERGED_DURATION_MS: Long = 60 * 1000L

    fun buildVisits(
        dayStart: Long,
        dayEnd: Long,
        sessions: List<ActivitySession>,
        triggers: List<TriggerEvent>,
        geofences: List<PlaceGeofence>,
        namedPlaces: List<UnknownPlaceSession>,
        nowMs: Long
    ): List<PlaceVisit> {
        // Auch deaktivierte Geofences können historische Visits erklären.
        // (Soft-gelöschte Geofences sind bewusst ausgeschlossen — wer den
        // Ort entfernt hat, will ihn nicht mehr sehen.)
        val geofenceById = geofences.associateBy { it.id }
        val visits = mutableListOf<PlaceVisit>()

        // ── Quelle 1: aufgezeichnete Sessions mit Trigger-Evidenz ──
        // session.sourceTriggerId → TriggerEvent (geofenceId). Das ist der
        // autoritative Link; wir raten NICHT anhand von ID-Formaten.
        //
        // M18.83.1 STALE-GUARD: Eine session mit endAt=null, die VOR diesem
        // Tag gestartet ist, ist eine VERLORENE Session (verpasster EXIT —
        // GMS-Reliability-Muster) und KEINE heutige Anwesenheits-Evidenz.
        // Vorher: endAt = nowMs + Tag-Clipping ⇒ "Gym 00:00–jetzt" am nächsten
        // Morgen, UND die Phantom-Coverage verdrängte per Session-Dedup den
        // ehrlichen Zuhause-Trigger-Intervall (Kettenbug: User sah "Gym
        // 0:00–11:00" statt "Zuhause 00:00–11:00"). Stale Sessions gehören
        // in den Start-Tag — heute zählen sie nicht.
        val triggerById = triggers.associateBy { it.id }
        val sessionCovered = mutableListOf<Pair<Long, Long>>()
        for (s in sessions) {
            val triggerId = s.sourceTriggerId ?: continue
            val trigger = triggerById[triggerId] ?: continue
            val geofenceId = trigger.geofenceId ?: continue
            val geo = geofenceById[geofenceId] ?: continue
            // Stale-Guard + Live-Zweig: endAt==null ist nur DANN legitime
            // Anwesenheit, wenn die Session HEUTE gestartet ist (läuft grade).
            // Gestern gestartet + noch offen = verlorene SESSION (verpasster
            // EXIT) → gehört in den Start-Tag, zählt heute nicht.
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

        // ── Quelle 2: Trigger-Paare GLOBAL über einen Zustandsautomaten ──
        // M18.83.1: Der alte per-Geofence-Loop hatte drei verkettete Fehler,
        // die zusammen den gemeldeten "Gym 0:00–11:00 obwohl Zuhause"-Bug
        // produzierten:
        //   (a) Stale offene Gym-Session (EXIT verloren) → Phantom 00:00–jetzt
        //   (b) Zuhause-ENTER gestern + EXIT heute 11:00 → EXIT war der
        //       chronologisch erste Event des Tages → "bare EXIT" → verworfen
        //       → Zuhause fehlte komplett
        //   (c) Offener gestriger Gym-ENTER → erneut Phantom
        // NEU: Der User ist immer an GENAU EINEM Ort (oder unterwegs). Ein
        // GLOBALES Event (irgendein Geofence-ENTER oder irgendein Geofence-EXIT)
        // beendet den vorherigen Aufenthalt implizit. Intervalle werden über
        // die GESAMTE Trigger-Chronik gebaut und erst am Ende auf den Tag
        // geclippt — ENTER vom Vortag + EXIT heute funktioniert damit natürlich.
        val coveredBySession: (Long, Long) -> Boolean = { start, end ->
            sessionCovered.any { (cs, ce) -> start < ce && end > cs }
        }
        data class OpenVisit(val geofenceId: String, val enterAt: Long)
        data class ClosedVisit(
            val geofenceId: String,
            val startAt: Long,
            val endAt: Long,
            /** true = durch einen EXIT des GLEICHEN Ortes beendet (selbstkonsistentes Paar). */
            val explicitClose: Boolean
        )

        val closed = mutableListOf<ClosedVisit>()
        var current: OpenVisit? = null
        val allEvents = triggers.filter { it.geofenceId != null }.sortedBy { it.occurredAt }
        for (t in allEvents) {
            val isEnter = t.type.contains("ENTER") || t.type.contains("DWELL") || t.type.contains("ARRIVED")
            val isExit = t.type.contains("EXIT") || t.type.contains("LEFT")
            if (!isEnter && !isExit) continue
            if (isEnter) {
                // Neuer Anwesenheits-Beweis: der bisherige offene Aufenthalt
                // (egal welcher Geofence) endet implizit hier — der User kann
                // nur an einem Ort sein.
                val cur = current
                if (cur != null && cur.geofenceId != t.geofenceId) {
                    closed += ClosedVisit(cur.geofenceId, cur.enterAt, t.occurredAt, explicitClose = false)
                } else if (cur != null && cur.geofenceId == t.geofenceId) {
                    // ENTER desselben Ortes ohne EXIT: stiller Merge (GPS-Drift).
                    // Intervall läuft weiter.
                    continue
                }
                current = OpenVisit(t.geofenceId!!, t.occurredAt)
            } else if (current != null && t.occurredAt > current!!.enterAt) {
                // EXIT (irgendeines Geofences) NACH dem aktuellen ENTER →
                // schließt das Intervall. EIN-ORT-AXIOM: Ein EXIT beweist
                // Bewegung — wenn der User um 11:00 Zuhause verlässt, kann er
                // nicht mehr "im Gym" sein, selbst wenn der Gym-EXIT verloren
                // ging. GPS-EXIT-Echos alter Besuche erzeugen höchstens kurze
                // Intervalle, die am <60s-Filter zerbrechen (Design-Zweck).
                closed += ClosedVisit(
                    current.geofenceId, current.enterAt, t.occurredAt,
                    explicitClose = t.geofenceId == current.geofenceId
                )
                current = null
            }
            // EXIT VOR dem aktuellen ENTER (verzögertes Echo aus dem Vortag):
            // bewusst ignoriert — kann den aktuellen Aufenthalt logisch
            // nicht beenden.
        }
        // Offen gebliebenes Intervall am Ende: nur zeigen, falls "jetzt" im
        // Intervall liegt (sonst wie ein Phantom wirken — kein Ende erfunden).
        current?.let { cur ->
            if (nowMs >= cur.enterAt && nowMs <= dayEnd) {
                closed += ClosedVisit(cur.geofenceId, cur.enterAt, minOf(nowMs, dayEnd), explicitClose = false)
            }
        }

        for (c in closed) {
            val geo = geofenceById[c.geofenceId] ?: continue
            // Mitternacht: das Intervall darf am Vortag beginnen; die Dauer
            // ist chronologisch positiv, nur der ANZEIGE-Ausschnitt wird geclippt.
            val displayStart = c.startAt.coerceAtLeast(dayStart)
            val displayEnd = c.endAt.coerceAtMost(dayEnd)
            if (displayEnd <= dayStart || c.startAt >= dayEnd) continue
            // ⭐ PHANTOM-GUARD (der gemeldete Gym-0:00–11:00-Bug): Ein Intervall,
            // das VOR diesem Tag beginnt, wird nur dann für HEUTE gezeigt, wenn
            // es durch einen EXIT des GLEICHEN Ortes sauber beendet wurde
            // (selbstkonsistente Nacht). Implizit beendete Übernahmen (fremdes
            // Event / immer noch offen) gehören in den Start-Tag — sonst
            // fabriziert ein verlorener EXIT das Phantom "Gym 00:00–jetzt".
            if (c.startAt < dayStart && !c.explicitClose) continue
            if (displayEnd - displayStart < MIN_MERGED_DURATION_MS && c.startAt >= dayStart) continue
            // Session-Coverage verdrängt Roh-Trigger (ein <60s-Ausschnitt, der
            // voll im Tag liegt, stammt aus GPS-Drift — an Tagesgrenzen ist
            // der geclippte Ausschnitt legitimerweise kürzer).
            if (coveredBySession(c.startAt, c.endAt)) continue
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

        // ── Quelle 3: benannte Orte (unknown_place_session mit name) ──
        for (u in namedPlaces) {
            if (u.name.isNullOrBlank()) continue
            if (u.endAt <= dayStart || u.startAt >= dayEnd) continue
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

        return visits.sortedBy { it.startAt }
    }

    private fun visitEvidence(durationMs: Long): VisitEvidence =
        if (durationMs >= LONG_STAY_MS) VisitEvidence.GEOFENCE_LONG else VisitEvidence.GEOFENCE_SHORT
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