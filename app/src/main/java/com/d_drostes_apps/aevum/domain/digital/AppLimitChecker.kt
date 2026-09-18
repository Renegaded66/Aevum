package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.AppLimit
import java.time.Instant
import java.time.LocalDate
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
 *
 * M18.131: Die Ausnahme-Prüfung ist in [exceptionAllowsBlocking] extrahiert.
 * Grund: die neue Vorwarnung (siehe [isWarningDue]) muss exakt derselben
 * Ausnahme-Logik folgen wie die Sperre selbst. Eine Vorwarnung für ein
 * Limit, das gerade überhaupt nicht greift (ALWAYS_ALLOW, oder TIME_WINDOW
 * außerhalb des Fensters), wäre schlicht falsch — der Nutzer würde eine
 * Sperre angekündigt bekommen, die nie kommt.
 */
object AppLimitChecker {

    /**
     * M18.131: Vorlauf der Warnung vor dem Sperrzeitpunkt (Minuten).
     *
     * Auftrag: „5 Minuten vor Erreichen des Limits eine Handybenachrichtigung
     * von Aevum gesendet wird, dass die App in 5 Minuten gesperrt wird."
     *
     * Die Warnung ist ein Zeitpunkt, kein Prozentwert: sie greift, sobald
     * die Restzeit die Schwelle unterschreitet. Der frühere 80-%-Ansatz
     * taugt dafür nicht, weil er bei langen Limits viel zu früh meldet
     * (bei 60 min Limit wäre 80 % = 12 min Restzeit) und bei kurzen Limits
     * zu spät (bei 10 min Limit = 2 min Restzeit).
     */
    const val WARNING_LEAD_MINUTES = 5

    /**
     * Greift eine der Ausnahmen, die das Sperren verhindern?
     *
     * @param nowMs Bezugszeit für das Zeitfenster. Bewusst als Parameter
     *        statt `LocalTime.now()`: die Signatur von [isBlocked] versprach
     *        schon immer eine Bezugszeit, nutzte intern aber die echte Uhr —
     *        dadurch war das Mitternachts-/Zeitfenster-Verhalten nicht
     *        testbar und konnte von der Aufrufer-Zeit abweichen.
     */
    fun exceptionAllowsBlocking(limit: AppLimit, nowMs: Long): Boolean =
        when (limit.exceptionType) {
            AppLimit.EXCEPTION_ALWAYS_ALLOW -> false
            AppLimit.EXCEPTION_TIME_WINDOW -> {
                val local = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
                val minuteOfDay = local.hour * 60 + local.minute
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

        return exceptionAllowsBlocking(limit, nowMs)
    }

    /**
     * M18.131: Ist JETZT die Vorwarnung fällig?
     *
     * Bedingungen (alle müssen gelten):
     *  - Limit existiert und ist aktiv ([isBlocked] liefert noch false —
     *    ist die Sperre bereits aktiv, wäre eine Warnung sinnlos).
     *  - Die Restzeit liegt innerhalb der Schwelle
     *    [WARNING_LEAD_MINUTES] und ist noch echt positiv.
     *  - Die Ausnahme-Logik erlaubt das Sperren (sonst kündigt die Warnung
     *    eine Sperre an, die nie eintritt — siehe [exceptionAllowsBlocking]).
     *
     * WICHTIG zur Formulierung der Meldung: Die Sperre tritt ein, sobald
     * die Nutzungszeit das Limit erreicht. Die Warnung sagt also „noch
     * etwa N Minuten bei FORTGESETZTER Nutzung" — nicht „in N Minuten, egal
     * was du tust". Deshalb formuliert der Notification-Text genau so.
     *
     * @param usedTodayMs bisherige Nutzung heute.
     * @param nowMs Bezugszeit.
     * @param leadMinutes Vorlauf; Default [WARNING_LEAD_MINUTES].
     */
    fun isWarningDue(
        limit: AppLimit?,
        usedTodayMs: Long,
        nowMs: Long,
        leadMinutes: Int = WARNING_LEAD_MINUTES
    ): Boolean {
        if (limit == null || !limit.enabled) return false
        if (limit.limitMinutes <= 0) return false
        if (leadMinutes <= 0) return false
        // Bereits gesperrt → keine Vorwarnung mehr.
        if (usedTodayMs >= limit.limitMinutes * 60_000L) return false
        if (!exceptionAllowsBlocking(limit, nowMs)) return false
        val remaining = remainingMs(limit, usedTodayMs) ?: return false
        return remaining > 0L && remaining <= leadMinutes * 60_000L
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
