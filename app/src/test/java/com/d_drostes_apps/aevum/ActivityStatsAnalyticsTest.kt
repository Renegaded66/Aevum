package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsAnalytics
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsRange
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.146: Verlaufs-Analytik der Aktivitäts-Statistikseite.
 *
 * Die Seite hat EINE Tagesbasis, aus der täglich/wöchentlich/monatlich
 * gebucketet wird — die Tests sichern genau das ab: die drei Sichten sind
 * per Konstruktion konsistent (gleiche Fenster-Tage, nur anders gruppiert),
 * Sessions werden auf Tagesgrenzen geclippt, und der Trend vergleicht mit
 * dem gleich langen Fenster davor. Genau dieser Fehlertyp (zwei divergierende
 * Perioden-Definitionen) war der Auslöser des Pauschalen-Bugs.
 */
class ActivityStatsAnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    /** Fester Referenztag: Sonntag, 4. Oktober 2026 (Bug-Report-Szenario). */
    private val today: LocalDate = LocalDate.of(2026, 10, 4)

    // ---------------------------------------------------------------
    // Bucket-Grenzen
    // ---------------------------------------------------------------

    @Test
    fun dailyBucketsEndTodayAndCoverThirtyDays() {
        val buckets = ActivityStatsAnalytics.bucketStarts(ActivityStatsRange.Daily, today)

        assertThat(buckets).hasSize(30)
        assertThat(buckets.last()).isEqualTo(today)
        assertThat(buckets.first()).isEqualTo(today.minusDays(29))
    }

    @Test
    fun weeklyBucketsStartOnMondaysAndEndWithCurrentWeek() {
        // 4. Okt 2026 ist ein Sonntag → Kalenderwoche beginnt am 28. Sep.
        val buckets = ActivityStatsAnalytics.bucketStarts(ActivityStatsRange.Weekly, today)

        assertThat(buckets).hasSize(12)
        assertThat(buckets.last()).isEqualTo(LocalDate.of(2026, 9, 28))
        assertThat(buckets.first()).isEqualTo(LocalDate.of(2026, 7, 13))
        // Jeder Bucket-Start ist ein Montag.
        assertThat(buckets.all { it.dayOfWeek.value == 1 }).isTrue()
    }

    @Test
    fun monthlyBucketsStartOnFirstOfMonth() {
        val buckets = ActivityStatsAnalytics.bucketStarts(ActivityStatsRange.Monthly, today)

        assertThat(buckets).hasSize(12)
        assertThat(buckets.last()).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(buckets.first()).isEqualTo(LocalDate.of(2025, 11, 1))
    }

    @Test
    fun bucketEndExclusiveDoesNotOverflow() {
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(LocalDate.of(2026, 10, 1), ActivityStatsRange.Monthly))
            .isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(LocalDate.of(2026, 9, 28), ActivityStatsRange.Weekly))
            .isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(today, ActivityStatsRange.Daily))
            .isEqualTo(today.plusDays(1))
    }

    // ---------------------------------------------------------------
    // Konsistenz der drei Sichten — EINE Tagesbasis
    // ---------------------------------------------------------------

    /**
     * KERN-Eigenschaft: Die Gesamt-Summe der Buckets entspricht über alle
     * drei Granularitäten exakt derselben aufgezeichneten Zeit (nur anders
     * gruppiert). Zwei verschiedene Definitionen (der alte Bug-Typ) würden
     * hier auseinanderlaufen.
     */
    @Test
    fun dailyWeeklyMonthlyTotalsAgreeOnTheSameData() {
        val sessions = listOf(
            session("a", daysAgo = 0, startHour = 8, durationMinutes = 120),
            session("b", daysAgo = 3, startHour = 10, durationMinutes = 90),
            session("c", daysAgo = 10, startHour = 14, durationMinutes = 45),
            session("d", daysAgo = 25, startHour = 9, durationMinutes = 60)
        )

        val daily = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)
        val weekly = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Weekly, today, zone)
        val monthly = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Monthly, today, zone)

        val expectedMs = (120 + 90 + 45 + 60) * 60_000L
        assertThat(daily.totalMs).isEqualTo(expectedMs)
        assertThat(weekly.totalMs).isEqualTo(expectedMs)
        assertThat(monthly.totalMs).isEqualTo(expectedMs)

        // Und die Buckets summieren sich jeweils zur Gesamt-Zeit.
        assertThat(daily.buckets.sumOf { it.durationMs }).isEqualTo(expectedMs)
        assertThat(weekly.buckets.sumOf { it.durationMs }).isEqualTo(expectedMs)
        assertThat(monthly.buckets.sumOf { it.durationMs }).isEqualTo(expectedMs)
    }

    // ---------------------------------------------------------------
    // Filter & Clipping
    // ---------------------------------------------------------------

    @Test
    fun onlySessionsOfTheRequestedActivityCount() {
        val sessions = listOf(
            session("a", daysAgo = 1, startHour = 8, durationMinutes = 60, typeId = "deep_work"),
            session("b", daysAgo = 1, startHour = 10, durationMinutes = 30, typeId = "gym")
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.totalMs).isEqualTo(60 * 60_000L)
        assertThat(result.sessionCount).isEqualTo(1)
    }

    @Test
    fun deletedSessionsAreExcluded() {
        val sessions = listOf(
            session("a", daysAgo = 1, startHour = 8, durationMinutes = 60)
                .copy(deletedAt = today.minusDays(1).toEpochDay())
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.totalMs).isEqualTo(0L)
        assertThat(result.hasData).isFalse()
    }

    @Test
    fun midnightSpanningSessionIsSplitAcrossBothDays() {
        // 23:00 → 01:00 über Mitternacht: 1h gestern, 1h heute (Dauer 2h).
        val start = today.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = today.atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        val session = ActivitySession(
            id = "midnight", title = "deep_work", activityTypeId = "deep_work",
            categoryId = "work", startAt = start, endAt = end
        )

        val result = ActivityStatsAnalytics.build(listOf(session), "deep_work", ActivityStatsRange.Daily, today, zone)

        // Beide Tage sind im 30-Tage-Fenster und zählen je 1h.
        assertThat(result.totalMs).isEqualTo(2 * 60 * 60_000L)
        assertThat(result.activeDays).isEqualTo(2)
    }

    @Test
    fun sessionsOutsideTheWindowAreIgnored() {
        // 40 Tage zurück liegt außerhalb des 30-Tage-Fensters (und außerhalb
        // des Vormonats-Vergleichsfensters).
        val result = ActivityStatsAnalytics.build(
            listOf(session("old", daysAgo = 40, startHour = 8, durationMinutes = 60)),
            "deep_work", ActivityStatsRange.Daily, today, zone
        )

        assertThat(result.totalMs).isEqualTo(0L)
        assertThat(result.hasData).isFalse()
    }

    // ---------------------------------------------------------------
    // Kennzahlen
    // ---------------------------------------------------------------

    @Test
    fun activeDaysAndBestStreakCountConsecutiveDays() {
        val sessions = listOf(
            session("d1", daysAgo = 4, startHour = 8, durationMinutes = 30),
            session("d2", daysAgo = 3, startHour = 8, durationMinutes = 30),
            session("d3", daysAgo = 2, startHour = 8, durationMinutes = 30),
            // Lücke bei daysAgo = 1
            session("d4", daysAgo = 0, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.activeDays).isEqualTo(4)
        assertThat(result.bestStreak).isEqualTo(3)
    }

    @Test
    fun longestIsTheLongestSingleSessionNotTheTotal() {
        val sessions = listOf(
            session("a", daysAgo = 2, startHour = 8, durationMinutes = 60),
            session("b", daysAgo = 1, startHour = 8, durationMinutes = 150),
            session("c", daysAgo = 0, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.longestMs).isEqualTo(150 * 60_000L)
        assertThat(result.totalMs).isEqualTo(240 * 60_000L)
    }

    @Test
    fun avgPerDayDividesByAllDaysInWindowNotJustActiveOnes() {
        val sessions = listOf(session("a", daysAgo = 0, startHour = 8, durationMinutes = 300))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        // 300 min über 30 Tage = 10 min/Tag.
        assertThat(result.avgPerDayMs).isEqualTo(10 * 60_000L)
    }

    @Test
    fun weekdayTotalsUseMondayFirstIndexing() {
        // 4. Okt 2026 ist ein Sonntag (Index 6); 2. Okt ist ein Freitag (4).
        val sessions = listOf(
            session("sun", daysAgo = 0, startHour = 8, durationMinutes = 60),
            session("fri", daysAgo = 2, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.weekdayMs[6]).isEqualTo(60 * 60_000L) // Sonntag
        assertThat(result.weekdayMs[4]).isEqualTo(30 * 60_000L) // Freitag
        assertThat(result.weekdayMs[0]).isEqualTo(0L) // Montag leer
    }

    // ---------------------------------------------------------------
    // Trend
    // ---------------------------------------------------------------

    @Test
    fun trendComparesAgainstTheEquallyLongPreviousWindow() {
        // Fenster: letzte 30 Tage. Vormonat davor: 30 Tage vorher.
        val sessions = listOf(
            // Vormonat: 60 min
            session("prev", daysAgo = 35, startHour = 8, durationMinutes = 60),
            // Aktuelles Fenster: 90 min → +50 %
            session("now", daysAgo = 2, startHour = 8, durationMinutes = 90)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.trendPercent).isEqualTo(50)
    }

    @Test
    fun trendIsNullWithoutPreviousData() {
        val sessions = listOf(session("now", daysAgo = 2, startHour = 8, durationMinutes = 90))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.trendPercent).isNull()
    }

    @Test
    fun trendPercentFormulaHandlesDeclineAndFlat() {
        assertThat(ActivityStatsAnalytics.trendPercent(120, 60)).isEqualTo(100)
        assertThat(ActivityStatsAnalytics.trendPercent(30, 60)).isEqualTo(-50)
        assertThat(ActivityStatsAnalytics.trendPercent(60, 60)).isEqualTo(0)
        assertThat(ActivityStatsAnalytics.trendPercent(60, 0)).isNull()
    }

    // ---------------------------------------------------------------
    // Labels & Leerzustand
    // ---------------------------------------------------------------

    @Test
    fun labelsAreLocalizedAndNonEmpty() {
        val result = ActivityStatsAnalytics.build(
            listOf(session("a", daysAgo = 1, startHour = 8, durationMinutes = 30)),
            "deep_work", ActivityStatsRange.Daily, today, zone
        )

        assertThat(result.buckets.all { it.label.isNotEmpty() }).isTrue()
        assertThat(result.buckets.all { it.fullLabel.isNotEmpty() }).isTrue()
        assertThat(result.hasData).isTrue()
    }

    @Test
    fun emptySessionsProduceNoDataButTheFullTimeline() {
        val result = ActivityStatsAnalytics.build(emptyList(), "deep_work", ActivityStatsRange.Daily, today, zone)

        // Kein Datenzustand — die UI zeigt den Leerzustand statt des Charts.
        assertThat(result.hasData).isFalse()
        assertThat(result.totalMs).isEqualTo(0L)
        // Die Buckets bleiben trotzdem die vollständige Zeitachse: sie
        // beschreiben den leeren Zeitraum, nicht Datenpunkte.
        assertThat(result.buckets).hasSize(30)
        assertThat(result.buckets.all { it.durationMs == 0L }).isTrue()
    }

    @Test
    fun sessionCountsAreAttributedToTheirBuckets() {
        val sessions = listOf(
            session("a", daysAgo = 0, startHour = 8, durationMinutes = 30),
            session("b", daysAgo = 0, startHour = 10, durationMinutes = 30),
            session("c", daysAgo = 7, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Daily, today, zone)

        assertThat(result.sessionCount).isEqualTo(3)
        assertThat(result.buckets.last().sessionCount).isEqualTo(2) // heute
        assertThat(result.buckets.first { it.start == today.minusDays(7) }.sessionCount).isEqualTo(1)
    }

    // ---------------------------------------------------------------
    // Helfer
    // ---------------------------------------------------------------

    private fun session(
        id: String,
        daysAgo: Long,
        startHour: Int,
        durationMinutes: Int,
        typeId: String = "deep_work"
    ): ActivitySession {
        val date = today.minusDays(daysAgo)
        val start = date.atTime(startHour, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id,
            title = typeId,
            activityTypeId = typeId,
            categoryId = "work",
            startAt = start,
            endAt = start + durationMinutes * 60_000L
        )
    }
}
