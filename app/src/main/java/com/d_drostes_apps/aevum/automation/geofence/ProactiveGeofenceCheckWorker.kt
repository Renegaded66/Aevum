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
// M18.66-FIX2: PROAKTIVER GEOFENCE-CHECK
//
// Root-Cause (User-Report: "Geofence betreten, Automatisierung aktiviert,
// Activity startet nicht"):
//   1. GMS-Geofences feuern unzuverlässig — besonders bei Mock-Location-
//      Tests (Fake-GPS), aber auch im Hintergrund nach langer Laufzeit.
//   2. Der EventDrivenLocationChecker prüft den Standort NUR bei AR-Events
//      (IN_VEHICLE etc.) — wenn Google kein AR-Event liefert, wird kein
//      Geofence-Check gemacht.
//   3. Ergebnis: Der User betritt eine Geofence, aber die App bemerkt es
//      nicht → kein Auto-Start.
//
// LÖSUNG: Dieser Worker prüft alle 2 Minuten proaktiv den GPS-Standort
// gegen alle aktiven Geofences. Bei ENTER/EXIT ruft er die BESTEHENDE
// Pipeline auf (GeofenceTransitionProcessor.processTransition) — mit
// Debouncer-Schutz gegen Flattern.
//
// Das ist der gleiche Ansatz wie Life360: Statt sich auf GMS-Geofencing
// allein zu verlassen, wird der Standort regelmäßig geprüft.
// ══════════════════════════════════════════════════════════════════════

private const val TAG = "ProactiveGeofenceCheck"
private const val CHECK_INTERVAL_MS = 2L * 60 * 1000  // 2 Minuten
private const val CHECK_WORK = "aevum.proactive_geofence_check"

class ProactiveGeofenceCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun eventDrivenLocationChecker(): EventDrivenLocationChecker
        fun processor(): GeofenceTransitionProcessor
        fun debugLogger(): GeofenceDebugLogger
        fun settingsRepository(): com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
        fun currentZoneProvider(): CurrentZoneProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val checker = deps.eventDrivenLocationChecker()
        val processor = deps.processor()
        val debugLogger = deps.debugLogger()
        val settingsRepo = deps.settingsRepository()
        val zoneProvider = deps.currentZoneProvider()

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

        // Standort gegen alle Geofences prüfen.
        try {
            val result = checker.checkCurrentLocationAgainstGeofences()
            val matched = result.matchedGeofence
            val location = result.location

            if (location == null) {
                debugLogger.log(TAG, "Kein GPS-Fix — Check übersprungen")
                scheduleNext(applicationContext)
                return Result.success()
            }

            // Status-Tracking: Welcher Geofence war beim letzten Check aktiv?
            // Wenn sich der Status ändert (drinnen→draußen oder draußen→drinnen),
            // rufen wir processTransition auf — die bestehende Pipeline
            // (Debouncer → StabilizationWorker → Processor) übernimmt.
            val prefs = applicationContext.getSharedPreferences(
                "aevum_geofence_state", Context.MODE_PRIVATE
            )
            val lastInsideId = prefs.getString("last_inside_geofence", null)

            if (matched != null) {
                // User ist in einer Geofence.
                if (matched.id != lastInsideId) {
                    // Status geändert: jetzt drinnen (oder andere Geofence).
                    debugLogger.log(TAG, "ENTER erkannt: ${matched.name} (proaktiv)")
                    processor.processTransition(
                        geofenceId = matched.id,
                        transition = GeofenceTransition.Enter,
                        occurredAt = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    prefs.edit().putString("last_inside_geofence", matched.id).apply()
                }
                // M18.66-FIX3: Zone-Banner aktualisieren
                zoneProvider.setZone(matched)
            } else {
                // User ist in keiner Geofence.
                if (lastInsideId != null) {
                    // Status geändert: war drinnen, jetzt draußen.
                    debugLogger.log(TAG, "EXIT erkannt: $lastInsideId (proaktiv)")
                    processor.processTransition(
                        geofenceId = lastInsideId,
                        transition = GeofenceTransition.Exit,
                        occurredAt = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    prefs.edit().remove("last_inside_geofence").apply()
                }
                // M18.66-FIX3: Zone-Banner aktualisieren ("Abwesend")
                zoneProvider.setZone(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Proaktiver Geofence-Check fehlgeschlagen: ${e.message}", e)
        }

        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        /** Startet den periodischen Check (REPLACE = immer genau ein Lauf). */
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