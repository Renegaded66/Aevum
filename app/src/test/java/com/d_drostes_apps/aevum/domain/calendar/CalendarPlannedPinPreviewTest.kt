package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.ui.screens.timeline.buildPlannedSessionsForDay
import com.d_drostes_apps.aevum.ui.screens.timeline.buildPlannedSessionsForWeek
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.131: Tests für die Timeline-Vorschau mit markierten Einzel-Terminen.
 *
 * DIE GRUNDREGEL, die hier abgesichert wird: die Vorschau muss zeigen, was
 * der Auto-Start-Worker TATSÄCHLICH tun wird. Beide nutzen dieselbe
 * Auflösung ([CalendarMatchEngine.evaluateWithPins]) — dieser Test hält
 * das fest, weil eine abweichende Vorschau den Nutzer belügt (entweder
 * „geplant, passiert aber nicht" oder „nicht geplant, passiert aber").
 */
class CalendarPlannedPinPreviewTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val monday = LocalDate.of(2026, 9, 14) // Montag

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun event(
        title: String = "Zahnarzt",
        startAt: Long = millis(monday, 10),
        endAt: Long = millis(monday, 11),
        id: String = "1"
    ) = CalendarEventCache(
        eventId = "cal:$id:$startAt",
        calendarId = "cal",
        calendarName = "Privat",
        title = title,
        description = null,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = false,
        attendees = null,
        syncedAt = 0L
    )

    private fun pin(event: CalendarEventCache, activityTypeId: String? = "health", title: String? = null) =
        CalendarEventPin(
            eventId = event.eventId,
            activityTypeId = activityTypeId,
            defaultTitle = title,
            eventStartAt = event.startAt,
            eventEndAt = event.endAt,
            eventTitle = event.title,
            calendarName = event.calendarName
        )

    private fun type(id: String = "health", name: String = "Gesundheit") = ActivityType(
        id = id,
        name = name,
        defaultCategoryId = "health",
        isSystem = false,
        positivityScore = 60,
        icon = "\uD83E\uDE7A",
        color = 0xFF00AA88L
    )

    private val types = listOf(type(), type(id = "work", name = "Arbeit"))

    // ── Der Kernfall: markierter Termin erscheint als geplanter Block ──

    @Test
    fun `markierter Termin erscheint in der Vorschau`() {
        val e = event()
        val planned = buildPlannedSessionsForDay(
            date = monday,
            events = listOf(e),
            rules = emptyList(),
            types = types,
            zone = zone,
            pins = listOf(pin(e))
        )
        assertThat(planned).hasSize(1)
        assertThat(planned.first().isUserPinned).isTrue()
        assertThat(planned.first().title).isEqualTo("Gesundheit")
    }

    @Test
    fun `markierter Termin wird als markiert gekennzeichnet`() {
        val e = event()
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        ).first()
        assertThat(planned.isUserPinned).isTrue()
    }

    /** Ohne Markierung und ohne Regel: kein Block (kein Kalender-Dump). */
    @Test
    fun `unmarkierter Termin ohne Regel erscheint nicht`() {
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(event()), rules = emptyList(),
            types = types, zone = zone, pins = emptyList()
        )
        assertThat(planned).isEmpty()
    }

    @Test
    fun `ohne Regeln aber mit Markierung funktioniert die Vorschau`() {
        val e = event()
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        )
        assertThat(planned).isNotEmpty()
    }

    // ── Markierung bestimmt Aktivität und Titel ────────────────────────

    @Test
    fun `Aktivitaet der Markierung wird verwendet`() {
        val e = event()
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e, activityTypeId = "work"))
        ).first()
        assertThat(planned.activityTypeName).isEqualTo("Arbeit")
    }

    @Test
    fun `eigener Titel der Markierung wird verwendet`() {
        val e = event()
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e, title = "Kontrolle"))
        ).first()
        assertThat(planned.title).isEqualTo("Kontrolle")
    }

    /** Ohne eigenen Titel und ohne Typ: der Termin-Titel bleibt lesbar. */
    @Test
    fun `ohne eigenen Titel und ohne Typ erscheint der Termin-Titel`() {
        val e = event(title = "Zahnarzt Dr. Meyer")
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e, activityTypeId = null))
        ).first()
        assertThat(planned.title).isEqualTo("Zahnarzt Dr. Meyer")
    }

    // ── Der Vorrang in der Vorschau ────────────────────────────────────

    /**
     * Termin passt auf eine Regel UND ist markiert. Die Vorschau muss die
     * Markierung zeigen (Vorrang) — nicht die Regel-Aktivität.
     */
    @Test
    fun `Markierung ueberstimmt die Regel auch in der Vorschau`() {
        val e = event(title = "Vorlesung Analysis")
        val rule = com.d_drostes_apps.aevum.data.model.CalendarRule(
            id = "r1", name = "Studium", enabled = true,
            matchType = com.d_drostes_apps.aevum.data.model.CalendarRuleType.ANY_FIELD_CONTAINS,
            matchValue = "Vorlesung", activityTypeId = "work", priority = 50
        )
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = listOf(rule),
            types = types, zone = zone, pins = listOf(pin(e, activityTypeId = "health"))
        ).first()
        assertThat(planned.isUserPinned).isTrue()
        assertThat(planned.activityTypeName).isEqualTo("Gesundheit")
    }

    // ── 7-Tage-Vorschau ────────────────────────────────────────────────

    @Test
    fun `markierter Termin erscheint am richtigen Tag in der 7-Tage-Vorschau`() {
        val wednesday = monday.plusDays(2)
        val e = event(startAt = millis(wednesday, 14), endAt = millis(wednesday, 15))
        val week = buildPlannedSessionsForWeek(
            startDate = monday, days = 7, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        )
        assertThat(week.keys).containsExactly(wednesday)
        assertThat(week[wednesday]).hasSize(1)
    }

    @Test
    fun `ohne Regeln und ohne Markierungen ist die Woche leer`() {
        val week = buildPlannedSessionsForWeek(
            startDate = monday, days = 7, events = listOf(event()), rules = emptyList(),
            types = types, zone = zone, pins = emptyList()
        )
        assertThat(week).isEmpty()
    }

    /**
     * Ein Mitternachts-Termin erscheint an BEIDEN Tagen — die Markierung
     * gilt für den ganzen Zeitraum, nicht nur den Starttag.
     */
    @Test
    fun `Mitternachts-Termin erscheint an beiden Tagen`() {
        val start = millis(monday, 23)
        val end = millis(monday.plusDays(1), 1)
        val e = event(startAt = start, endAt = end)
        val week = buildPlannedSessionsForWeek(
            startDate = monday, days = 7, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        )
        assertThat(week.keys).containsExactly(monday, monday.plusDays(1))
        // Beide Tage zeigen den markierten Block.
        assertThat(week[monday]!!.first().isUserPinned).isTrue()
        assertThat(week[monday.plusDays(1)]!!.first().isUserPinned).isTrue()
    }

    /** Markierter Termin außerhalb des Tages erscheint nicht an diesem Tag. */
    @Test
    fun `Termin eines anderen Tages erscheint nicht`() {
        val e = event(startAt = millis(monday, 10), endAt = millis(monday, 11))
        val planned = buildPlannedSessionsForDay(
            date = monday.plusDays(1), events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        )
        assertThat(planned).isEmpty()
    }

    // ── Zeitgeometrie des Vorschau-Blocks ──────────────────────────────

    @Test
    fun `Vorschau-Block hat die richtigen Tagesminuten`() {
        val e = event(startAt = millis(monday, 9, 30), endAt = millis(monday, 11, 15))
        val planned = buildPlannedSessionsForDay(
            date = monday, events = listOf(e), rules = emptyList(),
            types = types, zone = zone, pins = listOf(pin(e))
        ).first()
        assertThat(planned.startMinuteOfDay).isEqualTo(9 * 60 + 30)
        assertThat(planned.endMinuteOfDay).isEqualTo(11 * 60 + 15)
    }
}
