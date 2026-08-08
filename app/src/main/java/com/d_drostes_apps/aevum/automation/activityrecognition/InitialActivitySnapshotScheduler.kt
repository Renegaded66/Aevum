package com.d_drostes_apps.aevum.automation.activityrecognition

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * M17.4: Scheduler für den Initial-Activity-Snapshot. Wird sowohl vom
 * [com.d_drostes_apps.aevum.automation.geofence.BootReceiver] (BOOT_COMPLETED,
 * MY_PACKAGE_REPLACED) als auch aus dem normalen App-Cold-Start
 * (AevumApplication.onCreate) aufgerufen, damit der Use-Case "User startet
 * das Auto, öffnet Aevum kurz, schließt es" ebenfalls abgedeckt ist.
 *
 * Warum uniqueWork mit KEEP-Policy: Wir wollen nicht, dass ein doppelter
 * Probe (z.B. BootReceiver + Application.onCreate) zwei Probe-Cluster
 * auslöst. Der zweite Aufruf ist ein No-Op.
 */
object InitialActivitySnapshotScheduler {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<InitialActivitySnapshotWorker>()
            // M17.4: 30s Delay nach Boot/App-Start — gibt Play Services Zeit
            // zum Sensor-Aufwärmen. Ohne Delay kommen oft "no result yet".
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            InitialActivitySnapshotWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
