package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M18.131: Tests für den Vorrang manuell markierter Termine über Regeln.
 *
 * Auftrag: „…was bspw. passieren soll, wenn eine Regel ebenfalls bei diesem
 * Termin greift und eine Aufzeichnung starten will. Dann sollte das
 * benutzerdefinierte überwiegen über die Regel."
 *
 * Die Tests sichern vier Zusicherungen ab:
 *  1. Die Markierung gewinnt — auch gegen eine Regel mit HÖHERER Priorität.
 *  2. Ohne Markierung bleibt das Regel-Verhalten unverändert.
 *  3. Der Instanz-Schlüssel trifft genau EIN Vorkommen einer Serie.
 *  4. Eine Markierung ohne Aktivität (Aktivität gelöscht → SET NULL) ist
 *     inert und crasht nicht.
 */
class CalendarPinPrecedenceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 9, 18)

    private fun millis(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun event(
        title: String = "Vorlesung Analysis",
        startAt: Long = millis(10),
        endAt: Long = millis(12),
        idSuffix: String = "1"
    ) = CalendarEventCache(
        eventId = "uni:$idSuffix:$startAt",
        calendarId = "uni",
        calendarName = "Universität",
        title = title,
        description = null,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = false,
        attendees = null,
        syncedAt = 0L
    )

    private fun pin(
        event: CalendarEventCache,
        activityTypeId: String? = "fitness",
        defaultTitle: String? = null,
        overlapPolicy: String = CalendarOverlapPolicy.OVERRIDE
    ) = CalendarEventPin(
        eventId = event.eventId,
        activityTypeId = activityTypeId,
        defaultTitle = defaultTitle,
        eventStartAt = event.startAt,
        eventEndAt = event.endAt,
        eventTitle = event.title,
        calendarName = event.calendarName,
        overlapPolicy = overlapPolicy
    )

    private fun rule(
        id: String = "r1",
        activityTypeId: String? = "studium",
        priority: Int = 0,
        matchValue: String = "Vorlesung",
        overlapPolicy: String = CalendarOverlapPolicy.OVERRIDE
    ) = CalendarRule(
        id = id,
        name = "Studium aus Kalender",
        enabled = true,
        matchType = CalendarRuleType.ANY_FIELD_CONTAINS,
        matchValue = matchValue,
        activityTypeId = activityTypeId,
        overlapPolicy = overlapPolicy,
        priority = priority
    )

    // ── 1. Der Kernfall: Markierung schlägt Regel ──────────────────────

    @Test
    fun `Markierung gewinnt gegen passende Regel`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()),
            pins = listOf(pin(e, activityTypeId = "fitness")),
            events = listOf(e),
            zone = zone
        )
        assertThat(matches).hasSize(1)
        assertThat(matches.first().isUserPinned).isTrue()
        assertThat(matches.first().activityTypeId).isEqualTo("fitness")
    }

    /**
     * Der kritische Fall: die Regel hat eine HÖHERE Priorität (99 gegen 0).
     * Trotzdem darf sie die Markierung nicht überstimmen — Priorität ordnet
     * nur Regeln untereinander, nicht gegen eine ausdrückliche
     * Nutzer-Entscheidung.
     */
    @Test
    fun `Markierung gewinnt auch gegen Regel mit hoeherer Prioritaet`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule(priority = 99)),
            pins = listOf(pin(e, activityTypeId = "fitness")),
            events = listOf(e),
            zone = zone
        )
        assertThat(matches.first().activityTypeId).isEqualTo("fitness")
        assertThat(matches.first().isUserPinned).isTrue()
    }

    /** Der eigene Titel der Markierung gewinnt gegen den der Regel. */
    @Test
    fun `eigener Titel der Markierung gewinnt`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()),
            pins = listOf(pin(e, defaultTitle = "Sport mit Alex")),
            events = listOf(e),
            zone = zone
        )
        assertThat(matches.first().sessionTitle).isEqualTo("Sport mit Alex")
    }

    /** Die Overlap-Policy kommt aus der Markierung, nicht aus der Regel. */
    @Test
    fun `Overlap-Policy der Markierung gewinnt`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule(overlapPolicy = CalendarOverlapPolicy.OVERRIDE)),
            pins = listOf(pin(e, overlapPolicy = CalendarOverlapPolicy.ONLY_IF_IDLE)),
            events = listOf(e),
            zone = zone
        )
        assertThat(matches.first().shouldOverrideRunning).isFalse()
    }

    @Test
    fun `Markierung liefert einen vom Regel-Namen unterscheidbaren Quelltext`() {
        val e = event()
        val withPin = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()), pins = listOf(pin(e)), events = listOf(e), zone = zone
        ).first()
        val withoutPin = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()), pins = emptyList(), events = listOf(e), zone = zone
        ).first()
        // Die UI nutzt das, um „von dir markiert" von „per Regel" zu trennen.
        assertThat(withPin.sourceLabel).isNotEqualTo(withoutPin.sourceLabel)
        assertThat(withoutPin.isUserPinned).isFalse()
    }

    // ── 2. Ohne Markierung bleibt alles wie vorher ─────────────────────

    @Test
    fun `ohne Markierung greift die Regel wie bisher`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()), pins = emptyList(), events = listOf(e), zone = zone
        )
        assertThat(matches).hasSize(1)
        assertThat(matches.first().activityTypeId).isEqualTo("studium")
        assertThat(matches.first().isUserPinned).isFalse()
    }

    @Test
    fun `Markierung an einem anderen Termin beeinflusst diesen nicht`() {
        val e1 = event(title = "Vorlesung A", idSuffix = "1")
        val e2 = event(title = "Vorlesung B", startAt = millis(14), endAt = millis(16), idSuffix = "2")
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule()),
            pins = listOf(pin(e1, activityTypeId = "fitness")),
            events = listOf(e1, e2),
            zone = zone
        )
        val forE2 = matches.first { it.event.eventId == e2.eventId }
        assertThat(forE2.isUserPinned).isFalse()
        assertThat(forE2.activityTypeId).isEqualTo("studium")
    }

    @Test
    fun `ohne Regeln und ohne Markierungen wird nichts aufgezeichnet`() {
        assertThat(
            CalendarMatchEngine.evaluateWithPins(
                rules = emptyList(), pins = emptyList(), events = listOf(event()), zone = zone
            )
        ).isEmpty()
    }

    /**
     * Markierter Termin, der zu KEINER Regel passt — der Kernnutzen des
     * Features („nur diesen einen Termin, keine Regel").
     */
    @Test
    fun `markierter Termin wird auch ohne passende Regel aufgezeichnet`() {
        val e = event(title = "Zahnarzt", idSuffix = "9")
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = listOf(rule(matchValue = "Vorlesung")), // passt nicht
            pins = listOf(pin(e, activityTypeId = "health")),
            events = listOf(e),
            zone = zone
        )
        assertThat(matches).hasSize(1)
        assertThat(matches.first().activityTypeId).isEqualTo("health")
    }

    /**
     * Markierter Termin OHNE jede Regel im System — der Worker darf hier
     * nicht mit „keine Regeln vorhanden" abbrechen (M18.131-Gate).
     */
    @Test
    fun `markierter Termin funktioniert auch ohne jede Regel`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = emptyList(), pins = listOf(pin(e, activityTypeId = "fitness")),
            events = listOf(e), zone = zone
        )
        assertThat(matches).hasSize(1)
        assertThat(matches.first().activityTypeId).isEqualTo("fitness")
    }

    // ── 3. Wiederkehrende Termine: nur EIN Vorkommen ───────────────────

    /**
     * Eine wöchentliche Vorlesung: vier Vorkommen, dieselbe eventId, aber
     * unterschiedliche Startzeiten. Die Markierung auf Vorkommen 2 darf
     * weder die anderen mitnehmen noch verfehlen.
     */
    @Test
    fun `Markierung trifft genau ein Vorkommen einer Serie`() {
        val occurrences = (0..3).map { week ->
            val d = day.plusWeeks(week.toLong())
            event(idSuffix = "42", startAt = millis(10, date = d), endAt = millis(12, date = d))
        }
        val target = occurrences[2]
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = emptyList(),
            pins = listOf(pin(target, activityTypeId = "fitness")),
            events = occurrences,
            zone = zone
        )
        assertThat(matches).hasSize(1)
        assertThat(matches.first().event.eventId).isEqualTo(target.eventId)
    }

    // ── 4. Robustheit ──────────────────────────────────────────────────

    /** Aktivität gelöscht (ON DELETE SET NULL) → inert, kein Crash. */
    @Test
    fun `Markierung ohne Aktivitaet ist inert`() {
        val e = event()
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = emptyList(),
            pins = listOf(pin(e, activityTypeId = null)),
            events = listOf(e),
            zone = zone
        )
        // Der Match entsteht, hat aber keine Aktivität — der Worker
        // überspringt ihn (statt zu crashen).
        assertThat(matches).hasSize(1)
        assertThat(matches.first().activityTypeId).isNull()
    }

    /** Verwaiste Markierung (Termin nicht im Cache) erzeugt keinen Match. */
    @Test
    fun `verwaiste Markierung erzeugt keinen Match`() {
        val e = event(idSuffix = "1")
        val gone = event(idSuffix = "99")
        val matches = CalendarMatchEngine.evaluateWithPins(
            rules = emptyList(), pins = listOf(pin(gone)), events = listOf(e), zone = zone
        )
        assertThat(matches).isEmpty()
    }

    /**
     * CalendarMatch verlangt genau EINE Quelle. Beide gesetzt wäre ein
     * Programmierfehler, der still zu falschen Aufzeichnungen führen könnte
     * — deshalb ist er ein harter Fehler.
     */
    @Test
    fun `CalendarMatch akzeptiert nicht beide Quellen gleichzeitig`() {
        val e = event()
        val failure = runCatching {
            CalendarMatch(event = e, rule = rule(), pin = pin(e))
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `CalendarMatch akzeptiert nicht gar keine Quelle`() {
        val failure = runCatching { CalendarMatch(event = event()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
