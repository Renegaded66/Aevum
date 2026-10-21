package com.d_drostes_apps.aevum.automation.addiction

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.domain.addiction.AddictionSessionCollector
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * M27: Sichert den Sucht-Detektor-Verlauf auch ohne geöffnete App.
 *
 * Android hält Usage-Events nur wenige Tage vor. Ein 30-Minuten-Takt ist
 * mehr als ausreichend, um keine Events zu verlieren, und deutlich
 * akkuschonender als ein Minuten-Worker. Der Collector schreibt idempotent;
 * ein zusätzlicher Sync beim Öffnen des Detektors ist deshalb unkritisch.
 */
class AddictionSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun collector(): AddictionSessionCollector
    }

    override suspend fun doWork(): Result {
        return try {
            val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
            deps.collector().sync()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sucht-Detektor-Sync fehlgeschlagen", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AddictionSyncWorker"
        const val WORK_NAME = "aevum.addiction_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AddictionSyncWorker>(30, TimeUnit.MINUTES)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
