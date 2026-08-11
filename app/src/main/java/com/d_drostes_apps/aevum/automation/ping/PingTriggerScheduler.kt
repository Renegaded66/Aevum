package com.d_drostes_apps.aevum.automation.ping

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M18.61g: Plant den PingTriggerWorker.
 * Wird beim App-Start und nach Trigger-Änderungen aufgerufen.
 *
 * M18.62-FIX (Root Cause "Ping-Trigger zeichnet nichts auf"): Der alte
 * Scheduler nutzte PeriodicWorkRequest mit 2 Minuten — unter dem
 * WorkManager-Minimum von 15 Minuten → IllegalArgumentException. Der
 * Fehler wurde in AevumApplication von try/catch geschluckt → der Job
 * wurde NIE enqueued. Jetzt: OneTimeWorkRequest mit initialer
 * Verzögerung; der Worker erneuert sich selbst (Selbst-Scheduling).
 * REPLACE statt KEEP: ein frischer Scheduler-Aufruf (App-Start,
 * Trigger-Änderung) startet den Check-Takt neu.
 */
@Singleton
class PingTriggerScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = OneTimeWorkRequestBuilder<PingTriggerWorker>()
            .setInitialDelay(PingTriggerWorker.INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            PingTriggerWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(PingTriggerWorker.WORK_NAME)
    }
}
