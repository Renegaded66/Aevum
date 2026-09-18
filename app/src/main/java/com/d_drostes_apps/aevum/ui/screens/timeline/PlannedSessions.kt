package com.d_drostes_apps.aevum.ui.screens.timeline

import android.content.Context
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarEventCache
import com.d_drostes_apps.aevum.data.model.CalendarEventPin
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.domain.calendar.CalendarMatchEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M18.129: Ein GEPLANTER Block in der Timeline.
 *
 * Bewusst NICHT [TimelineSessionUi]: ein Plan ist keine Aufzeichnung
 * und darf NIE in Statistiken, Summen oder Insights einfließen. Die
 * Trennung ist hier typ-sicher verankert — es ist unmöglich, einen
 * geplanten Block versehentlich als Session zu zählen.
 *
 * Die Darstellung (diagonal gestrichelt) ist in [PlannedBlockTexture]
 * definiert.
 */
data class PlannedSessionUi(
    val id: String,
    val title: String,
    val activityTypeId: String?,
    val activityTypeName: String,
    val activityIcon: String,
    val activityColor: Long,
    val ruleName: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val timeRange: String,
    val durationMinutes: Int,
    /** true = ganztägiger Termin (Urlaub/Feiertag). */
    val allDay: Boolean = false,
    /**
     * M18.131: true = der Nutzer hat DIESEN Termin einzeln zur Aufzeichnung
     * freigegeben (statt ihn einer Regel zu überlassen). Die UI kann das
     * unterscheiden — eine manuelle Zusage ist belastbarer als eine
     * regel-basierte Schätzung.
     */
    val isUserPinned: Boolean = false
)

/**
 * M18.129: Erzeugt die geplanten Blöcke für einen konkreten Tag.
 *
 * VERWENDUNG: Diese EINE Funktion speist sowohl die 7-Tage-Vorschau als
 * auch die Anzeige des ausgewählten Tages. Damit kann die Vorschau
 * niemals von dem abweichen, was der Auto-Start-Worker tatsächlich tun
 * wird — beide nutzen [CalendarMatchEngine.evaluate].
 *
 * WICHTIG (M18.60c-Lektion — „Kalender zeigt Live-Activity in allen
 * Zukunftstagen"): Der Tages-Filter ist ein ECHTER Overlap mit explizitem
 * Ende. Geplante Blöcke haben immer ein Ende (der Reader deckelt
 * Null-Längen-Termine auf 15 Minuten), daher entsteht hier kein
 * Zukunfts-Leck.
 *
 * @param date Der Tag, für den geplant werden soll.
 * @param events Termine aus dem Cache (Sync-Fenster ist größer als ein Tag).
 * @param rules Aktive Regeln.
 * @param types ActivityTypes für Name/Icon/Farbe.
 * @param zone Zeitzone des Nutzers.
 */
