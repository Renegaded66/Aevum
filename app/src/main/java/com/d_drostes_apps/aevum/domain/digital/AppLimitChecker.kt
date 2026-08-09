package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.AppLimit
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * M18.61: Digital Balance — Sperr-Entscheidung pro App.
 *
 * Eine App ist gesperrt, wenn:
 *  1. Ein Limit existiert UND enabled ist
 *  2. Die heutige Nutzungszeit >= Limit ist
 *  3. KEINE Ausnahme greift:
 *     - ALWAYS_ALLOW: nie sperren
 *     - TIME_WINDOW: nur sperren, wenn die aktuelle Uhrzeit im
 *       Sperr-Fenster [windowStartMin, windowEndMin] liegt
 */
object AppLimitChecker {

    /**
     * @param usedTodayMs Nutzungszeit der App heute (UsageStats)
     * @param nowMs aktuelle Zeit
     * @return true wenn die App gesperrt werden soll
     */
    fun isBlocked(limit: AppLimit?, usedTodayMs: Long, nowMs: Long): Boolean {
        if (limit == null || !limit.enabled) return false
        if (limit.limitMinutes <= 0) return false

        // Limit erreicht?
        val limitMs = limit.limitMinutes * 60_000L
        if (usedTodayMs < limitMs) return false

        // Ausnahmen
        return when (limit.exceptionType) {
            AppLimit.EXCEPTION_ALWAYS_ALLOW -> false
            AppLimit.EXCEPTION_TIME_WINDOW -> {
                val zone = ZoneId.systemDefault()
                val minuteOfDay = LocalTime.now(zone).hour * 60 + LocalTime.now(zone).minute
                val start = limit.windowStartMin
                val end = limit.windowEndMin
                if (start <= end) {
                    minuteOfDay in start until end
                } else {
                    // Fenster über Mitternacht (z.B. 22:00–06:00)
                    minuteOfDay >= start || minuteOfDay < end
                }
            }
            else -> true // NONE: Limit gilt immer
        }
    }

    /**
     * Verbleibende Zeit bis zum Limit (für Countdown-Anzeige).
     * @return ms bis zum Limit, oder null wenn kein Limit aktiv
     */
    fun remainingMs(limit: AppLimit?, usedTodayMs: Long): Long? {
        if (limit == null || !limit.enabled || limit.limitMinutes <= 0) return null
        val limitMs = limit.limitMinutes * 60_000L
        return (limitMs - usedTodayMs).coerceAtLeast(0L)
    }

    /**
     * Fortschritt in Prozent (0..1) für die Fortschrittsbalken.
     */
    fun progress(limit: AppLimit?, usedTodayMs: Long): Float {
        if (limit == null || !limit.enabled || limit.limitMinutes <= 0) return 0f
        val limitMs = limit.limitMinutes * 60_000L
        return (usedTodayMs.toFloat() / limitMs).coerceIn(0f, 1f)
    }

    /**
     * Ist die App in der Ausnahme-Liste (ALWAYS_ALLOW)?
     */
    fun isAlwaysAllowed(limit: AppLimit?): Boolean =
        limit?.exceptionType == AppLimit.EXCEPTION_ALWAYS_ALLOW

    /**
     * Tages-Key für die Nutzungs-Zuordnung (heute).
     */
    fun todayKey(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
}
