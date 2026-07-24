package de.devondroste.aevum.automation.geofence

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M9.2: Schedules the periodic GeofenceRefreshWorker.
 *
 * Runs every 6 hours. Without this, the user must open the app
 * to keep Geofence registration alive.
 */
@Singleton
class GeofenceRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<GeofenceRefreshWorker>(
            6, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        ).build()

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
        private const val WORK_NAME = "aevum.geofence_refresh"
    }
}
