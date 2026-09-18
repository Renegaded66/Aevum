package com.d_drostes_apps.aevum.domain.calendar

import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * M18.129: Die Kalender-Matching-Engine.
 *
 * Bewusst ein REINES JVM-OBJEKT (kein Android, kein Context, kein
 * Logger): nur so lässt sich die gesamte Matching-Logik in Unit-Tests
 * ohne Robolectric abdecken — inklusive der Randfälle, die auf dem Gerät
 * nur schwer reproduzierbar sind (Mitternachts-Termine, Zeitfenster,
 * Wochentage, Groß-/Kleinschreibung).
 *
 * Es gibt KEIN Fuzzy-Matching. Der Nutzer definiert explizit, welche
 * Wörter zu welcher Aktivität führen — nur so ist das Verhalten
 * vorhersagbar und in der Timeline-Vorschau ehrlich darstellbar.
 */
object CalendarMatchEngine {

    /** Kommagetrennte/Strichpunkt-getrennte Suchwörter zerlegen. */
    fun splitTerms(raw: String): List<String> =
        raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Prüft eine Regel gegen einen Termin.
     *
     * @param weekdayOverride Für Tests: erzwingt den Wochentag (statt den
     *        des Termin-Starts). Ohne Override wird der Wochentag des
     *        Termin-Beginns in [zone] verwendet.
     */
    fun matches(
        rule: CalendarRule,
        event: CalendarEventCache,
        zone: ZoneId = ZoneId.systemDefault(),
        weekdayOverride: DayOfWeek? = null
    ): Boolean {
        if (!rule.enabled) return false
        // Regeln ohne (noch) gültige Aktivität sind inert statt crashend —
        // ON DELETE SET NULL kann den Typ jederzeit entfernen (M18.51).
        if (rule.activityTypeId == null) return false
        // Vergangenes ignorieren ist Aufgabe der Aufrufer; hier zählt nur
        // die inhaltliche Bedingung.
        if (!matchesType(rule, event)) return false
        if (!matchesDuration(rule, event)) return false
        if (!matchesWeekday(rule, event, zone, weekdayOverride)) return false
        if (!matchesTimeWindow(rule, event, zone)) return false
        return true
    }

    /** Die inhaltliche Bedingung (Typ-spezifisch). */
    private fun matchesType(rule: CalendarRule, event: CalendarEventCache): Boolean =
        when (rule.matchType) {
            CalendarRuleType.ANY_FIELD_CONTAINS ->
                containsTerms(rule, listOf(event.title, event.description ?: ""))

            CalendarRuleType.TITLE_CONTAINS ->
                containsTerms(rule, listOf(event.title))

            CalendarRuleType.DESCRIPTION_CONTAINS ->
                containsTerms(rule, listOf(event.description ?: ""))

            CalendarRuleType.TITLE_REGEX ->
                regexMatches(rule, event.title)

            CalendarRuleType.CALENDAR_IS ->
                calendarMatches(rule, event)

            CalendarRuleType.ATTENDEE_CONTAINS ->
                containsTerms(rule, listOf(event.attendees ?: ""))

            CalendarRuleType.ALL_DAY_ONLY ->
                event.allDay

            // Unbekannter Typ (z. B. aus einer künftigen Version) →
            // trifft nichts, statt eine Ausnahme zu werfen.
            else -> false
        }

    /**
     * Wortsuche über mehrere Felder.
     *
     * Semantik (bewusst deckungsgleich mit der UI-Beschreibung):
     *  - ODER (Default): EIN Suchwort irgendwo genügt.
     *  - UND (`requireAllWords`): JEDES Suchwort muss vorkommen —
     *    die Wörter dürfen dabei über die Felder verteilt sein
     *    („Vorlesung" im Titel + „Übung" in der Beschreibung = Treffer).
     */
    private fun containsTerms(rule: CalendarRule, fields: List<String>): Boolean {
        val terms = splitTerms(rule.matchValue)
        if (terms.isEmpty()) return false
        val haystacks = fields.map { if (rule.caseSensitive) it else it.lowercase() }
        val needles = terms.map { if (rule.caseSensitive) it else it.lowercase() }

        return if (rule.requireAllWords) {
            needles.all { needle -> haystacks.any { it.contains(needle) } }
        } else {
            needles.any { needle -> haystacks.any { it.contains(needle) } }
        }
    }

