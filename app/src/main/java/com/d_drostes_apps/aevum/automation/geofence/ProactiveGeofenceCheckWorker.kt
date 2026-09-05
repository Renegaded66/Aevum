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
import com.d_drostes_apps.aevum.automation.activityrecognition.DetectionBurstPolicy
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

        // M18.104 (Akku-Redesign): BEWEGUNGS-VERDACHTS-CHECK — schließt
        // die Lücke, die der Wegfall des 24/7-GPS-Streams öffnet (M18.64:
        // "Wenn Google kein IN_VEHICLE-Event liefert, wurde NIE eine
        // Fahrt erkannt"). Der Worker hat SOEBEN einen GPS-Fix geholt
        // (checkNow); wird er mit dem Fix von vor ~5 Minuten verglichen,
        // zeigt große Netto-Distanz echte Fortbewegung:
        //   >= 1500 m -> CONFIRM-Burst (Fahrzeug-Verdacht)
        //   >= 200 m  -> WALKING_CHECK-Burst (Outdoor-Bewegungs-Verdacht)
        // NULL zusätzliche GPS-Kosten — der Fix ist längst da, es wird
        // nur gerechnet. Indoor-Drift pendelt um denselben Punkt (Netto
        // ~0), Gehen schafft in 5 Min ~400 m — der Fahrzeug-Pfad bleibt
        // exklusiv für schnelle Ortsveränderung. Burst-Kaskaden fangen
        // die Cooldowns im Service ab.
        try {
            val fix = zoneProvider.lastFixSnapshot()
            if (fix != null) {
                suspicionCheck(applicationContext, fix)
            }
        } catch (e: Exception) {
            Log.w(TAG, "M18.104: Verdachts-Check fehlgeschlagen (nicht blockierend): ${e.message}")
        }

        scheduleNext(applicationContext)
        return Result.success()
    }

    /** M18.104: Vergleicht den aktuellen Fix mit dem vor ~5 Min
     *  (SharedPreferences — Worker-Instanzen leben nicht zwischen
     *  Läufen, WorkManager instanziiert neu). Schwellen:
     *  DetectionBurstPolicy-Konstanten. */
    private fun suspicionCheck(context: Context, fix: CurrentZoneProvider.FixSnapshot) {
        val prefs = context.getSharedPreferences(SUSPICION_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val prevAt = prefs.getLong(KEY_FIX_AT, 0L)
        val prevLat = if (prefs.contains(KEY_FIX_LAT)) Double.fromBits(prefs.getLong(KEY_FIX_LAT, 0L)) else null
        val prevLon = if (prefs.contains(KEY_FIX_LON)) Double.fromBits(prefs.getLong(KEY_FIX_LON, 0L)) else null

        // Basis für den nächsten Vergleich speichern (immer — auch wenn
        // der aktuelle Fix keinen Verdacht auslöst).
        prefs.edit()
            .putLong(KEY_FIX_AT, now)
            .putLong(KEY_FIX_LAT, fix.latitude.toRawBits())
            .putLong(KEY_FIX_LON, fix.longitude.toRawBits())
            .apply()

        if (prevLat == null || prevLon == null) return
        val dtMs = now - prevAt
        if (dtMs < DetectionBurstPolicy.SUSPICION_MIN_DT_MS) return
        if (dtMs > DetectionBurstPolicy.SUSPICION_MAX_DT_MS) return

        val net = haversineMeters(prevLat, prevLon, fix.latitude, fix.longitude)

        if (net >= DetectionBurstPolicy.DRIVE_SUSPICION_MIN_DISPLACEMENT_M) {
            Log.d(TAG, "M18.104: Bewegungs-Verdacht (${net.toInt()}m in ${dtMs / 60000} Min) -> CONFIRM-Burst")
            com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.start(
                context,
                com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.ACTION_CONFIRM
            )
            return
        }
        if (net >= DetectionBurstPolicy.WALK_SUSPICION_MIN_DISPLACEMENT_M) {
            Log.d(TAG, "M18.104: Bewegungs-Verdacht (${net.toInt()}m in ${dtMs / 60000} Min) -> WALKING_CHECK-Burst")
            com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.start(
                context,
                com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionService.ACTION_WALKING_CHECK
            )
        }
    }

    /** M18.104: Haversine-Distanz in Metern (gleiche Formel wie überall). */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        private const val SUSPICION_PREFS = "aevum_suspicion_fix"
        private const val KEY_FIX_AT = "fix_at"
        private const val KEY_FIX_LAT = "fix_lat"
        private const val KEY_FIX_LON = "fix_lon"

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