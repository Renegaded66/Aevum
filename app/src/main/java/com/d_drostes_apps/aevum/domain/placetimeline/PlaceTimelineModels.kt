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
        val triggerById = triggers.associateBy { it.id }
        val sessionCovered = mutableListOf<Pair<Long, Long>>()
        for (s in sessions) {
            val triggerId = s.sourceTriggerId ?: continue
            val trigger = triggerById[triggerId] ?: continue
            val geofenceId = trigger.geofenceId ?: continue
            val geo = geofenceById[geofenceId] ?: continue
            val endAt = s.endAt ?: nowMs
            if (endAt <= dayStart || s.startAt >= dayEnd) continue
            val clippedStart = s.startAt.coerceAtLeast(dayStart)
            val clippedEnd = endAt.coerceAtMost(dayEnd)
            val ongoing = s.endAt == null && nowMs in dayStart..dayEnd
            visits += PlaceVisit(
                id = "session_${s.id}",
                geofenceId = geo.id,
                name = geo.name,
                icon = geo.icon,
                color = geo.color,
                startAt = clippedStart,
                endAt = clippedEnd,
                evidence = visitEvidence(endAt - s.startAt),
                isOngoing = ongoing
            )
            sessionCovered += clippedStart to clippedEnd
        }

        // ── Quelle 2: Roh-Trigger-Paare (ENTER/DWELL ... EXIT) pro Geofence ──
        val coveredBySession: (Long, Long) -> Boolean = { start, end ->
            sessionCovered.any { (cs, ce) -> start < ce && end > cs }
        }
        for ((geofenceId, events) in triggers.filter { it.geofenceId != null }.groupBy { it.geofenceId!! }) {
            val geo = geofenceById[geofenceId] ?: continue
            var enterAt: Long? = null
            for (t in events.sortedBy { it.occurredAt }) {
                val isEnter = t.type.contains("ENTER") || t.type.contains("DWELL") || t.type.contains("ARRIVED")
                val isExit = t.type.contains("EXIT") || t.type.contains("LEFT")
                when {
                    isEnter && enterAt == null -> enterAt = t.occurredAt
                    // ENTER/DWELL nach ENTER ohne EXIT: stiller Merge (GPS-Drift),
                    // das Intervall endet weiterhin am finalen EXIT.
                    isEnter -> Unit
                    isExit && enterAt != null -> {
                        val start = enterAt!!
                        enterAt = null
                        if (t.occurredAt - start >= MIN_MERGED_DURATION_MS &&
                            !coveredBySession(start, t.occurredAt)
                        ) {
                            visits += PlaceVisit(
                                id = "trigger_${t.id}",
                                geofenceId = geo.id,
                                name = geo.name,
                                icon = geo.icon,
                                color = geo.color,
                                startAt = start.coerceAtLeast(dayStart),
                                endAt = t.occurredAt.coerceAtMost(dayEnd),
                                evidence = visitEvidence(t.occurredAt - start),
                                isOngoing = false
                            )
                        }
                    }
                    // EXIT ohne ENTER → bewusst ignoriert (keine erfindbare Dauer).
                }
            }
            // Offener ENTER am Tagesende: nur anzeigen, wenn "jetzt" wirklich
            // in dem Intervall liegt (sonst fehlt jede weitere Evidenz).
            val open = enterAt
            if (open != null && nowMs >= open && nowMs <= dayEnd) {
                if (!(coveredBySession(open, minOf(nowMs, dayEnd)))) {
                    visits += PlaceVisit(
                        id = "trigger_open_${geo.id}",
                        geofenceId = geo.id,
                        name = geo.name,
                        icon = geo.icon,
                        color = geo.color,
                        startAt = open.coerceAtLeast(dayStart),
                        endAt = minOf(nowMs, dayEnd),
                        evidence = visitEvidence(nowMs - open),
                        isOngoing = true
                    )
                }
            }
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