    /** Regex-Matching. Ungültige Muster treffen nichts (statt zu crashen). */
    private fun regexMatches(rule: CalendarRule, title: String): Boolean {
        val pattern = rule.matchValue.trim()
        if (pattern.isEmpty()) return false
        return try {
            val options = if (rule.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(pattern, options).containsMatchIn(title)
        } catch (_: Exception) {
            // Ein unvollständiges Regex (z. B. "(" während des Tippens)
            // darf niemals die Aufzeichnung oder die Vorschau sprengen.
            false
        }
    }

    /**
     * Kalender-Auswahl. [CalendarRule.matchCalendarIds] ist ein JSON-Array
     * der Kalender-IDs. Fehlt/ist es leer, gilt jeder Kalender als Treffer
     * (das erspart dem Nutzer das Abwählen jedes einzelnen Kalenders).
     */
    private fun calendarMatches(rule: CalendarRule, event: CalendarEventCache): Boolean {
        val ids = parseIds(rule.matchCalendarIds)
        if (ids.isEmpty()) return true
        return event.calendarId in ids
    }

    /** Robustes Parsen des JSON-Arrays — kaputte Daten = leere Liste. */
    fun parseIds(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Serialisiert eine Auswahl für [CalendarRule.matchCalendarIds]. */
    fun serializeIds(ids: Collection<String>): String {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun matchesDuration(rule: CalendarRule, event: CalendarEventCache): Boolean {
        if (rule.minDurationMinutes <= 0) return true
        val minutes = ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(event.startAt),
            Instant.ofEpochMilli(event.endAt)
        )
        return minutes >= rule.minDurationMinutes
    }

    /**
     * Wochentags-Filter (Bitmaske Mo=1 … So=64).
     * Ganztägige Termine werden nach ihrem START-Tag bewertet — ein
     * Urlaubstag am Montag zählt als Montag.
     */
    private fun matchesWeekday(
        rule: CalendarRule,
        event: CalendarEventCache,
        zone: ZoneId,
        weekdayOverride: DayOfWeek?
    ): Boolean {
        if (rule.weekdayMask == 0x7F) return true
        val dow = weekdayOverride
            ?: Instant.ofEpochMilli(event.startAt).atZone(zone).dayOfWeek
        val bit = 1 shl (dow.value - 1) // Mo.value = 1 → Bit 0
        return (rule.weekdayMask and bit) != 0
    }

    /**
     * Zeitfenster (Minuten seit Mitternacht).
     *
     * Beide Grenzen sind optional (-1 = keine Grenze). Die Prüfung nutzt
     * den Termin-START — die entscheidende Frage ist „beginnt dieser
     * Termin in meinem Aufzeichnungsfenster?", nicht „liegt er
     * vollständig darin" (sonst würde eine Vorlesung von 08:00–12:00
     * bei Fenster 09:00–20:00 komplett ignoriert).
     *
     * Mitternachts-übergreifende Fenster (z. B. 22:00–06:00) werden
     * korrekt behandelt: ist [windowStartMinute] > [windowEndMinute],
     * gilt das Fenster über den Tageswechsel.
     */
    private fun matchesTimeWindow(rule: CalendarRule, event: CalendarEventCache, zone: ZoneId): Boolean {
        val start = rule.windowStartMinute
        val end = rule.windowEndMinute
        if (start < 0 && end < 0) return true

        val local = Instant.ofEpochMilli(event.startAt).atZone(zone)
        val minuteOfDay = local.hour * 60 + local.minute

        return when {
            start < 0 -> minuteOfDay <= end
            end < 0 -> minuteOfDay >= start
            start <= end -> minuteOfDay in start..end
            // Mitternachts-übergreifend: 22:00–06:00
            else -> minuteOfDay >= start || minuteOfDay <= end
        }
    }

    /**
     * Findet für einen Termin die passende Regel.
     *
     * Bei mehreren Treffern gewinnt die Regel mit der höchsten [priority];
     * bei Gleichstand die zuerst gelieferte (die DAO sortiert bereits
     * nach `priority DESC, name ASC` → deterministisch).
     */
    fun findRule(
        rules: List<CalendarRule>,
        event: CalendarEventCache,
        zone: ZoneId = ZoneId.systemDefault()
    ): CalendarRule? =
        rules.asSequence()
            .filter { matches(it, event, zone) }
            .maxByOrNull { it.priority }

    /**
     * Wendet die Regeln auf eine Terminliste an.
     *
     * Rückgabe: alle (Termin, Regel)-Paare, die aufgezeichnet würden.
     * Genau diese Liste speist sowohl den Auto-Start-Worker als auch die
     * Timeline-Vorschau — die Vorschau ist damit keine zweite,
     * abweichende Implementierung, sondern dieselbe Wahrheit.
     */
    fun evaluate(
        rules: List<CalendarRule>,
        events: List<CalendarEventCache>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarMatch> =
        events.mapNotNull { event ->
            findRule(rules, event, zone)?.let { CalendarMatch(event, it) }
        }

    /**
     * M18.131: Auflösung MIT den manuell markierten Einzel-Terminen.
     *
     * DAS VORRANG-PRINZIP (Auftrag: „Dann sollte das benutzerdefinierte
     * überwiegen über die Regel"):
     *
     *  1. Ist der Termin manuell markiert, gewinnt IMMER die Markierung —
     *     unabhängig davon, ob eine Regel ebenfalls passt und unabhängig
     *     von deren [CalendarRule.priority]. Die Priorität ordnet nur
     *     Regeln untereinander; eine ausdrückliche Nutzer-Entscheidung zu
     *     EINEM Termin kann durch keine Regel-Metrik überstimmt werden.
     *  2. Ohne Markierung gilt die Regel mit der höchsten Priorität
     *     ([findRule]) — unverändertes Bestandsverhalten.
     *  3. Passt nichts, wird der Termin nicht aufgezeichnet.
     *
     * Bewusst EINE Funktion für beide Quellen: der Auto-Start-Worker und
     * die Timeline-Vorschau rufen ausschließlich diese Variante auf. Damit
     * kann die Vorschau nicht von dem abweichen, was tatsächlich passiert
     * (dieselbe Begründung wie bei [evaluate]).
     *
     * Die Markierung wird über den Instanz-Schlüssel des Termins gesucht —
     * bei einem wiederkehrenden Termin trifft sie also genau das eine
     * Vorkommen, nicht die ganze Serie.
     *
     * @param pins Manuell markierte Termine ([CalendarEventPin.eventId]
     *        entspricht [CalendarEventCache.eventId]).
     */
    fun evaluateWithPins(
        rules: List<CalendarRule>,
        pins: List<CalendarEventPin>,
        events: List<CalendarEventCache>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarMatch> {
        if (rules.isEmpty() && pins.isEmpty()) return emptyList()
        val pinByEventId = pins.associateBy { it.eventId }
        return events.mapNotNull { event ->
            val pin = pinByEventId[event.eventId]
            if (pin != null) {
                // Markierung gewinnt — auch gegen eine passende Regel.
                CalendarMatch(event, pin = pin)
            } else {
                findRule(rules, event, zone)?.let { CalendarMatch(event, it) }
            }
        }
    }
}

/**
 * M18.129/M18.131: Ein Termin, der aufgezeichnet werden soll.
 * Basis für Auto-Start UND Timeline-Vorschau.
 *
 * M18.131: Die Quelle ist entweder eine [rule] (Muster-Match) ODER ein
 * [pin] (vom Nutzer einzeln markierter Termin). Genau eine der beiden ist
 * gesetzt — das stellt [CalendarMatchEngine.evaluateWithPins] sicher.
 * Beide Fälle werden über DIESELBE Klasse geführt, damit Auto-Start,
 * Stop-Watchdog und Timeline-Vorschau nicht zwei Codepfade brauchen, die
 * auseinanderdriften könnten.
 */
data class CalendarMatch(
    val event: CalendarEventCache,
    /** Gesetzt, wenn eine Regel den Termin erfasst hat. */
    val rule: CalendarRule? = null,
    /** Gesetzt, wenn der Nutzer DIESEN Termin einzeln markiert hat. */
    val pin: CalendarEventPin? = null
) {
    init {
        require((rule != null) != (pin != null)) {
            "CalendarMatch braucht genau eine Quelle (Regel ODER Markierung)"
        }
    }

    /**
     * Der Titel der späteren Aufzeichnung: eigener Titel falls gesetzt,
     * sonst null — dann entscheidet der LiveActivityManager (er nimmt den
     * Namen des ActivityType, M18.66-FIX9-Muster: die Session heißt wie
     * die Aktivität, nicht wie der Termin).
     */
    val sessionTitle: String? get() =
        (pin?.defaultTitle ?: rule?.defaultTitle)?.takeIf { it.isNotBlank() }

    /** Die aufzuzeichnende Aktivität (aus Markierung oder Regel). */
    val activityTypeId: String? get() = pin?.activityTypeId ?: rule?.activityTypeId

    val shouldOverrideRunning: Boolean
        get() = (pin?.overlapPolicy ?: rule?.overlapPolicy ?: CalendarOverlapPolicy.OVERRIDE) ==
            CalendarOverlapPolicy.OVERRIDE

    /**
     * M18.132: true = der Termin wartet und startet nach, sobald keine
     * Aufzeichnung mehr läuft. Die Policy wird aus Markierung oder Regel
     * gelesen (Markierung gewinnt, wie überall).
     */
    val shouldQueueWhenBusy: Boolean
        get() = (pin?.overlapPolicy ?: rule?.overlapPolicy) == CalendarOverlapPolicy.QUEUE_IF_BUSY

    /** true = manuell markierter Einzel-Termin (UI kennzeichnet das). */
    val isUserPinned: Boolean get() = pin != null

    /**
     * M18.131: Sortier-Priorität für den Fall, dass MEHRERE Termine
     * gleichzeitig fällig sind (überlappende Termine, verspäteter
     * Worker-Lauf).
     *
     * Eine manuelle Markierung bekommt einen Wert oberhalb JEDER
     * Regel-Priorität: der Nutzer kann eine Regel mit beliebiger Priorität
     * anlegen, aber seine ausdrückliche Zusage zu einem konkreten Termin
     * darf davon nicht verdrängt werden. Der Wert ist bewusst NICHT
     * `Int.MAX_VALUE`, um bei einem späteren Rechenfehler (Addition) nicht
     * überzulaufen — [PIN_SORT_PRIORITY] liegt hoch genug über allem, was
     * die UI an Prioritäten zulässt.
     */
    val effectivePriority: Int
        get() = if (pin != null) PIN_SORT_PRIORITY else (rule?.priority ?: 0)

    companion object {
        /**
         * Sortier-Priorität einer manuellen Markierung. Die Regel-UI lässt
         * Prioritäten im niedrigen Bereich zu; 100.000 liegt weit darüber
         * und lässt trotzdem Raum, ohne in die Nähe von Int.MAX_VALUE zu
         * kommen.
         */
        const val PIN_SORT_PRIORITY = 100_000
    }

    /**
     * Anzeigename der Quelle für Vorschau/Tooltip: der Regel-Name oder ein
     * Hinweis auf die manuelle Markierung.
     */
    val sourceLabel: String get() = pin?.let { "pin" } ?: rule?.name.orEmpty()
}
