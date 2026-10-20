package com.d_drostes_apps.aevum.automation.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * M26: WATCHDOG der Anruf-Erkennung — 15-Minuten-Takt, der
 * [CallDetectionService] wiederbelebt, wenn das System ihn beendet hat.
 *
 * Warum überhaupt ein Takt, obwohl SPECIAL_USE-Vordergrunddienste nicht
 * gedrosselt werden dürfen: Hersteller-ROMs (Samsung „Sleeping apps",
 * Xiaomi, OnePlus) beenden sie trotzdem, und ein Anruf kommt ohne
 * Vorwarnung. Ohne diesen Takt bliebe die Erkennung bis zum nächsten
 * App-Öffnen stumm — der stille Ausfall, den dieses Projekt schon bei der
 * App-Aufzeichnung hatte (M19.2 → M20).
 *
 * Gates: Schalter aus ODER Berechtigung fehlt → No-Op (kein Start, kein
 * Log-Spam). Sonst `ensureAlive` (idempotent).
 */
class CallDetectionWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun automationSettingsRepository(): AutomationSettingsRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return try {
            val settings = deps.automationSettingsRepository().get().first()
            if (settings?.callDetectionEnabled != true) {
                Log.d(TAG, "Watchdog: Anruf-Erkennung aus — No-Op")
                return Result.success()
            }
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "Watchdog: READ_PHONE_STATE fehlt — No-Op")
                return Result.success()
            }
            if (CallDetectionService.alive) {
                Log.d(TAG, "Watchdog: Dienst läuft — No-Op")
                return Result.success()
            }
            Log.i(TAG, "Watchdog: Dienst tot — Wiederbelebung")
            CallDetectionService.ensureAlive(applicationContext, "watchdog")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog-Lauf fehlgeschlagen", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CallDetectionWatchdog"
        const val WORK_NAME = "call_detection_watchdog"

        /** Zentraler Einplan-Punkt (AevumApplication + BootReceiver). KEEP = idempotent. */
        fun schedule(context: Context) {
            try {
                val request =
                    androidx.work.PeriodicWorkRequestBuilder<CallDetectionWatchdogWorker>(
                        15, java.util.concurrent.TimeUnit.MINUTES
                    ).build()
                androidx.work.WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                        request
                    )
            } catch (e: Exception) {
                Log.w(TAG, "schedule fehlgeschlagen: ${e.message}")
            }
        }
    }
}
