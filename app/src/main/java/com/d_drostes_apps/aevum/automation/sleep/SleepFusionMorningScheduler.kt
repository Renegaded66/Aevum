package com.d_drostes_apps.aevum.automation.sleep

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.9: Garantierter MORGEN-Trigger für die Schlaf-Fusion.
 *
 * Vorher lief der SleepFusionWorker nur bei:
 *  - App-Start / App-Resume (Screen-ON)
 *  - STILL-Transition (nachts — jetzt durch die Nachtsperre blockiert)
 *
 * Ergebnis: Wer morgens die App nicht öffnete, bekam nie einen finalen
 * Schlaf-Candidate. Der periodische Worker (6h-Intervall) von
 * [com.d_drostes_apps.aevum.automation.health.SleepImportScheduler] importiert
 * nur Health-Connect-Daten, triggert aber NICHT die Fusion.
 *
 * Dieser Scheduler enqueut alle 6h einen Worker mit Initial-Delay, der
 * in das Schlaf-End-Fenster (05:00–11:59) fällt. Die Nachtsperre in
 * [SleepFusionEngine.analyzeLatest] sorgt dafür, dass nur Läufe im
 * Morgen-Fenster tatsächlich analysieren — alle anderen sind No-Ops.
 *
 * Design: Periodisch (nicht One-Shot) + KEEP — der WorkManager verlängert
 * das Intervall bei unzuverlässigen Geräten automatisch, ein einmaliges
 * Enqueue reicht für immer.
 */
@Singleton
class SleepFusionMorningScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<SleepFusionWorker>(
            6, TimeUnit.HOURS,
            // Flex-Fenster: Android wählt einen Moment im letzten 1h-Block.
            1, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val WORK_NAME = "aevum.sleep_fusion_morning"
    }
}
