package com.d_drostes_apps.aevum.ui.screens.activitystats

import android.content.Context
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.domain.digital.ScreenStatisticsPolicy
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodLabels
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * M18.146: Detail-Statistikseite einer Aktivität — Fenster des Verlaufs.
 *
 * M18.146b (User-Präzisierung): Die Umschalter sind KEINE Kalender-
 * Granularität (täglich/wöchentlich/monatlich), sondern FENSTERGRÖSSEN —
 * „einmal die letzten 7 Tage, einmal die letzten 30 Tage, einmal die
 * letzten 365 Tage und einmal die Gesamtzeit". Die Buckets richten sich
 * adaptiv nach dem Fenster ([bucketUnitFor]): 7/30-Tage-Fenster zeigen je
 * einen Punkt pro Tag, das 365-Tage-Fenster je Kalenderwoche, die
 * Gesamtzeit adaptiv (Tage bei kurzer Historie, sonst Wochen, sonst
 * Monate) — ein Punkt bleibt dabei IMMER ein gleiches Zeit-Mass, damit
 * die Kurvenform nicht suggeriert, was nicht in den Daten liegt.
 */
enum class ActivityStatsRange {
    Days7,
    Days30,
    Days365,
    All;

    companion object {
        /**
         * Persistenz-freier Default der Seite. Alte gespeicherte Werte der
         * ersten Fassung („Daily"/„Weekly"/„Monthly") werden auf das
         * nahegelegene FENSTER abgebildet statt auf den Default zurück-
         * gesetzt — die gespeicherte Nutzer-Präferenz bleibt also erhalten.
         */
        fun fromStorage(raw: String?): ActivityStatsRange = when (raw) {
            "Daily" -> Days7
            "Weekly" -> Days30
            "Monthly" -> Days365
            null, "" -> Days30
            else -> entries.firstOrNull { it.name == raw } ?: Days30
        }
    }
}

/** Zeit-Mass eines Verlaufs-Buckets (Punktabstand auf der X-Achse). */
enum class BucketUnit {
    /** Ein Punkt = ein Kalendertag. */
    Daily,
    /** Ein Punkt = eine Kalenderwoche (Montag-Start, wie überall in der App). */
    Weekly,
    /** Ein Punkt = ein Kalendermonat (Beginn am 1., wie überall in der App). */
    Monthly
}

/**
 * Ein Bucket des Verlaufs-Graphen: [unit] sagt, welches Zeit-Mass der Punkt
 * bündelt. [label] ist die KURZE Achsen-Beschriftung, [fullLabel] die
 * ausgeschriebene Form für den Tooltip beim Halten des Fingers.
 */
data class ActivityStatBucket(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val unit: BucketUnit,
    val label: String,
    val fullLabel: String,
    val durationMs: Long,
    val sessionCount: Int
)

/**
 * Vollständiger Zustand der Aktivitäts-Statistikseite. Alles, was die UI
 * braucht — bewusst ohne Android-Typen (außer optional Context für Strings),
 * damit die komplette Berechnung unit-testbar bleibt.
 */
