package de.devondroste.aevum.automation.unknownplace

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M17.2: Schedules the periodic [UnknownPlaceDetectorWorker].
 *
 * Runs every 5 minutes (minimum WorkManager interval) when the device
 * is in a state that allows it. WorkManager defers execution under
 * battery-saver / doze automatically.
 */
@Singleton
class UnknownPlaceDetectorScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UnknownPlaceDetectorWorker>(
            UnknownPlaceDetectorWorker.WORK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UnknownPlaceDetectorWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UnknownPlaceDetectorWorker.WORK_NAME)
    }
}
