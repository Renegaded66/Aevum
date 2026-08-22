package com.d_drostes_apps.aevum.automation.activityrecognition

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.d_drostes_apps.aevum.automation.geofence.EventDrivenLocationChecker
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════════
// M18.66: AUTOFART-ERKENNUNG — EINFACH UND ZUVERLÄSSIG (User-Spezifikation)
//
// "Sobald die Autofahrt erkannt wird → Activity Autofahren starten.
//  Wenn keine mehr erkannt wird (für 5 Minuten) → Activity stoppen."
//
// Die alte Pipeline (M18.45/M18.64) hatte vier Killer:
//   1. Start erst nach ~3 Min (2 Min Confirm-Delay + 60s GPS-Doppelcheck)
//   2. EXIT-Pfad tot: Receiver enqueued bei EXIT nie den Session-Worker
//   3. Watchdog (90s Transition + GPS-Stillstand) stoppte an der Ampel
//   4. Activity "Mobilität" statt "Autofahren"
//
// Neue Pipeline:
//   START  → IN_VEHICLE-ENTER (Google) ODER GPS-Speed-Serie ≥ 8 m/s
//            → SOFORT Activity "Autofahren" (driving) starten.
//   STOP   → 5 Minuten ohne Fahrt-Signal (AR-Sample ODER GPS-Bewegung
//            ≥ 100 m zwischen Probes) → Session stoppen.
//            Jedes Fahrt-Signal refresht den 5-Min-Watchdog (REPLACE).
// ══════════════════════════════════════════════════════════════════════

/** 5 Minuten ohne Fahrt-Signal = Fahrt vorbei (User-Spezifikation). */
private const val DRIVE_WATCHDOG_NO_SIGNAL_MS = 5L * 60 * 1000
/** M18.67-FIX4: Mindest-Bewegung zwischen zwei Probes (2 Min Abstand),
 *  die als "Fahrt lebt" zählt. 360 m / 2 Min = 3 m/s = 10,8 km/h —
 *  schließt Gehen (1,2-1,5 m/s = 144-180 m) aus, erfasst aber
 *  Stadtverkehr (5-15 m/s = 600-1800 m). */
private const val DRIVE_MIN_PROBE_MOVEMENT_M = 360.0
/** Work-Name (UniqueWork für REPLACE-Semantik). */
private const val DRIVE_WATCHDOG_WORK = "aevum.drive_watchdog"

/**
 * M18.66: STARTET die Autofahrt-Session SOFORT.
 *
 * Wird vom [ActivityTransitionReceiver] bei jedem IN_VEHICLE-ENTER und
 * vom [DriveProbeWorker] nach bestätigter GPS-Speed-Serie aufgerufen.
 * Kein 2-Minuten-Confirm mehr, keine GPS-Bewegungs-Prüfung vor dem
 * Start — die Erkennung (Google-Transition ODER Speed-Serie) IST die
 * Bestätigung (User-Spezifikation: "Sobald die Autofahrt erkannt wird,
 * soll die Activity Autofahren gestartet werden").
 *
 * Der Worker selbst prüft den Duplikat-Schutz (läuft schon eine
 * Auto-Session, wird nichts Neues gestartet) und startet den
 * 5-Minuten-Watchdog (Stopp ohne weiteres Signal).
 */
class DriveStartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        fun triggerEventRepository(): com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val triggerRepo = deps.triggerEventRepository()

        // M18.66-FIX15: AR-START-GATE gegen False-Positives.
        // Root-Cause (User: "zeichnet ständig zuhause auf"): Der
        // ActivityTransitionReceiver rief bei jedem IN_VEHICLE-ENTER
        // DriveStartWorker.schedule() SOFORT auf — ohne Warmup, ohne
        // Netto-Displacement, ohne Speed-Prüfung. Googles Activity
        // Recognition liefert bei Sensorrauschen (Vibration, Zug/Bus
        // vor dem Fenster, GPS-Kaltstart nach Update) regelmäßig
        // IN_VEHICLE-False-Positives.
        // Jetzt: Ein Start ist NUR erlaubt, wenn
        //   a) GPS-bestätigt (markDriveConfirmed von DriveProbeWorker/
        //      InitialActivitySnapshotWorker), ODER
        //   b) die aktuellen GPS-Probes des DriveDetectionService
        //      klassifizieren als Driving (alle Gates: Warmup,
        //      Netto-Displacement ≥ 150m, 4 konsekutive ≥ 8 m/s,
        //      avgSpeed ≥ 5 m/s — M18.71 sensibler).
        // Beides zusammen deckt ab: echte Fahrten starten über den
        // GPS-Stream (der die Gates hat), AR-False-Positives starten
        // nichts mehr.
        val now = System.currentTimeMillis()
        val confirmed = bridge.consumeDriveConfirmation()
        val gpsOk = DriveDetectionEngine.classify(bridge.currentDriveProbes(), now) is
            DriveDetectionEngine.Classification.Driving
        if (!confirmed && !gpsOk) {
            // M18.68-FIX (Detection-Blackout): Das Confirmation-Flag wird
            // vom DriveDetectionService gesetzt, BEVOR die Probes gedrained
            // werden. Läuft parallel der ActivityRecognitionWorker (er
            // konsumiert das Flag IMMER — M18.64-FIX gegen Stale-
            // Confirmations), dann ist confirmed hier false und
            // currentDriveProbes() ist durch den Drain fast leer (gpsOk
            // false) → der Start wird verworfen. Ohne Recovery bliebe
            // driveActive=true stehen (von markDriveConfirmed gesetzt) und
            // der DriveDetectionService würde NIE WIEDER klassifizieren
            // (handleFix: if (!isDriveActive())) — die Fahrt wird dauerhaft
            // nicht aufgezeichnet, obwohl sie real stattfindet.
            // Recovery ist konservativ: driveActive=false erlaubt NUR die
            // NEUE Klassifikation (alle Gates: 5×9 m/s-Kette, avg 6 m/s,
            // Netto-Displacement ≥ 200 m). Eine False-Positive kann so
            // nicht entstehen — es wird nichts gestartet, nur die
            // Erkennung wieder aktiviert.
            Log.d(TAG, "Start-Gate: keine GPS-Bestätigung und keine Driving-Klassifikation — AR-Cluster verworfen (False-Positive-Schutz); driveActive zurückgesetzt für neue Erkennung")
            bridge.clearDriveActive()
            return Result.success()
        }

        // M18.66: Die Erkennung (ENTER-Event ODER Speed-Serie) ist die
        // Bestätigung — die Session startet JETZT, nicht erst nach
        // Minuten. Cluster-Start = ältestes Signal (deckt "Fahrt begann
        // vor der Erkennung" ab).
        val cluster = bridge.drainVehicleCluster()
        val startedAt = cluster?.startMs ?: System.currentTimeMillis()

        // M18.66: Gate — Autofahren in den Trigger-Settings aus?
        if (!bridge.isDrivingEnabled()) {
            Log.d(TAG, "Autofahren-Erkennung deaktiviert — kein Start")
            return Result.success()
        }

        val liveSession = live.liveSession.value

        // Duplikat-Schutz: Läuft schon eine Auto-Fahr-Session → nichts tun
        // (nur Herzschlag refreshen, damit der Watchdog lebt).
        if (liveSession != null && liveSession.isLive &&
            liveSession.activityTypeId == "driving" &&
            liveSession.sourceType == "ACTIVITY_RECOGNITION_AUTO"
        ) {
            bridge.refreshDriveHeartbeat(now)
            DriveWatchdogWorker.schedule(applicationContext)
            Log.d(TAG, "Auto-Session läuft bereits — Watchdog refresht")
            return Result.success()
        }

        try {
            // Andere Live-Session (z.B. manuelles Workout) vorher sauber
            // beenden — wie bei Geofence.
            if (liveSession != null && liveSession.isLive) {
                live.forceFinishForAuto()
            }
            val session = live.start(
                activityTypeId = "driving",
                title = "Autofahren",
                sourceType = "ACTIVITY_RECOGNITION_AUTO",
                startedAt = startedAt
            )
            Log.d(TAG, "Auto-Session gestartet: ${session.id} (start=$startedAt)")
            // Foreground-Service, damit der Timer im Hintergrund weiterläuft.
            com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
            // 5-Minuten-Watchdog: ohne weiteres Fahrt-Signal stoppt die Session.
            DriveWatchdogWorker.schedule(applicationContext)
            triggerRepo.insert(
                com.d_drostes_apps.aevum.data.model.TriggerEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    occurredAt = now,
                    type = "DRIVING_STARTED",
                    source = "activity_recognition",
                    confidence = 0.9f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"detected\"}",
                    anchorQuality = "HIGH"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auto-Start fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "DriveStartWorker"

        /** Vom Receiver/ProbeWorker aufgerufen: Start sofort enqueuen. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_START_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveStartWorker>().build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DRIVE_START_WORK)
        }
    }
}

/** Work-Name für den sofortigen Start (UniqueWork). */
private const val DRIVE_START_WORK = "aevum.drive_start"

/** Work-Name für den sofortigen Stop (UniqueWork). */
private const val DRIVE_STOP_WORK = "aevum.drive_stop"

/**
 * M18.66: Beendet eine laufende Auto-Fahr-Session SOFORT.
 *
 * Wird vom [ActivityTransitionReceiver] bei einem IN_VEHICLE-EXIT
 * aufgerufen (Google meldet explizit "nicht mehr im Fahrzeug"). Im
 * Gegensatz zum [DriveWatchdogWorker] (5 Minuten ohne Signal) stoppt
 * dieser Worker IMMEDIAT — ein bestätigter EXIT ist das Ende der Fahrt.
 */
class DriveStopWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        fun triggerEventRepository(): com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
        // M18.67-FIX4: driveActive zurücksetzen, sonst refresht der
        // DriveDetectionService weiter den Heartbeat.
        fun activityRecognitionBridge(): ActivityRecognitionBridge
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val live = deps.liveActivityManager()
        val triggerRepo = deps.triggerEventRepository()
        val bridge = deps.activityRecognitionBridge()

        val session = live.liveSession.value
        val isDrivingSession = session != null && session.isLive &&
            session.activityTypeId == "driving" &&
            session.sourceType == "ACTIVITY_RECOGNITION_AUTO"
        if (!isDrivingSession) {
            Log.d(TAG, "Keine laufende Auto-Fahr-Session -> DriveStop No-Op")
            return Result.success()
        }

        try {
            // M18.67-FIX4: driveActive zurücksetzen, sonst refresht der
            // DriveDetectionService weiter den Heartbeat (speed >= 3 m/s
            // beim Gehen/Velo/Bus) und der Watchdog läuft nie ab.
            bridge.clearDriveActive()
            live.stop()
            triggerRepo.insert(
                com.d_drostes_apps.aevum.data.model.TriggerEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    occurredAt = System.currentTimeMillis(),
                    type = "DRIVING_ENDED",
                    source = "activity_recognition",
                    confidence = 0.9f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"google_exit\"}",
                    anchorQuality = "HIGH"
                )
            )
            Log.d(TAG, "Auto-Fahr-Session sofort gestoppt (Google-EXIT)")
            // Watchdog nicht weiterlaufen lassen.
            DriveWatchdogWorker.cancel(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Sofort-Stop fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "DriveStopWorker"

        /** Vom Receiver bei IN_VEHICLE-EXIT aufgerufen: sofort stoppen. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_STOP_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveStopWorker>().build()
            )
        }
    }
}

/**
 * M18.66: Watchdog für laufende Auto-Fahrten — 5-Minuten-Regel.
 *
 * User-Spezifikation: "Wenn keine Autofahrt mehr erkannt wird (für
 * 5 Minuten), soll die Activity Autofahren wieder stoppen."
 *
 * Jedes Fahrt-Signal (IN_VEHICLE-Sample ODER GPS-Bewegung ≥ 100 m
 * zwischen zwei Probes) refresht den Timer (REPLACE). Läuft der Timer
 * ab, ohne dass ein Signal kam, wird die Session gestoppt.
 *
 * KEINE 90s-Transition- und KEINE 8-Minuten-Modi mehr — der 90s-Modus
 * stoppte an der Ampel (Fahrt ging weiter, Google meldete nur kurz
 * STILL), der 8-Minuten-Modus ließ die Session nach dem Parken endlos
 * laufen. Die 5-Minuten-Regel ist der Kompromiss aus beidem: Ampel-
 * Halte (< 5 Min) stoppen nichts, kurze Besorgungen (> 5 Min) schon.
 */
class DriveWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        fun triggerEventRepository(): com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val triggerRepo = deps.triggerEventRepository()
        val provider = deps.locationProvider()
        val now = System.currentTimeMillis()

        // M18.66: EXIT-Marker IMMER konsumieren — wenn Google explizit
        // "nicht mehr im Fahrzeug" meldet und eine Auto-Session läuft,
        // sofort stoppen (nicht 5 Minuten warten). Wenn KEINE Session
        // läuft, wird der Marker verworfen — er darf nie eine SPÄTERE
        // Fahrt stoppen (veralteter EXIT).
        val exited = bridge.consumeVehicleExited()

        // Läuft überhaupt eine Auto-Fahr-Session?
        val session = live.liveSession.value
        val isDrivingSession = session != null && session.isLive &&
            session.activityTypeId == "driving" &&
            session.sourceType == "ACTIVITY_RECOGNITION_AUTO"
        if (!isDrivingSession) {
            Log.d(TAG, "Keine laufende Auto-Fahr-Session -> Watchdog No-Op")
            return Result.success()
        }

        if (exited != null) {
            Log.d(TAG, "IN_VEHICLE-EXIT gemeldet -> Auto-Fahr-Session sofort beenden")
            stopDrivingSession(live, triggerRepo, now)
            return Result.success()
        }

        // 1) Frisches AR-Signal (IN_VEHICLE-Sample)? → Fahrt lebt.
        val last = bridge.lastVehicleSample()
        if (last > 0 && now - last < DRIVE_WATCHDOG_NO_SIGNAL_MS) {
            Log.d(TAG, "Fahrt lebt (letztes AR-Sample vor ${(now - last) / 1000}s) -> Watchdog verlängert")
            schedule(applicationContext)
            return Result.success()
        }

        // 2) Kein frisches AR-Signal → GPS-Bewegungs-Check: bewegt sich
        //    der Standort zwischen zwei Probes (2 Min Abstand) um
        //    ≥ 100 m, läuft die Fahrt weiter (Google liefert bei
        //    GPS-Erkennung oft keine AR-Samples). Der Check dauert 2
        //    Minuten — währenddessen läuft die Session weiter.
        Log.d(TAG, "Kein frisches AR-Signal -> GPS-Bewegungs-Check")
        val first = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success -> r
                else -> null
            }
        } catch (e: Exception) { null }
        if (first == null) {
            Log.d(TAG, "Kein GPS-Fix -> Fahrt konservativ beenden")
        } else {
            kotlinx.coroutines.delay(DRIVE_PROBE_INTERVAL_MS)
            val second = try {
                when (val r = provider.getCurrentLocation()) {
                    is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success -> r
                    else -> null
                }
            } catch (e: Exception) { null }
            if (second != null) {
                val distance = haversine(
                    first.latitude, first.longitude,
                    second.latitude, second.longitude
                )
                if (distance >= DRIVE_MIN_PROBE_MOVEMENT_M) {
                    Log.d(TAG, "Standort bewegt sich (${distance.toInt()}m/2min) -> Fahrt läuft weiter, Watchdog verlängert")
                    schedule(applicationContext)
                    return Result.success()
                }
                Log.d(TAG, "Standort steht (${distance.toInt()}m/2min) -> Fahrt beenden")
            } else {
                Log.d(TAG, "Kein zweiter GPS-Fix -> Fahrt konservativ beenden")
            }
        }

        // 3) Fahrt beenden: Session stoppen + Trigger für die Timeline.
        stopDrivingSession(live, triggerRepo, now)
        return Result.success()
    }

    /** M18.66: Auto-Fahr-Session beenden + Trigger schreiben. */
    private suspend fun stopDrivingSession(
        live: com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager,
        triggerRepo: com.d_drostes_apps.aevum.data.repository.TriggerEventRepository,
        now: Long
    ) {
        try {
            // M18.67-FIX3: driveActive zurücksetzen, damit der
            // DriveDetectionService eine neue Fahrt erkennen kann.
            val bridge = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).activityRecognitionBridge()
            bridge.clearDriveActive()
            live.stop()
            triggerRepo.insert(
                com.d_drostes_apps.aevum.data.model.TriggerEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    occurredAt = now,
                    type = "DRIVING_ENDED",
                    source = "activity_recognition",
                    confidence = 0.9f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"watchdog_5min_or_exit\"}",
                    anchorQuality = "HIGH"
                )
            )
            Log.d(TAG, "Auto-Fahr-Session gestoppt")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog-Stop fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "DriveWatchdogWorker"

        /** Fahrt-Signale refreshen den 5-Min-Timer (REPLACE). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_WATCHDOG_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveWatchdogWorker>()
                    .setInitialDelay(DRIVE_WATCHDOG_NO_SIGNAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DRIVE_WATCHDOG_WORK)
        }
    }
}

/** Haversine-Distanz in Metern (gleiche Formel wie Geofence-Checks). */
private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