data class ActivityStatsUiModel(
    val range: ActivityStatsRange = ActivityStatsRange.Days30,
    val buckets: List<ActivityStatBucket> = emptyList(),
    /**
     * KUMULIERTE Zeit im Fenster — die Hero-Zahl der Seite (User: „kumulierte
     * Werte"). Die Summe aller Buckets, geclippt auf das gezeigte Fenster.
     */
    val totalMs: Long = 0L,
    /** Ø pro Tag über das Fenster (auch über Null-Tage gemittelt). */
    val avgPerDayMs: Long = 0L,
    /** Ø pro Woche über das Fenster (User: „Durchschnittswerte"). */
    val avgPerWeekMs: Long = 0L,
    /** Anzahl Aufzeichnungen, die das Fenster berühren. */
    val sessionCount: Int = 0,
    /** Längste EINZELNE Aufzeichnung im Fenster (geclippt). */
    val longestMs: Long = 0L,
    /** Tage mit Aufzeichnung im Fenster. */
    val activeDays: Int = 0,
    /** Längste Serie aufeinanderfolgender aktiver Tage im Fenster. */
    val bestStreak: Int = 0,
    /** Minuten je Wochentag (Index 0 = Montag … 6 = Sonntag) im Fenster. */
    val weekdayMs: List<Long> = List(7) { 0L },
    /**
     * Entwicklung gegenüber dem unmittelbar davorliegenden Fenster gleicher
     * Länge — null, wenn es dort keine Daten gab (dann gibt es nichts zu
     * vergleichen; eine „+∞ %"-Anzeige wäre irreführend) und null bei der
     * Gesamtzeit-Ansicht (die Gesamthistorie hat per Definition kein
     * „davor" — der Vergleich wäre leer und suggerierte Stagnation).
     */
    val trendPercent: Int? = null,
    val hasData: Boolean = false
)

/**
 * M18.146: Berechnung des Aktivitäts-Verlaufs — pure Funktionen, keine
 * Android-Abhängigkeit, damit die Semantik (Bucket-Grenzen, Clipping,
 * Serien, Trend) direkt testbar ist.
 *
 * Bewusste Design-Entscheidungen:
 *
 *  1. **EINE Tagesbasis.** Alle Tageswerte werden einmal über das gesamte
 *     Fenster (inkl. Vorfenster für den Trend) berechnet; die Buckets
 *     summieren diese Tageswerte nur auf. Fensterwechsel ändern die
 *     Gruppierung, nie die Werte — die Sichten können per Konstruktion
 *     nicht auseinanderlaufen (derselbe Fehlertyp wie beim
 *     Pauschalen-Bug ist hier ausgeschlossen).
 *
 *  2. **Aufzeichnungen zählen nicht** ([ScreenStatisticsPolicy.forStatistics]):
 *     Bildschirm-Aufzeichnungen (SCREEN_AUTO) sind laut M18.138 eine
 *     Timeline-Darstellung, keine Messung. Die Bilanz-Zeit der Digital-
 *     Balance fließt bewusst NICHT ein — UsageStats-Abfragen über Jahre
 *     wären teuer, und die Digital-Statistik hat mit dem Balance-Tab eine
 *     eigene Heimat. Bewusst als „Nicht-Bestandteil" dokumentiert.
 *
 *  3. **Clipping + Pausen** laufen über [ActivitySession.activeDurationInWindow]
 *     — dieselbe zentrale Berechnung wie Dashboard, Timeline und Insights.
 *
 *  4. **Fenster** (M18.146b): 7 Tage / 30 Tage / 365 Tage / Gesamtzeit.
 *     Der Trend vergleicht das Fenster mit dem gleich langen Fenster
 *     davor (nicht bei „Gesamtzeit" — siehe [ActivityStatsUiModel.trendPercent]).
 */
object ActivityStatsAnalytics {

    /** Fenster in Tagen für die drei Tages-/Wochen-/Jahres-Fenster. */
    const val DAYS7 = 7L
    const val DAYS30 = 30L
    const val DAYS365 = 365L

    /** Adaptions-Grenzen des Gesamtzeit-Buckets (in Tagen). */
    const val ALL_DAILY_MAX_DAYS = 62L
    const val ALL_WEEKLY_MAX_DAYS = 730L

