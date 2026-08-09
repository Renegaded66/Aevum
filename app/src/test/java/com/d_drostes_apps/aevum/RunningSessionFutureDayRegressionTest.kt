package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.domain.activity.SessionTimeValidator
import org.junit.Test

/**
 * M18.59-REGRESSION (User: "laufende Activity erscheint an jedem
 * ZUKÜNFTIGEN Tag von 0 Uhr bis Startzeit"):
 *
 * Eine laufende Session (endAt = null) wurde in der Timeline-Filterung
 * mit endAt = Long.MAX_VALUE behandelt → sie überlappte mit JEDEM
 * zukünftigen Tag. Der Fix ersetzt null durch "jetzt" (nowMs), damit
 * eine laufende Session nur an Tagen ≤ heute erscheint.
 *
 * Diese Tests prüfen die Semantik, die buildTimelineState jetzt nutzt:
 *   rangesOverlap(dayStart, dayEnd, startAt, endAt ?: nowMs)
 */
class RunningSessionFutureDayRegressionTest {

    // Fester Zeitpunkt: 16. Juli 2026, 20:40 UTC
    private val nowMs = 1_784_234_400_000L
    // Session läuft seit 19:40 UTC (startAt = now - 1h)
    private val runningStart = nowMs - 3_600_000L

    /** Mitternacht (UTC) des Tages [now + dayOffset] — Integer-Division rundet ab. */
    private fun dayStart(dayOffset: Long): Long =
        (nowMs / 86_400_000L) * 86_400_000L + dayOffset * 86_400_000L

    @Test
    fun runningSession_overlapsToday() {
        // Heute: [00:00, 24:00) — Session läuft seit 09:00 → überlappt
        val todayStart = dayStart(0)
        val todayEnd = dayStart(1)
        val overlaps = SessionTimeValidator.rangesOverlap(
            todayStart, todayEnd, runningStart, null ?: nowMs
        )
        assertThat(overlaps).isTrue()
    }

    @Test
    fun runningSession_doesNotOverlapTomorrow() {
        // Morgen: [24:00, 48:00) — Session endet effektiv bei nowMs (heute
        // 10:00) → darf NICHT überlappen (vorher: Long.MAX_VALUE → überlappte)
        val tomorrowStart = dayStart(1)
        val tomorrowEnd = dayStart(2)
        val overlaps = SessionTimeValidator.rangesOverlap(
            tomorrowStart, tomorrowEnd, runningStart, null ?: nowMs
        )
        assertThat(overlaps).isFalse()
    }

    @Test
    fun runningSession_doesNotOverlapAnyFutureDay() {
        // Auch in 30 Tagen nicht — der Kern des gemeldeten Bugs
        for (offset in 2L..30L) {
            val start = dayStart(offset)
            val end = dayStart(offset + 1)
            val overlaps = SessionTimeValidator.rangesOverlap(
                start, end, runningStart, null ?: nowMs
            )
            assertThat(overlaps).isFalse()
        }
    }

    @Test
    fun finishedSession_stillOverlapsItsOwnDay() {
        // Abgeschlossene Session gestern 08:00–09:00 → überlappt gestern
        val yesterdayStart = dayStart(-1)
        val yesterdayEnd = dayStart(0)
        val finishedStart = nowMs - 26 * 3_600_000L
        val finishedEnd = nowMs - 25 * 3_600_000L
        val overlaps = SessionTimeValidator.rangesOverlap(
            yesterdayStart, yesterdayEnd, finishedStart, finishedEnd
        )
        assertThat(overlaps).isTrue()
    }
}
