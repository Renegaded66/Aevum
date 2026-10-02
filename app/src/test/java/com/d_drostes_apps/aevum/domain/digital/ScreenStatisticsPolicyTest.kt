package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.138: Trennung von Bildschirm-AUFZEICHNUNG und BildschirmZEIT.
 *
 * User-Spec (2026-10-01):
 *  - „Im Dashboard […] soll die sein, die auch bei Digital Balance steht."
 *  - „Im Dashboard und generell was die Statistik angeht, soll nur die
 *    Digital-Balance-Zeit zählen, nicht die Zeit, die sich aus den
 *    Aufzeichnungen ergibt."
 *  - Die Vorlaufzeit-Einstellung („ab wie viel Minuten aktiver Bildschirm
 *    die Aufzeichnung starten soll") bezieht sich „nur auf die Timeline".
 *
 * Diese Tests sichern die Trennung ab: Aufzeichnung = Timeline,
 * Bildschirmzeit = Statistik.
 */
class ScreenStatisticsPolicyTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 1)

    // ── Quellen-Erkennung ──

    @Test
    fun `screen recording is detected by source type`() {
        assertThat(ScreenStatisticsPolicy.isScreenRecording("SCREEN_AUTO")).isTrue()
        assertThat(ScreenStatisticsPolicy.isScreenRecording("MANUAL")).isFalse()
        assertThat(ScreenStatisticsPolicy.isScreenRecording(null)).isFalse()
    }

    @Test
    fun `screen recording does not count in statistics`() {
        assertThat(ScreenStatisticsPolicy.countsInStatistics("SCREEN_AUTO")).isFalse()
        assertThat(ScreenStatisticsPolicy.countsInStatistics("MANUAL")).isTrue()
        assertThat(ScreenStatisticsPolicy.countsInStatistics("GEOFENCE_AUTO")).isTrue()
    }

    // ── Kern-Regel: Aufzeichnung fliegt aus der Statistik ──

    @Test
    fun `forStatistics removes recordings but keeps everything else`() {
        val sessions = listOf(
            session("rec1", source = "SCREEN_AUTO", hours = 5),
            session("manual1", source = "MANUAL", hours = 2),
            session("geo1", source = "GEOFENCE_AUTO", hours = 1)
        )

        val result = ScreenStatisticsPolicy.forStatistics(sessions)

        assertThat(result.map { it.id }).containsExactly("manual1", "geo1")
        assertThat(result).hasSize(2)
    }

    @Test
    fun `forStatistics on empty list stays empty`() {
        assertThat(ScreenStatisticsPolicy.forStatistics(emptyList())).isEmpty()
    }

    @Test
    fun `forStatistics keeps deleted sessions untouched`() {
        // Die Policy filtert NUR nach sourceType — deletedAt behandelt der
        // Aufrufer (dieselbe Trennung der Zuständigkeiten wie bisher).
        val deleted = session("del1", source = "MANUAL", hours = 1).copy(deletedAt = 123L)
        val result = ScreenStatisticsPolicy.forStatistics(listOf(deleted))
        assertThat(result).hasSize(1)
    }

    // ── Balance-Beiträge für die Statistik ──

    @Test
    fun `balance session carries the measured screen time`() {
        val twoAndAHalfHours = 2L * 3_600_000 + 30 * 60_000

        val sessions = ScreenStatisticsPolicy.digitalBalanceSessions(
            balanceMsPerDay = mapOf(day to twoAndAHalfHours),
            zoneId = zone
        )

        assertThat(sessions).hasSize(1)
        assertThat(sessions.first().durationMs).isEqualTo(twoAndAHalfHours)
        assertThat(sessions.first().activityTypeId).isEqualTo("digital")
        assertThat(sessions.first().sourceType).isEqualTo("DIGITAL_BALANCE")
    }

    @Test
    fun `balance session lies inside its own day`() {
        val ms = 3L * 3_600_000
        val s = ScreenStatisticsPolicy
            .digitalBalanceSessions(mapOf(day to ms), zone)
            .single()

        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        assertThat(s.startAt).isEqualTo(dayStart)
        assertThat(s.endAt).isEqualTo(dayStart + ms)
        assertThat(s.endAt!!).isAtMost(dayEnd)
    }

    @Test
    fun `zero and negative balance days create no session`() {
        val sessions = ScreenStatisticsPolicy.digitalBalanceSessions(
            balanceMsPerDay = mapOf(
                day to 0L,
                day.plusDays(1) to -5L,
                day.plusDays(2) to 60_000L
            ),
            zoneId = zone
        )

        assertThat(sessions).hasSize(1)
        assertThat(sessions.first().id).isEqualTo("digital_balance_${day.plusDays(2)}")
    }

    @Test
    fun `balance is capped at 24 hours per day`() {
        val impossible = 30L * 3_600_000 // 30 h — physikalisch unmöglich

        val s = ScreenStatisticsPolicy
            .digitalBalanceSessions(mapOf(day to impossible), zone)
            .single()

        assertThat(s.durationMs).isEqualTo(24L * 3_600_000)
    }

    @Test
    fun `one balance session per day`() {
        val sessions = ScreenStatisticsPolicy.digitalBalanceSessions(
            balanceMsPerDay = mapOf(
                day to 60_000L,
                day.plusDays(1) to 120_000L
            ),
            zoneId = zone
        )

        assertThat(sessions).hasSize(2)
        assertThat(sessions.map { it.durationMs }).containsExactly(60_000L, 120_000L)
    }

    @Test
    fun `balance for single day uses the convenience helper`() {
        val sessions = ScreenStatisticsPolicy.digitalBalanceSessionForDay(
            date = day,
            balanceMs = 90 * 60_000L,
            zoneId = zone
        )

        assertThat(sessions).hasSize(1)
        assertThat(sessions.first().durationMs).isEqualTo(90 * 60_000L)
    }

    // ── Der eigentliche Bug: 5 h Aufzeichnung vs. 2,5 h Bildschirmzeit ──

    @Test
    fun `statistics show balance time instead of inflated recording time`() {
        // Der gemeldete Fall: Die Aufzeichnung lief über das Weglegen des
        // Handys hinaus und sammelte >5 h; die echte Bildschirmzeit lag bei
        // 2,5 h. Nach der Trennung darf die Statistik NUR die 2,5 h zeigen.
        val recordings = listOf(
            session("rec_morning", source = "SCREEN_AUTO", hours = 3),
            session("rec_evening", source = "SCREEN_AUTO", hours = 2),
            session("rec_night", source = "SCREEN_AUTO", hours = 1)
        )
        val realScreenTime = 2L * 3_600_000 + 30 * 60_000 // 2,5 h

        val stats = StatisticsSessionSource.mergeForDay(
            sessions = recordings,
            date = day,
            balanceMs = realScreenTime,
            zoneId = zone
        )

        val digitalOnly = stats.filter { it.activityTypeId == "digital" }
        val digitalMs = digitalOnly.sumOf { it.durationMs }

        // 6 h Aufzeichnung wären falsch — die Statistik zeigt 2,5 h.
        assertThat(digitalMs).isEqualTo(realScreenTime)
        assertThat(digitalMs).isNotEqualTo(6L * 3_600_000)
        assertThat(stats.none { it.sourceType == "SCREEN_AUTO" }).isTrue()
    }

    @Test
    fun `merge keeps non-digital sessions and adds balance time`() {
        val sessions = listOf(
            session("work", source = "MANUAL", hours = 4, typeId = "work"),
            session("rec", source = "SCREEN_AUTO", hours = 5)
        )

        val merged = StatisticsSessionSource.mergeForDay(
            sessions = sessions,
            date = day,
            balanceMs = 2L * 3_600_000 + 30 * 60_000,
            zoneId = zone
        )

        assertThat(merged.map { it.id }).contains("work")
        assertThat(merged.count { it.sourceType == "DIGITAL_BALANCE" }).isEqualTo(1)
        assertThat(merged.none { it.sourceType == "SCREEN_AUTO" }).isTrue()
    }

    @Test
    fun `merge without balance data yields no digital time at all`() {
        // Ohne Nutzungszugriff gibt es keinen Balance-Wert. Dann darf auch
        // die Aufzeichnung NICHT als Ersatz einspringen — sonst stünde
        // wieder eine erfundene Digitalzeit in der Statistik.
        val merged = StatisticsSessionSource.mergeForDay(
            sessions = listOf(session("rec", source = "SCREEN_AUTO", hours = 5)),
            date = day,
            balanceMs = 0L,
            zoneId = zone
        )

        assertThat(merged).isEmpty()
    }

    @Test
    fun `recording stays visible for the timeline`() {
        // Gegenprobe zur Statistik-Regel: Die Aufzeichnung selbst bleibt in
        // der Session-Liste, die die Timeline liest — sie wird nur nicht
        // mehr in Statistiken eingerechnet.
        val recordings = listOf(session("rec", source = "SCREEN_AUTO", hours = 5))

        val timelineSessions = recordings // Timeline nutzt die Rohdaten
        val statisticSessions = ScreenStatisticsPolicy.forStatistics(recordings)

        assertThat(timelineSessions).hasSize(1)
        assertThat(statisticSessions).isEmpty()
    }

    // ── Hilfen ──

    private fun session(
        id: String,
        source: String,
        hours: Int,
        typeId: String = "digital"
    ) = ActivitySession(
        id = id,
        title = "Test",
        categoryId = typeId,
        activityTypeId = typeId,
        startAt = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        endAt = day.atStartOfDay(zone).toInstant().toEpochMilli() + hours * 3_600_000L,
        timezoneId = zone.id,
        sourceType = source
    )
}

private val ActivitySession.durationMs: Long get() = (endAt ?: startAt) - startAt
