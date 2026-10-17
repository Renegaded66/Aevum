package com.d_drostes_apps.aevum.automation.apptracking

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * M19.2: WATCHDOG der App-Aufzeichnung — 15-Minuten-Takt, der den
 * [AppTrackingService] wiederbelebt, wenn er tot ist.
 *
 * User-Report: „… wird einfach weiterhin nur Digital aufgezeichnet — der
 * Start der zugewiesenen Activity für Instagram kommt nicht mehr.“ Die
 * Ursache: Der Service hatte nur zwei Start-Pfade (App-Prozess-Start in
 * AevumApplication + Settings-Änderungen im AppTrackingViewModel); nach
 * Reboot / App-Update (MY_PACKAGE_REPLACED) / OEM-Kill blieb er deshalb
 * dauerhaft tot, während „Digital“ über den Manifest-Receiver
 * (ScreenEventReceiver + ScreenRecordingWorker) von selbst weiterlief —
 * genau diese Asymmetrie schließt der Watchdog. Er läuft bewusst im
 * selben WorkManager-Ökosystem wie „Digital“: Auch nach Kill/Reboot/
 * Update feuert er und stößt den Service nach. Doppel-Aufrufe sind
 * idempotent (UniqueWork + KEEP): Eingesteckt wird er von
 * AevumApplication (App-Start) und BootReceiver (Boot /
 * MY_PACKAGE_REPLACED). Im Normalbetrieb ist ein Lauf ein No-Op:
 *
 *  1. Keine getrackten Apps ([AppTrackingEntryRepository.getEnabledOnce]
 *     leer) → No-Op. Der Service beendet sich im Gate von onStartCommand
 *     ohnehin sofort selbst — ein Start wäre sinnlos.
 *  2. [AppTrackingService.isAlive] → No-Op (prozess-lokal; der Worker
 *     läuft im selben Prozess wie der Service — der Flag genügt, kein
 *     Systemaufruf nötig; siehe AppTrackingService-Companion).
 */
class AppTrackingWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // Gleicher EntryPoint-Stil wie ScreenRecordingWorker (M18.70): Der
    // Worker ist KEIN @AndroidEntryPoint — der EntryPoint ist der
    // bewährte Weg, Dependencies außerhalb des Hilt-Scopes zu holen.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun appTrackingEntryRepository(): AppTrackingEntryRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        return try {
            // Gate 1: Feature ohne Nutzung — nichts zu beleben.
            val tracked = deps.appTrackingEntryRepository().getEnabledOnce()
            if (tracked.isEmpty()) {
                Log.d(TAG, "Watchdog: keine getrackten Apps — No-Op")
                return Result.success()
            }
            // Gate 2: Service lebt und pollt — nichts zu tun. isAlive()
            // ist prozess-lokal und genau deshalb hier korrekt: Der Worker
            // teilt sich den Prozess mit dem Service.
            if (AppTrackingService.isAlive()) {
                Log.d(TAG, "Watchdog: Service lebt — No-Op")
                return Result.success()
            }
            Log.d(TAG, "Watchdog: Service tot bei ${tracked.size} getrackten Apps — Wiederbelebung (M19.2)")
            AppTrackingService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog-Lauf fehlgeschlagen", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AppTrackingWatchdog"
        const val WORK_NAME = "app_tracking_watchdog"

        /**
         * M19.2: Zentraler Einplan-Punkt (genutzt von AevumApplication und
         * BootReceiver). Periodikarbeit mit KEEP — erneutes Einstecken
         * nach Boot/App-Update ist damit idempotent, ein bestehender
         * Takt bleibt unberührt (M18.151-Muster: REPLACE nur, wo ein
         * Delay neu gesetzt werden muss — hier gibt es keinen).
         */
        fun schedule(context: Context) {
            try {
                val request = androidx.work.PeriodicWorkRequestBuilder<AppTrackingWatchdogWorker>(
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