package com.d_drostes_apps.aevum.automation.garmin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.58: Scheduler für den Garmin-Connect-Sync.
 *
 * - Periodisch alle 30 Minuten (nur mit Netzwerk): holt Schritte/Kalorien/
 *   Distanz + Aktivitäten von der Bridge.
 * - Der Garmin-Schlaf-Import läuft über denselben Worker-Zyklus (der
 *   Worker respektiert das sleepSource-Gate intern).
 */
@Singleton
class GarminSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<GarminSyncWorker>(
            30, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Manueller Sync (aus den Einstellungen) — sofort, einmalig. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<GarminSyncWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putBoolean("manual", true)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        private const val WORK_NAME = "aevum.garmin_sync"
    }
}