    fun build(
        sessions: List<ActivitySession>,
        activityTypeId: String,
        range: ActivityStatsRange,
        today: LocalDate,
        zoneId: ZoneId,
        context: Context? = null
    ): ActivityStatsUiModel {
        // Aufzeichnungen raus (M18.138), gelöschte raus, fremde Typen raus.
        val relevant = ScreenStatisticsPolicy.forStatistics(
            sessions.filter { it.deletedAt == null && it.activityTypeId == activityTypeId }
        )
        if (relevant.isEmpty()) {
            // Leerzustand: keine Aufzeichnung dieser Aktivität überhaupt.
            return ActivityStatsUiModel(range = range, hasData = false)
        }

        val earliest = relevant.minOf { it.startAt }
            .let { java.time.Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }

        val unit = bucketUnitFor(range, today, earliest)
        val buckets = bucketStarts(range, today, earliest, unit)
        if (buckets.isEmpty()) {
            return ActivityStatsUiModel(range = range, hasData = false)
        }
        val windowStart = buckets.first()
        val windowEndExclusive = bucketEndExclusive(buckets.last(), unit)

        // Trend-Vergleichsbasis: das gleich lange Vorfenster. Die Tagesbasis
        // deckt BEIDE Fenster ab — sonst würde der Trend-Vergleich die Tage
        // davor überspringen (dayTotals hätte dort keine Keys).
        val hasTrend = range != ActivityStatsRange.All
        val trendStart = if (hasTrend) {
            val windowDays = java.time.temporal.ChronoUnit.DAYS
                .between(windowStart, windowEndExclusive)
            windowStart.minusDays(windowDays)
        } else windowStart

        // EINE Tagesbasis — Grundlage für alle Sichten.
        val dayTotals = dayDurations(
            sessions = relevant,
            start = trendStart,
            endExclusive = windowEndExclusive,
            zoneId = zoneId
        )

        val bucketModels = buckets.map { start ->
            val endEx = bucketEndExclusive(start, unit)
            val endInclusive = endEx.minusDays(1)
            val duration = sumDays(dayTotals, start, endEx)
            ActivityStatBucket(
                start = start,
                endInclusive = endInclusive,
                unit = unit,
                label = axisLabel(unit, start, context),
                fullLabel = fullLabel(unit, start, today, context),
                durationMs = duration,
                sessionCount = countSessions(relevant, start, endEx, zoneId)
            )
        }

        val windowTotal = sumDays(dayTotals, windowStart, windowEndExclusive)
        val windowDays = java.time.temporal.ChronoUnit.DAYS
            .between(windowStart, windowEndExclusive)
            .coerceAtLeast(1L)

        // Trend: gleich langes Fenster davor — außer bei „Gesamtzeit",
        // dessen Historie per Definition kein „davor" hat.
        val previousTotal = if (range == ActivityStatsRange.All) null
        else sumDays(dayTotals, windowStart.minusDays(windowDays), windowStart)

        val longestMs = relevant.maxOfOrNull { session ->
            session.activeDurationInWindow(
                windowStart.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                windowEndExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
            )
        } ?: 0L

        val activeDayFlags = (0 until windowDays.toInt()).map { i ->
            val day = windowStart.plusDays(i.toLong())
            (dayTotals[day] ?: 0L) > 0L
        }

        return ActivityStatsUiModel(
            range = range,
            buckets = bucketModels,
            totalMs = windowTotal,
            avgPerDayMs = windowTotal / windowDays,
            avgPerWeekMs = windowTotal / (windowDays / 7L).coerceAtLeast(1L),
            sessionCount = countSessions(relevant, windowStart, windowEndExclusive, zoneId),
            longestMs = longestMs,
            activeDays = activeDayFlags.count { it },
            bestStreak = longestStreak(activeDayFlags),
            weekdayMs = weekdayTotals(dayTotals, windowStart, windowEndExclusive),
            trendPercent = if (range == ActivityStatsRange.All) null
            else trendPercent(windowTotal, previousTotal ?: 0L),
            // M18.146: „Daten vorhanden" heisst: Zeit IM GEZEIGTEN FENSTER.
            // Frueher: relevant.isNotEmpty() — dann zeigte eine Aktivitaet,
            // die nur ausserhalb des Fensters Aufzeichnungen hat, einen
            // Graphen voller Nullen statt des Leerzustands.
            hasData = windowTotal > 0L
        )
    }