// ══════════════════════════════════════════════════════════════════════
// M18.64: GPS-GESCHWINDIGKEITS-PFAD (DriveProbeWorker)
//
// Root-Cause (User: \"Autofahrten werden nicht zuverlässig erkannt\"):
// Die Erkennung hing KOMPLETT an Googles IN_VEHICLE-Transitions. Wenn
// Google kein Event liefert (App im Hintergrund, Fahrt begann vor dem
// App-Start, Sensor-Spring, AR-Permission fehlt), gab es KEINEN
// unabhängigen Pfad — die Fahrt wurde nie erkannt.
//
// Dieser Worker ist der unabhängige Fallback: Er holt alle 2 Minuten
// einen GPS-Fix (CurrentLocationProvider, geofence-unabhängig) und
// klassifiziert die Geschwindigkeits-Serie über DriveDetectionEngine
// (mehrere aufeinanderfolgende Messungen >= 8 m/s über >= 1 Minute —
// robust gegen Ausreißer, Gehen, Laufen, Fahrrad).
//
// Selbst-Erneuerung statt PeriodicWork: WorkManager-Minimum für
// periodische Jobs ist 15 Minuten (M18.62-Lektion) — der Takt plant
// sich am Ende jedes Laufs selbst neu (OneTimeWork + REPLACE).
// ══════════════════════════════════════════════════════════════════════

