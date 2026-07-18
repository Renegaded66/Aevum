package de.devondroste.aevum.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

object TimeFormatting {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun startOfDayMillis(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zoneId).toInstant().toEpochMilli()

    fun endOfDayMillis(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    fun millisToLocalDate(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    fun formatTime(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalTime().format(timeFormatter)

    fun formatDate(date: LocalDate): String = date.format(dateFormatter)

    fun formatDayTitle(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Heute"
        today.minusDays(1) -> "Gestern"
        today.plusDays(1) -> "Morgen"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN)}, ${formatDate(date)}"
    }

    fun formatDuration(durationMs: Long): String {
        val safeMs = max(0L, durationMs)
        val totalMinutes = safeMs / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun parseHourMinuteToMillis(date: LocalDate, hour: Int, minute: Int, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zoneId).toInstant().toEpochMilli()

    fun minutesOfDay(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val time = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalTime()
        return time.hour * 60 + time.minute
    }

    fun millisAtMinuteOfDay(date: LocalDate, minuteOfDay: Int, zoneId: ZoneId = ZoneId.systemDefault()): Long {
        val safeMinute = minuteOfDay.coerceIn(0, 24 * 60)
        val day = if (safeMinute == 24 * 60) date.plusDays(1) else date
        val minuteInDay = if (safeMinute == 24 * 60) 0 else safeMinute
        return day.atTime(LocalTime.of(minuteInDay / 60, minuteInDay % 60)).atZone(zoneId).toInstant().toEpochMilli()
    }
}
