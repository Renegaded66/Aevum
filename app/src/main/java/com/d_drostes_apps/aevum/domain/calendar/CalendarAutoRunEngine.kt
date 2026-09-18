package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache

/**
 * M18.129: Entscheidungslogik für Auto-Start / Auto-Stop.
 *
 * REINE FUNKTIONEN (kein Android, kein Room, kein Logger): nur so sind
 * die kritischen Randfälle in Unit-Tests abdeckbar — insbesondere der
 * Mitternachts-Übergang und die verspätete Ausführung durch WorkManager.
 *
 * Das Kernproblem: WorkManager feuert inexakt. Ein Termin kann um 10:15
 * beginnen, der Worker aber erst 10:18 laufen. Die Logik muss also
 * beantworten: „soll JETZT gestartet werden?" — nicht „ist gerade genau
 * der Startzeitpunkt?". Dafür gibt es ein Toleranzfenster.
 *
 * Umgekehrt genauso wichtig: ein Termin von gestern darf NICHT nachträglich
 * gestartet werden, nur weil der Cache noch einen alten Eintrag hat. Dafür
 * gibt es die Rückwärts-Grenze.
 */
object CalendarAutoRunEngine {

    /**
     * Wie weit NACH dem Termin-Start ein Start noch zulässig ist.
     *
     * 20 Minuten deckt den WorkManager-Takt (≤ 15 min) plus Reserve ab.
     * Danach wäre der Start so weit in der Vergangenheit, dass er mehr
     * Störung als Nutzen bringt (z. B. Handy war aus).
     */
    const val START_TOLERANCE_MS = 20L * 60 * 1000

    /**
     * Wie weit VOR dem Termin-Ende nicht gestoppt wird — bewusst 0.
     *
     * FRÜHERE FASSUNG (falsch): ein 60-Sekunden-Abzug am Terminende ließ
     * den Stop eine Minute ZU FRÜH greifen — die Aufzeichnung wurde
     * abgeschnitten. Das ist die schlimmere Fehlerrichtung: eine zu kurze
     * Aufzeichnung verfälscht Daten, ein paar Sekunden zu lange nicht.
     *
     * Der vermeintliche Grund für den Abzug (ein minimal früh feuernder
     * Worker-Lauf) löst sich von selbst: [nextWakeUpDelayMs] hat eine
     * Untergrenze von 1 s, sodass der Worker eine Sekunde später erneut
     * feuert und dann korrekt stoppt.
     */
    const val STOP_GRACE_MS = 0L

    /**
     * Soll der Termin JETZT gestartet werden?
     *
     * Bedingungen:
     *  - Der Termin hat bereits begonnen.
     *  - Der Beginn liegt höchstens [START_TOLERANCE_MS] zurück.
     *  - Der Termin ist noch nicht vorbei (sonst würde ein Winzling
     *    von 1 Minute gestartet und sofort gestoppt).
     *
     * @param now Aktuelle Zeit.
     * @param lastStartedEventId Bereits gestartete Session → kein Doppelstart.
     */
    fun shouldStart(
        event: CalendarEventCache,
        now: Long,
        lastStartedEventId: String? = null
    ): Boolean {
        if (event.eventId == lastStartedEventId) return false
        // Noch nicht begonnen.
        if (now < event.startAt) return false
        // Zu lange her (Handy aus, Worker verpasst).
        if (now - event.startAt > START_TOLERANCE_MS) return false
        // Schon vorbei (0-Minuten-Termin oder extrem kurzer Termin).
        if (now >= event.endAt) return false
        return true
    }

    /**
     * Soll die laufende Session JETZT gestoppt werden?
     *
     * Bedingung: der zugehörige Termin hat geendet. Bewusst OHNE Vorlauf
     * (siehe [STOP_GRACE_MS]) — ein zu früher Stop schneidet die
     * Aufzeichnung ab, ein minimal später nicht.
     *
     * WICHTIG (M18.61e-Lektion „Auto-Start impliziert Auto-Stop"): Der
     * Aufrufer muss den Stop prüfen, solange EINE Kalender-Session läuft —
     * unabhängig davon, ob der Cache aktualisiert wurde. Läuft die Session
     * länger als ihr Termin, wird sie trotzdem beendet (Watchdog).
     */
    fun shouldStop(event: CalendarEventCache, now: Long): Boolean =
        now >= (event.endAt + STOP_GRACE_MS)

    /**
     * Die nächste Zeit, zu der der Worker wieder etwas tun MUSS.
     *
     * Statt blind alle 15 Minuten zu laufen, plant der Worker den nächsten
     * Lauf auf die nächste echte Ereignis-Grenze. Das ist der Unterschied
     * zwischen „pünktlich bei 10:15" und „irgendwann zwischen 10:15 und
     * 10:30".
     *
     * @param maxDelayMs Obergrenze (WorkManager-Takt), damit ein Lauf auch
     *        dann stattfindet, wenn weit und breit kein Termin ansteht
     *        (der Nutzer könnte unterwegs einen Termin anlegen).
     * @return Verzögerung in Millisekunden (immer > 0).
     */
    fun nextWakeUpDelayMs(
        events: List<CalendarEventCache>,
        now: Long,
        maxDelayMs: Long
    ): Long {
        val candidates = mutableListOf<Long>()
        events.forEach { e ->
            if (e.startAt > now) candidates += e.startAt
            if (e.endAt > now) candidates += e.endAt
        }
        val next = candidates.filter { it > now }.minOrNull() ?: return maxDelayMs
        val delay = next - now
        // Nie 0 (WorkManager würde sofort feuern und eine Schleife bilden).
        return delay.coerceIn(1_000L, maxDelayMs)
    }

    /**
     * Wählt unter mehreren fälligen Terminen den „richtigen" Start.
     *
     * Bei überlappenden Terminen (z. B. „Übung" 10–12 und „Vorlesung"
     * 11–13) entscheidet die Regel-Priorität, sonst der spätere Beginn
     * (der spezifischere, gerade angefangene Termin).
     */
    fun pickStartCandidate(
        matches: List<CalendarMatch>,
        now: Long,
        lastStartedEventId: String?
    ): CalendarMatch? =
        matches.asSequence()
            .filter { shouldStart(it.event, now, lastStartedEventId) }
            // M18.131: Sortierung über die Match-Auflöser statt direkt über
            // rule.priority — ein Match kann aus einer manuellen Markierung
            // stammen und hat dann gar keine Regel (null). Markierungen
            // bekommen die höchste Priorität, weil eine ausdrückliche
            // Nutzer-Entscheidung über jeder Regel steht (siehe
            // CalendarMatchEngine.evaluateWithPins). Ohne diese Anpassung
            // hätte ein markierter Termin gegen einen gleichzeitig
            // anstehenden Regel-Termin verloren — genau der Vorrang, den
            // der Auftrag verlangt, wäre beim Doppel-Start verloren gegangen.
            .sortedWith(
                compareByDescending<CalendarMatch> { it.effectivePriority }
                    .thenByDescending { it.event.startAt }
            )
            .firstOrNull()
}