/** Takt: alle 2 Minuten ein GPS-Fix (Akku: 1 Fix/2 Min, nur bei
 *  Bewegung relevant — der Fix selbst ist ein einzelner CurrentLocation-
 *  Call, kein kontinuierlicher Stream). */
private const val DRIVE_PROBE_INTERVAL_MS = 2L * 60 * 1000
private const val DRIVE_PROBE_WORK = "aevum.drive_probe"

/**
 * M18.64: GPS-Geschwindigkeits-Probe für die Fahrterkennung.
 *
 * Läuft dauerhaft im Hintergrund (selbst-erneuernd), solange die
 * Autofahrt-Erkennung aktiv ist. Jeder Lauf:
 *  1. GPS-Fix holen (Speed + Accuracy + Distanz zum letzten Probe).
 *  2. Probe puffern (ActivityRecognitionBridge).
 *  3. Serie klassifizieren (DriveDetectionEngine).
 *  4. Fahrt bestätigt → markDriveConfirmed + Session-Starter enqueuen
 *     (Cluster-Start = ältester Probe → deckt \"Fahrt begann vor der
 *     Erkennung\" ab) + Watchdog starten.
 *  5. Nächsten Lauf planen (REPLACE).
 */
class DriveProbeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()

        // Gate: Autofahrt-Erkennung in den Trigger-Settings aus?
        // (Cache in der Bridge — kein DB-Zugriff pro Lauf nötig.)
        if (!bridge.isDrivingEnabled()) {
            // Takt NICHT weiterplanen — der Scheduler startet ihn neu,
            // sobald das Gate wieder an ist (App-Start / Settings-Änderung).
            return Result.success()
        }

        val provider = deps.locationProvider()
        val now = System.currentTimeMillis()

        // 1) GPS-Fix holen (geofence-unabhängig).
        val fix = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success -> r
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        if (fix != null) {
            // 2) Probe puffern. Distanz zum letzten Probe für den
            //    GPS-Sprung-Ausreißer-Filter.
            val last = bridge.currentDriveProbes().lastOrNull()
            val distance = if (last != null && last.latitude != null && last.longitude != null) {
                haversine(last.latitude!!, last.longitude!!, fix.latitude, fix.longitude)
            } else null
            val probe = DriveDetectionEngine.DriveProbe(
                timestampMs = now,
                speedMps = fix.speedMps,
                accuracyMeters = fix.accuracyMeters,
                distanceFromLastM = distance,
                latitude = fix.latitude,
                longitude = fix.longitude
            )
            bridge.addDriveProbe(probe, refreshHeartbeat = false)
            Log.d(TAG, "Probe: speed=${fix.speedMps?.let { "%.1f".format(it) } ?: "?"} m/s, acc=${fix.accuracyMeters.toInt()}m")

            // 3) Serie klassifizieren.
            when (val result = DriveDetectionEngine.classify(bridge.currentDriveProbes(), now)) {
                is DriveDetectionEngine.Classification.Driving -> {
                    Log.d(TAG, "Fahrt per GPS-Geschwindigkeit bestätigt (confidence=${result.confidence})")
                    // 4) M18.66: SOFORT starten — die Speed-Serie ist die
                    //    Bestätigung. Der DriveStartWorker legt die
                    //    Session mit dem ältesten Probe als Startzeit an
                    //    (deckt "Fahrt begann vor der Erkennung" ab).
                    //    Dafür den Cluster in die Bridge legen und die
                    //    Probes leeren (kein erneutes Driving-Melden).
                    DriveDetectionEngine.toVehicleCluster(bridge.currentDriveProbes(), now)?.let { cluster ->
                        bridge.addSample(cluster.startMs, 75)
                        bridge.addSample(cluster.endMs, 75)
                    }
                    bridge.refreshDriveHeartbeat(now)
                    // M18.66-FIX15: GPS-Bestätigung markieren, BEVOR die
                    // Probes gedrained werden (siehe DriveDetectionService).
                    bridge.markDriveConfirmed()
                    bridge.drainDriveProbes()
                    DriveStartWorker.schedule(applicationContext)
                    DriveWatchdogWorker.schedule(applicationContext)
                }
                is DriveDetectionEngine.Classification.NotDriving -> {
                    Log.d(TAG, "Keine Fahrt (Probes=${bridge.currentDriveProbes().size})")
                }
                DriveDetectionEngine.Classification.InsufficientData -> {
                    Log.d(TAG, "Zu wenige Probes für eine Entscheidung (${bridge.currentDriveProbes().size})")
                }
            }
        } else {
            Log.d(TAG, "Kein GPS-Fix — Probe übersprungen")
        }

        // 5) Nächsten Lauf planen (REPLACE = immer genau ein Takt).
        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "DriveProbeWorker"

        /** Takt (neu) starten — REPLACE: genau ein Lauf, jeder Start
         *  resetet den Timer. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_PROBE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveProbeWorker>()
                    .setInitialDelay(DRIVE_PROBE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        /** Takt stoppen (z.B. Gate aus). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DRIVE_PROBE_WORK)
        }

        private fun scheduleNext(context: Context) = schedule(context)
    }
}
