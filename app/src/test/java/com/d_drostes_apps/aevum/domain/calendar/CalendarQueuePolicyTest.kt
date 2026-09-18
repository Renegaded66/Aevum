package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M18.132: Tests für die QUEUE-Overlap-Policy („startet, sobald keine
 * Aufzeichnung mehr läuft").
 *
 * Auftrag: der Nutzer soll pro Termin wählen können, was bei einer
 * bereits laufenden Aufzeichnung passiert:
 *  1. OVERRIDE — die laufende wird beendet, der Termin übernimmt.
 *  2. ONLY_IF_IDLE — es wird nichts gemacht.
 *  3. QUEUE_IF_BUSY — der Termin wartet und startet automatisch, sobald
 *     nichts mehr aufgezeichnet wird (NEU, diese Welle).
 *
 * Die Tests sichern die Engine-Seite ab: Policy-Erkennung am Match,
 * Fälligkeit des Nachholers und die EINHEITLICHE Definition von
 * „diese Session gehört zu diesem Termin" (findRelatedMatch) — die der
 * Doppelstart-Schutz UND der Stop-Pfad benutzen.
 */
class CalendarQueuePolicyTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 9, 19)

    private fun millis(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun event(
        title: String = "Grosseltern",
        startAt: Long = millis(15),
        endAt: Long = millis(18),
        id: String = "privat:1:$startAt"
    ) = CalendarEventCache(
        eventId = id,
        calendarId = "privat",
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

    private fun pinnedMatch(
        event: CalendarEventCache,
        activityTypeId: String? = "social",
        policy: String = CalendarOverlapPolicy.QUEUE_IF_BUSY
    ) = CalendarMatch(
        event = event,
        pin = CalendarEventPin(
            eventId = event.eventId,
            activityTypeId = activityTypeId,
            defaultTitle = null,
            eventStartAt = event.startAt,
            eventEndAt = event.endAt,
            eventTitle = event.title,
            calendarName = "Privat",
            overlapPolicy = policy
        )
    )

    // ── Policy-Erkennung am Match ────────────────────────────────────

    @Test
    fun `QUEUE-Policy wird am Markierung erkannt`() {
        assertThat(pinnedMatch(event(), policy = CalendarOverlapPolicy.QUEUE_IF_BUSY).shouldQueueWhenBusy)
            .isTrue()
    }

    @Test
    fun `OVERRIDE und ONLY_IF_IDLE sind keine QUEUE`() {
        assertThat(pinnedMatch(event(), policy = CalendarOverlapPolicy.OVERRIDE).shouldQueueWhenBusy)
            .isFalse()
        assertThat(pinnedMatch(event(), policy = CalendarOverlapPolicy.ONLY_IF_IDLE).shouldQueueWhenBusy)
            .isFalse()
    }

    @Test
    fun `QUEUE-Regel-Match wird erkannt`() {
        // Auch Regeln können die Policy speichern (Datenmodell erlaubt es,
        // auch wenn der Regel-Editor nur zwei Optionen anbietet).
        val rule = CalendarRule(
            id = "r1",
            name = "Soziales",
            matchType = com.d_drostes_apps.aevum.data.model.CalendarRuleType.ANY_FIELD_CONTAINS,
            matchValue = "Grosseltern",
            activityTypeId = "social",
            overlapPolicy = CalendarOverlapPolicy.QUEUE_IF_BUSY
        )
        val match = CalendarMatch(event = event(), rule = rule)
        assertThat(match.shouldQueueWhenBusy).isTrue()
    }

    // ── shouldStartQueued: Fälligkeit des Nachholers ────────────────

    @Test
    fun `QUEUE-Nachholer ist faellig waehrend der Termin laeuft`() {
        // Termin 15-18 Uhr, jetzt 16:30 — die 20-Min-Toleranz von
        // shouldStart ist lange vorbei, aber der Termin läuft noch.
        assertThat(CalendarAutoRunEngine.shouldStartQueued(event(), millis(16, 30))).isTrue()
    }

    @Test
    fun `QUEUE-Nachholer ist faellig genau zum Terminbeginn`() {
        assertThat(CalendarAutoRunEngine.shouldStartQueued(event(), millis(15))).isTrue()
    }

    @Test
    fun `QUEUE-Nachholer ist NICHT faellig vor dem Termin`() {
        assertThat(CalendarAutoRunEngine.shouldStartQueued(event(), millis(14, 59))).isFalse()
    }

    @Test
    fun `QUEUE-Nachholer ist NICHT faellig nach Terminende`() {
        assertThat(CalendarAutoRunEngine.shouldStartQueued(event(), millis(18))).isFalse()
    }

    // ── pickStartCandidate mit QUEUE ────────────────────────────────

    @Test
    fun `Kandidat - QUEUE-Termin lange nach Beginn wird nachgeholt`() {
        val match = pinnedMatch(event()) // 15-18 Uhr, QUEUE
        // Um 16:30 wäre shouldStart (20-Min-Toleranz) schon false —
        // ohne QUEUE-Verzweigung wäre der Termin für immer verloren.
        val now = millis(16, 30)
        val candidate = CalendarAutoRunEngine.pickStartCandidate(listOf(match), now, null)
        assertThat(candidate).isSameInstanceAs(match)
    }

    @Test
    fun `Kandidat - abgelaufener OVERRIDE-Termin wird NICHT nachgeholt`() {
        // Regressionsschutz: die Toleranz-Ausnahme gilt NUR für QUEUE.
        val match = pinnedMatch(event(), policy = CalendarOverlapPolicy.OVERRIDE)
        val candidate = CalendarAutoRunEngine.pickStartCandidate(listOf(match), millis(16, 30), null)
        assertThat(candidate).isNull()
    }

    @Test
    fun `Kandidat - faelliger OVERRIDE gewinnt gegen wartenden QUEUE`() {
        // Um 16:30 startet ein frischer OVERRIDE-Termin, während ein
        // QUEUE-Termin seit 15:00 wartet: der FRISCHE Termin gewinnt —
        // die Warteschlange darf ein aktuelles Ereignis nicht verdrängen.
        val queued = pinnedMatch(event(startAt = millis(15), endAt = millis(20)), policy = CalendarOverlapPolicy.QUEUE_IF_BUSY)
        val fresh = pinnedMatch(
            event(startAt = millis(16, 30), endAt = millis(17, 30), id = "privat:2:${millis(16, 30)}"),
            policy = CalendarOverlapPolicy.OVERRIDE
        )
        val candidate = CalendarAutoRunEngine.pickStartCandidate(listOf(queued, fresh), millis(16, 30), null)
        assertThat(candidate).isSameInstanceAs(fresh)
    }

    @Test
    fun `Kandidat - Match ohne Aktivitaet wird uebersprungen`() {
        // Eine Markierung, deren Aktivität gelöscht wurde (ON DELETE
        // SET NULL), darf den Start nicht blockieren (inert statt
        // bremsend). Vor M18.132 konnte ein solcher Match das Feld
        // für alle weiteren Kandidaten blockieren.
        val deadPin = pinnedMatch(event(), activityTypeId = null)
        val live = pinnedMatch(
            event(startAt = millis(16), endAt = millis(17), id = "privat:3:${millis(16)}"),
            policy = CalendarOverlapPolicy.OVERRIDE
        )
        val candidate = CalendarAutoRunEngine.pickStartCandidate(listOf(deadPin, live), millis(16), null)
        assertThat(candidate).isSameInstanceAs(live)
    }

    // ── findRelatedMatch: „Session gehört zu diesem Termin“ ─────────

    @Test
    fun `RelatedMatch - puenktlicher Start gehoert zum Termin`() {
        val match = pinnedMatch(event(), policy = CalendarOverlapPolicy.OVERRIDE)
        // Session startete exakt zum Terminbeginn (Auto-Start setzt
        // startedAt = Termin-Beginn).
        assertThat(CalendarAutoRunEngine.findRelatedMatch(listOf(match), millis(15), "social"))
            .isSameInstanceAs(match)
    }

    @Test
    fun `RelatedMatch - QUEUE-Nachholer mitten im Termin gehoert zum Termin`() {
        // Der Nachholer startet zum FREIWERDE-Zeitpunkt (16:30), nicht
        // zum Terminbeginn — über die alte Typ/Titel-Suche wäre er
        // nicht gefunden worden und wäre in den 8-Stunden-Watchdog
        // gefallen.
        val match = pinnedMatch(event())
        assertThat(CalendarAutoRunEngine.findRelatedMatch(listOf(match), millis(16, 30), "social"))
            .isSameInstanceAs(match)
    }

    @Test
    fun `RelatedMatch - Session eines ANDEREN Termins gleicher Aktivitaet`() {
        // Der M18.129-Bug: Typ/Titel-Suche ordnete die Session des
        // späteren Termins dem abgelaufenen früheren Termin zu und
        // stoppte sie vorzeitig. Fix: die Session-Startzeit muss
        // INNERHALB des Terminfensters liegen.
        val earlier = pinnedMatch(
            event(title = "Vorlesung", startAt = millis(10), endAt = millis(12), id = "uni:1:${millis(10)}"),
            activityTypeId = "social",
            policy = CalendarOverlapPolicy.OVERRIDE
        )
        val sessionMatch = pinnedMatch(
            event(title = "Grosseltern", startAt = millis(15), endAt = millis(18), id = "privat:1:${millis(15)}"),
            activityTypeId = "social",
            policy = CalendarOverlapPolicy.OVERRIDE
        )
        // Session startete 15:00 für den 15-18-Termin. Um 12:05 ist der
        // 10-12-Termin abgelaufen — die Session darf ihm NICHT zugeordnet
        // werden (sonst würde sie gestoppt, obwohl ihr Termin bis 18:00 läuft).
        val related = CalendarAutoRunEngine.findRelatedMatch(listOf(earlier, sessionMatch), millis(15), "social")
        assertThat(related).isSameInstanceAs(sessionMatch)
    }

    @Test
    fun `RelatedMatch - null wenn kein Match mehr existiert`() {
        // Termin im Kalender gelöscht → kein Match → Watchdog-Fallback.
        assertThat(CalendarAutoRunEngine.findRelatedMatch(emptyList(), millis(15), "social")).isNull()
    }

    @Test
    fun `RelatedMatch - andere Aktivitaet gehoert nicht zum Termin`() {
        val match = pinnedMatch(event(), activityTypeId = "social")
        assertThat(CalendarAutoRunEngine.findRelatedMatch(listOf(match), millis(15), "fitness")).isNull()
    }
}