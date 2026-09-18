package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M18.129: Tests für die Auto-Start/Stop-Entscheidungen.
 *
 * Die kritischen Fälle:
 *  - verspäteter Worker-Lauf (WorkManager ist inexakt)
 *  - zu spät (Handy war aus) → KEIN nachträglicher Start
 *  - Ende-Schutz (Lauf knapp vor dem Terminende)
 *  - nächste Weckzeit = nächste Termingrenze, nie 0 (Schleifen-Schutz)
 */
class CalendarAutoRunEngineTest {

    private val base = 1_700_000_000_000L // fester Bezugspunkt
    private val minute = 60_000L

    private fun event(
        startAt: Long,
        endAt: Long,
        id: String = "e1"
    ) = CalendarEventCache(
        eventId = id,
        calendarId = "1",
        calendarName = "Uni",
        title = "Vorlesung",
        description = null,
        location = null,
        startAt = startAt,
        endAt = endAt,
        allDay = false,
        attendees = null,
        syncedAt = 0L
    )

    private fun match(
        startAt: Long,
        endAt: Long,
        priority: Int = 0,
        id: String = "e1",
        activityTypeId: String = "studium"
    ) = CalendarMatch(
        event = event(startAt, endAt, id),
        rule = CalendarRule(id = "r1", name = "Studium", activityTypeId = activityTypeId, priority = priority)
    )

    /** M18.131: Match aus einer manuellen Markierung (ohne Regel). */
    private fun pinnedMatch(
        startAt: Long,
        endAt: Long,
        id: String = "e1",
        activityTypeId: String = "fitness"
    ) = CalendarMatch(
        event = event(startAt, endAt, id),
        pin = com.d_drostes_apps.aevum.data.model.CalendarEventPin(
            eventId = event(startAt, endAt, id).eventId,
            activityTypeId = activityTypeId,
            eventStartAt = startAt,
            eventEndAt = endAt,
            eventTitle = "Termin"
        )
    )

    // ── shouldStart ───────────────────────────────────────────────────

