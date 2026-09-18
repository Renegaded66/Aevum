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
     * M18.132: Soll ein QUEUE_IF_BUSY-Termin JETZT nachgestartet werden?
     *
     * WARUM EINE EIGENE FUNKTION STATT shouldStart: Der QUEUE-Termin
     * wartet, bis nichts mehr aufgezeichnet wird. Das kann Minuten oder
     * Stunden nach dem Termin-Beginn sein — die [START_TOLERANCE_MS]
     * (20 min) der regulären Start-Regel wäre für den Nachholer tödlich:
     * er wäre nach 20 Minuten „zu spät" und würde für immer nicht
     * gestartet, obwohl sein Termin noch läuft. Der Nachholer braucht
     * also keine Begin-Toleranz, sondern nur die zwei harten Grenzen:
     *  - Termin hat begonnen,
     *  - Termin ist noch nicht vorbei (ein Nachholen nach Terminende
     *    wäre eine Aufzeichnung ohne Terminkontext — nicht gewollt).
     */
    fun shouldStartQueued(event: CalendarEventCache, now: Long): Boolean =
        now >= event.startAt && now < event.endAt

    /**
     * M18.132: Findet den Match, zu dem die LAUFENDE Kalender-Session
     * gehört — einheitlich für den Stop-Pfad und den Doppelstart-Schutz.
     *
     * BIS M18.131 (zwei Alt-Fehler, beide gefunden, weil der QUEUE-Nachholer
     * sie offengelegt hat):
     *  1. Der Stop-Pfad suchte den „zugehörigen" Termin über Aktivitäts-Typ
     *     ODER Session-Titel und stoppte den ERSTEN Treffer. Gehören zwei
     *     Terminen dieselbe Aktivität („Soziales" für Grosseltern 15-18 UND
     *     für Kino 20-22), wurde die laufende Session am Ende des FALSCHEN
     *     Termins gestoppt.
     *  2. Der QUEUE-Nachholer startet zum Freiwerde-Zeitpunkt (z. B. 16:30),
     *     nicht zum Termin-Beginn (15:00) — die alte Zuordnung über
     *     Typ/Titel hätte ihn gefunden, aber die Zeitfenster-Prüfung im
     *     Doppelstart-Schutz (delta < 5 min zum Termin-Beginn) hätte ihn
     *     fälschlich als NEU eingestuft.
     *
     * DIE REGEL: Erst alle Matches sammeln, in deren [startAt, endAt]-Fenster
     * die Session-Startzeit liegt (das deckt pünktliche Starts, verspätete
     * Worker-Läufe UND QUEUE-Nachholer ab). Liegen MEHRERE im Fenster
     * (überlappende Termine), gewinnt der mit dem nächsten Termin-Beginn
     * zur Session-Startzeit — der pünktlich gestartete Termin hat exakt
     * delta 0; ein nachträglicher Nachbar hat immer ein größeres Delta.
     * Bei exakt gleichem Beginn (wirklich ununterscheidbar) gewinnt das
     * SPÄTERE Termin-Ende: ein zu früher Stop schneidet Daten ab (die
     * schlimmere Fehlerrichtung, siehe STOP_GRACE_MS).
     *
     * @param sessionStartAt Die Startzeit der laufenden Session (Auto-Starts
     *        setzen sie auf Termin-Beginn; QUEUE-Nachholer auf den
     *        Freiwerde-Zeitpunkt — beides liegt im Terminfenster).
     * @param activityTypeId Typ der laufenden Session — zusätzliche
     *        Absicherung gegen Fehl-Zuordnung. Null auf EINER Seite
     *        überspringt den Typ-Vergleich (gelöschte Aktivität mitten in
     *        der Aufzeichnung darf den Auto-Stop nicht verhindern — sonst
     *        liefe die Session bis zum 8-Stunden-Watchdog).
     */
    fun findRelatedMatch(
        matches: List<CalendarMatch>,
        sessionStartAt: Long,
        activityTypeId: String?
    ): CalendarMatch? {
        val inWindow = matches.filter { m ->
            m.event.startAt <= sessionStartAt && sessionStartAt < m.event.endAt &&
                (m.activityTypeId == null || activityTypeId == null ||
                    m.activityTypeId == activityTypeId)
        }
        if (inWindow.isEmpty()) return null
        return inWindow.minByOrNull { kotlin.math.abs(sessionStartAt - it.event.startAt) }
            ?.let { closest ->
                // Gleichstand mehrerer Starts (selbe Beginnzeit): das
                // spätere Ende gewinnen lassen — Datenabzug ist schlimmer
                // als ein paar Minuten Nachlauf.
                val sameStart = inWindow.filter {
                    kotlin.math.abs(sessionStartAt - it.event.startAt) ==
                        kotlin.math.abs(sessionStartAt - closest.event.startAt)
                }
                sameStart.maxByOrNull { it.event.endAt } ?: closest
            }
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
     * M18.129: Wählt unter mehreren fälligen Terminen den „richtigen" Start.
     *
     * M18.132: Drei Erweiterungen, alle aus der QUEUE-Policy begründet:
     *
     *  1. TOTER MATCH-FILTER: Matches ohne Aktivität (Aktivität gelöscht,
     *     ON DELETE SET NULL) werden übersprungen statt zu gewinnen —
     *     vorher konnte ein toter Match das Feld blockieren und einen
     *     startbaren Termin verdrängen.
     *
     *  2. QUEUE-KANDIDATEN: Ein QUEUE_IF_BUSY-Termin, dessen Beginn länger
     *     als die 20-Minuten-Toleranz zurückliegt, ist über [shouldStart]
     *     unerreichbar — aber genau dann soll er ja erst recht starten
     *     können, sobald nichts mehr läuft. Die Fälligkeit läuft deshalb
     *     über [shouldStartQueued].
     *
     *  3. VORRANG REGULÄR VOR WARTESCHLANGE: Sind ein regulärer
     *     (OVERRIDE/ONLY_IF_IDLE) und ein wartender QUEUE-Kandidat
     *     gleichzeitig startbar, gewinnt der reguläre — dessen
     *     20-Minuten-Fenster schließt sich, die Warteschlange hat Zeit.
     *     Erst danach folgen die QUEUE-Kandidaten (FIFO: längste
     *     Wartezeit zuerst).
     *
     * NACHHOL-SEMANTIK (ergibt sich aus 2 + 3, bewusst so): Wird ein
     * QUEUE-Termin von einem OVERRIDE-Termin verdrängt, startet er
     * automatisch NACH, sobald die Übernahme endet und der Termin noch
     * läuft — „beginnt, sobald keine Aufzeichnung mehr läuft" gilt damit
     * auch mitten im Termin. Ein OVERRIDE-Termin dagegen wird nach
     * Verdrängung NICHT nachgeholt (seine Policy sagt: übernehmen, nicht
     * warten).
     */
    fun pickStartCandidate(
        matches: List<CalendarMatch>,
        now: Long,
        lastStartedEventId: String?
    ): CalendarMatch? {
        val due = matches.asSequence()
            // M18.132: Tote Matches (Aktivität gelöscht) können nichts
            // starten und dürfen nichts blockieren.
            .filter { it.activityTypeId != null }
            .filter { m ->
                shouldStart(m.event, now, lastStartedEventId) ||
                    (m.shouldQueueWhenBusy && shouldStartQueued(m.event, now))
            }
            .toList()

        val regular = due.filter { !it.shouldQueueWhenBusy }
        return if (regular.isNotEmpty()) {
            // M18.129/M18.131-Ordnung: höchste Priorität, bei Gleichstand
            // der spätere Beginn (der spezifischere, gerade angefangene
            // Termin).
            regular.maxWithOrNull(
                compareBy<CalendarMatch> { it.effectivePriority }
                    .thenBy { it.event.startAt }
            )
        } else {
            // M18.132: Warteschlange — FIFO innerhalb gleicher Priorität
            // (frühester wartender Termin zuerst, gerecht statt zufällig).
            due.maxWithOrNull(
                compareBy<CalendarMatch> { it.effectivePriority }
                    .thenBy { -it.event.startAt }
            )
        }
    }
}