fun buildPlannedSessionsForDay(
    date: LocalDate,
    events: List<CalendarEventCache>,
    rules: List<CalendarRule>,
    types: List<ActivityType>,
    zone: ZoneId = ZoneId.systemDefault(),
    /**
     * M18.129-i18n: Optionaler Context — liefert den lokalisierten
     * Fallback-Titel für Termine ohne eigenen Titel („Termin"/„Event").
     * Ohne Context (reine JVM-Aufrufe, Unit-Tests) gilt der deutsche
     * Quelltext — gleiche Konvention wie [com.d_drostes_apps.aevum.domain.time.TimeFormatting].
     */
    context: Context? = null,
    /**
     * M18.131: Vom Nutzer einzeln markierte Termine. Sie haben Vorrang vor
     * den Regeln — dieselbe Auflösung, die der Auto-Start-Worker nutzt.
     * Ohne sie würde die Timeline einen markierten Termin als „nicht
     * geplant" zeigen, obwohl er aufgezeichnet wird.
     */
    pins: List<CalendarEventPin> = emptyList()
): List<PlannedSessionUi> {
    if (rules.isEmpty() && pins.isEmpty()) return emptyList()
    if (events.isEmpty()) return emptyList()

    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val typeById = types.associateBy { it.id }
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val fallbackTitle = context?.getString(R.string.calendar_plan_event_fallback_title) ?: "Termin"

    return CalendarMatchEngine.evaluateWithPins(rules, pins, events, zone)
        .mapNotNull { match ->
            val event = match.event
            // Echter Overlap: der Termin schneidet diesen Tag.
            if (event.startAt >= dayEnd || event.endAt <= dayStart) {
                null
            } else {
                // Auf den Tag clippen (Mitternachts-Termine wie Schlaf).
                val clipStart = event.startAt.coerceAtLeast(dayStart)
                val clipEnd = event.endAt.coerceAtMost(dayEnd)
                val startMin = ((clipStart - dayStart) / 60_000L).toInt().coerceIn(0, 1440)
                val endMin = ((clipEnd - dayStart) / 60_000L).toInt().coerceIn(0, 1440)
                val type = match.activityTypeId?.let { typeById[it] }
                val startLocal = Instant.ofEpochMilli(event.startAt).atZone(zone)
                val endLocal = Instant.ofEpochMilli(event.endAt).atZone(zone)
                PlannedSessionUi(
                    // Eindeutig pro Tag+Termin: ein Mitternachts-Termin
                    // erscheint an zwei Tagen mit je eigenem Block.
                    id = "planned_${event.eventId}_$dayStart",
                    title = match.sessionTitle
                        ?: type?.name
                        ?: event.title.ifBlank { fallbackTitle },
                    activityTypeId = match.activityTypeId,
                    activityTypeName = type?.name ?: "?",
                    activityIcon = type?.icon ?: "•",
                    activityColor = type?.color ?: 0L,
                    ruleName = match.sourceLabel,
                    startMinuteOfDay = startMin,
                    endMinuteOfDay = endMin.coerceAtLeast(startMin + 1),
                    timeRange = "${startLocal.format(timeFmt)}–${endLocal.format(timeFmt)}",
                    durationMinutes = ((clipEnd - clipStart) / 60_000L).toInt(),
                    allDay = event.allDay,
                    // M18.131: Die UI kennzeichnet manuell markierte Termine
                    // (sie sind zugesagt, nicht nur regel-basiert geschätzt).
                    isUserPinned = match.isUserPinned
                )
            }
        }
        .sortedBy { it.startMinuteOfDay }
}

/**
 * M18.129: Die nächsten [days] Tage als Karte Tag → geplante Blöcke.
 *
 * Das ist die vom Auftrag geforderte 7-Tage-Vorschau: alle Termine der
 * kommenden Woche, die durch die eigenen Regeln tatsächlich aufgezeichnet
 * würden — nicht pauschal der ganze Kalender.
 */
fun buildPlannedSessionsForWeek(
    startDate: LocalDate,
    days: Int,
    events: List<CalendarEventCache>,
    rules: List<CalendarRule>,
    types: List<ActivityType>,
    zone: ZoneId = ZoneId.systemDefault(),
    /** M18.129-i18n: siehe [buildPlannedSessionsForDay]. */
    context: Context? = null,
    /** M18.131: markierte Einzel-Termine — siehe [buildPlannedSessionsForDay]. */
    pins: List<CalendarEventPin> = emptyList()
): Map<LocalDate, List<PlannedSessionUi>> {
    if (rules.isEmpty() && pins.isEmpty()) return emptyMap()
    // EINE Auswertung für alle Tage (die Engine prüft Regeln pro Termin,
    // nicht pro Tag) — die Tages-Zuordnung passiert danach im Speicher.
    val byDay = mutableMapOf<LocalDate, MutableList<PlannedSessionUi>>()
    for (i in 0 until days) {
        val date = startDate.plusDays(i.toLong())
        val planned = buildPlannedSessionsForDay(date, events, rules, types, zone, context, pins)
        if (planned.isNotEmpty()) byDay[date] = planned.toMutableList()
    }
    return byDay
}
