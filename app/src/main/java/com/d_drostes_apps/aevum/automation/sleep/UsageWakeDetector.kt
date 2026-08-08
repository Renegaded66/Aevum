package com.d_drostes_apps.aevum.automation.sleep

import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.45-FIX (User-Feedback: "Ich habe das Handy um 07:50 entsperrt,
 * aber Aevum sagt, ich hätte bis 07:57 geschlafen"):
 *
 * Seit Android 14 werden SCREEN_ON-Broadcasts nicht mehr an
 * Hintergrund-Apps geliefert. Aevum sieht daher nur noch den
 * LIFECYCLE-Fallback (App-Öffnung, hier 07:57) — der echte Wake
 * (erste Bildschirm-Nutzung, 07:50) ging verloren.
 *
 * UsageStatsManager kennt die WAHRHEIT: `lastTimeUsed` pro App.
 * Das früheste `lastTimeUsed` nach dem Screen-Off ist die echte
 * erste Nutzung am Morgen. Diese Quelle wird in
 * [SleepFusionEngine.detectScreenSleepWindow] und
 * [SleepHeuristicEngine] als Wake-Korrektur genutzt, wenn sie
 * VOR dem LIFECYCLE-Zeitpunkt liegt.
 */
@Singleton
class UsageWakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Liefert die früheste echte App-Nutzung (lastTimeUsed) nach [fromMs].
     * Null, wenn keine Permission, kein Sample oder keine Nutzung danach.
     */
    fun firstUsageSince(fromMs: Long): Long? {
        return try {
            val mgr = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = mgr.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                fromMs - 60L * 60 * 1000, // 1h Puffer vor dem Screen-Off
                now
            ) ?: return null
            stats.asSequence()
                .filter { it.totalTimeInForeground > 0 }
                .map { it.lastTimeUsed }
                .filter { it > fromMs && it <= now }
                .minOrNull()
        } catch (_: Exception) {
            null // keine Permission / OEM-Blockade — nie crashen
        }
    }
}
