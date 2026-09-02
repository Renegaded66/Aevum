package com.d_drostes_apps.aevum.automation.geofence

import android.content.Context
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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// ══════════════════════════════════════════════════════════════════════
// M18.66-FIX8: PROAKTIVER GEOFENCE-CHECK — DIREKTER PFAD
//
// FIX7 nutzte CurrentZoneProvider.checkNow() als direkten Pfad (kein
// Processor). Aber dieser Worker hatte noch den ALTEN Pfad
// (processor.processTransition) + separate SharedPreferences
// ("aevum_geofence_state" / "last_inside_geofence").
//
// Das führte zu zwei konkurrierenden Pfaden:
//  - checkNow() → direkt start() → schreibt "prev_zone_id"
//  - Worker → processor.processTransition() → schreibt "last_inside_geofence"
//  - Worker ruft zoneProvider.setZone() OHNE checkNow() → überschreibt
//    die Zone ohne Trigger/Activity → beim nächsten App-Öffnen ist
//    prev_zone_id schon gesetzt → zoneChanged=false → kein Auto-Start.
//
// FIX8: Der Worker nutzt jetzt checkNow() als EINZIGEN Pfad. Kein
// processor, keine separaten SharedPreferences, keine setZone().
// ══════════════════════════════════════════════════════════════════════

private const val TAG = "ProactiveGeofenceCheck"
// M18.93v11 (User: "Akkuverbrauch weiter drosseln"): 2 Min war
// Fallback-Dichte für Sport-Apps — 720 GPS-Wakes/Tag. 5 Min reicht
// für den GMS-Geofence-Fallback völlig (Geofence-Trigger mit 5 Min
// Latenz sind für Zuhause/Gym/Arbeit unsichtbar), spart 60% der
// Wakes (720 -> 288/Tag).
private const val CHECK_INTERVAL_MS = 5L * 60 * 1000  // 5 Minuten
private const val CHECK_WORK = "aevum.proactive_geofence_check"

class ProactiveGeofenceCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun currentZoneProvider(): CurrentZoneProvider
        fun settingsRepository(): com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val zoneProvider = deps.currentZoneProvider()
        val settingsRepo = deps.settingsRepository()

        // Gate: Geofencing in den Trigger-Settings deaktiviert?
        try {
            val settings = settingsRepo.get().first()
            if (settings?.geofencingEnabled == false) {
                Log.d(TAG, "Geofencing deaktiviert — überspringe Check")
                scheduleNext(applicationContext)
                return Result.success()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Settings-Check fehlgeschlagen: ${e.message} — führe Check konservativ aus")
        }

        // M18.66-FIX8: Einziger Pfad ist checkNow() — er übernimmt
        // Zonenerkennung, Zonenwechsel-Erkennung, direkten Auto-Start,
        // Trigger-Erzeugung und Auto-Stop. Kein processor, keine separaten
        // SharedPreferences mehr.
        try {
            zoneProvider.checkNow()
        } catch (e: Exception) {
            Log.e(TAG, "Proaktiver Geofence-Check fehlgeschlagen: ${e.message}", e)
        }

        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CHECK_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ProactiveGeofenceCheckWorker>()
                    .setInitialDelay(CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CHECK_WORK)
        }

        private fun scheduleNext(context: Context) = schedule(context)
    }
}