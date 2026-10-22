package com.d_drostes_apps.aevum.domain.addiction

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * M27: Sucht-Detektor — reine, JVM-testbare Auswertung.
 *
 * ── M28: AKTUELLER Zeitraum statt „abgeschlossene Perioden" ─────────────
 *
 * User: „Bei der Handy sucht Detektor Ansicht sollten unten die Diagramme
 * auch jeweils die aktuellen Daten wie Tage Wochen etc anzeigen und dann
 * halt bei jedem neuen öffnen aktualisieren mit den neuen Daten des
 * aktuellen Tages."
 *
 * Vorher endeten [summary], [appStats] und [trend] bewusst GESTERN (bzw. mit
 * der letzten vollen Woche/Monat). Der heutige Tag tauchte nur in der
 * „Heute"-Karte auf — die Diagramme zeigten also nie die aktuellen Daten.
 * Jetzt gilt:
 *
 *   - [today] zeigt weiterhin den LIVE-Stand seit Mitternacht,
 *   - [summary], [appStats] und [trend] umfassen den AKTUELLEN Zeitraum
 *     INKLUSIVE heute (Tage: 7 Tage bis heute; Wochen: aktuelle + 3
 *     vorherige Kalenderwochen; Monate: aktueller + 5 vorherige Monate).
 *
 * Bewusst gezahlter Preis: Der laufende Tag/die laufende Woche ist noch
 * nicht vollständig, der Vergleich zur Vorperiode (gleiche Anzahl Tage
 * direkt davor) fällt morgens niedriger aus. Das ist die vom Nutzer
 * gewünschte Sichtbarkeit der aktuellen Daten — die Alternative (nur volle
 * Perioden) hatte genau den gemeldeten Fehler erzeugt.
 *
 * ── Öffnungen vs. Sitzungen ─────────────────────────────────────────────
 *
 * Eine „Öffnung" ist eine Vordergrund-Phase der App (App kam nach hinten und
 * wieder nach vorn). Sehr kurze Phasen (< [MIN_SESSION_MS]) werden verworfen
 * — sonst zählen Splash-Screens, Benachrichtigungs-Vorschauen und
 * System-Flackern als Suchtdruck. Eine Sitzung, die über Mitternacht läuft,
 * zählt als Öffnung an ihrem START-Tag; ihre Dauer wird für Tages-Summen
 * an der Mitternachtsgrenze geteilt.
 */
object AddictionAnalytics {

    /** Kürzere Vordergrund-Phasen sind Rauschen, keine echte App-Öffnung. */
    const val MIN_SESSION_MS = 3_000L

    /** So lange werden Sitzungen lokal aufbewahrt (Gerätewechsel/Verlauf). */
    const val RETENTION_DAYS = 400

    /** Rückwirkender Erst-Scan beim Hinzufügen einer App. */
    const val BACKFILL_DAYS = 45

    const val SCORE_LOW_MAX = 39
    const val SCORE_HIGH_MIN = 70

    private const val TIME_REFERENCE_MS = 4L * 60 * 60 * 1000
    private const val OPENS_REFERENCE = 70f
    private const val LONGEST_REFERENCE_MS = 60L * 60 * 1000
    private const val NIGHT_REFERENCE_SHARE = 0.20f
    private const val NIGHT_START_HOUR = 22
    private const val NIGHT_END_HOUR = 6

    /** Auswahl des Vergleichszeitraums. */
    enum class Range(val id: String) {
        DAYS_7("7D"),
        WEEKS_4("4W"),
        MONTHS_6("6M");

        companion object {
            fun fromId(id: String?): Range = entries.firstOrNull { it.id == id } ?: DAYS_7
        }
    }

    data class Session(
        val packageName: String,
        val startAt: Long,
        val endAt: Long
    ) {
        val durationMs: Long get() = (endAt - startAt).coerceAtLeast(0L)
    }

    data class WatchedApp(
        val packageName: String,
        val appLabel: String
    )

    data class DayStat(
        val date: LocalDate,
        val totalMs: Long,
        val opens: Int,
        val longestSessionMs: Long,
        val nightMs: Long
    )

    data class Summary(
        val totalMs: Long,
        val opens: Int,
        val avgSessionMs: Long,
        val longestSessionMs: Long,
        val activeDays: Int,
        val days: Int,
        val avgPerDayMs: Long
    )

    data class AppStat(
        val packageName: String,
        val appLabel: String,
        val totalMs: Long,
        val opens: Int,
        val avgSessionMs: Long,
        val longestSessionMs: Long,
        /** Anteil an der Gesamtzeit des Zeitraums (0..1). */
        val share: Float,
        /** Veränderung zur Vorperiode in Prozent; null = keine Vorperioden-Daten. */
        val trendPercent: Float?
    )

