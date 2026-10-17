package com.d_drostes_apps.aevum.automation.apptracking

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.d_drostes_apps.aevum.data.repository.AppTrackingEntryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * M20: BOOT-CHECK der App-Aufzeichnung — einmalig ~60 s nach Boot/Update.
 *
 * Warum: Der FGS-Start aus dem BOOT_COMPLETED-Broadcast ist von Android
 * ausdrücklich erlaubt (Ausnahmenliste der Hintergrund-Start-Regeln), aber
 * aggressive OEM-ROMs (Samsung „Sleeping/Deep sleeping apps") können den
 * Prozess zwischen Broadcast und etabliertem Dienst beenden — oder der
 * Start scheitert am DB-Race im frisch gestarteten Prozess. Beides war
 * bisher unsichtbar: „Digital" lief weiter, die App-Aufzeichnung blieb tot,
 * ohne dass irgendwo ein Beleg stand.
 *
 * Dieser Check läuft in der WorkManager-Ökologie (überlebt Boot/Kill) und
 * schreibt SEINEN Befund ins Diagnose-Protokoll ([AppTrackingLog]):
 *  - „Boot-Check: Service läuft (Poll frisch)" → alles gut,
 *  - „Boot-Check: Service tot/hängt — Neustart" → Versuch + Ergebnis.
 * Ist die App von der Akku-Optimierung ausgenommen, darf der Worker den
 * Dienst auch aus dem Hintergrund starten; sonst protokolliert der Versuch
 * wenigstens das Android-Verbot („blockiert — Akku-Optimierung …"), damit
 * der nächste Report echte Belege hat statt Vermutungen.
 */
class AppTrackingBootCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun appTrackingEntryRepository(): AppTrackingEntryRepository
    }

    override suspend fun doWork(): Result {
        return try {
            val deps = EntryPointAccessors.fromApplication(
                applicationContext,
                Deps::class.java
            )
            val tracked = deps.appTrackingEntryRepository().getEnabledOnce()
            if (tracked.isEmpty()) {
                Log.d(TAG, "Boot-Check: keine getrackten Apps — No-Op")
                return Result.success()
            }
            val interactive = (applicationContext.getSystemService(Context.POWER_SERVICE)
                as? PowerManager)?.isInteractive ?: true
            if (AppTrackingService.isPollFresh(interactive)) {
                AppTrackingLog.i(applicationContext, "Boot-Check: Service läuft (Poll frisch) — ok")
                return Result.success()
            }
            AppTrackingLog.w(
                applicationContext,
                "Boot-Check: Service tot oder Poll hängt (${tracked.size} App(s)) — Wiederbelebung"
            )
            AppTrackingService.ensureAlive(applicationContext, "boot-check")
            Result.success()
        } catch (e: Exception) {
            // Kein retry()-Backoff-Stapeln — der 15-Minuten-Watchdog ist das
            // Sicherheitsnetz; dieser Check ist der schnelle Einmal-Blick.
            Log.w(TAG, "Boot-Check fehlgeschlagen", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "AppTrackingBootCheck"
        const val WORK_NAME = "app_tracking_boot_check"

        /** Einmalig ~60 s nach Boot/Update einplanen (REPLACE bleibt idempotent). */
        fun schedule(context: Context) {
            try {
                val request = androidx.work.OneTimeWorkRequestBuilder<AppTrackingBootCheckWorker>()
                    .setInitialDelay(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        WORK_NAME,
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        request
                    )
            } catch (e: Exception) {
                Log.w(TAG, "schedule fehlgeschlagen: ${e.message}")
            }
        }
    }
}
