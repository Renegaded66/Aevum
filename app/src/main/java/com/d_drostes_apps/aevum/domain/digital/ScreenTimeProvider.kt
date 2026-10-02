package com.d_drostes_apps.aevum.domain.digital

import java.time.LocalDate

/**
 * M18.138: Bildschirmzeit-Provider — die Abstraktion über
 * [AppUsageAggregator] für die Statistik-Sichten.
 *
 * WARUM ein Interface: [AppUsageAggregator] liest UsageStatsManager und
 * springt dafür auf `Dispatchers.IO`. Ein Flow, der dort emittiert, ist im
 * Unit-Test nicht deterministisch: `advanceUntilIdle()` wartet nur auf den
 * Test-Scheduler, nicht auf echte IO-Threads — die Statistik-Zustände
 * (Laden/Inhalt) wären nicht prüfbar. Über dieses Interface kann der Test
 * eine sofort antwortende Implementierung einsetzen, während die App
 * unverändert den Aggregator nutzt.
 */
interface ScreenTimeProvider {

    /**
     * Bildschirmzeit je Tag für [startDate, endDateExclusive).
     *
     * @return Map Tag → Bildschirmzeit in ms (0-Tage enthalten)
     */
    suspend fun dailyTotalsForRange(
        startDate: LocalDate,
        endDateExclusive: LocalDate
    ): Map<LocalDate, Long>
}
