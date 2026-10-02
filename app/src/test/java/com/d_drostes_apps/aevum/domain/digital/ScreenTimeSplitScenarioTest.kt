package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * M18.138: Beweis für den gemeldeten Fehler (Ende-zu-Ende auf Logik-Ebene).
 *
 * Ausgangslage laut User:
 *  - Digital-Balance-Tab: 2,5 h Bildschirmzeit
 *  - Dashboard: > 5 h  ← falsch
 *  - Ursache: Aufzeichnungen liefen über das Weglegen des Handys hinaus.
 *
 * Dieser Test baut den Tag mit allen drei Session-Arten nach und rechnet
 * BEIDE Statistik-Pfade (Dashboard-Tag und Insights-Zeitraum) durch.
 */
class ScreenTimeSplitScenarioTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 1)

    /** 2,5 Stunden echte Bildschirmzeit — der Wert aus Digital Balance. */
    private val realScreenTimeMs = 2L * 3_600_000 + 30 * 60_000

    /** Aufzeichnungen: zusammen 5,5 h, weil sie zu spät gestoppt wurden. */
    private val recordings = listOf(
        rec("rec_1", 6, 0, 9, 0),      // 3,0 h
        rec("rec_2", 12, 0, 14, 0),    // 2,0 h
        rec("rec_3", 20, 0, 20, 30)    // 0,5 h
    )

    private val realSessions = listOf(
        manual("work_1", "work", 9, 0, 12, 0),      // 3,0 h Arbeit
        manual("sport_1", "sport", 18, 0, 19, 0)    // 1,0 h Sport
    )

    // ── Dashboard: der gemeldete 2,5 h vs. 5 h-Fall ──

    @Test
    fun `dashboard shows 2_5h not the 5_5h from recordings`() {
        val all = recordings + realSessions

        // VORHER (Bug): Die „Digital"-Zeit kam aus der Aufzeichnung — hier
        // 5,5 h aus drei Blöcken, die über das Weglegen hinausliefen.
        val recordedDigitalMs = recordings.sumOf { it.durationMs() }
        assertThat(recordedDigitalMs).isEqualTo(5L * 3_600_000 + 30 * 60_000) // 5,5 h

        // NACHHER: Aufzeichnungen raus, echte Bildschirmzeit rein.
        val stats = StatisticsSessionSource.mergeForDay(
            sessions = all,
            date = day,
            balanceMs = realScreenTimeMs,
            zoneId = zone
        )
        val digitalMs = stats.filter { it.activityTypeId == "digital" }.sumOf { it.durationMs() }

        assertThat(digitalMs).isEqualTo(realScreenTimeMs) // 2,5 h
        assertThat(stats.count { it.sourceType == "SCREEN_AUTO" }).isEqualTo(0)
    }

    @Test
    fun `non digital activities stay untouched in the dashboard`() {
        val stats = StatisticsSessionSource.mergeForDay(
            sessions = recordings + realSessions,
            date = day,
            balanceMs = realScreenTimeMs,
            zoneId = zone
        )

        val work = stats.filter { it.activityTypeId == "work" }.sumOf { it.durationMs() }
        val sport = stats.filter { it.activityTypeId == "sport" }.sumOf { it.durationMs() }

        assertThat(work).isEqualTo(3L * 3_600_000)
        assertThat(sport).isEqualTo(1L * 3_600_000)
    }

    @Test
    fun `timeline still sees the recordings`() {
        val all = recordings + realSessions

        // Die Timeline liest die Rohdaten — die Aufzeichnung ist dort der
        // sichtbare Block (User: „nur größere Blöcke in der Timeline").
        val timeline = all.filter { it.deletedAt == null }
        assertThat(timeline.count { it.sourceType == "SCREEN_AUTO" }).isEqualTo(3)

        // Die Statistik derselben Daten enthält keine Aufzeichnung.
        val stats = StatisticsSessionSource.merge(
            sessions = all,
            balanceMsPerDay = mapOf(day to realScreenTimeMs),
            zoneId = zone
        )
        assertThat(stats.none { it.sourceType == "SCREEN_AUTO" }).isTrue()
    }

    // ── Mehrere Tage (Insights/Weekly-Pfad) ──

    @Test
    fun `each day gets its own balance amount`() {
        val day2 = day.plusDays(1)
        val sessions = listOf(
            rec("r1", 6, 0, 9, 0, day),          // 3 h Aufzeichnung Tag 1
            rec("r2", 15, 0, 18, 0, day2)        // 3 h Aufzeichnung Tag 2
        )

        val stats = StatisticsSessionSource.merge(
            sessions = sessions,
            balanceMsPerDay = mapOf(
                day to 90 * 60_000L,       // 1,5 h
                day2 to 45 * 60_000L       // 0,75 h
            ),
            zoneId = zone
        )

        val balance = stats.filter { it.sourceType == "DIGITAL_BALANCE" }
        assertThat(balance).hasSize(2)

        val perDay = balance.groupBy {
            java.time.Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate()
        }.mapValues { (_, v) -> v.sumOf { it.durationMs() } }

        assertThat(perDay[day]).isEqualTo(90 * 60_000L)
        assertThat(perDay[day2]).isEqualTo(45 * 60_000L)
    }

    @Test
    fun `recordings from other days are removed too`() {
        val sessions = listOf(
            rec("r1", 6, 0, 9, 0, day.minusDays(3)),
            rec("r2", 6, 0, 9, 0, day)
        )

        val stats = StatisticsSessionSource.merge(
            sessions = sessions,
            balanceMsPerDay = mapOf(day to 60 * 60_000L),
            zoneId = zone
        )

        assertThat(stats.none { it.sourceType == "SCREEN_AUTO" }).isTrue()
        assertThat(stats).hasSize(1)
    }

    // ── Vorlaufzeit-Einstellung wirkt NUR auf die Timeline ──

    @Test
    fun `lead time does not change the statistics`() {
        // Die Einstellung (z.B. 5 min) verschiebt nur, WANN die Aufzeichnung
        // beginnt. Für die Statistik ist das irrelevant: sie nimmt die
        // Balance-Zeit. Beide Konfigurationen müssen dasselbe Ergebnis liefern.
        val withLeadTime = listOf(rec("rec", 7, 0, 9, 0))       // startete 5 min später
        val withoutLeadTime = listOf(rec("rec", 6, 0, 9, 0))    // startete sofort

        fun digitalMs(sessions: List<ActivitySession>): Long =
            StatisticsSessionSource.mergeForDay(sessions, day, realScreenTimeMs, zone)
                .filter { it.activityTypeId == "digital" }
                .sumOf { it.durationMs() }

        assertThat(digitalMs(withLeadTime)).isEqualTo(digitalMs(withoutLeadTime))
        assertThat(digitalMs(withLeadTime)).isEqualTo(realScreenTimeMs)
    }

    // ── Hilfen ──

    /** Aufzeichnungs-Session (SCREEN_AUTO). */
    private fun rec(
        id: String,
        fromHour: Int,
        fromMin: Int,
        toHour: Int,
        toMin: Int,
        date: LocalDate = day
    ) = session(id, "digital", "SCREEN_AUTO", date, fromHour, fromMin, toHour, toMin)

    /** Normale, gemessene/manuelle Session. */
    private fun manual(
        id: String,
        typeId: String,
        fromHour: Int,
        fromMin: Int,
        toHour: Int,
        toMin: Int
    ) = session(id, typeId, "MANUAL", day, fromHour, fromMin, toHour, toMin)

    private fun session(
        id: String,
        typeId: String,
        source: String,
        date: LocalDate,
        fromHour: Int,
        fromMin: Int,
        toHour: Int,
        toMin: Int
    ): ActivitySession {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = dayStart + fromHour * 3_600_000L + fromMin * 60_000L
        val end = dayStart + toHour * 3_600_000L + toMin * 60_000L
        return ActivitySession(
            id = id,
            title = typeId,
            categoryId = typeId,
            activityTypeId = typeId,
            startAt = start,
            endAt = end,
            timezoneId = zone.id,
            sourceType = source
        )
    }

    private fun ActivitySession.durationMs(): Long = (endAt ?: startAt) - startAt
}