    data class SessionRow(
        val startAt: Long,
        val durationMs: Long
    )

    data class TrendPoint(
        val startDate: LocalDate,
        val endDateExclusive: LocalDate,
        val totalMs: Long,
        val opens: Int,
        val days: Int
    )

    /**
     * Transparente Bestandteile des Druckindex (0..100). Die UI erklärt sie
     * im Info-Dialog — kein magischer Score, sondern vier nachvollziehbare
     * Beiträge.
     */
    data class ScoreBreakdown(
        val timePoints: Float,
        val opensPoints: Float,
        val longestPoints: Float,
        val nightPoints: Float,
        val avgDailyMs: Long,
        val avgOpens: Float,
        val avgLongestMs: Long,
        val nightShare: Float
    )

    data class Result(
        val range: Range,
        val anchorDate: LocalDate,
        val today: DayStat,
        val todayApps: List<AppStat>,
        val todaySessions: Map<String, List<SessionRow>>,
        val summary: Summary,
        val previousSummary: Summary,
        val appStats: List<AppStat>,
        val trend: List<TrendPoint>,
        val score: Int,
        val scoreAvailable: Boolean,
        val scoreBreakdown: ScoreBreakdown,
        val hourlyTodayMs: List<Long>,
        val periodStart: LocalDate,
        val periodEndExclusive: LocalDate,
        val previousStart: LocalDate,
        val previousEndExclusive: LocalDate,
        /** Tage mit Nutzung in den letzten 7 vollen Tagen (0..7). */
        val scoreDaysWithData: Int,
        /** Alle beobachteten Apps — auch ohne Messwerte im Zeitraum. */
        val trackedAppCount: Int
    )

    // ── Öffentliche Auswertung ────────────────────────────────────────────

