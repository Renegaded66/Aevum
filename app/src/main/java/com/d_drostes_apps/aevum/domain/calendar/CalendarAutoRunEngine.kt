package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.ActivitySession
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
     * M18.134: SourceType aller vom Kalender gestarteten Sessions.
     *
     * Liegt seit dieser Welle hier (domain) statt nur im Worker: die
     * Resume-Prüfung [wasDisplacedByForeignSession] vergleicht die Quelle
     * der Vorgänger-Session, und die Engine ist ein reines JVM-Objekt —
     * ein Import aus `automation` würde die Schichtung brechen.
     * [com.d_drostes_apps.aevum.automation.calendar.CalendarAutoRunWorker]
     * verweist auf dieselbe Konstante.
     */
    const val SOURCE_CALENDAR = "CALENDAR_AUTO"

    /**
     * M18.134: Wie viele beendete Kalender-Sessions für die
     * Wiedereinstiegs-Evidenz betrachtet werden ([displacedEventIds]).
     *
     * Warum mehr als eine: Bei mehreren Konflikten hintereinander
     * (Fahrt → Wanderung → Fahrt) liegen mehrere abgeschnittene Sessions
     * im Verlauf, und die jüngste kann zu einem bereits beendeten Termin
     * gehören. 8 deckt einen realistischen Tag ab (eine Query über den
     * Index `source_type+start_at`, kein Tabellen-Scan). Liegt hier statt
     * im Worker, weil der Datenbedarf zur Evidenz-Logik gehört und so
     * auch aus JVM-Tests ohne Android-Paket nutzbar ist.
     */
    const val RESUME_EVIDENCE_LOOKBACK = 8

    /**
     * M18.134: Toleranz beim Verdrängungs-Beweis.
     *
     * Der Trim in [com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager]
     * schreibt das Ende der verdrängten Session exakt auf die Startzeit der
     * übernehmenden Session (`endAt = newStart`). Der Beweis lautet deshalb:
     * es gibt eine fremde Session, die genau dort begann. Ein paar
     * Millisekunden Spielraum decken getrennte `System.currentTimeMillis()`-
     * Aufrufe ab; mehr wäre unscharf (ein manueller Stop und ein Minuten
     * späterer Auto-Start sind zwei verschiedene Ereignisse).
     */
    const val DISPLACEMENT_WITNESS_TOLERANCE_MS = 2_000L

    /**
     * M18.134: Ein abgeschnittener Termin als KANDIDAT der Wiederaufnahme.
     *
     * [cutAtMs] ist der Zeitpunkt, an dem die Kalender-Session endete — also
     * die Stelle, an der eine fremde Session übernommen haben MUSS, wenn es
     * eine Verdrängung war. Der Nachweis selbst braucht die Datenbank
     * (`DISPLACEMENT_WITNESS_TOLERANCE_MS`) und bleibt beim Aufrufer; diese
     * Struktur ist das reine, testbare Zwischenergebnis.
     */
    data class DisplacedMarker(val eventId: String, val cutAtMs: Long)

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
     * M18.134: Soll ein Termin nach einer VERDRÄNGUNG wieder aufgenommen
     * werden? (Kanban t_0bf5541e — gemeldeter Fall: „Während dem
     * Kalendereintrag Auto gefahren → Kalendereintrag gestoppt, danach
     * nicht wieder weitergeführt, obwohl der Termin noch lief.")
     *
     * DIE REGEL DES NUTZERS wörtlich: „der Kalendereintrag soll immer
     * laufen, wenn nichts anderes läuft". Für einen Termin, der bereits
     * aufgezeichnet wurde und dann von einer Fremd-Session abgeschnitten
     * wurde, ist das ein Wiedereinstieg — und der ist nur an zwei harten
     * Grenzen zu messen:
     *  - der Termin hat begonnen,
     *  - der Termin ist noch nicht vorbei.
     *
     * WARUM NICHT [shouldStart] (die 20-Minuten-Toleranz)? Die Toleranz
     * beantwortet eine ANDERE Frage: „war der Nutzer zum Terminbeginn
     * überhaupt da?" (Handy aus, verspäteter Worker-Lauf). Nach einer
     * Verdrängung ist diese Frage bereits beantwortet — es GIBT eine
     * Session für diesen Termin, sie wurde nur abgeschnitten. Ohne dieses
     * eigene Prädikat wäre ein Wiedereinstieg nach 20 Minuten Terminlauf
     * grundsätzlich unmöglich (genau der gemeldete Fehler).
     *
     * WARUM NICHT EINFACH [shouldStartQueued]? Weil „verdrängt" bewiesen
     * sein muss. QUEUE_IF_BUSY gilt für Termine, deren Warteschlangen-
     * Semantik der Nutzer ausdrücklich gewählt hat. Ein OVERRIDE-Termin
     * (Standard) sagt: „übernimm, warte NICHT". Ihm die Warteschlange
     * anzudichten, würde jeden 8-Stunden-Termin beim Einschalten des
     * Handys mitten am Nachmittag starten — eine Aufzeichnung ohne
     * Anlass. Der Unterschied ist [displaced]: nur wer nachweislich
     * aufgezeichnet und dann abgeschnitten wurde, darf nachholen.
     *
     * @param displaced true, wenn dieser Termin bereits eine Kalender-
     *        Session hatte, die von einer anderen Session abgeschnitten
     *        wurde (Evidenz über [wasDisplacedByForeignSession]).
     */
    fun shouldResumeAfterDisplacement(
        event: CalendarEventCache,
        now: Long,
        displaced: Boolean
    ): Boolean = displaced && now >= event.startAt && now < event.endAt

    /**
     * M18.134: Wurde ein Termin verdrängt — und WELCHER? Liefert die
     * `eventId` des Termins, dessen Kalender-Session abgeschnitten wurde,
     * oder null (keine Evidenz).
     *
     * Das ist die fehlende Evidenz, die „Resume" von „Handy war aus"
     * trennt (Root-Cause-Report t_61143053, Integrationspunkt A). Ein
     * Wiedereinstieg ist nur dann gerechtfertigt, wenn für DENSELBEN
     * Termin schon eine Kalender-Session existierte und diese NICHT an
     * ihrem natürlichen Terminende endete:
     *
     *  - Die beendete Session gehört zu einem der Matches — geprüft über
     *    [findRelatedMatch], also EXAKT dieselbe Zuordnungsregel wie im
     *    Stop-Pfad und im Doppelstart-Schutz (keine zweite Wahrheit).
     *  - Ihr Ende liegt VOR dem Termin-Ende → sie wurde abgeschnitten.
     *    Hätte sie bis zum Ende laufen dürfen, wäre sie vom Kalender-Stop
     *    dort beendet worden („Auto-Start impliziert Auto-Stop") und der
     *    Termin wäre vollständig. Der Kalender stoppt seine eigene Session
     *    nie vorzeitig (STOP_GRACE_MS = 0) — ein früheres Ende stammt also
     *    von einer fremden Übernahme (der Drive-Start trimmt die laufende
     *    Session exakt bis zu seinem Start, siehe LiveActivityManager).
     *
     * WICHTIG — Fehlerrichtung: Ein fälschlich angenommenes „verdrängt"
     * startet eine Aufzeichnung mitten im Termin (störend, aber harmlos
     * und durch Doppelstart-Schutz/Trimming gegen Überlappung gesichert).
     * Ein fälschlich verneintes „verdrängt" lässt den Termin leer — genau
     * der gemeldete Fehler. Die Prüfung ist deshalb großzügig in Richtung
     * „verdrängt", aber nie ohne eine echte, zum Termin gehörende
     * Vorgänger-Session.
     *
     * @param lastFinishedSession Letzte BEENDETE Kalender-Session
     *        (`getLastFinishedBySourceType(SOURCE_CALENDAR)`).
     *        Null = es gab nie eine → nichts wiederaufzunehmen.
     */
    fun displacedEventId(
        matches: List<CalendarMatch>,
        lastFinishedSession: ActivitySession?
    ): String? = displacedEventIds(matches, listOfNotNull(lastFinishedSession)).firstOrNull()

    /**
     * M18.134: Wie [displacedEventId], aber über einen VERLAUF beendeter
     * Sessions — für den Fall mehrerer Konflikte hintereinander.
     *
     * Warum der Verlauf nötig ist: Bei „Fahrt → Wanderung → Fahrt" liegen
     * mehrere abgeschnittene Kalender-Sessions hintereinander. Die jeweils
     * JÜNGSTE kann zu einem bereits beendeten Termin gehören; wird nur sie
     * betrachtet, bliebe ein ÄLTERER, noch laufender Termin unversorgt.
     * Geliefert wird deshalb ALLE abgeschnittenen Events, jüngste zuerst.
     *
     * ACHTUNG — nur KANDIDATEN: „abgeschnitten" allein beweist noch keine
     * Verdrängung. Ein ABGESCHNITTENER Block entsteht auch, wenn der Nutzer
     * selbst stoppt, pausiert oder die Aktivität wechselt. Der positive
     * Nachweis (eine fremde Session begann exakt an der Schnittstelle) ist
     * [DisplacedMarker] + `DISPLACEMENT_WITNESS_TOLERANCE_MS` und wird vom
     * Aufrufer geführt (er braucht dafür die Datenbank).
     */
    fun displacedEventIds(
        matches: List<CalendarMatch>,
        recentFinishedSessions: List<ActivitySession>
    ): List<String> = displacedMarkers(matches, recentFinishedSessions).map { it.eventId }

    /**
     * M18.134: Die abgeschnittenen Termine samt Schnittstelle — Grundlage
     * für den Verdrängungs-Beweis.
     *
     * @see displacedEventIds
     */
    fun displacedMarkers(
        matches: List<CalendarMatch>,
        recentFinishedSessions: List<ActivitySession>
    ): List<DisplacedMarker> =
        recentFinishedSessions
            .asSequence()
            .filter { it.sourceType == SOURCE_CALENDAR }
            .filter { it.endAt != null }
            .mapNotNull { session ->
                val related = findRelatedMatch(matches, session.startAt, session.activityTypeId)
                    ?: return@mapNotNull null
                // Wurde sie abgeschnitten? (Ende vor dem Termin-Ende.)
                related.event.eventId
                    .takeIf { session.endAt!! < related.event.endAt }
                    ?.let { DisplacedMarker(it, session.endAt!!) }
            }
            .distinctBy { it.eventId }
            .toList()

    /**
     * M18.134: Ist der Termin JETZT als „Fallback" fällig — d. h. er darf
     * starten, sobald nichts anderes läuft?
     *
     * Diese Zusammenfassung ist die einzige Stelle, an der die drei
     * Fälligkeits-Gründe zusammenlaufen: regulär ([shouldStart]),
     * Warteschlange ([shouldStartQueued]) und Wiedereinstieg nach
     * Verdrängung ([shouldResumeAfterDisplacement]). Der Worker und
     * [pickStartCandidate] rufen nur noch hier an — die Policy-Logik
     * ([CalendarMatch.shouldQueueWhenBusy]) bleibt unangetastet.
     *
     * WICHTIG: [displacedEventIds] ist eine Liste von EVENT-IDs, kein Bool.
     * Bei überlappenden Terminen („multiple conflicting recordings") darf
     * der Wiedereinstieg nur Termine treffen, die nachweislich
     * abgeschnitten wurden — sonst würde ein Nachbartermin mitgerissen,
     * für den jede Evidenz fehlt.
     */
    fun isFallbackDue(
        match: CalendarMatch,
        now: Long,
        displacedEventIds: Collection<String>
    ): Boolean {
        if (match.shouldQueueWhenBusy) return shouldStartQueued(match.event, now)
        val displaced = match.event.eventId in displacedEventIds
        return shouldResumeAfterDisplacement(match.event, now, displaced)
    }

    /**
     * M18.134: Mit welcher Startzeit beginnt die Session für diesen Match?
     *
     * DIE REGEL (bewusst nur zwei Fälle):
     *  - **Wiedereinstieg** ([displaced]): Startzeit = JETZT. In der Zeit
     *    vor dem Wiedereinstieg lief nachweislich eine andere Aufzeichnung
     *    (die verdrängende Session — und davor der abgeschnittene
     *    Kalender-Block). Eine Rückdatierung auf den Terminbeginn würde
     *    dieselbe Zeit ein zweites Mal belegen und die Timeline belügen
     *    (M18.132-Lektion „Ehrlichkeit der Daten > optische Termin-Treue",
     *    hier verschärft: es entstünde eine echte Überlappung).
     *  - **Alles andere**: unverändert der Termin-Beginn (M18.70-Muster
     *    „rückwirkende Startzeit bei Vorlauf"), außer beim QUEUE-Nachholer
     *    nach Ablauf der Start-Toleranz → JETZT (M18.132).
     */
    fun startAnchorMs(
        match: CalendarMatch,
        now: Long,
        displaced: Boolean
    ): Long = when {
        displaced -> now
        match.shouldQueueWhenBusy && now - match.event.startAt > START_TOLERANCE_MS -> now
        else -> match.event.startAt
    }

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
     *
     * M18.134 (Kanban t_0bf5541e) — DIE AUSNAHME ZU DIESEM SATZ: Ein
     * OVERRIDE/ONLY_IF_IDLE-Termin, der nachweislich SCHON AUFGEZEICHNET
     * und dann abgeschnitten wurde, wird sehr wohl nachgeholt
     * ([isFallbackDue] + [displacedEventId]). „Nicht warten" verbietet
     * nicht das Wiederaufnehmen einer eigenen, zerstörten Aufzeichnung —
     * es verbietet nur, auf eine FREMDE Session zu warten, die noch läuft.
     * Genau diese Semantik hat der Nutzer für den Kalender gefordert:
     * der Termin läuft immer, wenn nichts anderes läuft.
     *
     * @param displacedEventIds Termine, deren Session nachweislich
     *        abgeschnitten wurde ([displacedEventIds]) — nur SIE dürfen den
     *        Wiedereinstieg nutzen, damit ein Nachbartermin bei
     *        überlappenden Terminen nicht mitgerissen wird.
     */
    fun pickStartCandidate(
        matches: List<CalendarMatch>,
        now: Long,
        lastStartedEventId: String?,
        displacedEventIds: Collection<String> = emptyList()
    ): CalendarMatch? {
        val due = matches.asSequence()
            // M18.132: Tote Matches (Aktivität gelöscht) können nichts
            // starten und dürfen nichts blockieren.
            .filter { it.activityTypeId != null }
            .filter { m ->
                shouldStart(m.event, now, lastStartedEventId) ||
                    isFallbackDue(m, now, displacedEventIds)
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
