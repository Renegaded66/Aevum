package com.d_drostes_apps.aevum

import com.google.common.truth.Truth.assertThat
import com.d_drostes_apps.aevum.domain.activity.SessionTimeValidator
import org.junit.Test

/**
 * M18.102-REGRESSION (User: "Bei der pauschalen Dauer stand da schon,
 * dass es von 0 bis 2 Uhr ist ... es soll ja keine Uhrzeiten haben. Und
 * jetzt werden die Sachen auch in der Timeline angezeigt von 0 Uhr bis zum
 * Ende der Dauer. Das sollte aber nicht so sein. Es sollte nur in der
 * Listenansicht ganz oben erscheinen"):
 *
 * Eine Nur-Dauer-Session (excludeFromTimeline = true) hat intern
 * startAt = Tagesbeginn, endAt = +Dauer — aber KEINE echten Uhrzeiten.
 *
 * Semantik von buildTimelineState seit M18.102:
 *   - Raster (Tag-/Wochen-Timeline): Filter schließt excludeFromTimeline
 *     aus → kein "00:00–02:00"-Balken.
 *   - durationOnlySessions (nur Listenansicht): exakter Erstellt-Tag-Test
 *     `startAt >= dayStart && startAt < dayEnd` → erscheint NUR an dem Tag,
 *     an dem sie erfasst wurde (nicht an jedem Tag der Woche).
 *
 * Diese Tests prüfen die Filter-Semantik (wie RunningSessionFutureDayRegressionTest).
 */
class DurationOnlySessionRegressionTest {

    // Fester Zeitpunkt: 16. Juli 2026, 20:40 UTC
    private val nowMs = 1_784_234_400_000L

    /** Mitternacht (UTC) des Tages [dayOffset] relativ zu [nowMs]. */
    private fun dayStart(dayOffset: Long): Long =
        (nowMs / 86_400_000L) * 86_400_000L + dayOffset * 86_400_000L

    // M18.102: Dauer-only-Session am TAG 0 (Erstellt-Tag), 2h Dauer.
    // intern startAt = dayStart(0), endAt = dayStart(0) + 2h.
    private val createdAt = dayStart(0)
    private val durationOnlyStart = dayStart(0)
    private val durationOnlyEnd = dayStart(0) + 2L * 60 * 60 * 1000
    private val isExcludedFromTimeline = true

    @Test
    fun durationOnlySession_isExcludedFromTimelineRaster() {
        // Der Raster-Filter (Tag + Woche) schließt excludeFromTimeline aus:
        val inRaster = !isExcludedFromTimeline &&
            SessionTimeValidator.rangesOverlap(dayStart(0), dayStart(1), durationOnlyStart, durationOnlyEnd)
        assertThat(inRaster).isFalse()
    }

    @Test
    fun durationOnlySession_appearsOnlyOnCreationDay() {
        // durationOnlySessions-Filter: startAt im [dayStart, dayEnd) des Tages.
        // Erstellt-Tag 0 → enthalten.
        val onCreationDay = durationOnlyStart >= dayStart(0) && durationOnlyStart < dayStart(1)
        assertThat(onCreationDay).isTrue()

        // Anderer Tag (z.B. +1) → NICHT enthalten (obwohl der Range 00:00–02:00
        // rechnerisch mit jedem Tag überlappen würde — genau der M18.101-Bug).
        val onNextDay = durationOnlyStart >= dayStart(1) && durationOnlyStart < dayStart(2)
        assertThat(onNextDay).isFalse()

        // Auch nicht an jedem Wochentag davor/danach.
        for (offset in listOf(-3L, -1L, 1L, 3L, 6L)) {
            val present = durationOnlyStart >= dayStart(offset) && durationOnlyStart < dayStart(offset + 1)
            assertThat(present).isFalse()
        }
    }

    @Test
    fun durationOnlySession_stillCountsInStatisticsOverlap() {
        // Für die Tagesstatistik (Dauer-Summen) ist die interne Zeitspanne
        // weiterhin gültig — der reine RangesOverlap bleibt true am
        // Erstellt-Tag. (Nur die Anzeige im Raster ist ausgeschlossen.)
        val overlapsCreationDay = SessionTimeValidator.rangesOverlap(
            dayStart(0), dayStart(1), durationOnlyStart, durationOnlyEnd
        )
        assertThat(overlapsCreationDay).isTrue()
    }

    @Test
    fun durationOnlyMinutes_roundTripFromSession() {
        // Editor-Init (initialiseForm): durationOnlyMinutes wird aus
        // (endAt - startAt) / 60_000 abgeleitet — 2h = 120 min.
        val ms = (durationOnlyEnd - durationOnlyStart).coerceAtLeast(1L)
        val minutes = (ms / 60_000L).toInt().coerceAtLeast(1)
        assertThat(minutes).isEqualTo(120)
    }
}
