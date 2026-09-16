package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.d_drostes_apps.aevum.ui.screens.timeline.buildPlannedSessionsForDay
import com.d_drostes_apps.aevum.ui.screens.timeline.buildPlannedSessionsForWeek
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.129: Tests für die Timeline-Vorausschau geplanter Blöcke.
 *
 * Die wichtigsten Zusicherungen:
 *  - Ein Termin erscheint NUR an Tagen, die er wirklich schneidet
 *    (kein Zukunfts-Leck — M18.60c-Bugklasse).
 *  - Mitternachts-Termine werden pro Tag korrekt geclippt.
 *  - Ohne Regeln entsteht nichts (kein pauschaler Kalender-Dump).
 *  - Die 7-Tage-Vorschau deckt genau den Auftragszeitraum ab.
 */
class PlannedSessionsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val monday = LocalDate.of(2026, 9, 14)

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        title: String = "Vorlesung Analysis",
        startAt: Long,
        endAt: Long,
        allDay: Boolean = false
    ) = CalendarEventCache(
        eventId = "uni:1:$startAt",
        calendarId = "uni",
        calendarName = "Universität",
        title = title,
        description = null,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        attendees = null,
        syncedAt = 0L
    )

    private fun rule(
        activityTypeId: String = "studium",
        matchValue: String = "Vorlesung",
        enabled: Boolean = true
    ) = CalendarRule(
        id = "r1",
        name = "Studium",
        enabled = enabled,
        matchType = CalendarRuleType.ANY_FIELD_CONTAINS,
        matchValue = matchValue,
        activityTypeId = activityTypeId
    )

    private val types = listOf(
        ActivityType(id = "studium", name = "Studium", icon = "📚", color = 0xFF4F46E5)
    )

    // ── Tages-Zuordnung ───────────────────────────────────────────────

    @Test
    fun `Termin erscheint am Tag seines Beginns`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        val planned = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone)
        assertThat(planned).hasSize(1)
        assertThat(planned.first().startMinuteOfDay).isEqualTo(10 * 60)
        assertThat(planned.first().endMinuteOfDay).isEqualTo(12 * 60)
    }

    @Test
    fun `Termin erscheint NICHT an anderen Tagen`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        // Der naechste Tag darf den Termin nicht zeigen (Zukunfts-Leck).
        val nextDay = buildPlannedSessionsForDay(monday.plusDays(1), events, listOf(rule()), types, zone)
        assertThat(nextDay).isEmpty()
        val prevDay = buildPlannedSessionsForDay(monday.minusDays(1), events, listOf(rule()), types, zone)
        assertThat(prevDay).isEmpty()
    }

    @Test
    fun `ohne Regeln entsteht nichts`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        assertThat(buildPlannedSessionsForDay(monday, events, emptyList(), types, zone)).isEmpty()
    }

    @Test
    fun `nicht passende Termine werden nicht geplant`() {
        val events = listOf(
            event(title = "Mittagessen", startAt = millis(monday, 12), endAt = millis(monday, 13))
        )
        assertThat(buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone)).isEmpty()
    }

    @Test
    fun `deaktivierte Regel plant nichts`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        val planned = buildPlannedSessionsForDay(
            monday, events, listOf(rule(enabled = false)), types, zone
        )
        assertThat(planned).isEmpty()
    }

    // ── Mitternachts-Termine ──────────────────────────────────────────

    @Test
    fun `Mitternachts-Termin wird pro Tag geclippt`() {
        // Termin von Sonntag 23:00 bis Montag 01:00.
        val sunday = monday.minusDays(1)
        val events = listOf(
            event(title = "Vorlesung Nacht", startAt = millis(sunday, 23), endAt = millis(monday, 1))
        )
        val plannedMonday = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone)
        assertThat(plannedMonday).hasSize(1)
        // Am Montag nur der Teil 00:00–01:00.
        assertThat(plannedMonday.first().startMinuteOfDay).isEqualTo(0)
        assertThat(plannedMonday.first().endMinuteOfDay).isEqualTo(60)
    }

    @Test
    fun `Mitternachts-Termin erscheint auch am Starttag`() {
        val sunday = monday.minusDays(1)
        val events = listOf(
            event(title = "Vorlesung Nacht", startAt = millis(sunday, 23), endAt = millis(monday, 1))
        )
        val plannedSunday = buildPlannedSessionsForDay(sunday, events, listOf(rule()), types, zone)
        assertThat(plannedSunday).hasSize(1)
        assertThat(plannedSunday.first().startMinuteOfDay).isEqualTo(23 * 60)
        // Ende ist auf Tagesende gedeckelt.
        assertThat(plannedSunday.first().endMinuteOfDay).isEqualTo(1440)
    }

    // ── Aktivitäts-Zuordnung ──────────────────────────────────────────

    @Test
    fun `Icon und Farbe der Aktivitaet werden uebernommen`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        val planned = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone).first()
        assertThat(planned.activityIcon).isEqualTo("📚")
        assertThat(planned.activityColor).isEqualTo(0xFF4F46E5)
        assertThat(planned.activityTypeName).isEqualTo("Studium")
    }

    @Test
    fun `Titel ist der Aktivitaetsname wenn kein eigener Titel gesetzt ist`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        val planned = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone).first()
        // M18.66-FIX9-Muster: die Session heisst wie die AKTIVITAET,
        // nicht wie der Kalender-Termin.
        assertThat(planned.title).isEqualTo("Studium")
    }

    @Test
    fun `eigener Titel der Regel gewinnt`() {
        val r = rule().copy(defaultTitle = "Uni-Zeit")
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        val planned = buildPlannedSessionsForDay(monday, events, listOf(r), types, zone).first()
        assertThat(planned.title).isEqualTo("Uni-Zeit")
    }

    @Test
    fun `Regel ohne Aktivitaet plant nichts`() {
        // ON DELETE SET NULL nach dem Loeschen der Aktivitaet.
        val r = rule().copy(activityTypeId = null)
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        assertThat(buildPlannedSessionsForDay(monday, events, listOf(r), types, zone)).isEmpty()
    }

    // ── 7-Tage-Vorausschau ────────────────────────────────────────────

    @Test
    fun `Wochenvorausschau deckt genau 7 Tage ab`() {
        val events = (0..8).map { offset ->
            val day = monday.plusDays(offset.toLong())
            event(startAt = millis(day, 10), endAt = millis(day, 12))
        }.mapIndexed { i, e -> e.copy(eventId = "e$i") }

        val week = buildPlannedSessionsForWeek(monday, 7, events, listOf(rule()), types, zone)
        // 9 Termine vorhanden, aber nur 7 Tage werden geplant.
        assertThat(week.keys).hasSize(7)
        assertThat(week.keys.min()).isEqualTo(monday)
        assertThat(week.keys.max()).isEqualTo(monday.plusDays(6))
    }

    @Test
    fun `Wochenvorausschau ohne Regeln ist leer`() {
        val events = listOf(event(startAt = millis(monday, 10), endAt = millis(monday, 12)))
        assertThat(buildPlannedSessionsForWeek(monday, 7, events, emptyList(), types, zone)).isEmpty()
    }

    @Test
    fun `mehrere Termine am selben Tag sind nach Zeit sortiert`() {
        val events = listOf(
            event(title = "Vorlesung B", startAt = millis(monday, 14), endAt = millis(monday, 16)),
            event(title = "Vorlesung A", startAt = millis(monday, 8), endAt = millis(monday, 10))
        ).mapIndexed { i, e -> e.copy(eventId = "e$i") }

        val planned = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone)
        assertThat(planned).hasSize(2)
        assertThat(planned.map { it.startMinuteOfDay })
            .containsExactly(8 * 60, 14 * 60)
            .inOrder()
    }

    @Test
    fun `mehrere Termine am selben Tag haben eindeutige IDs`() {
        val events = listOf(
            event(title = "Vorlesung B", startAt = millis(monday, 14), endAt = millis(monday, 16)),
            event(title = "Vorlesung A", startAt = millis(monday, 8), endAt = millis(monday, 10))
        ).mapIndexed { i, e -> e.copy(eventId = "e$i") }
        val planned = buildPlannedSessionsForDay(monday, events, listOf(rule()), types, zone)
        assertThat(planned.map { it.id }.toSet()).hasSize(2)
    }
}
