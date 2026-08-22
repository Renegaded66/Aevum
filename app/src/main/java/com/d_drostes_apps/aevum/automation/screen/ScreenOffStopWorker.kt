package com.d_drostes_apps.aevum.automation.screen

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import java.util.concurrent.TimeUnit

/**
 * M18.71: Stoppt die Bildschirm-Aufzeichnung („Digital") erst, wenn der
 * Screen [ScreenRecordingEngine.SCREEN_OFF_STOP_DELAY_MS] (30 s) am
 * Stück aus war.
 *
 * Wird vom [com.d_drostes_apps.aevum.automation.sleep.ScreenEventReceiver]
 * bei Screen-OFF enqueued (nur wenn gerade eine SCREEN_AUTO-Session
 * läuft). Beim Feuern prüft der Worker erneut:
 *  - Screen immer noch aus? (Kommt vorher ein Screen-ON/UNLOCK, wird
 *    der Worker gecancelt und die Aufzeichnung läuft weiter.)
 *  - Läuft die SCREEN_AUTO-Session noch? (Eine andere Session — z. B.
 *    Autofahrt oder Geofence — wurde inzwischen gestartet und hat die
 *    Digital-Session bereits unterbrochen → nichts zu tun.)
 *
 * Manuelle Sessions (LIVE) werden nie angefasst.
 */
class ScreenOffStopWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun liveActivityManager(): LiveActivityManager
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        return try {
            // Bedingung 1: Screen muss immer noch aus sein. Wenn der User
            // inzwischen wieder eingeschaltet hat (Worker wurde nicht
            // gecancelt, z. B. weil der Receiver kurz verzögert lief),
            // läuft die Aufzeichnung weiter.
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE)
                as PowerManager
            if (powerManager.isInteractive) {
                Log.d(TAG, "Screen ist wieder an — keine Screen-Aufzeichnung stoppen")
                return Result.success()
            }

            // Bedingung 2: Es muss noch die SCREEN_AUTO-Session laufen.
            // Wenn inzwischen eine andere Aufzeichnung (Autofahrt,
            // Geofence) gestartet wurde, hat die die Digital-Session
            // bereits unterbrochen — nichts zu tun.
            val live = deps.liveActivityManager().liveSession.value
            if (live == null || !live.isLive || live.sourceType != "SCREEN_AUTO") {
                Log.d(TAG, "Keine SCREEN_AUTO-Session mehr live — Stop übersprungen")
                return Result.success()
            }

            deps.liveActivityManager().stop()
            LiveActivityService.stop(applicationContext)
            Log.d(TAG, "Screen-Aufzeichnung gestoppt (Screen ${ScreenRecordingEngine.SCREEN_OFF_STOP_DELAY_MS / 1000}s aus)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Screen-OFF-Stop fehlgeschlagen", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScreenOffStopWorker"
        const val WORK_NAME = "screen_recording_off_stop"

        /** Vom ScreenEventReceiver bei Screen-OFF aufgerufen: Stop in 30s. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScreenOffStopWorker>()
                    .setInitialDelay(
                        ScreenRecordingEngine.SCREEN_OFF_STOP_DELAY_MS,
                        TimeUnit.MILLISECONDS
                    )
                    .build()
            )
        }
    }
}
