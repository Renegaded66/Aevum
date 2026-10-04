package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsAnalytics
import com.d_drostes_apps.aevum.ui.screens.activitystats.ActivityStatsRange
import com.d_drostes_apps.aevum.ui.screens.activitystats.BucketUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.146b: Fenster-Analytik der Aktivitäts-Statistikseite.
 *
 * Der Nutzer präzisierte: Die Ansichten sind FENSTER (letzte 7 Tage, letzte
 * 30 Tage, letzte 365 Tage, Gesamtzeit) — keine Kalender-Granularität.
 * Diese Suite sichert die Kern-Eigenschaften ab: adaptiver Bucket-Schnitt
 * (Tag/Woche/Monat je Fenstergröße), die drei Tages-Fenster summieren über
 * EXAKT dieselbe Tagesbasis (konsistent per Konstruktion — der Fehlertyp
 * des Pauschalen-Bugs), der 365-Tage-Verlauf bucketiert kalendarisch in
 * Wochen, und die Gesamtzeit deckt die komplette Historie ab.
 */
class ActivityStatsAnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    /** Fester Referenztag: Sonntag, 4. Oktober 2026 (Bug-Report-Szenario). */
    private val today: LocalDate = LocalDate.of(2026, 10, 4)

    // ---------------------------------------------------------------
    // Fenster-Grenzen + adaptiver Bucket-Schnitt
    // ---------------------------------------------------------------

    @Test
    fun days7WindowIsExactlySevenDailyBucketsEndingToday() {
        val buckets = ActivityStatsAnalytics.bucketStarts(
            ActivityStatsRange.Days7, today, earliest = null, unit = BucketUnit.Daily
        )

        assertThat(buckets).hasSize(7)
        assertThat(buckets.last()).isEqualTo(today)
        assertThat(buckets.first()).isEqualTo(today.minusDays(6))
    }

    @Test
    fun days30WindowIsExactlyThirtyDailyBucketsEndingToday() {
        val buckets = ActivityStatsAnalytics.bucketStarts(
            ActivityStatsRange.Days30, today, earliest = null, unit = BucketUnit.Daily
        )

        assertThat(buckets).hasSize(30)
        assertThat(buckets.first()).isEqualTo(today.minusDays(29))
    }

    @Test
    fun days365WindowBucketsInCalendarWeeks() {
        val buckets = ActivityStatsAnalytics.bucketStarts(
            ActivityStatsRange.Days365, today, earliest = null, unit = BucketUnit.Weekly
        )

        // 4. Okt 2026 ist ein Sonntag → die Woche des Fensters endet am
        // 28. Sep (Montag). Jeder Bucket-Start ist ein Montag.
        assertThat(buckets.last()).isEqualTo(LocalDate.of(2026, 9, 28))
        assertThat(buckets.all { it.dayOfWeek.value == 1 }).isTrue()
        // Der früheste Bucket beginnt (auf Montag normalisiert) nicht später
        // als der Fensterbeginn.
        assertThat(buckets.first().isBefore(today.minusDays(364).plusDays(7))).isTrue()
    }

    @Test
    fun bucketEndExclusiveDoesNotOverflow() {
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(LocalDate.of(2026, 10, 1), BucketUnit.Monthly))
            .isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(LocalDate.of(2026, 9, 28), BucketUnit.Weekly))
            .isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(ActivityStatsAnalytics.bucketEndExclusive(today, BucketUnit.Daily))
            .isEqualTo(today.plusDays(1))
    }

    // ---------------------------------------------------------------
    // Adaptiver Schnitt der Gesamtzeit
    // ---------------------------------------------------------------

    @Test
    fun allTimeBucketUnitAdaptsToHistoryLength() {
        // Kurze Historie (30 Tage) → tägliche Buckets.
        assertThat(
            ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.All, today, today.minusDays(29))
        ).isEqualTo(BucketUnit.Daily)
        // Historie = 62 Tage → noch gerade täglich (Limit inklusive).
        assertThat(
            ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.All, today, today.minusDays(61))
        ).isEqualTo(BucketUnit.Daily)
        // Historie = 63 Tage → wöchentlich.
        assertThat(
            ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.All, today, today.minusDays(62))
        ).isEqualTo(BucketUnit.Weekly)
        // Historie = 2 Jahre + 1 Tag → monatlich.
        assertThat(
            ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.All, today, today.minusDays(731))
        ).isEqualTo(BucketUnit.Monthly)
    }

    @Test
    fun allTimeCoversTheEntireHistory() {
        val earliest = today.minusDays(200)
        val buckets = ActivityStatsAnalytics.bucketStarts(
            ActivityStatsRange.All, today, earliest, BucketUnit.Weekly
        )

        // Erster Bucket beginnt (auf Wochen-Anfang normalisiert) bei der
        // frühesten Aufzeichnung, letzter in der aktuellen Woche.
        assertThat(buckets.first().isBefore(earliest.plusDays(7))).isTrue()
        assertThat(buckets.last()).isEqualTo(LocalDate.of(2026, 9, 28))
    }

    @Test
    fun fixedWindowsKeepTheirUnitRegardlessOfHistory() {
        val earliest = today.minusDays(1000)
        // 7/30 Tage bleiben täglich; 365 Tage bleibt wöchentlich — unabhängig
        // davon, wie alt die Historie ist.
        assertThat(ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.Days7, today, earliest))
            .isEqualTo(BucketUnit.Daily)
        assertThat(ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.Days30, today, earliest))
            .isEqualTo(BucketUnit.Daily)
        assertThat(ActivityStatsAnalytics.bucketUnitFor(ActivityStatsRange.Days365, today, earliest))
            .isEqualTo(BucketUnit.Weekly)
    }

    // ---------------------------------------------------------------
    // Konsistenz der Fenster — EINE Tagesbasis
    // ---------------------------------------------------------------

    /**
     * KERN-Eigenschaft: Die Gesamt-Summe entspricht über alle Fenster exakt
     * derselben aufgezeichneten Zeit, nur anders gruppiert/geclippt. Zwei
     * verschiedene Definitionen (der alte Bug-Typ) würden hier auseinanderlaufen.
     */
    @Test
    fun allWindowsAgreeOnTheSameData() {
        val sessions = listOf(
            session("a", daysAgo = 0, startHour = 8, durationMinutes = 120),
            session("b", daysAgo = 3, startHour = 10, durationMinutes = 90),
            session("c", daysAgo = 10, startHour = 14, durationMinutes = 45),
            session("d", daysAgo = 25, startHour = 9, durationMinutes = 60),
            session("e", daysAgo = 100, startHour = 9, durationMinutes = 60)
        )

        val d7 = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days7, today, zone)
        val d30 = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)
        val d365 = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days365, today, zone)
        val all = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.All, today, zone)

        // Letzte 7 Tage: nur a + b.
        assertThat(d7.totalMs).isEqualTo(210 * 60_000L)
        // Letzte 30 Tage: a + b + c + d.
        assertThat(d30.totalMs).isEqualTo(315 * 60_000L)
        // 365 Tage + Gesamtzeit: alle fünf.
        assertThat(d365.totalMs).isEqualTo(375 * 60_000L)
        assertThat(all.totalMs).isEqualTo(375 * 60_000L)

        // Und die Buckets summieren sich jeweils zur Gesamt-Zeit.
        assertThat(d7.buckets.sumOf { it.durationMs }).isEqualTo(d7.totalMs)
        assertThat(d30.buckets.sumOf { it.durationMs }).isEqualTo(d30.totalMs)
        assertThat(d365.buckets.sumOf { it.durationMs }).isEqualTo(d365.totalMs)
        assertThat(all.buckets.sumOf { it.durationMs }).isEqualTo(all.totalMs)
    }

    /**
     * Guard aus dem Original-Bug-Muster: 30-Tage-Fenster und Gesamtzeit
     * MÜSSEN sich unterscheiden, sobald ältere Daten existieren — sonst
     * wäre „Gesamtzeit" heimlich wieder „letzte N Tage".
     */
    @Test
    fun totalWindowDiffersFrom30DaysWhenOlderDataExists() {
        val sessions = listOf(
            session("a", daysAgo = 5, startHour = 8, durationMinutes = 60),
            session("old", daysAgo = 200, startHour = 8, durationMinutes = 30)
        )
        val d30 = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)
        val all = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.All, today, zone)

        assertThat(d30.totalMs).isEqualTo(60 * 60_000L)
        assertThat(all.totalMs).isEqualTo(90 * 60_000L)
        assertThat(all.totalMs).isNotEqualTo(d30.totalMs)
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

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.totalMs).isEqualTo(60 * 60_000L)
        assertThat(result.sessionCount).isEqualTo(1)
    }

    @Test
    fun deletedSessionsAreExcluded() {
        val sessions = listOf(
            session("a", daysAgo = 1, startHour = 8, durationMinutes = 60)
                .copy(deletedAt = 1L)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

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

        val result = ActivityStatsAnalytics.build(listOf(session), "deep_work", ActivityStatsRange.Days30, today, zone)

        // Beide Tage sind im Fenster und zählen je 1h.
        assertThat(result.totalMs).isEqualTo(2 * 60 * 60_000L)
        assertThat(result.activeDays).isEqualTo(2)
    }

    @Test
    fun sessionsOutsideTheWindowShowEmptyState() {
        // 40 Tage zurück liegt außerhalb des 30-Tage-Fensters → der
        // Leerzustand gilt, statt eines Null-Charts.
        val result = ActivityStatsAnalytics.build(
            listOf(session("old", daysAgo = 40, startHour = 8, durationMinutes = 60)),
            "deep_work", ActivityStatsRange.Days30, today, zone
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

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

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

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.longestMs).isEqualTo(150 * 60_000L)
        assertThat(result.totalMs).isEqualTo(240 * 60_000L)
    }

    @Test
    fun avgPerDayDividesByAllDaysInWindowNotJustActiveOnes() {
        val sessions = listOf(session("a", daysAgo = 0, startHour = 8, durationMinutes = 300))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        // 300 min über 30 Tage = 10 min/Tag.
        assertThat(result.avgPerDayMs).isEqualTo(10 * 60_000L)
    }

    @Test
    fun avgPerWeekDividesByWholeWeeksInWindow() {
        val sessions = listOf(session("a", daysAgo = 0, startHour = 8, durationMinutes = 210))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        // 210 min über 30 Tage: Ø/Woche = 210 / (30/7 = 4 ganze Wochen, ceil
        // nicht — floor) = 52,5 min → als ms 52 min 500 000 ms. Die Division
        // ist ganzzahlig: 210*60000 / 4 = 3_150_000 ms = 52,5 min.
        assertThat(result.avgPerWeekMs).isEqualTo(3_150_000L)
    }

    @Test
    fun weekdayTotalsUseMondayFirstIndexing() {
        // 4. Okt 2026 ist ein Sonntag (Index 6); 2. Okt ist ein Freitag (4).
        val sessions = listOf(
            session("sun", daysAgo = 0, startHour = 8, durationMinutes = 60),
            session("fri", daysAgo = 2, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.weekdayMs[6]).isEqualTo(60 * 60_000L) // Sonntag
        assertThat(result.weekdayMs[4]).isEqualTo(30 * 60_000L) // Freitag
        assertThat(result.weekdayMs[0]).isEqualTo(0L) // Montag leer
    }

    // ---------------------------------------------------------------
    // Trend
    // ---------------------------------------------------------------

    @Test
    fun trendComparesAgainstTheEquallyLongPreviousWindow() {
        // Fenster: letzte 30 Tage. Das Vorfenster: die 30 Tage davor.
        val sessions = listOf(
            // Vorfenster: 60 min
            session("prev", daysAgo = 35, startHour = 8, durationMinutes = 60),
            // Aktuelles Fenster: 90 min → +50 %
            session("now", daysAgo = 2, startHour = 8, durationMinutes = 90)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.trendPercent).isEqualTo(50)
    }

    @Test
    fun trendIsNullWithoutPreviousData() {
        val sessions = listOf(session("now", daysAgo = 2, startHour = 8, durationMinutes = 90))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.trendPercent).isNull()
    }

    /**
     * Gesamtzeit hat KEINEN Trend — die Gesamthistorie hat kein „davor";
     * ein Vergleich gegen eine leere Vorzeit wäre eine erfundene Zahl.
     */
    @Test
    fun allTimeHasNoTrend() {
        val sessions = listOf(session("a", daysAgo = 2, startHour = 8, durationMinutes = 90))

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.All, today, zone)

        assertThat(result.hasData).isTrue()
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
    // Persistenz-Migration (alte Granularität → Fenster)
    // ---------------------------------------------------------------

    @Test
    fun storageMigrationMapsOldGranularityToNearestWindow() {
        assertThat(ActivityStatsRange.fromStorage("Daily")).isEqualTo(ActivityStatsRange.Days7)
        assertThat(ActivityStatsRange.fromStorage("Weekly")).isEqualTo(ActivityStatsRange.Days30)
        assertThat(ActivityStatsRange.fromStorage("Monthly")).isEqualTo(ActivityStatsRange.Days365)
        assertThat(ActivityStatsRange.fromStorage(null)).isEqualTo(ActivityStatsRange.Days30)
        assertThat(ActivityStatsRange.fromStorage("Days7")).isEqualTo(ActivityStatsRange.Days7)
        assertThat(ActivityStatsRange.fromStorage("All")).isEqualTo(ActivityStatsRange.All)
        assertThat(ActivityStatsRange.fromStorage("Unbekannt")).isEqualTo(ActivityStatsRange.Days30)
    }

    // ---------------------------------------------------------------
    // Labels & Leerzustand
    // ---------------------------------------------------------------

    @Test
    fun labelsAreNonEmptyAcrossUnits() {
        val result = ActivityStatsAnalytics.build(
            listOf(session("a", daysAgo = 1, startHour = 8, durationMinutes = 30)),
            "deep_work", ActivityStatsRange.Days30, today, zone
        )

        assertThat(result.buckets.all { it.label.isNotEmpty() }).isTrue()
        assertThat(result.buckets.all { it.fullLabel.isNotEmpty() }).isTrue()
        // Alle Buckets des 30-Tage-Fensters sind Tages-Buckets.
        assertThat(result.buckets.all { it.unit == BucketUnit.Daily }).isTrue()
        assertThat(result.hasData).isTrue()
    }

    @Test
    fun emptySessionsProduceNoDataAndNoBuckets() {
        val result = ActivityStatsAnalytics.build(emptyList(), "deep_work", ActivityStatsRange.Days30, today, zone)

        assertThat(result.hasData).isFalse()
        assertThat(result.buckets).isEmpty()
        assertThat(result.totalMs).isEqualTo(0L)
    }

    @Test
    fun sessionCountsAreAttributedToTheirBuckets() {
        val sessions = listOf(
            session("a", daysAgo = 0, startHour = 8, durationMinutes = 30),
            session("b", daysAgo = 0, startHour = 10, durationMinutes = 30),
            session("c", daysAgo = 6, startHour = 8, durationMinutes = 30)
        )

        val result = ActivityStatsAnalytics.build(sessions, "deep_work", ActivityStatsRange.Days7, today, zone)

        assertThat(result.sessionCount).isEqualTo(3)
        assertThat(result.buckets.last().sessionCount).isEqualTo(2) // heute
        assertThat(result.buckets.first { it.start == today.minusDays(6) }.sessionCount).isEqualTo(1)
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