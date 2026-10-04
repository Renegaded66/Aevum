package com.d_drostes_apps.aevum.ui.screens.activitystats

import android.content.Context
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.domain.digital.ScreenStatisticsPolicy
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodLabels
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * M18.146: Aktivitäts-Statistikseite — Granularität der Verlaufs-Buckets.
 *
 * Der Nutzer kann den Graphen zwischen täglich, wöchentlich und monatlich
 * umstellen („den Graphen umstellen"). Die Granularität bestimmt NUR die
 * Bucket-Breite und das Fenster — die zugrunde liegenden Tageswerte werden
 * EINMAL gerechnet und in Buckets zusammengefasst, damit Tages-, Wochen-
 * und Monatsansicht nicht auseinanderlaufen können (gleiches Prinzip wie
 * [com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics]).
 */
enum class ActivityStatsRange {
    Daily,
    Weekly,
    Monthly;

    companion object {
        /** Persistenz-freier Default der Seite. */
        fun fromStorage(raw: String?): ActivityStatsRange =
            entries.firstOrNull { it.name == raw } ?: Daily
    }
}

/**
 * Ein Bucket des Verlaufs-Graphen: entweder ein Tag, eine Kalenderwoche oder
 * ein Kalendermonat. [label] ist die KURZE Achsen-Beschriftung,
 * [fullLabel] die ausgeschriebene Form für den Tooltip beim Halten des
 * Fingers.
 */
data class ActivityStatBucket(
    val start: LocalDate,
    val endInclusive: LocalDate,
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
    val range: ActivityStatsRange = ActivityStatsRange.Daily,
    val buckets: List<ActivityStatBucket> = emptyList(),
    /** Gesamt-Zeit im gezeigten Fenster. */
    val totalMs: Long = 0L,
    /** Ø pro Tag über das Fenster (auch über Null-Tage gemittelt). */
    val avgPerDayMs: Long = 0L,
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
     * vergleichen; eine „+∞ %"-Anzeige wäre irreführend).
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
 *     summieren diese Tageswerte nur auf. Tages-, Wochen- und Monatsansicht
 *     sind dadurch per Konstruktion konsistent — genau der Fehlertyp, der
 *     beim Pauschalen-Bug entstand (zwei Perioden-Definitionen), kann hier
 *     nicht entstehen.
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
 *  4. **Fenster**: Täglich = letzte 30 Tage, Wöchentlich = letzte 12
 *     Kalenderwochen, Monatlich = letzte 12 Kalendermonate. Der Trend
 *     vergleicht das Fenster mit dem gleich langen Fenster davor.
 */
object ActivityStatsAnalytics {

    /** Anzahl Buckets je Granularität. */
    const val DAILY_BUCKETS = 30
    const val WEEKLY_BUCKETS = 12
    const val MONTHLY_BUCKETS = 12

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

        val buckets = bucketStarts(range, today)
        val previousBuckets = previousBucketStarts(range, today)
        val windowStart = buckets.first()
        val windowEndExclusive = bucketEndExclusive(buckets.last(), range)
        val previousStart = previousBuckets.first()
        val previousEndExclusive = bucketEndExclusive(previousBuckets.last(), range)

        // EINE Tagesbasis über beide Fenster — Grundlage für alle Sichten.
        val dayTotals = dayDurations(
            sessions = relevant,
            start = previousStart,
            endExclusive = windowEndExclusive,
            zoneId = zoneId
        )

        val bucketModels = buckets.map { start ->
            val endEx = bucketEndExclusive(start, range)
            val endInclusive = endEx.minusDays(1)
            val duration = sumDays(dayTotals, start, endEx)
            ActivityStatBucket(
                start = start,
                endInclusive = endInclusive,
                label = axisLabel(range, start, today, context),
                fullLabel = fullLabel(range, start, today, context),
                durationMs = duration,
                sessionCount = countSessions(relevant, start, endEx, zoneId)
            )
        }

        val windowTotal = sumDays(dayTotals, windowStart, windowEndExclusive)
        val previousTotal = sumDays(dayTotals, previousStart, previousEndExclusive)

        val windowDays = java.time.temporal.ChronoUnit.DAYS
            .between(windowStart, windowEndExclusive)
            .coerceAtLeast(1L)

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
            sessionCount = countSessions(relevant, windowStart, windowEndExclusive, zoneId),
            longestMs = longestMs,
            activeDays = activeDayFlags.count { it },
            bestStreak = longestStreak(activeDayFlags),
            weekdayMs = weekdayTotals(dayTotals, windowStart, windowEndExclusive),
            trendPercent = trendPercent(windowTotal, previousTotal),
            // M18.146: „Daten vorhanden" heisst: Zeit IM GEZEIGTEN FENSTER.
            // Frueher: relevant.isNotEmpty() — dann zeigte eine Aktivitaet,
            // die nur ausserhalb des Fensters Aufzeichnungen hat (z.B. aelter
            // als 30 Tage), einen Graphen voller Nullen statt des
            // Leerzustands. Die UI entscheidet damit korrekt zwischen
            // „noch nichts aufgezeichnet" und „hier verlaeuft die Kurve".
            hasData = windowTotal > 0L
        )
    }

    // ---------------------------------------------------------------
    // Bucket-Grenzen
    // ---------------------------------------------------------------

    /** Start-Datum jedes Buckets im Fenster (aufsteigend, letzter = heute). */
    fun bucketStarts(range: ActivityStatsRange, today: LocalDate): List<LocalDate> = when (range) {
        ActivityStatsRange.Daily ->
            (DAILY_BUCKETS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        ActivityStatsRange.Weekly -> {
            val thisWeek = PeriodLabels.weekStart(today)
            (WEEKLY_BUCKETS - 1 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
        }
        ActivityStatsRange.Monthly -> {
            val thisMonth = today.withDayOfMonth(1)
            (MONTHLY_BUCKETS - 1 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
        }
    }

    /** Start-Datum jedes Buckets des UNMITTELBAR davorliegenden Fensters. */
    private fun previousBucketStarts(range: ActivityStatsRange, today: LocalDate): List<LocalDate> = when (range) {
        ActivityStatsRange.Daily ->
            (2 * DAILY_BUCKETS - 1 downTo DAILY_BUCKETS).map { today.minusDays(it.toLong()) }
        ActivityStatsRange.Weekly -> {
            val thisWeek = PeriodLabels.weekStart(today)
            (2 * WEEKLY_BUCKETS - 1 downTo WEEKLY_BUCKETS).map { thisWeek.minusWeeks(it.toLong()) }
        }
        ActivityStatsRange.Monthly -> {
            val thisMonth = today.withDayOfMonth(1)
            (2 * MONTHLY_BUCKETS - 1 downTo MONTHLY_BUCKETS).map { thisMonth.minusMonths(it.toLong()) }
        }
    }

    /** Exklusives Ende eines Buckets (Tag danach). */
    fun bucketEndExclusive(start: LocalDate, range: ActivityStatsRange): LocalDate = when (range) {
        ActivityStatsRange.Daily -> start.plusDays(1)
        ActivityStatsRange.Weekly -> start.plusWeeks(1)
        ActivityStatsRange.Monthly -> start.plusMonths(1)
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

    private fun axisLabel(range: ActivityStatsRange, start: LocalDate, today: LocalDate, context: Context?): String =
        when (range) {
            ActivityStatsRange.Daily -> PeriodLabels.dayShortLabel(start)
            ActivityStatsRange.Weekly -> PeriodLabels.weekShortLabel(start, context)
            ActivityStatsRange.Monthly -> PeriodLabels.monthShortLabel(start)
        }

    private fun fullLabel(range: ActivityStatsRange, start: LocalDate, today: LocalDate, context: Context?): String =
        when (range) {
            ActivityStatsRange.Daily -> PeriodLabels.dayLabel(start, today, context)
            ActivityStatsRange.Weekly -> PeriodLabels.weekLabel(start, today, context)
            ActivityStatsRange.Monthly -> PeriodLabels.monthLabel(start, context)
        }
}
