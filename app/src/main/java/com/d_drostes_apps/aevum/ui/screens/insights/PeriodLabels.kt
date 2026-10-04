package com.d_drostes_apps.aevum.ui.screens.insights

import android.content.Context
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.util.AppLocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * M18.146: Beschriftungen für Zeiträume — Datum, Kalenderwoche (mit Nummer),
 * Monat. EINE Stelle für alle Perioden-Labels der Statistik (Navigations-Zeile,
 * Auswahl-Sheet, Graph-Tooltips).
 *
 * Warum als eigenes Objekt: Die Labels werden an drei Stellen gebraucht
 * (Insights-Navigationszeile, Zeitraum-Picker, Aktivitäts-Statistikseite).
 * Dreimal dieselbe Formatierung wäre dreimal dieselbe Chance, Woche/Monat
 * unterschiedlich zu benennen.
 *
 * Lokalisierung: Wochentag- und Monatsnamen kommen aus dem App-Locale
 * ([AppLocale.current], gesetzt von LocaleHelper), Datums-MUSTER werden
 * sprachabhängig gewählt (DE "d. MMM" vs. EN "MMM d" — eine gemeinsame
 * Reihenfolge gibt es nicht). Ohne Context (JVM-Tests) greifen
 * sprachabhängige Fallback-Texte statt Ressourcen.
 *
 * Kalenderwoche: ISO WeekFields (Montag-basiert) — identisch zur Definition
 * der Wochen-Periode in [InsightsAnalytics.periodDates]. Eine andere
 * Wochenzählung (z.B. US-Sonntag) würde die angezeigte Nummer und die
 * tatsächlich aggregierten Tage auseinanderlaufen lassen.
 */
object PeriodLabels {

    private fun isGerman(locale: Locale): Boolean = locale.language == "de"

    /** Montag der Kalenderwoche von [date]. */
    fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** ISO-Kalenderwoche von [date] (z.B. 40). */
    fun weekNumber(date: LocalDate): Int =
        weekStart(date).get(WeekFields.ISO.weekOfWeekBasedYear())

    /**
     * Tageslabel: "Heute" / "Gestern" / "Morgen" für die drei Sonderfälle,
     * sonst Wochentag + Datum ("So, 4. Okt." / "Sun, Oct 4"). Jahreszahl nur,
     * wenn das Jahr vom heutigen abweicht.
     */
    fun dayLabel(date: LocalDate, today: LocalDate, context: Context? = null): String {
        if (date == today) return str(context, R.string.common_today)
        if (date == today.minusDays(1)) return str(context, R.string.common_yesterday)
        if (date == today.plusDays(1)) return str(context, R.string.common_tomorrow)
        val locale = AppLocale.current
        val dow = date.dayOfWeek
            .getDisplayName(TextStyle.SHORT, locale)
            .removeSuffix(".")
        val base = date.format(DateTimeFormatter.ofPattern(dayPattern(locale), locale))
        val withYear = if (date.year != today.year) {
            if (isGerman(locale)) "$base ${date.year}" else "$base, ${date.year}"
        } else base
        return "$dow, $withYear"
    }

    /** Kurzes Tageslabel für enge Stellen (z.B. Graph-Achse): "4. Okt." / "Oct 4". */
    fun dayShortLabel(date: LocalDate): String {
        val locale = AppLocale.current
        return date.format(DateTimeFormatter.ofPattern(dayPattern(locale), locale))
    }

    /**
     * Wochenlabel mit Datum UND Wochennummer:
     * DE "KW 40 · 28. Sep. – 4. Okt."
     * EN "Week 40 · Sep 28 – Oct 4"
     */
    fun weekLabel(dateInWeek: LocalDate, today: LocalDate, context: Context? = null): String {
        val start = weekStart(dateInWeek)
        return str(
            context,
            R.string.insights_week_label,
            weekNumber(dateInWeek),
            rangeLabel(start, start.plusDays(6), today)
        )
    }

    /** Kurzes Wochenlabel ("KW 40" / "Week 40") — für enge Stellen. */
    fun weekShortLabel(dateInWeek: LocalDate, context: Context? = null): String =
        str(context, R.string.insights_week_short, weekNumber(dateInWeek))

    /** Monatslabel: "Oktober 2026" / "October 2026". */
    fun monthLabel(date: LocalDate, context: Context? = null): String =
        date.format(DateTimeFormatter.ofPattern("MMMM yyyy", AppLocale.current))

    /** Kurzes Monatslabel: "Okt. 26" / "Oct 26". */
    fun monthShortLabel(date: LocalDate): String {
        val locale = AppLocale.current
        return date.format(DateTimeFormatter.ofPattern("MMM yy", locale))
    }

    /** Concrete period label for the currently selected period + anchor. */
    fun periodLabel(period: InsightPeriod, anchor: LocalDate, today: LocalDate, context: Context? = null): String =
        when (period) {
            InsightPeriod.Today -> dayLabel(anchor, today, context)
            InsightPeriod.Week -> weekLabel(anchor, today, context)
            InsightPeriod.Month -> monthLabel(anchor, context)
        }