    @Test
    fun `puenktlicher Lauf startet`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStart(e, base)).isTrue()
    }

    @Test
    fun `verspaeteter Lauf innerhalb der Toleranz startet`() {
        val e = event(base, base + 60 * minute)
        // 10 Minuten zu spät — WorkManager ist inexakt, das ist normal.
        assertThat(CalendarAutoRunEngine.shouldStart(e, base + 10 * minute)).isTrue()
    }

    @Test
    fun `zu spaeter Lauf startet NICHT`() {
        val e = event(base, base + 180 * minute)
        // 30 Minuten zu spät (> 20 min Toleranz) → kein nachtraeglicher Start.
        assertThat(CalendarAutoRunEngine.shouldStart(e, base + 30 * minute)).isFalse()
    }

    @Test
    fun `Lauf vor dem Terminbeginn startet nicht`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStart(e, base - minute)).isFalse()
    }

    @Test
    fun `bereits abgelaufener Termin startet nicht`() {
        // 1-Minuten-Termin, Lauf 5 Minuten spaeter → wuerde sofort stoppen.
        val e = event(base, base + minute)
        assertThat(CalendarAutoRunEngine.shouldStart(e, base + 5 * minute)).isFalse()
    }

    @Test
    fun `bereits gestarteter Termin wird nicht doppelt gestartet`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStart(e, base + minute, lastStartedEventId = "e1")).isFalse()
        assertThat(CalendarAutoRunEngine.shouldStart(e, base + minute, lastStartedEventId = "e2")).isTrue()
    }

    // ── shouldStop ────────────────────────────────────────────────────

    @Test
    fun `Stop erst nach dem Terminende`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStop(e, base + 30 * minute)).isFalse()
    }

    @Test
    fun `Stop greift am Terminende`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStop(e, base + 60 * minute)).isTrue()
    }

    @Test
    fun `Stop greift nicht vor dem Terminende`() {
        val e = event(base, base + 60 * minute)
        // 30 s vor dem Ende: KEIN Stop — eine zu frühe Beendigung würde
        // die Aufzeichnung abschneiden (schlimmere Fehlerrichtung).
        assertThat(CalendarAutoRunEngine.shouldStop(e, base + 60 * minute - 30_000)).isFalse()
    }

    @Test
    fun `Stop greift minimal nach dem Terminende`() {
        val e = event(base, base + 60 * minute)
        // 30 s nach dem Ende: Stop (die Aufzeichnung ist vollständig).
        assertThat(CalendarAutoRunEngine.shouldStop(e, base + 60 * minute + 30_000)).isTrue()
    }

    @Test
    fun `Stop greift deutlich nach dem Terminende`() {
        val e = event(base, base + 60 * minute)
        assertThat(CalendarAutoRunEngine.shouldStop(e, base + 3 * 60 * minute)).isTrue()
    }

    // ── nextWakeUpDelayMs ─────────────────────────────────────────────

    @Test
    fun `naechster Lauf liegt auf der naechsten Termingrenze`() {
        val now = base
        val events = listOf(event(base + 30 * minute, base + 90 * minute))
        val delay = CalendarAutoRunEngine.nextWakeUpDelayMs(events, now, maxDelayMs = 60 * minute)
        // Die naechste Grenze ist der Start in 30 Minuten — nicht das Maximum.
        assertThat(delay).isEqualTo(30 * minute)
    }

    @Test
    fun `naechster Lauf nutzt das Ende wenn es naeher liegt`() {
        val now = base
        // Termin laeuft seit 10 min, endet in 5 min.
        val events = listOf(event(base - 10 * minute, base + 5 * minute))
        val delay = CalendarAutoRunEngine.nextWakeUpDelayMs(events, now, maxDelayMs = 60 * minute)
        assertThat(delay).isEqualTo(5 * minute)
    }

    @Test
    fun `ohne Termine gilt das Maximum`() {
        val delay = CalendarAutoRunEngine.nextWakeUpDelayMs(emptyList(), base, maxDelayMs = 15 * minute)
        assertThat(delay).isEqualTo(15 * minute)
    }

    @Test
    fun `Delay ist nie null - Schleifenschutz`() {
        // Grenze genau JETZT → darf nicht 0 werden (Endlos-Schleife).
        val events = listOf(event(base, base + 60 * minute))
        val delay = CalendarAutoRunEngine.nextWakeUpDelayMs(events, base, maxDelayMs = 15 * minute)
        assertThat(delay).isAtLeast(1_000L)
    }

    @Test
    fun `Delay wird auf das Maximum gedeckelt`() {
        // Naechste Grenze in 10 Stunden, Deckel bei 15 Minuten.
        val events = listOf(event(base + 600 * minute, base + 660 * minute))
        val delay = CalendarAutoRunEngine.nextWakeUpDelayMs(events, base, maxDelayMs = 15 * minute)
        assertThat(delay).isEqualTo(15 * minute)
    }

    // ── pickStartCandidate ────────────────────────────────────────────

    @Test
    fun `hoehere Prioritaet gewinnt bei zwei faelligen Terminen`() {
        val now = base + minute
        val low = match(base, base + 60 * minute, priority = 0, id = "low")
        val high = match(base, base + 60 * minute, priority = 10, id = "high")
        val picked = CalendarAutoRunEngine.pickStartCandidate(listOf(low, high), now, null)
        assertThat(picked?.event?.eventId).isEqualTo("high")
    }

    @Test
    fun `bei gleicher Prioritaet gewinnt der spaetere Beginn`() {
        val now = base + 5 * minute
        val frueh = match(base, base + 60 * minute, priority = 0, id = "frueh")
        val spaet = match(base + 3 * minute, base + 60 * minute, priority = 0, id = "spaet")
        val picked = CalendarAutoRunEngine.pickStartCandidate(listOf(frueh, spaet), now, null)
        assertThat(picked?.event?.eventId).isEqualTo("spaet")
    }

    @Test
    fun `kein Kandidat wenn nichts faellig ist`() {
        val now = base
        val future = match(base + 60 * minute, base + 120 * minute, id = "future")
        assertThat(CalendarAutoRunEngine.pickStartCandidate(listOf(future), now, null)).isNull()
    }

    @Test
    fun `leere Liste ergibt keinen Kandidaten`() {
        assertThat(CalendarAutoRunEngine.pickStartCandidate(emptyList(), base, null)).isNull()
    }

    // ── M18.131: manuelle Markierung gewinnt beim Doppel-Start ────────

    /**
     * Zwei Termine sind gleichzeitig fällig: einer per Regel, einer vom
     * Nutzer markiert. Der markierte MUSS gewinnen — auch wenn die Regel
     * eine höhere Priorität trägt. Der Auftrag verlangt den Vorrang des
     * Benutzerdefinierten; ohne diese Zusicherung wäre er beim
     * Doppel-Start-Fall wieder verloren.
     */
    @Test
    fun `manuell markierter Termin gewinnt gegen Regel mit hoeherer Prioritaet`() {
        val now = base + minute
        val ruleMatch = match(base, base + 60 * minute, priority = 10, id = "regel")
        val pinMatch = pinnedMatch(base, base + 60 * minute, id = "markiert")
        val picked = CalendarAutoRunEngine.pickStartCandidate(listOf(ruleMatch, pinMatch), now, null)
        assertThat(picked?.event?.eventId).isEqualTo("markiert")
        assertThat(picked?.isUserPinned).isTrue()
    }

    @Test
    fun `manuell markierter Termin gewinnt auch bei gleichem Beginn`() {
        val now = base + 5 * minute
        // Der Regel-Termin beginnt SPÄTER — nach der alten Regel „späterer
        // Beginn gewinnt" hätte er den markierten verdrängt.
        val ruleMatch = match(base + 3 * minute, base + 60 * minute, priority = 0, id = "regel")
        val pinMatch = pinnedMatch(base, base + 60 * minute, id = "markiert")
        val picked = CalendarAutoRunEngine.pickStartCandidate(listOf(ruleMatch, pinMatch), now, null)
        assertThat(picked?.event?.eventId).isEqualTo("markiert")
    }

    /**
     * Gegenprobe: ohne Markierung funktioniert die alte Prioritäts-Ordnung
     * unverändert (die Anpassung hat das Bestandsverhalten nicht gekippt).
     */
    @Test
    fun `ohne Markierung ordnen Regeln weiter nach Prioritaet`() {
        val now = base + minute
        val low = match(base, base + 60 * minute, priority = 0, id = "low")
        val high = match(base, base + 60 * minute, priority = 10, id = "high")
        val picked = CalendarAutoRunEngine.pickStartCandidate(listOf(low, high), now, null)
        assertThat(picked?.event?.eventId).isEqualTo("high")
        assertThat(picked?.isUserPinned).isFalse()
    }
}
