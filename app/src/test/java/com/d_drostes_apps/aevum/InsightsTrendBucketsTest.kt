package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.ui.screens.insights.InsightPeriod
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M31: Die Balken des Verlaufs-Diagramms müssen zur oben gewählten
 * Perioden-Auswahl passen. Diese Suite prüft die Auflösung (Stunden, Tage),
 * die Zuordnung der Sessions und — besonders wichtig — dass die Summe der
 * Balken exakt die erfasste Zeit bleibt. Eine Verteilung, die pro Block neu
 * misst, würde Sessions über Mitternacht doppelt zählen; genau das ist hier
 * ausgeschlossen.
 */
class InsightsTrendBucketsTest {

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun todayUsesTwelveTwoHourBuckets() {
        val anchor = LocalDate.of(2026, 7, 19)
        val result = build(
            sessions = listOf(session("a", anchor, 9, 0, 11, 0)),
            period = InsightPeriod.Today,
            anchor = anchor
        )

        assertThat(result.trendBuckets).hasSize(12)
        assertThat(result.trendBuckets[0].label).isEqualTo("00")
        assertThat(result.trendBuckets[2].label).isEqualTo("04")
        assertThat(result.trendBuckets[4].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets[5].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets.sumOf { it.durationMs }).isEqualTo(2 * HOUR)
        assertThat(result.trendBuckets[4].detail).contains("08:00")
        assertThat(result.trendBuckets[4].detail).contains("10:00")
    }

    @Test
    fun weekUsesSevenCalendarDaysStartingMonday() {
        val anchor = LocalDate.of(2026, 7, 19) // Sonntag
        val wednesday = LocalDate.of(2026, 7, 15)
        val result = build(
            sessions = listOf(session("a", wednesday, 10, 0, 12, 0)),
            period = InsightPeriod.Week,
            anchor = anchor
        )

        assertThat(result.trendBuckets).hasSize(7)
        // Montag = Index 0, Mittwoch = Index 2.
        assertThat(result.trendBuckets[2].durationMs).isEqualTo(2 * HOUR)
        assertThat(result.trendBuckets.sumOf { it.durationMs }).isEqualTo(2 * HOUR)
        // Detail-Label trägt den konkreten Tag (nicht nur den Wochentag).
        assertThat(result.trendBuckets[2].detail).contains("15")
    }

    @Test
    fun monthUsesEveryDayOfTheMonth() {
        val anchor = LocalDate.of(2026, 7, 19)
        val result = build(
            sessions = listOf(
                session("first", LocalDate.of(2026, 7, 1), 9, 0, 10, 0),
                session("last", LocalDate.of(2026, 7, 31), 9, 0, 10, 0)
            ),
            period = InsightPeriod.Month,
            anchor = anchor
        )

        assertThat(result.trendBuckets).hasSize(31)
        assertThat(result.trendBuckets[0].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets[30].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets.sumOf { it.durationMs }).isEqualTo(2 * HOUR)
    }

    @Test
    fun sessionAcrossMidnightIsSplitWithoutDoubleCounting() {
        val anchor = LocalDate.of(2026, 7, 19)
        val result = build(
            sessions = listOf(session("night", LocalDate.of(2026, 7, 15), 23, 0, 25, 0)),
            period = InsightPeriod.Week,
            anchor = anchor
        )

        // 23:00–01:00: eine Stunde am Mittwoch, eine am Donnerstag.
        assertThat(result.trendBuckets[2].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets[3].durationMs).isEqualTo(HOUR)
        assertThat(result.trendBuckets.sumOf { it.durationMs }).isEqualTo(2 * HOUR)
    }

    @Test
    fun emptyPeriodProducesOnlyZeroBuckets() {
        val anchor = LocalDate.of(2026, 7, 19)
        val result = build(emptyList(), InsightPeriod.Week, anchor)

        assertThat(result.trendBuckets).hasSize(7)
        assertThat(result.trendBuckets.all { it.durationMs == 0L }).isTrue()
        assertThat(result.hasData).isFalse()
    }

    private fun build(
        sessions: List<ActivitySession>,
        period: InsightPeriod,
        anchor: LocalDate
    ) = InsightsAnalytics.build(
        sessions = sessions,
        categories = emptyList(),
        activityTypes = emptyList(),
        selectedPeriod = period,
        anchorDate = anchor,
        zoneId = zone
    )

    /**
     * Session von [startHour]:[startMinute] bis [endHour]:[endMinute].
     * Stunden > 23 laufen in den Folgetag (für Mitternachts-Tests).
     */
    private fun session(
        id: String,
        date: LocalDate,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ): ActivitySession {
        val start = date.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
        val endDate = if (endHour >= 24) date.plusDays(1) else date
        val end = endDate.atTime(endHour % 24, endMinute).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = id,
            title = id,
            startAt = start,
            endAt = end
        )
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
