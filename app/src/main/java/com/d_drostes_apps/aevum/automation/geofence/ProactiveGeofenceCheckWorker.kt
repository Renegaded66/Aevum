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
// M18.111 (User: „Akku hat Priorität"): 5 → 10 Min. Presence-/Timeline-
// Latenz ±5 Min bewusst in Kauf genommen.
// M18.112 (User: "Aufzeichnung soll wie Life360 nach ein paar Sekunden
// beginnen"): 10 → 5 Min. Begründung: Dieser Worker ist der EINZIGE
// zuverlässige Verdachts-Pfad, wenn Google im Hintergrund KEIN
// IN_VEHICLE-Transition liefert (Doku: "latency might vary by device";
// M18.64-Root-Cause). Bei 10 Min vergehen im schlechtesten Fall 10 Min
// bis zum Bewegungs-Verdacht → CONFIRM-Burst — das ist die gefühlte
// "Autofahrt wird gar nicht aufgezeichnet"-Latenz. 288 Fixe/Tag à
// BALANCED (WLAN/Cell, kein GPS-Chip) sind der Preis für
// Life360-Niveau; die AR-Continuous-Samples (M18.112) decken den
// Bewegungs-Fall schneller ab, der Worker bleibt das Sicherheitsnetz
// und die Presence-Basis. Zuhause (Stillstand) kostet der BALANCED-Fix
// praktisch nichts (kein GPS-Chip-Weckvorgang).
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
        // M18.130 (t_3ac05e06): Fallback-Fix-Quelle für den Bewegungs-
        // Verdacht, wenn checkNow() keinen Fix liefert (User ohne
        // Geofences: checkNow() kehrt vor der Fix-Akquise zurück).
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    /** M18.104: Bewegungs-Verdacht + Geofence-Zonen-Check — der Worker
     *  dient ZWEI Zwecken mit UNTERSCHIEDLICHEN Gates:
     *
     *  a) ZONEN-CHECK (nur wenn Geofencing aktiv): checkNow() —
     *     Zonenerkennung, Auto-Start/Stop, Presence-Sampling.
     *  b) BEWEGUNGS-VERDACHT (M18.104, IMMER wenn Auto- oder Walking-
     *     Erkennung aktiv): Der einzige AR-unabhängige CONFIRM-Burst-
     *     Trigger. Hängt er am Geofencing-Gate, stirbt die Fahrt-
     *     Erkennung für alle User ohne Geofences (geofencingEnabled
     *     Default FALSE — M18.130/t_3ac05e06: Motorrad-Totalausfall).
     *
     * Gate: Geofencing ODER Auto-Erkennung ODER Walking-Erkennung.
     * Nur wenn ALLE drei aus sind, ist der Worker sinnlos. Die pure
     * Entscheidung ist als [shouldRunCheck] im Companion JVM-testbar
     * (ProactiveGeofenceCheckGateTest). */
    private suspend fun shouldRun(
        settingsRepo: com.d_drostes_apps.aevum.data.repository.AutomationSettingsRepository
    ): Boolean? {
        // null = keine Settings geladen → konservativ laufen lassen.
        try {
            val settings = settingsRepo.get().first()
                ?: return true
            return shouldRunCheck(
                settings.geofencingEnabled,
                settings.drivingDetectionEnabled,
                settings.walkingDetectionEnabled
            )
        } catch (e: Exception) {
            Log.w(TAG, "Settings-Check fehlgeschlagen: ${e.message} — führe Check konservativ aus")
            return null
        }
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val zoneProvider = deps.currentZoneProvider()
        val settingsRepo = deps.settingsRepository()
        val settings = shouldRun(settingsRepo)
        if (settings != null && !settings) {
            Log.d(TAG, "Geofencing, Auto- und Walking-Erkennung deaktiviert — überspringe Check")
            scheduleNext(applicationContext)
            return Result.success()
        }

        // M18.66-FIX8: Einziger Pfad ist checkNow() — er übernimmt
        // Zonenerkennung, Zonenwechsel-Erkennung, direkten Auto-Start,
        // Trigger-Erzeugung und Auto-Stop. Für den Bewegungs-Verdacht
        // unten ist der Fix (lastFixSnapshot) die entscheidende Quelle.
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
        //
        // M18.130 (t_3ac05e06): Der Fix darf NICHT von checkNow() abhängen.
        // User ohne Geofences haben eine leere DB → checkNow() kehrt VOR
        // der Fix-Akquise zurück (Z.109-113) → lastFixSnapshot() bleibt
        // null → der Verdacht (einziger AR-unabhängiger CONFIRM-Trigger)
        // liefe nie. Fallback: eigener BALANCED-Fix über den
        // CurrentLocationProvider, nur wenn checkNow() keinen geliefert
        // hat — und auch bei Geofence-Usern bleibt der Verdacht bei einem
        // fehlgeschlagenen checkNow() am Leben.
        try {
            var fix = zoneProvider.lastFixSnapshot()
            if (fix == null) {
                try {
                    val loc = deps.locationProvider().getCurrentLocation()
                    if (loc is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success) {
                        fix = CurrentZoneProvider.FixSnapshot(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracyMeters = loc.accuracyMeters,
                            atMs = System.currentTimeMillis()
                        )
                        Log.d(TAG, "M18.130: Verdachts-Fix selbst akquiriert (checkNow lieferte keinen)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "M18.130: Fallback-Verdachts-Fix fehlgeschlagen: ${e.message}")
                }
            }
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

        /** M18.130 (t_3ac05e06): Gate-Entscheidung als pure Funktion für
         *  JVM-Tests (ProactiveGeofenceCheckGateTest). */
        fun shouldRunCheck(
            geofencingEnabled: Boolean,
            drivingEnabled: Boolean,
            walkingEnabled: Boolean
        ): Boolean = geofencingEnabled || drivingEnabled || walkingEnabled

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