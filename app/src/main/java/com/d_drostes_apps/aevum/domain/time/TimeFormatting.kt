package com.d_drostes_apps.aevum.domain.time

import android.content.Context
import com.d_drostes_apps.aevum.R
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

    /**
     * M12.1.1: Smart date+time label for trigger events.
     * - Heute: "Heute 08:41"
     * - Gestern: "Gestern 18:22"
     * - Andere Tage: "Fr, 24.07. 08:41" (Wochentag-Kurz + dd.MM. + Zeit)
     *
     * [context] liefert lokalisierte Labels; ohne Context (z. B. JVM-Unit-Tests)
     * werden die deutschen Fallback-Texte verwendet.
     */
    fun formatSmartDateTime(millis: Long, zoneId: ZoneId = ZoneId.systemDefault(), context: Context? = null): String {
        val zdt = Instant.ofEpochMilli(millis).atZone(zoneId)
        val date = zdt.toLocalDate()
        val today = LocalDate.now(zoneId)
        val time = zdt.toLocalTime().format(timeFormatter)
        return when (date) {
            today -> context?.getString(R.string.time_smart_today, time) ?: "Heute $time"
            today.minusDays(1) -> context?.getString(R.string.time_smart_yesterday, time) ?: "Gestern $time"
            else -> {
                val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                val dayMonth = "%02d.%02d.".format(date.dayOfMonth, date.monthValue)
                context?.getString(R.string.time_smart_other, dow, dayMonth, time) ?: "$dow, $dayMonth $time"
            }
        }
    }

    fun formatDayTitle(date: LocalDate, today: LocalDate = LocalDate.now(), context: Context? = null): String = when (date) {
        today -> context?.getString(R.string.time_today) ?: "Heute"
        today.minusDays(1) -> context?.getString(R.string.time_yesterday) ?: "Gestern"
        today.plusDays(1) -> context?.getString(R.string.time_tomorrow) ?: "Morgen"
        else -> {
            val dow = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN)
            val formatted = formatDate(date)
            context?.getString(R.string.time_day_title_other, dow, formatted) ?: "$dow, $formatted"
        }
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
