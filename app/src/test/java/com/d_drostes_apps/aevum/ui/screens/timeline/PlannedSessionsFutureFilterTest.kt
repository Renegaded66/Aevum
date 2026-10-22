package com.d_drostes_apps.aevum.ui.screens.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M28: Kalender-Blöcke sind eine PROGNOSE und verschwinden, sobald der
 * Termin beginnt.
 *
 * User: „Kalendereinträge werden ja gestrichelt in der Timeline angezeigt.
 * Das sollte aber nur so lange sein wie der Termin in der Zukunft liegt.
 * Sobald ein Termin entweder gerade stattfindet oder in der Vergangenheit
 * liegt soll es nicht mehr gestrichelt angezeigt werden, also der
 * Kalendereintrag gar nicht mehr, nur die Aufzeichnungen."
 */
class PlannedSessionsFutureFilterTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun planned(
        id: String,
        startMinute: Int,
        endMinute: Int
    ) = PlannedSessionUi(
        id = id,
        title = "Termin $id",
        activityTypeId = "work",
        activityTypeName = "Arbeit",
        activityIcon = "💼",
        activityColor = 0L,
        ruleName = "Regel",
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        timeRange = "",
        durationMinutes = endMinute - startMinute
    )

    @Test
    fun `laufender-Termin-wird-nicht-mehr-als-Prognose-angezeigt`() {
        val day = LocalDate.of(2026, 10, 22)
        val now = at(day, 10, 0) // Termin 09:00–11:00 läuft gerade
        val result = filterPlannedToFuture(
            planned = listOf(planned("running", 9 * 60, 11 * 60)),
            date = day,
            nowMs = now,
            zone = zone
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `vergangener-Termin-wird-nicht-mehr-angezeigt`() {
        val day = LocalDate.of(2026, 10, 22)
        val now = at(day, 18, 0)
        val result = filterPlannedToFuture(
            planned = listOf(planned("past", 9 * 60, 11 * 60)),
            date = day,
            nowMs = now,
            zone = zone
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `zukuenftiger-Termin-bleibt-gestrichelt`() {
        val day = LocalDate.of(2026, 10, 22)
        val now = at(day, 8, 0)
        val result = filterPlannedToFuture(
            planned = listOf(planned("future", 9 * 60, 11 * 60)),
            date = day,
            nowMs = now,
            zone = zone
        )
        assertThat(result.map { it.id }).containsExactly("future")
    }

    @Test
    fun `Termin-beginnt-in-dieser-Minute-gilt-als-gestartet`() {
        val day = LocalDate.of(2026, 10, 22)
        val now = at(day, 9, 0)
        val result = filterPlannedToFuture(
            planned = listOf(planned("now", 9 * 60, 11 * 60)),
            date = day,
            nowMs = now,
            zone = zone
        )
        assertThat(result).isEmpty()
    }

    @Test
    fun `ganzer-Zukunftstag-bleibt-vollstaendig`() {
        val day = LocalDate.of(2026, 10, 23)
        val now = at(LocalDate.of(2026, 10, 22), 20, 0)
        val result = filterPlannedToFuture(
            planned = listOf(planned("a", 8 * 60, 9 * 60), planned("b", 9 * 60, 10 * 60)),
            date = day,
            nowMs = now,
            zone = zone
        )
        assertThat(result).hasSize(2)
    }

    @Test
    fun `leere-Karte-bleibt-leer`() {
        assertThat(
            filterPlannedWeekToFuture(
                plannedByDay = emptyMap(),
                nowMs = at(LocalDate.of(2026, 10, 22), 12, 0),
                zone = zone
            )
        ).isEmpty()
    }

    @Test
    fun `Wochen-Karte-filtert-jeden-Tag-einzeln`() {
        val today = LocalDate.of(2026, 10, 22)
        val tomorrow = today.plusDays(1)
        val now = at(today, 12, 0)
        val result = filterPlannedWeekToFuture(
            plannedByDay = mapOf(
                today to listOf(planned("today-past", 9 * 60, 10 * 60)),
                tomorrow to listOf(planned("tomorrow", 9 * 60, 10 * 60))
            ),
            nowMs = now,
            zone = zone
        )
        assertThat(result.keys).containsExactly(tomorrow)
        assertThat(result.getValue(tomorrow).map { it.id }).containsExactly("tomorrow")
    }
}
