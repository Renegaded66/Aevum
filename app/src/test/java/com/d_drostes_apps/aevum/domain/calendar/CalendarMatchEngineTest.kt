package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M18.129: Tests für die Kalender-Matching-Engine.
 *
 * Deckt die Fälle ab, die auf dem Gerät kaum reproduzierbar sind:
 * Wochentags-Bitmaske, Mitternachts-Fenster, UND/ODER-Semantik,
 * Groß-/Kleinschreibung, kaputte Regex und der Typ-Fallback nach
 * dem Löschen einer Aktivität.
 */
class CalendarMatchEngineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private val monday = LocalDate.of(2026, 9, 14) // Montag
    private val saturday = LocalDate.of(2026, 9, 19)

    private fun event(
        title: String = "Vorlesung Analysis",
        description: String? = null,
        startAt: Long = millis(monday, 10),
        endAt: Long = millis(monday, 12),
        allDay: Boolean = false,
        attendees: String? = null,
        calendarId: String = "uni"
    ) = CalendarEventCache(
        eventId = "uni:1:$startAt",
        calendarId = calendarId,
        calendarName = "Universität",
        title = title,
        description = description,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        attendees = attendees,
        syncedAt = 0L
    )

    private fun rule(
        matchType: String = CalendarRuleType.ANY_FIELD_CONTAINS,
        matchValue: String = "Vorlesung,Übung",
        activityTypeId: String? = "studium",
        enabled: Boolean = true,
        caseSensitive: Boolean = false,
        requireAllWords: Boolean = false,
        titleOnly: Boolean = false,
        minDurationMinutes: Int = 0,
        windowStartMinute: Int = -1,
        windowEndMinute: Int = -1,
        weekdayMask: Int = 0x7F,
        priority: Int = 0,
        overlapPolicy: String = CalendarOverlapPolicy.OVERRIDE,
        matchCalendarIds: String? = null
    ) = CalendarRule(
        id = "r1",
        name = "Studium",
        enabled = enabled,
        matchType = matchType,
        matchValue = matchValue,
        matchCalendarIds = matchCalendarIds,
        caseSensitive = caseSensitive,
        requireAllWords = requireAllWords,
        titleOnly = titleOnly,
        activityTypeId = activityTypeId,
        minDurationMinutes = minDurationMinutes,
        windowStartMinute = windowStartMinute,
        windowEndMinute = windowEndMinute,
        weekdayMask = weekdayMask,
        overlapPolicy = overlapPolicy,
        priority = priority
    )

    // ── Der Kernfall aus der Spezifikation ─────────────────────────────

    @Test
    fun `Vorlesung im Titel trifft die Studium-Regel`() {
        assertThat(CalendarMatchEngine.matches(rule(), event(), zone)).isTrue()
    }

    @Test
    fun `Uebung im Titel trifft die Studium-Regel`() {
        assertThat(
            CalendarMatchEngine.matches(rule(), event(title = "Übung 3"), zone)
        ).isTrue()
    }

    @Test
    fun `Wort in der Beschreibung trifft bei ANY_FIELD`() {
        assertThat(
            CalendarMatchEngine.matches(
                rule(),
                event(title = "Termin", description = "Übungsblatt 4 abgeben"),
                zone
            )
        ).isTrue()
    }

    @Test
    fun `unpassender Termin trifft nicht`() {
        assertThat(
            CalendarMatchEngine.matches(rule(), event(title = "Mittagessen"), zone)
        ).isFalse()
    }

    // ── UND/ODER-Semantik ─────────────────────────────────────────────

    @Test
    fun `ODER ist Default - ein Wort genuegt`() {
        assertThat(
            CalendarMatchEngine.matches(rule(matchValue = "Vorlesung,Übung"), event(title = "Vorlesung"), zone)
        ).isTrue()
    }

    @Test
    fun `UND verlangt alle Woerter`() {
        val r = rule(matchValue = "Vorlesung,Übung", requireAllWords = true)
        assertThat(CalendarMatchEngine.matches(r, event(title = "Vorlesung"), zone)).isFalse()
        assertThat(CalendarMatchEngine.matches(r, event(title = "Vorlesung Übung"), zone)).isTrue()
    }

    @Test
    fun `UND darf Woerter ueber Titel und Beschreibung verteilen`() {
        val r = rule(matchValue = "Vorlesung,Übung", requireAllWords = true)
        val e = event(title = "Vorlesung Analysis", description = "Übungsblatt")
        assertThat(CalendarMatchEngine.matches(r, e, zone)).isTrue()
    }

    // ── Feld-Auswahl ──────────────────────────────────────────────────

    @Test
    fun `TITLE_CONTAINS ignoriert die Beschreibung`() {
        val r = rule(matchType = CalendarRuleType.TITLE_CONTAINS, matchValue = "Übung")
        val e = event(title = "Termin", description = "Übung")
        assertThat(CalendarMatchEngine.matches(r, e, zone)).isFalse()
    }

    @Test
    fun `DESCRIPTION_CONTAINS ignoriert den Titel`() {
        val r = rule(matchType = CalendarRuleType.DESCRIPTION_CONTAINS, matchValue = "Übung")
        val e = event(title = "Übung", description = "etwas anderes")
        assertThat(CalendarMatchEngine.matches(r, e, zone)).isFalse()
    }

    // ── Groß-/Kleinschreibung ─────────────────────────────────────────

    @Test
    fun `case-insensitive ist Default`() {
        assertThat(
            CalendarMatchEngine.matches(rule(matchValue = "vorlesung"), event(title = "VORLESUNG"), zone)
        ).isTrue()
    }

    @Test
    fun `case-sensitive unterscheidet`() {
        val r = rule(matchValue = "vorlesung", caseSensitive = true)
        assertThat(CalendarMatchEngine.matches(r, event(title = "VORLESUNG"), zone)).isFalse()
        assertThat(CalendarMatchEngine.matches(r, event(title = "vorlesung"), zone)).isTrue()
    }

    // ── Regex ─────────────────────────────────────────────────────────

    @Test
    fun `Regex trifft den Titel`() {
        val r = rule(matchType = CalendarRuleType.TITLE_REGEX, matchValue = "^VL\\s+\\d+")
        assertThat(CalendarMatchEngine.matches(r, event(title = "VL 12 Algorithmen"), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(title = "Vorlesung 12"), zone)).isFalse()
    }

    @Test
    fun `kaputtes Regex trifft nichts statt zu crashen`() {
        val r = rule(matchType = CalendarRuleType.TITLE_REGEX, matchValue = "(unvollständig")
        assertThat(CalendarMatchEngine.matches(r, event(title = "egal"), zone)).isFalse()
    }

    // ── Kalender-Auswahl ──────────────────────────────────────────────

    @Test
    fun `leere Kalender-Auswahl gilt fuer alle Kalender`() {
        val r = rule(matchType = CalendarRuleType.CALENDAR_IS, matchValue = "", matchCalendarIds = null)
        assertThat(CalendarMatchEngine.matches(r, event(calendarId = "irgendwas"), zone)).isTrue()
    }

    @Test
    fun `Kalender-Auswahl filtert`() {
        val r = rule(
            matchType = CalendarRuleType.CALENDAR_IS,
            matchValue = "",
            matchCalendarIds = CalendarMatchEngine.serializeIds(listOf("uni", "arbeit"))
        )
        assertThat(CalendarMatchEngine.matches(r, event(calendarId = "uni"), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(calendarId = "privat"), zone)).isFalse()
    }

    // ── Teilnehmer ────────────────────────────────────────────────────

    @Test
    fun `ATTENDEE_CONTAINS findet die Domain`() {
        val r = rule(matchType = CalendarRuleType.ATTENDEE_CONTAINS, matchValue = "@uni-")
        assertThat(
            CalendarMatchEngine.matches(r, event(attendees = "prof@uni-koeln.de,ich@mail.de"), zone)
        ).isTrue()
        assertThat(
            CalendarMatchEngine.matches(r, event(attendees = "ich@mail.de"), zone)
        ).isFalse()
    }

    // ── Ganztägig ─────────────────────────────────────────────────────

    @Test
    fun `ALL_DAY_ONLY trifft nur ganztagige Termine`() {
        val r = rule(matchType = CalendarRuleType.ALL_DAY_ONLY, matchValue = "")
        assertThat(CalendarMatchEngine.matches(r, event(allDay = true), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(allDay = false), zone)).isFalse()
    }

    // ── Mindestdauer ──────────────────────────────────────────────────

    @Test
    fun `Mindestdauer filtert kurze Termine`() {
        val r = rule(minDurationMinutes = 30)
        val kurz = event(startAt = millis(monday, 10), endAt = millis(monday, 10, 15))
        val lang = event(startAt = millis(monday, 10), endAt = millis(monday, 11))
        assertThat(CalendarMatchEngine.matches(r, kurz, zone)).isFalse()
        assertThat(CalendarMatchEngine.matches(r, lang, zone)).isTrue()
    }

    // ── Zeitfenster ───────────────────────────────────────────────────

    @Test
    fun `Zeitfenster filtert Termine ausserhalb`() {
        val r = rule(windowStartMinute = 6 * 60, windowEndMinute = 22 * 60)
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 10)), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 3)), zone)).isFalse()
    }

    @Test
    fun `Termin der im Fenster BEGINNT gilt auch wenn er hinausragt`() {
        val r = rule(windowStartMinute = 9 * 60, windowEndMinute = 20 * 60)
        // 08:00–12:00 beginnt vor dem Fenster → kein Treffer
        val frueh = event(startAt = millis(monday, 8), endAt = millis(monday, 12))
        assertThat(CalendarMatchEngine.matches(r, frueh, zone)).isFalse()
        // 09:30–14:00 beginnt im Fenster → Treffer (ragt hinaus, ist ok)
        val drin = event(startAt = millis(monday, 9, 30), endAt = millis(monday, 14))
        assertThat(CalendarMatchEngine.matches(r, drin, zone)).isTrue()
    }

    @Test
    fun `Mitternachts-Fenster funktioniert`() {
        // Nur nachts: 22:00–06:00
        val r = rule(windowStartMinute = 22 * 60, windowEndMinute = 6 * 60)
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 23)), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 4)), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 12)), zone)).isFalse()
    }

    // ── Wochentage ────────────────────────────────────────────────────

    @Test
    fun `Wochentags-Bitmaske filtert das Wochenende`() {
        // Mo–Fr = 1+2+4+8+16 = 31
        val r = rule(weekdayMask = 31)
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 10)), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(saturday, 10)), zone)).isFalse()
    }

    @Test
    fun `Wochenende-only funktioniert`() {
        // Sa+So = 32+64 = 96
        val r = rule(weekdayMask = 96)
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(saturday, 10)), zone)).isTrue()
        assertThat(CalendarMatchEngine.matches(r, event(startAt = millis(monday, 10)), zone)).isFalse()
    }

    // ── Inaktive/invalide Regeln ──────────────────────────────────────

    @Test
    fun `deaktivierte Regel trifft nie`() {
        assertThat(CalendarMatchEngine.matches(rule(enabled = false), event(), zone)).isFalse()
    }

    @Test
    fun `Regel ohne ActivityType trifft nie`() {
        // ON DELETE SET NULL nach dem Löschen der Aktivität (M18.51)
        assertThat(CalendarMatchEngine.matches(rule(activityTypeId = null), event(), zone)).isFalse()
    }

    @Test
    fun `leerer Suchwert trifft nie`() {
        assertThat(CalendarMatchEngine.matches(rule(matchValue = ""), event(), zone)).isFalse()
    }

    @Test
    fun `unbekannter Regel-Typ trifft nie statt zu crashen`() {
        assertThat(
            CalendarMatchEngine.matches(rule(matchType = "ZUKUNFTS_TYP"), event(), zone)
        ).isFalse()
    }

    // ── Auflösung mehrerer Regeln ─────────────────────────────────────

    @Test
    fun `hoechste Prioritaet gewinnt`() {
        val niedrig = rule(priority = 0).copy(id = "a", activityTypeId = "studium")
        val hoch = rule(priority = 10).copy(id = "b", activityTypeId = "lernen")
        val found = CalendarMatchEngine.findRule(listOf(niedrig, hoch), event(), zone)
        assertThat(found?.id).isEqualTo("b")
    }

    @Test
    fun `evaluate liefert nur Termine mit passender Regel`() {
        val rule = rule()
        val events = listOf(
            event(title = "Vorlesung Analysis"),
            event(title = "Mittagessen"),
            event(title = "Übung 2")
        )
        val matches = CalendarMatchEngine.evaluate(listOf(rule), events, zone)
        assertThat(matches).hasSize(2)
        assertThat(matches.map { it.event.title })
            .containsExactly("Vorlesung Analysis", "Übung 2")
    }

    @Test
    fun `evaluate auf leerer Regelliste liefert nichts`() {
        assertThat(CalendarMatchEngine.evaluate(emptyList(), listOf(event()), zone)).isEmpty()
    }

    // ── Overlap-Policy ────────────────────────────────────────────────

    @Test
    fun `OVERRIDE ist der Default und meldet shouldOverrideRunning`() {
        val m = CalendarMatch(event(), rule())
        assertThat(m.shouldOverrideRunning).isTrue()
    }

    @Test
    fun `ONLY_IF_IDLE meldet kein Override`() {
        val m = CalendarMatch(event(), rule(overlapPolicy = CalendarOverlapPolicy.ONLY_IF_IDLE))
        assertThat(m.shouldOverrideRunning).isFalse()
    }

    // ── Term-Zerlegung ────────────────────────────────────────────────

    @Test
    fun `splitTerms trennt an Komma und Semikolon und trimmt`() {
        assertThat(CalendarMatchEngine.splitTerms(" a , b;c ,, "))
            .containsExactly("a", "b", "c")
            .inOrder()
    }

    @Test
    fun `parseIds ist robust gegen kaputtes JSON`() {
        assertThat(CalendarMatchEngine.parseIds(null)).isEmpty()
        assertThat(CalendarMatchEngine.parseIds("")).isEmpty()
        assertThat(CalendarMatchEngine.parseIds("kein json")).isEmpty()
        assertThat(CalendarMatchEngine.parseIds("""["a","b"]""")).containsExactly("a", "b").inOrder()
    }
}
