package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import com.d_drostes_apps.aevum.domain.placetimeline.PlaceTimelineEngine
import com.d_drostes_apps.aevum.domain.placetimeline.VisitEvidence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.83: Tests für die Visit-Derivation der Place Timeline.
 *
 * Semantik-Verträge (aus der Usability-Reflexion in PlaceTimelineModels):
 *  - Session-Coverage verdrängt Roh-Trigger (keine Doppel-Visits)
 *  - EXIT ohne ENTER = kein Visit (keine erfundene Dauer)
 *  - < 60sère Roh-Trigger-Intervalle werden verworfen (GPS-Sprung-Schutz)
 *  - Mitternacht: Visits werden auf den Tag geclippt
 */
class PlaceTimelineEngineTest {

    private val day = 1_756_521_600_000L // 2026-08-30 00:00 UTC (nur als Basis)
    private val dayEnd = day + 24L * 60 * 60 * 1000
    private val now = day + 20L * 60 * 60 * 1000 // 20:00 des Tages

    private fun geofence(id: String = "g1") = PlaceGeofence(
        id = id, name = "Büro", latitude = 52.0, longitude = 8.0,
        radiusMeters = 100f, icon = "🏢", color = "#6366F1"
    )

    private fun trigger(id: String, type: String, at: Long, geoId: String = "g1") =
        TriggerEvent(id = id, occurredAt = at, type = type, source = "GMS", geofenceId = geoId)

    private fun session(id: String, start: Long, end: Long?, triggerId: String?) =
        ActivitySession(
            id = id, title = "Work", startAt = start, endAt = end,
            sourceType = "GEOFENCE_AUTO", sourceTriggerId = triggerId
        )

