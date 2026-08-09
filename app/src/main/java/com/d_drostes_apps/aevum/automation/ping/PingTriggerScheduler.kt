package com.d_drostes_apps.aevum.automation.ping

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.61g: Plant den periodischen PingTriggerWorker (alle 2 Minuten).
 * Wird beim App-Start und nach Trigger-Änderungen aufgerufen.
 */
@Singleton
class PingTriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<PingTriggerWorker>(
            PingTriggerWorker.INTERVAL_MINUTES, TimeUnit.MINUTES,
            1, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PingTriggerWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(PingTriggerWorker.WORK_NAME)
    }
}
