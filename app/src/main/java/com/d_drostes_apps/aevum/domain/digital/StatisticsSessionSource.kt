package com.d_drostes_apps.aevum.domain.digital

import com.d_drostes_apps.aevum.data.model.ActivitySession

/**
 * M18.138: Zusammenführung von Sessions und Bildschirmzeit für Statistiken.
 *
 * Hintergrund (User-Spec 2026-10-01): Die Bildschirm-Aufzeichnung
 * ([ScreenStatisticsPolicy.SOURCE_SCREEN_RECORDING]) ist eine Timeline-
 * Darstellung, keine Messung. In der Statistik zählt stattdessen die
 * Digital-Balance-Zeit (UsageStats).
 *
 * Damit ALLE Statistik-Pfade (Dashboard, Insights, Weekly Review, Todos,
 * Ziele, Life-View) dieselbe Regel anwenden, gibt es genau zwei Aufrufe:
 *
 * ```kotlin
 * val statsSessions = StatisticsSessionSource.merge(sessions, balanceMsPerDay, zoneId)
 * ```
 *
 * Der Merge ist bewusst eine reine Funktion (ohne Android, ohne DB) und
 * damit direkt testbar.
 */
object StatisticsSessionSource {

    /**
     * Ersetzt Aufzeichnungs-Sessions durch die echte Bildschirmzeit.
     *
     * Die Bildschirmzeit wird PRO TAG eingefügt (eine künstliche Session
     * mit `sourceType = DIGITAL_BALANCE` am Tagesbeginn), damit jede
     * Tages-Aggregation sie dem richtigen Tag zuordnet. Tage ohne
     * Balance-Wert erzeugen keine Session.
     *
     * @param sessions alle Sessions des Aufrufers (Timeline + Statistik)
     * @param balanceMsPerDay Bildschirmzeit je Tag (UsageStats, Digital
     *        Balance). Leer = keine Bildschirmzeit verfügbar (kein
     *        Zugriff) → die Statistik enthält dann keine Digitalzeit,
     *        aber auch keine Aufzeichnungszeit.
     * @param zoneId Zeitzone für die Tagesgrenzen
     */
    fun merge(
        sessions: List<ActivitySession>,
        balanceMsPerDay: Map<java.time.LocalDate, Long>,
        zoneId: java.time.ZoneId
    ): List<ActivitySession> {
        val withoutRecordings = ScreenStatisticsPolicy.forStatistics(sessions)
        val balanceSessions = ScreenStatisticsPolicy.digitalBalanceSessions(balanceMsPerDay, zoneId)
        if (balanceSessions.isEmpty()) return withoutRecordings
        return withoutRecordings + balanceSessions
    }

    /**
     * Bequemlichkeit für Sichten mit EINEM Bezugstag (Dashboard, Todos):
     * Aufzeichnungen raus, echter Balance-Wert dieses Tages rein.
     */
    fun mergeForDay(
        sessions: List<ActivitySession>,
        date: java.time.LocalDate,
        balanceMs: Long,
        zoneId: java.time.ZoneId
    ): List<ActivitySession> = merge(sessions, mapOf(date to balanceMs), zoneId)
}
