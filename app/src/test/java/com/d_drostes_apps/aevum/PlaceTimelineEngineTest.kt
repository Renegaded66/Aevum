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
    fun `Unbenannte Unknown-Places erscheinen nicht`() {
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

    private companion object {
        const val H = 60L * 60 * 1000
    }
}