package de.devondroste.aevum.domain.todo

import de.devondroste.aevum.data.model.Todo
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * M18.30: RecurrenceEngine — durchdachtes Periodik-System fuer Todos.
 *
 * Recurrence-Typen (hinterfragt und auf das Nötigste reduziert):
 *  - ONCE: einmalig. Faellig ab startDate; wenn dueDate gesetzt, nur
 *    an genau diesem Tag relevant. Nach Erledigung -> archiviert.
 *  - DAILY: jeden Tag ab startDate.
 *  - WEEKDAYS: Mo-Fr.
 *  - WEEKLY_ON: an bestimmten Wochentagen (Bitmask Mo=1..So=64).
 *  - EVERY_N_DAYS: alle x Tage, gestartet an startDate.
 *  - N_PER_WEEK: x-mal pro Woche — relevant JEDEN Tag, aber nur x
 *    Completion-Eintraege pro Kalenderwoche (flexibel: wann man
 *    die Einheiten macht, ist egal).
 *  - N_PER_MONTH: x-mal pro Monat — analog, pro Kalendermonat.
 *
 * Wichtig (hinterfragt):
 *  - N_PER_WEEK/N_PER_MONTH sind "flexible Quoten": Der User darf an
 *    beliebigen Tagen erledigen, solange die Quote pro Periode stimmt.
 *  - Ein ONCE-Todo ohne dueDate bleibt relevant, bis es erledigt ist
 *    (sonst wuerde es vergessen — schlechte UX).
 *  - WEEKLY_ON nutzt Bitmask statt Enum-Liste: kompakt in JSON,
 *    einfach in der UI als Chip-Reihe.
 */
object RecurrenceEngine {

    const val TYPE_ONCE = "ONCE"
    const val TYPE_DAILY = "DAILY"
    const val TYPE_WEEKDAYS = "WEEKDAYS"
    const val TYPE_WEEKLY_ON = "WEEKLY_ON"
    const val TYPE_EVERY_N_DAYS = "EVERY_N_DAYS"
    const val TYPE_N_PER_WEEK = "N_PER_WEEK"
    const val TYPE_N_PER_MONTH = "N_PER_MONTH"

    const val KEY_WEEKDAYS = "weekdaysBitmask"
    const val KEY_INTERVAL_DAYS = "intervalDays"
    const val KEY_COUNT_PER_PERIOD = "countPerPeriod"

    /**
     * Ist das Todo an [date] relevant (faellig)?
     */
    fun isRelevantOn(todo: Todo, date: LocalDate): Boolean {
        if (!todo.active) return false
        val start = LocalDate.parse(todo.startDate)
        if (date.isBefore(start)) return false

        return when (todo.recurrenceType) {
            TYPE_ONCE -> {
                val due = todo.dueDate?.let { LocalDate.parse(it) }
                if (due != null) date == due
                else true // ohne dueDate: relevant bis erledigt
            }
            TYPE_DAILY -> true
            TYPE_WEEKDAYS -> date.dayOfWeek.value <= 5 // Mo-Fr
            TYPE_WEEKLY_ON -> {
                val mask = json(todo).optInt(KEY_WEEKDAYS, 0)
                mask and (1 shl (date.dayOfWeek.value - 1)) != 0
            }
            TYPE_EVERY_N_DAYS -> {
                val interval = json(todo).optInt(KEY_INTERVAL_DAYS, 1).coerceAtLeast(1)
                val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(start, date)
                daysBetween % interval == 0L
            }
            TYPE_N_PER_WEEK, TYPE_N_PER_MONTH -> true // flexibel, Quote zaehlt
            else -> true
        }
    }

    /**
     * Wie viele Erledigungen braucht das Todo in der Periode, die [date]
     * enthaelt? (Fuer N_PER_WEEK/N_PER_MONTH relevant, sonst 1.)
     */
    fun requiredCompletionsInPeriod(todo: Todo, date: LocalDate): Int {
        return when (todo.recurrenceType) {
            TYPE_N_PER_WEEK, TYPE_N_PER_MONTH ->
                json(todo).optInt(KEY_COUNT_PER_PERIOD, 1).coerceAtLeast(1)
            else -> 1
        }
    }

    /**
     * Liefert den Perioden-Schluessel fuer [date] (z.B. "2026-W32" oder
     * "2026-08"), damit N_PER_WEEK/N_PER_MONTH ueber die Periode
     * hinweg gezaehlt werden koennen.
     */
    fun periodKey(todo: Todo, date: LocalDate): String {
        return when (todo.recurrenceType) {
            TYPE_N_PER_WEEK -> {
                val week = date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                val year = date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
                "${year}-W${week.toString().padStart(2, '0')}"
            }
            TYPE_N_PER_MONTH -> "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
            else -> date.toString()
        }
    }

    /**
     * Wochentags-Bitmask aus einer Liste von DayOfWeek bauen.
     */
    fun weekdayBitmask(days: List<DayOfWeek>): Int {
        return days.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) }
    }

    fun dayOfWeekFromBit(bit: Int): DayOfWeek = DayOfWeek.of(bit + 1)

    /** M18.38: Bitmask in DayOfWeek-Liste umwandeln (Edit-Modus). */
    fun weekdaysFromBitmask(mask: Int): List<DayOfWeek> {
        return (0..6).mapNotNull { bit ->
            if (mask and (1 shl bit) != 0) DayOfWeek.of(bit + 1) else null
        }
    }

    private fun json(todo: Todo): JSONObject {
        return try {
            JSONObject(todo.recurrenceJson)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    val allTypes: List<String> = listOf(
        TYPE_ONCE, TYPE_DAILY, TYPE_WEEKDAYS, TYPE_WEEKLY_ON,
        TYPE_EVERY_N_DAYS, TYPE_N_PER_WEEK, TYPE_N_PER_MONTH
    )

    fun labelFor(type: String): String = when (type) {
        TYPE_ONCE -> "Einmalig"
        TYPE_DAILY -> "Jeden Tag"
        TYPE_WEEKDAYS -> "Wochentags (Mo–Fr)"
        TYPE_WEEKLY_ON -> "An bestimmten Wochentagen"
        TYPE_EVERY_N_DAYS -> "Alle x Tage"
        TYPE_N_PER_WEEK -> "x-mal pro Woche"
        TYPE_N_PER_MONTH -> "x-mal pro Monat"
        else -> type
    }
}