    // ---------------------------------------------------------------
    // Bucket-Grenzen
    // ---------------------------------------------------------------

    /** Adaptives Zeit-Mass des Verlaufs je Fenster. */
    fun bucketUnitFor(
        range: ActivityStatsRange,
        today: LocalDate,
        earliest: LocalDate?
    ): BucketUnit = when (range) {
        ActivityStatsRange.Days7, ActivityStatsRange.Days30 -> BucketUnit.Daily
        ActivityStatsRange.Days365 -> BucketUnit.Weekly
        ActivityStatsRange.All -> {
            val spanDays = earliest
                ?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).plus(1) }
                ?: 0L
            when {
                spanDays <= ALL_DAILY_MAX_DAYS -> BucketUnit.Daily
                spanDays <= ALL_WEEKLY_MAX_DAYS -> BucketUnit.Weekly
                else -> BucketUnit.Monthly
            }
        }
    }

    /** Start-Datum jedes Buckets im Fenster (aufsteigend). */
    fun bucketStarts(
        range: ActivityStatsRange,
        today: LocalDate,
        earliest: LocalDate?,
        unit: BucketUnit
    ): List<LocalDate> = when (range) {
        ActivityStatsRange.Days7 ->
            (DAYS7 - 1 downTo 0).map { today.minusDays(it) }
        ActivityStatsRange.Days30 ->
            (DAYS30 - 1 downTo 0).map { today.minusDays(it) }
        ActivityStatsRange.Days365 -> {
            var cursor = PeriodLabels.weekStart(today.minusDays(DAYS365 - 1))
            val end = PeriodLabels.weekStart(today)
            buildList {
                while (!cursor.isAfter(end)) {
                    add(cursor)
                    cursor = cursor.plusWeeks(1)
                }
            }
        }
        ActivityStatsRange.All -> {
            val first = earliest ?: return emptyList()
            var cursor = when (unit) {
                BucketUnit.Daily -> first
                BucketUnit.Weekly -> PeriodLabels.weekStart(first)
                BucketUnit.Monthly -> first.withDayOfMonth(1)
            }
            val end = when (unit) {
                BucketUnit.Daily -> today
                BucketUnit.Weekly -> PeriodLabels.weekStart(today)
                BucketUnit.Monthly -> today.withDayOfMonth(1)
            }
            buildList {
                while (!cursor.isAfter(end)) {
                    add(cursor)
                    cursor = when (unit) {
                        BucketUnit.Daily -> cursor.plusDays(1)
                        BucketUnit.Weekly -> cursor.plusWeeks(1)
                        BucketUnit.Monthly -> cursor.plusMonths(1)
                    }
                }
            }
        }
    }

    /** Exklusives Ende eines Buckets. */
    fun bucketEndExclusive(start: LocalDate, unit: BucketUnit): LocalDate = when (unit) {
        BucketUnit.Daily -> start.plusDays(1)
        BucketUnit.Weekly -> start.plusWeeks(1)
        BucketUnit.Monthly -> start.plusMonths(1)
    }

    // ---------------------------------------------------------------
    // Tagesbasis
    // ---------------------------------------------------------------

    /**
     * Tagesdauern für ALLE Tage in [start, endExclusive), inklusive der
     * Null-Tage (fehlende Keys = 0). Sessions werden über ihre tatsächliche
     * Überlappung zugeordnet und auf die Tagesgrenzen geclippt — inklusive
     * Pausen-Abzug (zentrale Berechnung).
     */
    private fun dayDurations(
        sessions: List<ActivitySession>,
        start: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId
    ): Map<LocalDate, Long> {
        val totals = HashMap<LocalDate, Long>()
        if (sessions.isEmpty()) return totals
        val windowStartMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val windowEndMs = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        sessions.forEach { session ->
            val sessionEnd = session.endAt ?: minOf(now, windowEndMs)
            if (sessionEnd <= session.startAt) return@forEach
            if (session.startAt >= windowEndMs || sessionEnd <= windowStartMs) return@forEach

            var day = java.time.Instant.ofEpochMilli(maxOf(session.startAt, windowStartMs))
                .atZone(zoneId)
                .toLocalDate()
            val lastDay = java.time.Instant.ofEpochMilli(minOf(sessionEnd, windowEndMs) - 1)
                .atZone(zoneId)
                .toLocalDate()
            while (!day.isAfter(lastDay)) {
                val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val duration = session.activeDurationInWindow(dayStart, dayEnd, now)
                if (duration > 0L) {
                    totals[day] = (totals[day] ?: 0L) + duration
                }
                day = day.plusDays(1)
            }
        }
        return totals
    }

    /** Summe der Tagesdauern in [start, endExclusive). */
    private fun sumDays(dayTotals: Map<LocalDate, Long>, start: LocalDate, endExclusive: LocalDate): Long {
        var total = 0L
        var day = start
        while (day.isBefore(endExclusive)) {
            total += dayTotals[day] ?: 0L
            day = day.plusDays(1)
        }
        return total
    }

    /**
     * Anzahl Aufzeichnungen, die [start, endExclusive) berühren.
     *
     * Zählung ab der ersten BERÜHRUNG (nicht nach Starttag): eine
     * Mitternachts-Aufzeichnung zählt in beiden berührten Buckets als
     * Aufzeichnung — konsistent damit, dass ihre Dauer ebenfalls auf beide
     * Tage verteilt wird.
     */
    private fun countSessions(
        sessions: List<ActivitySession>,
        start: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId
    ): Int {
        val startMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        return sessions.count { session ->
            val sessionEnd = session.endAt ?: minOf(now, endMs)
            sessionEnd > session.startAt && session.startAt < endMs && sessionEnd > startMs
        }
    }

    /** Minuten je Wochentag (Mo..So) innerhalb des Fensters. */
    private fun weekdayTotals(
        dayTotals: Map<LocalDate, Long>,
        start: LocalDate,
        endExclusive: LocalDate
    ): List<Long> {
        val totals = LongArray(7)
        var day = start
        while (day.isBefore(endExclusive)) {
            val ms = dayTotals[day] ?: 0L
            if (ms > 0L) {
                // java.time: Monday = 1 … Sunday = 7 → Index 0..6
                totals[day.dayOfWeek.value - 1] += ms
            }
            day = day.plusDays(1)
        }
        return totals.toList()
    }

    /** Längste Serie aufeinanderfolgender true-Flags. */
    fun longestStreak(flags: List<Boolean>): Int {
        var best = 0
        var current = 0
        flags.forEach { active ->
            current = if (active) current + 1 else 0
            if (current > best) best = current
        }
        return best
    }

    /**
     * Prozentuale Veränderung des Fensters gegenüber dem Vorfenster.
     * Null bei fehlender Vergleichsbasis (Vorfenster komplett leer).
     */
    fun trendPercent(currentMs: Long, previousMs: Long): Int? {
        if (previousMs <= 0L) return null
        return (((currentMs - previousMs).toDouble() / previousMs.toDouble()) * 100.0).roundToInt()
    }

    // ---------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------

    private fun axisLabel(unit: BucketUnit, start: LocalDate, context: Context?): String =
        when (unit) {
            BucketUnit.Daily -> PeriodLabels.dayShortLabel(start)
            BucketUnit.Weekly -> PeriodLabels.weekShortLabel(start, context)
            BucketUnit.Monthly -> PeriodLabels.monthShortLabel(start)
        }

    private fun fullLabel(unit: BucketUnit, start: LocalDate, today: LocalDate, context: Context?): String =
        when (unit) {
            BucketUnit.Daily -> PeriodLabels.dayLabel(start, today, context)
            BucketUnit.Weekly -> PeriodLabels.weekLabel(start, today, context)
            BucketUnit.Monthly -> PeriodLabels.monthLabel(start, context)
        }
}