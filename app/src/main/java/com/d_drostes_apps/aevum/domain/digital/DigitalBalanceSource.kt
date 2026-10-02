package com.d_drostes_apps.aevum.domain.digital

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.138: Digital-Balance-Zeit als Flow — die EINE Quelle für alle
 * Statistiken.
 *
 * Warum ein eigener Flow: Die Bildschirmzeit kommt aus UsageStatsManager
 * (suspend), die Sessions aus Room (Flow). Die Statistik-Sichten
 * kombinieren beides. Dieser Provider kapselt die Abfrage und hält sie
 * aktuell (60-Sekunden-Takt wie der Digital-Balance-Tab seit M18.93v7 —
 * sonst müsste jede Sicht dieselbe Polling-Logik duplizieren und die
 * Werte würden auseinanderlaufen).
 *
 * Die Zeiträume sind bewusst TAGES-basiert: die Statistik braucht die
 * Bildschirmzeit pro Tag, damit Heatmap und Tagesdurchschnitte den Betrag
 * dem richtigen Tag zuordnen.
 *
 * WICHTIG für Tests: [refreshInMs] steuert das Polling. In Unit-Tests MUSS
 * es auf 0 gesetzt werden, sonst läuft die `while(true)`-Schleife in
 * `advanceUntilIdle()` endlos (der Test hängt bis zum Timeout).
 */
@Singleton
class DigitalBalanceSource @Inject constructor(
    private val aggregator: ScreenTimeProvider
) {
    private val zoneId: ZoneId get() = ZoneId.systemDefault()

    /**
     * Takt der Aktualisierung. 0 (oder negativ) = nur EIN Wert pro
     * Abonnement — das ist der Test-Modus.
     */
    @androidx.annotation.VisibleForTesting
    var refreshInMs: Long = REFRESH_INTERVAL_MS

    /**
     * Bildschirmzeit je Tag für die letzten [days] Tage (inklusive heute),
     * im 60-Sekunden-Takt aktualisiert.
     */
    fun dailyTotals(days: Int): Flow<Map<LocalDate, Long>> =
        dailyTotalsInRange(
            startDate = LocalDate.now(zoneId).minusDays((days - 1).coerceAtLeast(0).toLong()),
            endDateExclusive = LocalDate.now(zoneId).plusDays(1)
        )

    /**
     * Bildschirmzeit je Tag für [startDate, endDateExclusive).
     *
     * Emittiert den aktuellen Stand und — solange [refreshInMs] > 0 — den
     * aktualisierten Stand im konfigurierten Takt (Produktion: 60 s wie der
     * Digital-Balance-Tab seit M18.93v7).
     */
    fun dailyTotalsInRange(
        startDate: LocalDate,
        endDateExclusive: LocalDate
    ): Flow<Map<LocalDate, Long>> = flow {
        val interval = refreshInMs
        if (interval <= 0L) {
            emit(aggregator.dailyTotalsForRange(startDate, endDateExclusive))
            return@flow
        }
        while (true) {
            emit(aggregator.dailyTotalsForRange(startDate, endDateExclusive))
            delay(interval)
        }
    }

    companion object {
        /** Wie im Digital-Balance-Tab (M18.93v7): einmal pro Minute. */
        const val REFRESH_INTERVAL_MS = 60_000L
    }
}