    /**
     * M18.146: Einträge für das Zeitraum-Auswahl-Sheet — die letzten [count]
     * Perioden der gewählten Granularität (ab heute rückwärts), NEUESTE ZUERST
     * (gleiche Konvention wie eine Chat-Liste). Die aktuell GEZEIGTE Periode
     * ist immer enthalten (auch wenn sie weiter zurückliegt), damit die
     * eigene Position im Sheet sichtbar bleibt.
     */
    fun pickerItems(
        period: InsightPeriod,
        anchor: LocalDate,
        today: LocalDate,
        count: Int = 12,
        context: Context? = null
    ): List<PeriodPickerItem> {
        val representatives = LinkedHashMap<LocalDate, LocalDate>() // normalisiert -> Roh-Anker
        (0 until count).forEach { i ->
            val raw = when (period) {
                InsightPeriod.Today -> today.minusDays(i.toLong())
                InsightPeriod.Week -> weekStart(today.minusWeeks(i.toLong()))
                InsightPeriod.Month -> today.withDayOfMonth(1).minusMonths(i.toLong())
            }
            representatives[normalize(period, raw)] = raw
        }
        val shownKey = normalize(period, anchor)
        if (shownKey !in representatives) representatives[shownKey] = shownKey
        val currentKey = normalize(period, today)
        return representatives.entries
            .sortedByDescending { it.key }
            .map { (key, raw) ->
                PeriodPickerItem(
                    anchor = raw,
                    label = when (period) {
                        InsightPeriod.Today -> dayLabel(raw, today, context)
                        InsightPeriod.Week -> weekLabel(raw, today, context)
                        InsightPeriod.Month -> monthLabel(raw, context)
                    },
                    isCurrent = key == currentKey,
                    isShown = key == shownKey
                )
            }
    }

    /** Normalisiert einen Anker auf den Beginn seiner Periode. */
    fun normalize(period: InsightPeriod, date: LocalDate): LocalDate = when (period) {
        InsightPeriod.Today -> date
        InsightPeriod.Week -> weekStart(date)
        InsightPeriod.Month -> date.withDayOfMonth(1)
    }
    // ---------------------------------------------------------------
    // Intern
    // ---------------------------------------------------------------

    /** Datums-Muster für den Tag: DE mit Tages-Punkt, EN ohne. */
    private fun dayPattern(locale: Locale): String =
        if (isGerman(locale)) "d. MMM" else "MMM d"

    /**
     * Datumsbereich "28. Sep. – 4. Okt." / "Sep 28 – Oct 4".
     * Jahreszahl wird nur angehängt, wenn sie vom heutigen Jahr abweicht;
     * überspannt der Bereich einen Jahreswechsel, wird auf die numerische
     * Form mit beiden Jahren gewechselt ("28.12.2025 – 04.01.2026") — ein
     * einziges Jahr am Ende wäre dort mehrdeutig.
     */
    private fun rangeLabel(start: LocalDate, end: LocalDate, today: LocalDate): String {
        val locale = AppLocale.current
        val german = isGerman(locale)
        return if (start.year == end.year) {
            val formatter = DateTimeFormatter.ofPattern(dayPattern(locale), locale)
            val base = "${start.format(formatter)} – ${end.format(formatter)}"
            if (start.year != today.year) {
                if (german) "$base ${start.year}" else "$base, ${start.year}"
            } else base
        } else {
            val pattern = if (german) "dd.MM.yyyy" else "MM/dd/yyyy"
            val formatter = DateTimeFormatter.ofPattern(pattern, locale)
            "${start.format(formatter)} – ${end.format(formatter)}"
        }
    }

    /**
     * Sprachabhängiger Fallback ohne Context (JVM-Tests): spiegelt die
     * Ressourcen-Texte von values/ (EN) und values-de/ (DE) und substituiert
     * die Format-Positionen (%1$d/%2$s) selbst — sonst stünden in Tests
     * wörtliche Platzhalter statt "KW 40 · ...".
     */
    private fun str(context: Context?, key: Int, vararg args: Any): String {
        if (context != null) return context.getString(key, *args)
        val german = isGerman(AppLocale.current)
        val format = when (key) {
            R.string.common_today -> if (german) "Heute" else "Today"
            R.string.common_yesterday -> if (german) "Gestern" else "Yesterday"
            R.string.common_tomorrow -> if (german) "Morgen" else "Tomorrow"
            R.string.insights_week_label -> if (german) "KW %1\$d · %2\$s" else "Week %1\$d · %2\$s"
            R.string.insights_week_short -> if (german) "KW %1\$d" else "Week %1\$d"
            else -> return ""
        }
        return if (args.isEmpty()) format else String.format(format, *args)
    }
}

/**
 * M18.146: Ein Eintrag im Zeitraum-Auswahl-Sheet.
 *
 * [anchor] ist Roh-Anker für [InsightsViewModel.selectAnchor]. [isCurrent]
 * markiert die Periode, in der „heute" liegt (dezente Zweitmarkierung),
 * [isShown] die aktuell angezeigte (Auswahl-Markierung). Beide können
 * auseinanderfallen, während der Nutzer durch die Vergangenheit skippt.
 */
data class PeriodPickerItem(
    val anchor: LocalDate,
    val label: String,
    val isCurrent: Boolean,
    val isShown: Boolean
)