    fun analyze(
        watchedApps: List<WatchedApp>,
        sessions: List<Session>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        range: Range,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val watched = watchedApps.associateBy { it.packageName }
        val relevant = sessions
            .asSequence()
            .filter { watched.containsKey(it.packageName) }
            .filter { it.durationMs >= MIN_SESSION_MS }
            .sortedBy { it.startAt }
            .toList()

        val window = currentWindow(anchorDate, range)
        val previous = previousWindow(window)

        // Tages-Buckets für aktuellen UND Vorperioden-Zeitraum in EINEM Lauf.
        val dayStats = buildDayStats(
            sessions = relevant,
            start = previous.first,
            endExclusive = window.second,
            zoneId = zoneId
        )

        val currentDays = dayStats.filter { !it.date.isBefore(window.first) && it.date.isBefore(window.second) }
        val previousDays = dayStats.filter { !it.date.isBefore(previous.first) && it.date.isBefore(previous.second) }

        val summary = summarize(currentDays)
        val previousSummary = summarize(previousDays)

        val today = buildTodayStat(relevant, anchorDate, zoneId, nowMs)
        val todayApps = buildAppStats(
            watched = watchedApps,
            sessions = relevant,
            start = anchorDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endExclusive = anchorDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            totalMs = today.totalMs,
            previousStart = anchorDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            previousEndExclusive = anchorDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
        val todaySessions = buildTodaySessions(relevant, anchorDate, zoneId, nowMs)

        val appStats = buildAppStats(
            watched = watchedApps,
            sessions = relevant,
            start = window.first.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endExclusive = window.second.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            totalMs = summary.totalMs,
            previousStart = previous.first.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            previousEndExclusive = previous.second.atStartOfDay(zoneId).toInstant().toEpochMilli()
        )

        val trend = buildTrend(dayStats, window, range)
        val last7 = dayStats.filter { !it.date.isBefore(anchorDate.minusDays(7)) && it.date.isBefore(anchorDate) }
        val scoreBreakdown = buildScore(last7)
        val score = (
            scoreBreakdown.timePoints +
                scoreBreakdown.opensPoints +
                scoreBreakdown.longestPoints +
                scoreBreakdown.nightPoints
            ).roundToInt().coerceIn(0, 100)

        return Result(
            range = range,
            anchorDate = anchorDate,
            today = today,
            todayApps = todayApps,
            todaySessions = todaySessions,
            summary = summary,
            previousSummary = previousSummary,
            appStats = appStats,
            trend = trend,
            score = score,
            scoreAvailable = scoreBreakdown.avgDailyMs > 60_000L,
            scoreBreakdown = scoreBreakdown,
            hourlyTodayMs = buildHourlyToday(relevant, anchorDate, zoneId, nowMs),
            periodStart = window.first,
            periodEndExclusive = window.second,
            previousStart = previous.first,
            previousEndExclusive = previous.second,
            scoreDaysWithData = last7.count { it.totalMs > 60_000L },
            trackedAppCount = watchedApps.size
        )
    }

    // ── Zeitfenster ──────────────────────────────────────────────────────

    /**
     * Liefert `[start, endExclusive)` des AKTUELLEN Zeitraums — inklusive
     * heute (M28).
     *
     * „Heute" ist bewusst enthalten: Die Diagramme sollen die aktuellen
     * Daten des laufenden Tages zeigen (User-Anforderung). Die Periode ist
     * deshalb am letzten Tag ggf. noch nicht vollständig; die Vorperiode
     * umfasst dieselbe ANZAHL Tage direkt davor ([previousWindow]).
     */
    fun currentWindow(anchorDate: LocalDate, range: Range): Pair<LocalDate, LocalDate> =
        when (range) {
            // 7 Tage: heute + die 6 Tage davor.
            Range.DAYS_7 -> anchorDate.minusDays(6) to anchorDate.plusDays(1)
            // 4 Kalenderwochen: aktuelle Woche (ab Montag) + 3 davor.
            Range.WEEKS_4 -> {
                val currentMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                currentMonday.minusWeeks(3) to anchorDate.plusDays(1)
            }
            // 6 Kalendermonate: aktueller Monat + 5 davor.
            Range.MONTHS_6 -> {
                val currentMonthStart = anchorDate.withDayOfMonth(1)
                currentMonthStart.minusMonths(5) to anchorDate.plusDays(1)
            }
        }

    /**
     * M27/M28: Fenster der letzten VOLLSTÄNDIGEN Perioden.
     *
     * Wird vom Druckindex genutzt (er soll sich auf volle Tage beziehen) —
     * die Diagramme nutzen [currentWindow].
     */
    fun completedWindow(anchorDate: LocalDate, range: Range): Pair<LocalDate, LocalDate> =
        when (range) {
            Range.DAYS_7 -> anchorDate.minusDays(7) to anchorDate
            Range.WEEKS_4 -> {
                val end = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                end.minusWeeks(4) to end
            }
            Range.MONTHS_6 -> {
                val end = anchorDate.withDayOfMonth(1)
                end.minusMonths(6) to end
            }
        }

    fun previousWindow(window: Pair<LocalDate, LocalDate>): Pair<LocalDate, LocalDate> {
        val days = java.time.temporal.ChronoUnit.DAYS.between(window.first, window.second)
        return window.first.minusDays(days) to window.first
    }

    // ── Tages-Buckets ────────────────────────────────────────────────────

    private fun buildDayStats(
        sessions: List<Session>,
        start: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId
    ): List<DayStat> {
        val totals = HashMap<LocalDate, Long>()
        val opens = HashMap<LocalDate, Int>()
        val longest = HashMap<LocalDate, Long>()
        val night = HashMap<LocalDate, Long>()

        val startMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val startDate = start

        sessions.forEach { session ->
            if (session.endAt <= startMs || session.startAt >= endMs) return@forEach
            val effectiveStart = max(session.startAt, startMs)
            val effectiveEnd = min(session.endAt, endMs)
            if (effectiveEnd <= effectiveStart) return@forEach

            // Start-Tag bestimmt die Öffnung — auch bei einer Sitzung über
            // Mitternacht. Die Dauer wird anschließend tageweise geclippt.
            val sessionStartDate = Instant.ofEpochMilli(session.startAt).atZone(zoneId).toLocalDate()
            if (!sessionStartDate.isBefore(start) && sessionStartDate.isBefore(endExclusive)) {
                opens[sessionStartDate] = (opens[sessionStartDate] ?: 0) + 1
                longest[sessionStartDate] = max(longest[sessionStartDate] ?: 0L, session.durationMs)
            }

            var day = Instant.ofEpochMilli(effectiveStart).atZone(zoneId).toLocalDate()
            while (day.isBefore(endExclusive)) {
                val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
                if (dayStart >= effectiveEnd) break
                val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val segStart = max(effectiveStart, dayStart)
                val segEnd = min(effectiveEnd, dayEnd)
                if (segEnd > segStart) {
                    totals[day] = (totals[day] ?: 0L) + (segEnd - segStart)
                    night[day] = (night[day] ?: 0L) + nightOverlap(day, segStart, segEnd, zoneId)
                }
                day = day.plusDays(1)
            }
        }

        val result = ArrayList<DayStat>()
        var day = startDate
        while (day.isBefore(endExclusive)) {
            result += DayStat(
                date = day,
                totalMs = totals[day] ?: 0L,
                opens = opens[day] ?: 0,
                longestSessionMs = longest[day] ?: 0L,
                nightMs = night[day] ?: 0L
            )
            day = day.plusDays(1)
        }
        return result
    }

    private fun nightOverlap(day: LocalDate, segStart: Long, segEnd: Long, zoneId: ZoneId): Long {
        val night1Start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val night1End = day.atTime(NIGHT_END_HOUR, 0).atZone(zoneId).toInstant().toEpochMilli()
        val night2Start = day.atTime(NIGHT_START_HOUR, 0).atZone(zoneId).toInstant().toEpochMilli()
        val night2End = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return overlap(segStart, segEnd, night1Start, night1End) +
            overlap(segStart, segEnd, night2Start, night2End)
    }

    private fun overlap(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long {
        val s = max(aStart, bStart)
        val e = min(aEnd, bEnd)
        return (e - s).coerceAtLeast(0L)
    }

    private fun summarize(days: List<DayStat>): Summary {
        val total = days.sumOf { it.totalMs }
        val opens = days.sumOf { it.opens }
        return Summary(
            totalMs = total,
            opens = opens,
            avgSessionMs = if (opens > 0) total / opens else 0L,
            longestSessionMs = days.maxOfOrNull { it.longestSessionMs } ?: 0L,
            activeDays = days.count { it.totalMs > 0L },
            days = days.size,
            avgPerDayMs = if (days.isNotEmpty()) total / days.size else 0L
        )
    }

    // ── Heute ────────────────────────────────────────────────────────────

    private fun buildTodayStat(
        sessions: List<Session>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        nowMs: Long
    ): DayStat {
        val start = anchorDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = minOf(
            anchorDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            nowMs
        )
        if (end <= start) return DayStat(anchorDate, 0, 0, 0, 0)

        var total = 0L
        var opens = 0
        var longest = 0L
        var night = 0L
        sessions.forEach { session ->
            val segStart = max(session.startAt, start)
            val segEnd = min(session.endAt, end)
            if (segEnd > segStart) {
                total += segEnd - segStart
                night += nightOverlap(anchorDate, segStart, segEnd, zoneId)
            }
            if (session.startAt in start until end) {
                opens++
                longest = max(longest, session.durationMs)
            }
        }
        return DayStat(anchorDate, total, opens, longest, night)
    }

    private fun buildTodaySessions(
        sessions: List<Session>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        nowMs: Long
    ): Map<String, List<SessionRow>> {
        val start = anchorDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = minOf(
            anchorDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            nowMs
        )
        return sessions
            .asSequence()
            .filter { it.startAt in start until end }
            .groupBy { it.packageName }
            .mapValues { (_, list) ->
                list.sortedByDescending { it.startAt }.map { SessionRow(it.startAt, it.durationMs) }
            }
    }

    private fun buildHourlyToday(
        sessions: List<Session>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        nowMs: Long
    ): List<Long> {
        val buckets = LongArray(24)
        val start = anchorDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = minOf(
            anchorDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            nowMs
        )
        if (end <= start) return buckets.toList()
        sessions.forEach { session ->
            var cursor = max(session.startAt, start)
            val effectiveEnd = min(session.endAt, end)
            while (cursor < effectiveEnd) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
                val hourEnd = zoned.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                    .plusHours(1).toInstant().toEpochMilli()
                val segEnd = min(effectiveEnd, hourEnd)
                buckets[zoned.hour.coerceIn(0, 23)] += (segEnd - cursor).coerceAtLeast(0L)
                cursor = segEnd
            }
        }
        return buckets.toList()
    }

    // ── Pro App ──────────────────────────────────────────────────────────

    private fun buildAppStats(
        watched: List<WatchedApp>,
        sessions: List<Session>,
        start: Long,
        endExclusive: Long,
        totalMs: Long,
        previousStart: Long,
        previousEndExclusive: Long
    ): List<AppStat> {
        data class Acc(var total: Long = 0L, var opens: Int = 0, var longest: Long = 0L)

        val current = HashMap<String, Acc>()
        val previous = HashMap<String, Acc>()

        sessions.forEach { session ->
            val curOverlap = overlap(session.startAt, session.endAt, start, endExclusive)
            if (curOverlap > 0L || (session.startAt in start until endExclusive)) {
                val acc = current.getOrPut(session.packageName) { Acc() }
                acc.total += curOverlap
                if (session.startAt in start until endExclusive) {
                    acc.opens += 1
                    acc.longest = max(acc.longest, session.durationMs)
                }
            }
            val prevOverlap = overlap(session.startAt, session.endAt, previousStart, previousEndExclusive)
            if (prevOverlap > 0L || (session.startAt in previousStart until previousEndExclusive)) {
                val acc = previous.getOrPut(session.packageName) { Acc() }
                acc.total += prevOverlap
                if (session.startAt in previousStart until previousEndExclusive) {
                    acc.opens += 1
                    acc.longest = max(acc.longest, session.durationMs)
                }
            }
        }

        return watched.map { app ->
            val cur = current[app.packageName] ?: Acc()
            val prev = previous[app.packageName] ?: Acc()
            AppStat(
                packageName = app.packageName,
                appLabel = app.appLabel,
                totalMs = cur.total,
                opens = cur.opens,
                avgSessionMs = if (cur.opens > 0) cur.total / cur.opens else 0L,
                longestSessionMs = cur.longest,
                share = if (totalMs > 0) (cur.total.toFloat() / totalMs).coerceIn(0f, 1f) else 0f,
                trendPercent = percentChange(cur.total, prev.total)
            )
        }.sortedWith(
            compareByDescending<AppStat> { it.totalMs }
                .thenBy { it.appLabel.lowercase() }
        )
    }

    private fun percentChange(current: Long, previous: Long): Float? {
        if (previous <= 0L) return if (current > 0L) null else 0f
        return ((current - previous).toFloat() / previous.toFloat()) * 100f
    }

    // ── Verlauf ──────────────────────────────────────────────────────────

    private fun buildTrend(
        days: List<DayStat>,
        window: Pair<LocalDate, LocalDate>,
        range: Range
    ): List<TrendPoint> {
        if (range == Range.DAYS_7) {
            return days
                .filter { !it.date.isBefore(window.first) && it.date.isBefore(window.second) }
                .map {
                    TrendPoint(
                        startDate = it.date,
                        endDateExclusive = it.date.plusDays(1),
                        totalMs = it.totalMs,
                        opens = it.opens,
                        days = 1
                    )
                }
        }

        val points = ArrayList<TrendPoint>()
        var start = window.first
        while (start.isBefore(window.second)) {
            val end = when (range) {
                Range.WEEKS_4 -> start.plusWeeks(1)
                Range.MONTHS_6 -> start.plusMonths(1)
                Range.DAYS_7 -> start.plusDays(1)
            }
            val bucket = days.filter { !it.date.isBefore(start) && it.date.isBefore(end) }
            points += TrendPoint(
                startDate = start,
                endDateExclusive = end,
                totalMs = bucket.sumOf { it.totalMs },
                opens = bucket.sumOf { it.opens },
                days = bucket.size
            )
            start = end
        }
        return points
    }

    // ── Druckindex ───────────────────────────────────────────────────────

    private fun buildScore(last7: List<DayStat>): ScoreBreakdown {
        val total = last7.sumOf { it.totalMs }
        val opens = last7.sumOf { it.opens }
        val avgDaily = if (last7.isNotEmpty()) total / last7.size else 0L
        val avgOpens = if (last7.isNotEmpty()) opens.toFloat() / last7.size else 0f
        val avgLongest = if (last7.isNotEmpty()) {
            last7.sumOf { it.longestSessionMs } / last7.size
        } else 0L
        val nightShare = if (total > 0L) {
            last7.sumOf { it.nightMs }.toFloat() / total.toFloat()
        } else 0f

        val timePoints = 40f * (avgDaily.toFloat() / TIME_REFERENCE_MS).coerceIn(0f, 1f)
        val opensPoints = 25f * (avgOpens / OPENS_REFERENCE).coerceIn(0f, 1f)
        val longestPoints = 20f * (avgLongest.toFloat() / LONGEST_REFERENCE_MS).coerceIn(0f, 1f)
        val nightPoints = 15f * (nightShare / NIGHT_REFERENCE_SHARE).coerceIn(0f, 1f)

        return ScoreBreakdown(
            timePoints = timePoints,
            opensPoints = opensPoints,
            longestPoints = longestPoints,
            nightPoints = nightPoints,
            avgDailyMs = avgDaily,
            avgOpens = avgOpens,
            avgLongestMs = avgLongest,
            nightShare = nightShare
        )
    }

    /** UI-Helfer: kompakte Dauer „1h 42m" / „8m" / „—". */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val minutes = (ms / 60_000L).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> "${hours}h ${rest}m"
            hours > 0 -> "${hours}h"
            else -> "${rest}m"
        }
    }

    /** UI-Helfer: Tageszeit „HH:mm" im System-Zeitzonenkontext. */
    fun formatTime(epochMs: Long, zoneId: ZoneId): String {
        val t: LocalTime = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
