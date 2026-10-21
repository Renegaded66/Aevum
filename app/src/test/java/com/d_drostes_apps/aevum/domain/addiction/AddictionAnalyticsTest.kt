package com.d_drostes_apps.aevum.domain.addiction

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M27: Beweise für die Auswertung des Sucht-Detektors.
 *
 * Die wichtigsten Semantiken:
 *  - „Heute" ist live, Vergleichszeiträume enden GESTERN (bzw. mit der
 *    letzten vollen Woche/Monat),
 *  - Öffnungen zählen am Starttag, Dauer wird an Mitternacht geteilt,
 *  - <3-Sekunden-Schnipsel sind Rauschen,
 *  - der Druckindex nutzt nur die letzten 7 vollen Tage.
 */
class AddictionAnalyticsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val anchor = LocalDate.of(2026, 10, 21) // Mittwoch
    private val ig = "com.instagram.android"
    private val tiktok = "com.zhiliaoapp.musically"
    private val watched = listOf(
        AddictionAnalytics.WatchedApp(ig, "Instagram"),
        AddictionAnalytics.WatchedApp(tiktok, "TikTok")
    )

    private fun t(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun session(pkg: String, date: LocalDate, hour: Int, minute: Int, durationMin: Int): AddictionAnalytics.Session {
        val start = t(date, hour, minute)
        return AddictionAnalytics.Session(pkg, start, start + durationMin * 60_000L)
    }

    private fun analyze(
        sessions: List<AddictionAnalytics.Session>,
        range: AddictionAnalytics.Range = AddictionAnalytics.Range.DAYS_7
    ) = AddictionAnalytics.analyze(
        watchedApps = watched,
        sessions = sessions,
        anchorDate = anchor,
        zoneId = zone,
        range = range,
        nowMs = LocalDateTime.of(anchor, java.time.LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    )

    @Test
    fun `7-Tage-Vergleich-endet-gestern-und-heute-bleibt-separat`() {
        val sessions = listOf(
            session(ig, anchor.minusDays(1), 10, 0, 10),
            session(ig, anchor.minusDays(1), 18, 0, 5),
            session(tiktok, anchor.minusDays(3), 14, 0, 40),
            session(ig, anchor, 9, 0, 5)
        )

        val result = analyze(sessions)

        // Vergleich: 10 + 5 + 40 = 55 min, 3 Öffnungen. HEUTE (5 min) fehlt.
        assertThat(result.summary.totalMs).isEqualTo(55L * 60_000)
        assertThat(result.summary.opens).isEqualTo(3)
        assertThat(result.summary.activeDays).isEqualTo(2)
        assertThat(result.summary.days).isEqualTo(7)

        // Heute separat.
        assertThat(result.today.totalMs).isEqualTo(5L * 60_000)
        assertThat(result.today.opens).isEqualTo(1)
        assertThat(result.todaySessions.getValue(ig)).hasSize(1)
        assertThat(result.todaySessions.getValue(ig).first().durationMs).isEqualTo(5L * 60_000)
    }

    @Test
    fun `Mitternachtssitzung-oeffnet-am-Starttag-und-teilt-die-Dauer`() {
        // 19.10. 23:50 → 20.10. 00:20 = 30 min.
        val sessions = listOf(session(ig, anchor.minusDays(2), 23, 50, 30))

        val result = analyze(sessions)
        val oct19 = result.trend.first { it.startDate == anchor.minusDays(2) }
        val oct20 = result.trend.first { it.startDate == anchor.minusDays(1) }

        assertThat(oct19.totalMs).isEqualTo(10L * 60_000)
        assertThat(oct19.opens).isEqualTo(1)
        assertThat(oct20.totalMs).isEqualTo(20L * 60_000)
        assertThat(oct20.opens).isEqualTo(0) // keine zweite Öffnung um Mitternacht
        assertThat(result.summary.opens).isEqualTo(1)
        assertThat(result.summary.totalMs).isEqualTo(30L * 60_000)
    }

    @Test
    fun `Wochen-und-Monatsfenster-nutzen-nur-volle-Perioden`() {
        val empty = analyze(emptyList(), AddictionAnalytics.Range.WEEKS_4)
        assertThat(empty.periodStart).isEqualTo(LocalDate.of(2026, 9, 21))
        assertThat(empty.periodEndExclusive).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(empty.trend).hasSize(4)

        val months = analyze(emptyList(), AddictionAnalytics.Range.MONTHS_6)
        assertThat(months.periodStart).isEqualTo(LocalDate.of(2026, 4, 1))
        assertThat(months.periodEndExclusive).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(months.trend).hasSize(6)

        val days = analyze(emptyList(), AddictionAnalytics.Range.DAYS_7)
        assertThat(days.periodStart).isEqualTo(anchor.minusDays(7))
        assertThat(days.periodEndExclusive).isEqualTo(anchor)
        assertThat(days.trend).hasSize(7)
    }

    @Test
    fun `Schnipsel-unter-drei-Sekunden-zaehlen-nicht`() {
        val start = t(anchor.minusDays(1), 12, 0)
        val sessions = listOf(
            AddictionAnalytics.Session(ig, start, start + 2_000L),
            AddictionAnalytics.Session(ig, start + 60_000L, start + 63_000L) // genau 3 s
        )

        val result = analyze(sessions)

        assertThat(result.summary.opens).isEqualTo(1)
        assertThat(result.summary.totalMs).isEqualTo(3_000L)
    }

    @Test
    fun `Druckindex-nutzt-sieben-volle-Tage`() {
        val sessions = (0..6).map { offset ->
            session(ig, anchor.minusDays((offset + 1).toLong()), 10, 0, 240)
        }

        val result = analyze(sessions)

        assertThat(result.scoreAvailable).isTrue()
        assertThat(result.scoreDaysWithData).isEqualTo(7)
        assertThat(result.scoreBreakdown.avgDailyMs).isEqualTo(4L * 60 * 60 * 1000)
        // 40 Zeit + ~0,36 Öffnungen + 20 längste Sitzung + 0 Nacht.
        assertThat(result.score).isIn(58..62)
    }

    @Test
    fun `Nachtanteil-zaehlt-22-bis-6-Uhr`() {
        val sessions = listOf(session(ig, anchor.minusDays(1), 23, 0, 30))

        val result = analyze(sessions)

        assertThat(result.scoreBreakdown.nightShare).isWithin(0.001f).of(1f)
        assertThat(result.scoreBreakdown.nightPoints).isWithin(0.01f).of(15f)
    }

    @Test
    fun `App-Trend-vergleicht-mit-der-Vorperiode`() {
        val sessions = listOf(
            session(ig, anchor.minusDays(2), 10, 0, 60), // aktuelle 7 Tage
            session(ig, anchor.minusDays(9), 10, 0, 30)  // Vorperiode
        )

        val result = analyze(sessions)
        val instagram = result.appStats.first { it.packageName == ig }

        assertThat(instagram.totalMs).isEqualTo(60L * 60_000)
        assertThat(instagram.trendPercent).isWithin(0.01f).of(100f)
        assertThat(instagram.share).isWithin(0.001f).of(1f)
    }

    @Test
    fun `Ohne-Daten-ist-der-Druckindex-nicht-verfuegbar`() {
        val result = analyze(emptyList())

        assertThat(result.scoreAvailable).isFalse()
        assertThat(result.score).isEqualTo(0)
        assertThat(result.summary.totalMs).isEqualTo(0)
        assertThat(result.trackedAppCount).isEqualTo(2)
    }
}
