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
 * M19.2/M20: WATCHDOG der App-Aufzeichnung — 15-Minuten-Takt, der den
 * [AppTrackingService] wiederbelebt, wenn er tot ist oder sein Poll-Loop
 * hängt.
 *
 * M20 (Nutzerfall „Handy eingeschaltet … Digital startete statt der
 * zugewiesenen Activity"): Zwei Lücken gegenüber M19.2 geschlossen:
 *  1. Der alte Gate „isAlive()" übersah den Zustand „Service LEBT, pollt
 *     aber nicht mehr" (hängender/beendeter Loop, stuck screenOn—Flag) —
 *     KEIN Pfad fasste je nach. Jetzt entscheidet [AppTrackingService.isPollFresh]
 *     (echte Poll-Frische; bei ausgeschaltetem Bildschirm ist Stille normal).
 *  2. Der Start läuft über [AppTrackingService.ensureAlive], das Fehlschläge
 *     protokolliert — u. a. das Android-Hintergrund-Startverbot
 *     (ForegroundServiceStartNotAllowed), das hier ohne Ausnahme (z. B.
 *     „Akku-Optimierung aus") greift und bisher still verschluckt wurde.
 *
 * Doppel-Aufrufe sind idempotent (UniqueWork + KEEP; ensureAlive startet nur
 * bei Bedarf). Eingesteckt wird er von AevumApplication (App-Start),
 * BootReceiver (Boot / MY_PACKAGE_REPLACED) und dem Boot-Check.
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
            // Gate 2: Service lebt UND pollt frisch — nichts zu tun.
            // M20: isPollFresh statt isAlive — ein lebender Service mit
            // hängendem Poll-Loop wurde vorher für gesund gehalten und nie
            // nachgefasst. Bei ausgeschaltetem Bildschirm zählt Stille als
            // normal (der Loop schläft dann bewusst).
            val interactive = (applicationContext.getSystemService(Context.POWER_SERVICE)
                as? android.os.PowerManager)?.isInteractive ?: true
            if (AppTrackingService.isPollFresh(interactive)) {
                Log.d(TAG, "Watchdog: Service läuft (Poll frisch) — No-Op")
                return Result.success()
            }
            Log.d(TAG, "Watchdog: Service tot oder Poll hängt bei ${tracked.size} getrackten Apps — Wiederbelebung (M20)")
            AppTrackingLog.w(
                applicationContext,
                "Watchdog: Service tot oder Poll hängt (${tracked.size} App(s)) — Wiederbelebung"
            )
            AppTrackingService.ensureAlive(applicationContext, "watchdog")
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