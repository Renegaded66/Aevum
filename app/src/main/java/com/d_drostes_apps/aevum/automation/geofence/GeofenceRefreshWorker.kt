package com.d_drostes_apps.aevum.automation.geofence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * M9.2: Periodic re-registration of Geofences.
 *
 * Without this, Geofences silently die after the user hasn't opened
 * the app for a while — Play Services clears pending intents and
 * OEMs may kill the foreground service. The Worker restores them
 * every 6 hours using the same code path as the manual refresh.
 */
class GeofenceRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun registrar(): GeofenceRegistrar
    }

    override suspend fun doWork(): Result {
        val registrar = EntryPointAccessors.fromApplication(
            applicationContext, Deps::class.java
        ).registrar()

        return when (val result = registrar.refreshRegisteredGeofences()) {
            is GeofenceRegistrationResult.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
