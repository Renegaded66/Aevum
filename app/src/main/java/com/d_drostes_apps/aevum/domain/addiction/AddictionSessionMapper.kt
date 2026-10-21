package com.d_drostes_apps.aevum.domain.addiction

import com.d_drostes_apps.aevum.data.model.AddictionSession
import com.d_drostes_apps.aevum.domain.digital.UsageEventFolding

/**
 * M27: Übersetzt gefaltete Vordergrund-Phasen in persistierbare Sitzungen.
 *
 * Reine Funktion — die Android-Abfrage (UsageStatsManager) bleibt im
 * Collector. Dadurch ist die wichtige Semantik testbar:
 *  - nur beobachtete Pakete,
 *  - keine Phasen unter [AddictionAnalytics.MIN_SESSION_MS],
 *  - stabile ID `paket@start` (idempotenter Upsert).
 */
object AddictionSessionMapper {

    fun toSessions(
        phases: List<UsageEventFolding.Phase>,
        watchedPackages: Set<String>,
        minDurationMs: Long = AddictionAnalytics.MIN_SESSION_MS
    ): List<AddictionSession> =
        phases.asSequence()
            .filter { it.packageName in watchedPackages }
            .filter { it.durationMs >= minDurationMs }
            .map { phase ->
                AddictionSession(
                    id = idFor(phase.packageName, phase.start),
                    packageName = phase.packageName,
                    startAt = phase.start,
                    endAt = phase.end,
                    durationMs = phase.durationMs
                )
            }
            .sortedBy { it.startAt }
            .toList()

    fun idFor(packageName: String, startAt: Long): String = "$packageName@$startAt"
}