    @Test
    fun `ENTER EXIT Paar wird zu Visit`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 12 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].name).isEqualTo("Büro")
        assertThat(visits[0].startAt).isEqualTo(day + 9 * H)
        assertThat(visits[0].endAt).isEqualTo(day + 12 * H)
        assertThat(visits[0].isOngoing).isFalse()
    }

    @Test
    fun `Session-Abdeckung verdrängt Roh-Trigger`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("s1", day + 9 * H, day + 12 * H, triggerId = "t1")
            ),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 12 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        // Genau EIN Visit (aus der Session), NICHT ein zweiter aus dem Trigger-Paar.
        assertThat(visits).hasSize(1)
        assertThat(visits[0].id).isEqualTo("session_s1")
    }

    @Test
    fun `EXIT ohne ENTER ergibt keinen Visit`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(trigger("t1", "GEOFENCE_EXIT", day + 12 * H)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Roh-Trigger-Intervall unter 60s wird verworfen`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 9 * H + 30_000L)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Offener ENTER ohne EXIT ist laufend wenn jetzt im Intervall`() {
        val enterAt = day + 18 * H
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(trigger("t1", "GEOFENCE_DWELL", enterAt)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now // 20:00, ENTER um 18:00, kein EXIT
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].isOngoing).isTrue()
        assertThat(visits[0].endAt).isEqualTo(now)
    }

    @Test
    fun `Offener ENTER mit späterem Tag zeigt für historische Tage nichts`() {
        val enterAt = day + 18 * H
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(trigger("t1", "GEOFENCE_ENTER", enterAt)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now + 48 * H // zwei Tage später — Evidenz längst weg
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Mitternachts-Visit wird auf den Tag geclippt`() {
        // Visit 23:00 (Vortag) bis 02:00 (dieser Tag) — als Trigger-Paar.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day - 1 * H),   // 23:00 Vortag
                trigger("t2", "GEOFENCE_EXIT", day + 2 * H)     // 02:00
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = dayEnd
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].startAt).isEqualTo(day)
        assertThat(visits[0].endAt).isEqualTo(day + 2 * H)
    }

    @Test
    fun `Benannte Orte erscheinen mit NAMED_PLACE-Evidenz`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = emptyList(),
            geofences = emptyList(),
            namedPlaces = listOf(
                UnknownPlaceSession(
                    id = "u1", startAt = day + 14 * H, endAt = day + 15 * H,
                    latitude = 52.1, longitude = 8.1, name = "Ninas Café"
                )
            ),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].evidence).isEqualTo(VisitEvidence.NAMED_PLACE)
        assertThat(visits[0].name).isEqualTo("Ninas Café")
    }

    @Test
    fun `Unbenannte Unknown-Places erscheinen als UNNAMED_PLACE M18-87`() {
        // M18.87: Wie Google Timeline — auch Orte ohne Geofence/Name zeigen.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = emptyList(),
            geofences = emptyList(),
            namedPlaces = listOf(
                UnknownPlaceSession(
                    id = "u1", startAt = day + 14 * H, endAt = day + 15 * H,
                    latitude = 52.1, longitude = 8.1, name = null
                )
            ),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].evidence).isEqualTo(VisitEvidence.UNNAMED_PLACE)
        assertThat(visits[0].startAt).isEqualTo(day + 14 * H)
        assertThat(visits[0].endAt).isEqualTo(day + 15 * H)
    }

    @Test
    fun `Kurze unbenannte Unknown-Places unter 15 Minuten erscheinen nicht`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = emptyList(),
            geofences = emptyList(),
            namedPlaces = listOf(
                UnknownPlaceSession(
                    id = "u1", startAt = day + 14 * H, endAt = day + 14 * H + 10 * 60_000L,
                    latitude = 52.1, longitude = 8.1, name = null
                )
            ),
            nowMs = now
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Dauer ab 30 Minuten gilt als langer Aufenthalt`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 9 * H + 30 * 60_000L)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].evidence).isEqualTo(VisitEvidence.GEOFENCE_LONG)
    }

    @Test
    fun `Visits sind nach Startzeit sortiert`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 14 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 15 * H),
                trigger("t3", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t4", "GEOFENCE_EXIT", day + 10 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits.map { it.startAt }).isInOrder()
    }

    @Test
    fun `Stale offene Session von gestern zaehlt heute nicht mehr`() {
        // User-Kette: Gym-Session gestern gestartet, Gym-EXIT verloren,
        // Session läuft noch (endAt=null). Heute betritt/verlässt er Zuhause
        // (Trigger-Paar bis 11:00). ERWARTUNG: heute kein Gym-Visit, und der
        // Zuhause-Trigger-Intervall überlebt (nicht von Phantom-Coverage
        // abgedeckt).
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("stale", day - 20 * H, null, triggerId = "tstale")
            ),
            triggers = listOf(
                trigger("tstale", "GEOFENCE_ENTER", day - 20 * H, geoId = "g2"),
                // Zuhause: gestern 22:00 angekommen (ENTER), heute 11:00 verlassen (EXIT)
                trigger("tz1", "GEOFENCE_ENTER", day - 2 * H, geoId = "g3"),
                trigger("tz2", "GEOFENCE_EXIT", day + 11 * H, geoId = "g3")
            ),
            geofences = listOf(
                geofence(),
                geofence("g2").copy(name = "Gym"),
                geofence("g3").copy(name = "Zuhause")
            ),
            namedPlaces = emptyList(),
            nowMs = now // 20:00 des heutigen Tags
        )
        val names = visits.map { it.name }
        assertThat(names).doesNotContain("Gym")
        assertThat(names).contains("Zuhause")
        // Zuhause-Visit geclippt auf Tagesbeginn.
        val homeVisit = visits.first { it.name == "Zuhause" }
        assertThat(homeVisit.startAt).isEqualTo(day)
        assertThat(homeVisit.endAt).isEqualTo(day + 11 * H)
    }

    @Test
    fun `Heute gestartete offene Session bleibt laufend`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("live", day + 9 * H, null, triggerId = "t1")
            ),
            triggers = listOf(trigger("t1", "GEOFENCE_ENTER", day + 9 * H)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].isOngoing).isTrue()
        assertThat(visits[0].endAt).isEqualTo(now)
    }

    // ─────────────────────────────────────────────────────────────────
    // M18.87: PRESENCE-Trigger (GPS-Zonen-Wahrheit als Fallback-Evidenz)
    // ─────────────────────────────────────────────────────────────────

    private fun presenceTrigger(id: String, type: String, at: Long, geoId: String = "g3") =
        TriggerEvent(
            id = id, occurredAt = at, type = type, source = "presence_sampler",
            confidence = 0.55f, geofenceId = geoId
        )

    @Test
    fun `Presence-Intervall dauerhaft Zuhause wird gezeigt auch ohne Events heute`() {
        // User-Kette: Gestern 22:00 Zuhause angekommen (GMS) — aber HEUTE
        // kein GMS-Event (Dedup/verlorener EXIT). Der Presence-Sampler
        // weiß dafür: gestern 23:00 ENTER, EXIT um 20:00 offen... keins.
        // → Presence-Intervall muss "Zuhause 00:00–jetzt (läuft)" zeigen.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t_gms_enter", "GEOFENCE_ENTER", day - 2 * H, geoId = "g3"),
                presenceTrigger("p1", "PRESENCE_ENTER", day - 1 * H),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 11 * H)
            ),
            geofences = listOf(geofence(), geofence("g3").copy(name = "Zuhause")),
            namedPlaces = emptyList(),
            nowMs = now
        )
        val homeVisits = visits.filter { it.name == "Zuhause" }
        assertThat(homeVisits).isNotEmpty()
        // Der Presence-Intervall-Ausschnitt des Tags: 00:00–11:00.
        val presenceVisit = homeVisits.first { it.id.startsWith("presence_") }
        assertThat(presenceVisit.startAt).isEqualTo(day)
        assertThat(presenceVisit.endAt).isEqualTo(day + 11 * H)
    }

    @Test
    fun `Offene Presence endet bei jetzt und laeuft gerade`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day + 8 * H, geoId = "g1")
                // kein EXIT — User ist noch da
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now // 20:00
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].isOngoing).isTrue()
        assertThat(visits[0].endAt).isEqualTo(now)
    }

    @Test
    fun `Presence-Exit ohne Enter erzeugt keinen Visit`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(presenceTrigger("p1", "PRESENCE_EXIT", day + 9 * H)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Presence unter 60s wird verworfen`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day + 9 * H),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 9 * H + 30_000L)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).isEmpty()
    }

    @Test
    fun `Presence läuft um Session herum statt verworfen zu werden`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("s1", day + 8 * H, day + 12 * H, triggerId = "t1")
            ),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 8 * H, geoId = "g1"),
                presenceTrigger("p1", "PRESENCE_ENTER", day + 7 * H, geoId = "g1"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 14 * H, geoId = "g1")
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        // M18.88: Session (8–12) + Presence (7–14, gleicher Ort) werden
        // von der Konsolidierung zu EINEM durchgehenden Visit 7–14.
        assertThat(visits).hasSize(1)
        assertThat(visits[0].id).isEqualTo("session_s1")
        assertThat(visits[0].startAt).isEqualTo(day + 7 * H)
        assertThat(visits[0].endAt).isEqualTo(day + 14 * H)
    }

    @Test
    fun `Presence läuft um GMS-Trigger-Intervall herum statt verworfen zu werden`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 12 * H),
                presenceTrigger("p1", "PRESENCE_ENTER", day + 8 * H, geoId = "g1"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 14 * H, geoId = "g1")
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        // GMS-Paar (9–12) + Presence (8–14, gleicher Ort) → konsolidiert
        // zu EINEM Visit 8–14.
        assertThat(visits).hasSize(1)
        assertThat(visits[0].startAt).isEqualTo(day + 8 * H)
        assertThat(visits[0].endAt).isEqualTo(day + 14 * H)
        assertThat(visits[0].name).isEqualTo("Büro")
    }

    @Test
    fun `Sessions und GMS zusammen verdraengen den vollstaendigen Presence-Bereich`() {
        // Session deckt Presence komplett (7–14 voll in Session 6–16).
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("s1", day + 6 * H, day + 16 * H, triggerId = "t1")
            ),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 6 * H, geoId = "g1"),
                presenceTrigger("p1", "PRESENCE_ENTER", day + 7 * H, geoId = "g1"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 14 * H, geoId = "g1")
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].id).isEqualTo("session_s1")
    }

    @Test
    fun `Presence-Trigger erzeugen keine doppelten Echo-Visits im GMS-Automaten`() {
        // Wäre das Filtern der PRESENCE_*-Typen aus dem GMS-Automaten
        // entfernt, entstünde ein zweiter Visit für dasselbe Intervall.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day + 9 * H, geoId = "g1"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 12 * H, geoId = "g1")
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].id).startsWith("presence_")
    }

    @Test
    fun `Zonenwechsel im Presence-Verlauf erzeugt zwei sequenzielle Visits`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day + 8 * H, geoId = "g3"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 10 * H, geoId = "g3"),
                presenceTrigger("p3", "PRESENCE_ENTER", day + 10 * H, geoId = "g1"),
                presenceTrigger("p4", "PRESENCE_EXIT", day + 13 * H, geoId = "g1")
            ),
            geofences = listOf(geofence(), geofence("g3").copy(name = "Zuhause")),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(2)
        assertThat(visits[0].name).isEqualTo("Zuhause")   // 08:00–10:00
        assertThat(visits[1].name).isEqualTo("Büro")      // 10:00–13:00
        assertThat(visits[0].endAt).isEqualTo(day + 10 * H)
        assertThat(visits[1].startAt).isEqualTo(day + 10 * H)
    }

    // ─────────────────────────────────────────────────────────────────
    // M18.88: LÜCKENLOSE Tag-Story — Tail-Bridge, Live-Zone, Merge
    // ─────────────────────────────────────────────────────────────────

    private fun trackPoint(at: Long, lat: Double = 52.0, lon: Double = 8.0) =
        com.d_drostes_apps.aevum.data.model.LocationTrackPoint(
            id = "tp_${at}_$lat", sessionId = "s_track", recordedAt = at,
            latitude = lat, longitude = lon, accuracyMeters = 20f
        )

    @Test
    fun `Tail-Bridge leitet Ankunft-Station aus letztem Track-Punkt ab`() {
        // Reported-Fall: GYM-Visit (9-12), Heimfahrt (Tracks 12:00-12:45,
        // endet in Zuhause), GMS-ENTER fired nie → Ankunft fehlte.
        // Zuhause liegt bei 52.0/7.9, Radius 100 m — der letzte Track-
        // Punkt (12:45) liegt IN Zuhause, mitten in der Heimfahrt.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 12 * H)
            ),
            geofences = listOf(
                geofence(), // Büro @ 52.0/8.0
                geofence("g3").copy(name = "Zuhause", latitude = 52.0, longitude = 7.9)
            ),
            namedPlaces = emptyList(),
            nowMs = day + 20 * H,
            trackPoints = listOf(
                trackPoint(day + 12 * H + 10 * 60_000L, 51.5, 7.5),  // unterwegs
                trackPoint(day + 12 * H + 30 * 60_000L, 51.98, 7.9), // nah dran
                trackPoint(day + 12 * H + 45 * 60_000L, 52.0, 7.9)   // in Zuhause
            )
            // bewusst OHNE currentZone — die Bridge allein muss greifen
        )
        assertThat(visits.map { it.name }).contains("Zuhause")
        val arrival = visits.last { it.geofenceId == "g3" }
        assertThat(arrival.id).startsWith("tailbridge_")
        assertThat(arrival.startAt).isEqualTo(day + 12 * H + 45 * 60_000L)
        assertThat(arrival.isOngoing).isTrue()
    }

    @Test
    fun `Live-Zone zeigt heutigen Aufenthalt bei Kaltstart ohne Presence-Historie`() {
        // Reported-Fall nach Update: ein "Zuhause entered"-Trigger
        // existiert, aber kein GMS-Event heute — die Timeline war leer.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = emptyList(),
            geofences = listOf(geofence("g3").copy(name = "Zuhause")),
            namedPlaces = emptyList(),
            nowMs = now, // 20:00
            currentZoneGeofenceId = "g3",
            currentZoneSinceMs = day + 1 * H
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].name).isEqualTo("Zuhause")
        assertThat(visits[0].evidence).isEqualTo(VisitEvidence.LIVE_ZONE)
        assertThat(visits[0].isOngoing).isTrue()
        assertThat(visits[0].startAt).isEqualTo(day + 1 * H)
    }

    @Test
    fun `Live-Zone erscheint nicht wenn bereits eine echte Evidenz läuft`() {
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(trigger("t1", "GEOFENCE_DWELL", day + 15 * H)),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now,
            currentZoneGeofenceId = "g1",
            currentZoneSinceMs = day + 15 * H
        )
        // Genau EIN Visit (der offene GMS-ENTER) — keine Live-Zone doppelt.
        assertThat(visits).hasSize(1)
        assertThat(visits[0].evidence).isNotEqualTo(VisitEvidence.LIVE_ZONE)
    }

    @Test
    fun `Fragmentierte Visits desselben Ortes werden konsolidiert`() {
        // GMS-Visit (9:00–12:00) + Presence-Rest (12:00:30–14:00) — 30s
        // Lücke, gleicher Ort → EIN Visit.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 12 * H, geoId = "g1"),
                presenceTrigger("p1", "PRESENCE_ENTER", day + 12 * H + 30_000L, geoId = "g1"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 14 * H, geoId = "g1")
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        val officeVisits = visits.filter { it.geofenceId == "g1" }
        assertThat(officeVisits).hasSize(1)
        assertThat(officeVisits[0].startAt).isEqualTo(day + 9 * H)
        assertThat(officeVisits[0].endAt).isEqualTo(day + 14 * H)
    }

    @Test
    fun `Alle Visits desselben Ortes verschmelzen zu einem laufenden heute`() {
        // Der User-Report komplett: Gestern angekommen, heute kein Event.
        // Presence (gestern ENTER) + kein EXIT → heute offenes Intervall,
        // Konsolidierung macht EINEN Visit "Zuhause 00:00–jetzt".
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day - 8 * H, geoId = "g3"),
                presenceTrigger("p2", "PRESENCE_EXIT", day + 9 * H, geoId = "g3"),
                presenceTrigger("p3", "PRESENCE_ENTER", day + 9 * H + 60_000L, geoId = "g3"),
                presenceTrigger("p4", "PRESENCE_EXIT", day + 13 * H, geoId = "g3"),
                presenceTrigger("p5", "PRESENCE_ENTER", day + 13 * H + 30_000L, geoId = "g3")
            ),
            geofences = listOf(geofence(), geofence("g3").copy(name = "Zuhause")),
            namedPlaces = emptyList(),
            nowMs = now
        )
        // Presence-Intervalle (0–9, 9:01–13, 13:00:30–jetzt) → nach Merge
        // ZWEI: 0–9 (geclippt) und 9:01–20:00 (offen). Nicht 3.
        val homeVisits = visits.filter { it.geofenceId == "g3" }
        assertThat(homeVisits.size).isAtMost(2)
        assertThat(homeVisits.last().isOngoing).isTrue()
    }

    @Test
    fun `Live-Zone wird durch EXIT nach Zonen-Anker widerlegt`() {
        // M18.98-Report: "Orts-Timeline zeigt weiterhin Arbeit, obwohl
        // ich vor 3 Stunden gegangen bin." GMS-EXIT (17:00) ist in der
        // DB, aber CurrentZoneProvider._currentZone ist stale (Arbeit).
        // Die Live-Zone (Quelle 5) darf KEINEN Visit "Arbeit läuft
        // gerade" bis 20:00 zeichnen.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 17 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now,
            currentZoneGeofenceId = "g1",
            currentZoneSinceMs = day + 16 * H
        )
        // Nur der GMS-Visit 9–17 — KEINE Live-Zone-Station danach.
        assertThat(visits).hasSize(1)
        assertThat(visits[0].endAt).isEqualTo(day + 17 * H)
        assertThat(visits[0].isOngoing).isFalse()
    }

    @Test
    fun `Live-Zone bleibt wenn kein EXIT existiert`() {
        // Kein EXIT in der Chronik → die Live-Zone ist die einzige
        // Evidenz und bleibt (Kaltstart-Fall M18.88).
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = emptyList(),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now,
            currentZoneGeofenceId = "g1",
            currentZoneSinceMs = day + 15 * H
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].evidence).isEqualTo(VisitEvidence.LIVE_ZONE)
        assertThat(visits[0].isOngoing).isTrue()
    }

    @Test
    fun `Offene Session endet am EXIT statt jetzt`() {
        // M18.98: Session ist offen (endAt=null, verlorener Stop), aber
        // ein bestätigter EXIT (17:00) beweist das Verlassen → die
        // Session darf nicht "läuft gerade" bis 20:00 zeigen.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = listOf(
                session("s1", day + 9 * H, null, triggerId = "t1")
            ),
            triggers = listOf(
                trigger("t1", "GEOFENCE_ENTER", day + 9 * H),
                trigger("t2", "GEOFENCE_EXIT", day + 17 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].endAt).isEqualTo(day + 17 * H)
        assertThat(visits[0].isOngoing).isFalse()
    }

    @Test
    fun `Offene Presence endet am EXIT statt jetzt`() {
        // M18.98: Presence-ENTER (9:00) ohne Presence-EXIT (Sampler
        // hängt an checkNow), aber GMS-EXIT (17:00) beweist das
        // Verlassen → Presence-Intervall endet 17:00, nicht 20:00.
        val visits = PlaceTimelineEngine.buildVisits(
            dayStart = day, dayEnd = dayEnd,
            sessions = emptyList(),
            triggers = listOf(
                presenceTrigger("p1", "PRESENCE_ENTER", day + 9 * H, geoId = "g1"),
                trigger("t2", "GEOFENCE_EXIT", day + 17 * H)
            ),
            geofences = listOf(geofence()),
            namedPlaces = emptyList(),
            nowMs = now
        )
        assertThat(visits).hasSize(1)
        assertThat(visits[0].endAt).isEqualTo(day + 17 * H)
        assertThat(visits[0].isOngoing).isFalse()
    }

    private companion object {
        const val H = 60L * 60 * 1000
    }
}