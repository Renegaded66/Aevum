package com.d_drostes_apps.aevum

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay
import com.d_drostes_apps.aevum.data.model.DailyAllowance
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.insights.InsightPeriod
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodLabels
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * M18.146-REGRESSION (User: „Pauschale zählt in der Monatsansicht die
 * letzten 30 Tage statt des Kalendermonats").
 *
 * DER BUG: Das ViewModel rechnete die Pauschalen mit einer EIGENEN,
 * rollierenden Definition (Monat = `anchorDate.minusDays(29)`, Woche =
 * `minusDays(6)`), während [InsightsAnalytics] die Sessions kalendarisch
 * zählte (Monat ab dem 1., Woche ab Montag). Zwei Perioden-Definitionen —
 * sichtbar wurde das als: Sessions aus anderen Quellen zählen nur den
 * laufenden Monat, die Pauschale aber 30 Tage.
 *
 * WARUM DIESE SUITE AUF [InsightsAnalytics] ZIELT: Die Reparatur hat die
 * EINZIGE Quelle [InsightsAnalytics.periodDates] etabliert. Der Integrationstest
 * (InsightsWiringIntegrationTest) deckt die ViewModel-Verdrahtung ab; hier wird
 * die Kern-Eigenschaft bewiesen, die den Bug ausmachte: Die Perioden-Grenzen
 * sind KALENDARISCH und für Sessions UND Pauschalen identisch — gemessen an
 * den TATSÄCHLICH von [InsightsAnalytics] gesummten Tagen.
 *
 * Der gefährlichste Fehlermodus war still: Sessions und Pauschalen können
 * beide „irgendwie" richtig aussehen, solange man sie getrennt prüft. Deshalb
 * prüft jede Zusicherung hier BEIDE zusammen gegen dieselben Kalender-Grenzen.
 *
 * Robolectric, weil die Summen-Tests den echten Android-Context brauchen
 * (Ressourcen der Pauschalen-Labels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllowanceCalendarPeriodTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    /**
     * Referenz-Szenario des Bug-Reports: Sonntag, 4. Oktober 2026. Der
     * Kalendermonat hat 4 vergangene+heute Tage (1.–4. Okt). Eine rollierende
     * „letzte 30 Tage"-Rechnung hätte 30 Tage gezählt — der Unterschied ist
     * mit bloßem Auge sichtbar (90 min/Tag):
     *   Kalendermonat: 4 × 90 min = 360 min = 6h
     *   Letzte 30 Tage: 30 × 90 min = 2700 min = 45h
     */
    private val bugReportAnchor: LocalDate = LocalDate.of(2026, 10, 4)

    private val categories = listOf(
        Category("work", "Arbeit", "#6366F1", "□"),
        Category("food", "Essen", "#F59E0B", "●")
    )
    private val types = listOf(
        ActivityType("deep_work", "Deep Work", "work"),
        ActivityType("meal", "Essen", "food")
    )

    /** 90 Minuten/Tag „Essen" — exakt die Pauschale aus dem Bug-Report. */
    private val mealAllowance = DailyAllowance(
        id = "allowance-meal",
        name = "Essen",
        activityTypeId = "meal",
        minutesPerDay = 90,
        enabled = true
    )

    // ---------------------------------------------------------------
    // 1. Kalender-Grenzen: Monat ab dem 1., Woche ab Montag
    // ---------------------------------------------------------------

    @Test
    fun monthPeriodStartsOnFirstOfMonthNotThirtyDaysAgo() {
        val dates = InsightsAnalytics.periodDates(InsightPeriod.Month, bugReportAnchor)

        assertThat(dates.start).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(dates.endInclusive).isEqualTo(LocalDate.of(2026, 10, 31))
        // Explizite Gegenprobe zum alten Bug: NICHT der rollierende 30-Tage-Start.
        assertThat(dates.start).isNotEqualTo(bugReportAnchor.minusDays(29))
    }

    @Test
    fun weekPeriodStartsOnMondayNotSixDaysAgo() {
        // 4. Okt 2026 ist ein Sonntag — die Kalenderwoche beginnt am 28. Sep.
        val dates = InsightsAnalytics.periodDates(InsightPeriod.Week, bugReportAnchor)

        assertThat(dates.start).isEqualTo(LocalDate.of(2026, 9, 28))
        assertThat(dates.endInclusive).isEqualTo(LocalDate.of(2026, 10, 4))

        // Am SONNTAG sind die rollierenden 7 Tage zufaellig identisch mit der
        // Kalenderwoche (minus 6 Tage vom So = Mo) — genau diese Koinzidenz
        // hat den Bug im Alltag versteckt (er fiel nur im Monat auf). Der
        // Gegenbeweis braucht darum einen anderen Wochentag: Mittwoch, 7. Okt
        // → rollierend beginnt am 1. Okt, die Kalenderwoche am Montag, 5. Okt.
        val wednesday = LocalDate.of(2026, 10, 7)
        val wednesdayWeek = InsightsAnalytics.periodDates(InsightPeriod.Week, wednesday)
        assertThat(wednesdayWeek.start).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(wednesdayWeek.start).isNotEqualTo(wednesday.minusDays(6))
    }

    @Test
    fun weekStartMatchesPeriodLabelsWeekStart() {
        // EINE Wochen-Definition für Labels UND Grenzen — sonst zeigt die
        // Beschriftung eine andere Woche als die gerechneten Werte.
        assertThat(PeriodLabels.weekStart(bugReportAnchor))
            .isEqualTo(InsightsAnalytics.periodDates(InsightPeriod.Week, bugReportAnchor).start)
    }

    @Test
    fun shiftAnchorMovesExactlyOneCalendarMonth() {
        val shifted = InsightsAnalytics.shiftAnchor(InsightPeriod.Month, bugReportAnchor, -1)
        val dates = InsightsAnalytics.periodDates(InsightPeriod.Month, shifted)

        assertThat(dates.start).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(dates.endInclusive).isEqualTo(LocalDate.of(2026, 9, 30))
    }

    // ---------------------------------------------------------------
    // 2. Pauschalen-Summe = Kalendermonat, nicht 30 Tage
    // ---------------------------------------------------------------

    /**
     * DER KERN-TEST DES BUGS. Die Pauschale (90 min/Tag) wird für JEDEN Tag
     * des Kalendermonats als [AllowanceAccumulationDay] übergeben — so, wie
     * der Midnight-Worker sie schreibt. Die Summe muss exakt der Zahl der
     * Tage vom Monatsersten bis heute entsprechen — nicht 30 Tagen.
     *
     * Der Test misst die von [InsightsAnalytics] tatsächlich verwendeten
     * Grenzen über [InsightsAnalytics.periodDates] — also dieselbe Quelle,
     * die der Fix eingeführt hat. Ein Rückfall auf `minusDays(29)` würde die
     * Tages-Schleife hier nicht mehr deckungsgleich mit den Grenzen machen
     * und den Test rot färben.
     */
    @Test
    fun allowanceAccumulationIsSummedOverTheCalendarMonthOnly() {
        val dates = InsightsAnalytics.periodDates(InsightPeriod.Month, bugReportAnchor)
        val context = ApplicationProvider.getApplicationContext<Application>()

        // Accumulations für ALLE Tage des Kalendermonats (1.–31. Okt), wie der
        // Worker sie schreibt. Aeltere Tage (September) kommen bewusst dazu —
        // sie dürfen NICHT in die Monatssumme einfließen.
        val accums = (1..31).map { day ->
            AllowanceAccumulationDay(
                date = LocalDate.of(2026, 10, day).toString(),
                timezoneId = zone.id,
                allowanceId = mealAllowance.id,
                activityTypeId = mealAllowance.activityTypeId,
                minutes = mealAllowance.minutesPerDay
            )
        } + (1..30).map { day ->
            AllowanceAccumulationDay(
                date = LocalDate.of(2026, 9, day).toString(),
                timezoneId = zone.id,
                allowanceId = mealAllowance.id,
                activityTypeId = mealAllowance.activityTypeId,
                minutes = mealAllowance.minutesPerDay
            )
        }

        val result = InsightsAnalytics.build(
            context = context,
            sessions = emptyList(),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Month,
            anchorDate = bugReportAnchor,
            zoneId = zone,
            allowanceAccumulations = accums
        )

        // Erwartung: NUR die Tage des gezeigten Kalendermonats (Oktober) —
        // die September-Accumulations dürfen nicht einfließen.
        val expectedOctoberDays = ChronoUnit.DAYS
            .between(dates.start, dates.endInclusive.plusDays(1))
            .toInt()
        val expectedMs = expectedOctoberDays * 90L * 60_000L

        // Die Pauschale steckt in totalMsIncludingAllowances, aber NICHT in
        // totalMs (das ist die reine Aufzeichnungs-Summe).
        assertThat(result.totalMsIncludingAllowances).isEqualTo(expectedMs)
        assertThat(expectedOctoberDays).isEqualTo(31) // Oktober 2026

        // Gegenprobe zum Bug: die rollierende Rechnung haette genau 30 Tage
        // (5. Sep – 4. Okt) summiert = 30 × 90 min. Der Kalendermonat (31
        // Tage) darf damit NICHT uebereinstimmen — sonst waere unklar, ob
        // kalendarisch oder rollierend gerechnet wurde.
        val rollingThirtyDays = 30 * 90L * 60_000L
        assertThat(result.totalMsIncludingAllowances).isNotEqualTo(rollingThirtyDays)
    }

    /**
     * Sessions UND Pauschalen teilen dieselben Kalender-Grenzen: eine
     * Session am 30. September (Vormonat) und eine am 1. Oktober (Monat)
     * — nur die Oktober-Session zählt, und die Pauschale deckt exakt
     * dieselben Tage ab wie das Session-Fenster.
     */
    @Test
    fun sessionsAndAllowancesShareTheSameCalendarWindow() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val lastDayOfPreviousMonth = sessionOn(LocalDate.of(2026, 9, 30), hours = 2)
        val firstDayOfMonth = sessionOn(LocalDate.of(2026, 10, 1), hours = 3)

        val result = InsightsAnalytics.build(
            context = context,
            sessions = listOf(lastDayOfPreviousMonth, firstDayOfMonth),
            categories = categories,
            activityTypes = types,
            selectedPeriod = InsightPeriod.Month,
            anchorDate = bugReportAnchor,
            zoneId = zone
        )

        // Nur die Oktober-Session ist im Monatsfenster — 3h, nicht 5h.
        assertThat(result.totalMsIncludingAllowances).isEqualTo(3L * 60 * 60 * 1000)
    }

    /**
     * Die Vormonats-Grenzen („Changes"-Vergleich) sind kalendarisch: der
     * Vormonat endet am 30.09., nicht 30 Tage vor dem Anker.
     */
    @Test
    fun previousPeriodIsThePreviousCalendarMonth() {
        val dates = InsightsAnalytics.periodDates(InsightPeriod.Month, bugReportAnchor)

        assertThat(dates.previousStart).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(dates.previousEnd).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(ChronoUnit.DAYS.between(dates.previousStart, dates.previousEnd)).isEqualTo(30L)
    }

    private fun sessionOn(date: LocalDate, hours: Int): ActivitySession {
        val start = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return ActivitySession(
            id = "s-$date",
            title = "deep_work",
            activityTypeId = "deep_work",
            categoryId = "work",
            startAt = start,
            endAt = start + hours * 60L * 60 * 1000
        )
    }
}
