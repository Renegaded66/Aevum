package com.d_drostes_apps.aevum.automation.geofence

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
import com.d_drostes_apps.aevum.automation.location.CurrentLocationResult
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.UUID

// ══════════════════════════════════════════════════════════════════════
// M18.114: DRIVE-END → GEOFENCE-RE-START (Worker)
//
// Wird von DriveStopWorker (Google-EXIT) und DriveWatchdogWorker
// (Stillstand/5-Min-Regel) nach dem Drive-Session-Stop aufgerufen.
// Holt den letzten GPS-Fix, lädt die Geofences, entscheidet per
// GeofenceDriveEndResolver und startet ggf. die Geofence-Auto-Session.
//
// Eigener Unique-Work-Name: REPLACE-Semantik wie überall — ein zweites
// Drive-Ende ersetzt den ausstehenden Check (immer nur der letzte zählt).
//
// Warum ein eigener Worker statt Inline-Code in DriveStopWorker/
// DriveWatchdogWorker? Beide laufen bereits im WorkManager-Kontext —
// aber der Start-Pfad braucht (a) einen GPS-Fix (suspend, await()),
// (b) DB-Reads und (c) einen Session-Start. Ein eigener Worker hält
// die Stop-Pfade dünn und ist idempotent über den Unique-Namen.
// ══════════════════════════════════════════════════════════════════════

private const val TAG = "GeofenceDriveEnd"
private const val DRIVE_END_GEOFENCE_WORK = "aevum.drive_end_geofence_restart"

class DriveEndGeofenceRestarter(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun geofenceRepository(): com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        fun activityTypeRepository(): com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
        fun locationProvider(): CurrentLocationProvider
        fun triggerEventRepository(): com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
        // M18.121 (Crash-Loop t_fe3e99da): Singleton-Drossel gegen das
        // Start/Stop-Flackern des Auto-Re-Starts nach Fahrt-Ende (siehe
        // GeofenceRestartThrottle).
        fun geofenceRestartThrottle(): GeofenceRestartThrottle
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val geofenceRepo = deps.geofenceRepository()
        val live = deps.liveActivityManager()
        val typeRepo = deps.activityTypeRepository()
        val provider = deps.locationProvider()
        val triggerRepo = deps.triggerEventRepository()
        val throttle = deps.geofenceRestartThrottle()

        // 1) Letzter GPS-Fix (kein eigener Stream — ein einzelner
        //    getCurrentLocation-Aufruf, gleiche Quelle wie Watchdog/Probe).
        val fix = try {
            when (val r = provider.getCurrentLocation()) {
                is CurrentLocationResult.Success -> r
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        val now = System.currentTimeMillis()
        val geofences = try {
            geofenceRepo.getActiveOrDisabled().first().filter { it.deletedAt == null }
        } catch (e: Exception) {
            Log.w(TAG, "Geofences laden fehlgeschlagen: ${e.message}")
            emptyList()
        }

        // 2) Aktuelle Live-Session fürs Gate (nach dem Drive-Stop i. d. R.
        //    null — aber ein Race mit einem anderen Auto-Start ist möglich).
        val liveSession = live.liveSession.value

        // 3) Reine Entscheidung (JVM-testbar).
        val decision = GeofenceDriveEndResolver.resolve(
            geofences = geofences,
            fixLat = fix?.latitude,
            fixLon = fix?.longitude,
            fixAccuracyM = fix?.accuracyMeters,
            fixAtMs = now,
            nowMs = now,
            liveSessionTypeId = liveSession?.activityTypeId,
            liveSessionIsLive = liveSession?.isLive == true
        )

        if (decision is GeofenceDriveEndResolver.Decision.Restart) {
            // M18.121 (Crash-Loop t_fe3e99da): DROSSEL — bricht das
            // Start/Stop-Ping-Pong. Der Worker wird von JEDEM Drive-Stop-
            // Pfad geschedult (Watchdog 5-Min-Regel + Google-EXIT + erneut
            // nach jedem Geofence-Stop). Läuft die Kaskade (Resolver sagt
            // Restart → Session startet → nächste Automatik stoppt sie →
            // nächster Stop-Pfad feuert erneut), explodiert die Zahl der
            // Session-Starts/Stops in Minuten — das Flackern, das den
            // LiveActivityService-Restore-Race und damit den Crash-Loop
            // anheizt. Die Drossel erlaubt pro Geofence maximal 2 Starts
            // im 10-Min-Fenster und pausiert danach 30 Minuten — lange
            // genug, dass die Erkennungs-Cooldowns (M18.84) den Zustand
            // beruhigen, kurz genug, dass ein echter Folgebesuch normal
            // registriert wird.
            if (!throttle.allowRestart(decision.geofenceId)) {
                Log.d(
                    TAG,
                    "M18.121: Geofence-Re-Start gedrosselt (${decision.geofenceId}) — Flacker-Schutz aktiv, kein erneuter Start"
                )
                return Result.success()
            }
            try {
                // M18.114: Titel = ActivityType-Name (M18.66-FIX9-Konvention
                // des ENTER-Pfads), sourceType GEOFENCE_AUTO — die Session
                // verhält sich in Timeline/Banner exakt wie eine vom
                // Geofence-ENTER gestartete.
                val typeName = try {
                    typeRepo.getById(decision.type).first()?.name
                } catch (_: Exception) { null }
                val session = live.start(
                    activityTypeId = decision.type,
                    title = null,
                    sourceType = "GEOFENCE_AUTO",
                    sourceTriggerId = null
                )
                // FGS, damit der Timer im Hintergrund weiterläuft
                // (gleicher Pfad wie ENTER-Pfad M18.19).
                com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
                // Evidenz-Trigger für die Timeline (Re-Enter nach Fahrt).
                triggerRepo.insert(
                    TriggerEvent(
                        id = UUID.randomUUID().toString(),
                        occurredAt = now,
                        type = "GEOFENCE_REENTER_AFTER_DRIVE",
                        source = "geofence_auto",
                        confidence = 0.85f,
                        geofenceId = decision.geofenceId,
                        detectionEventId = null,
                        metadataJson = """{"reason":"drive_end_reenter","sessionTitle":"${typeName ?: decision.type}"}""",
                        anchorQuality = "HIGH"
                    )
                )
                Log.d(
                    TAG,
                    "M18.114: Geofence-Activity nach Drive-Ende neu gestartet: geofence=${decision.geofenceId}, type=${decision.type}, session=${session.id}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Re-Start nach Drive-Ende fehlgeschlagen", e)
            }
        } else {
            Log.d(TAG, "M18.114: Kein Geofence-Re-Start nach Drive-Ende (Resolver: NoRestart)")
        }

        return Result.success()
    }

    companion object {
        /** Nach einem Drive-Session-Stop aufrufen (REPLACE). */
        fun schedule(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    DRIVE_END_GEOFENCE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<DriveEndGeofenceRestarter>().build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Schedule fehlgeschlagen: ${e.message}")
            }
        }
    }
}