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
// M18.45: SMARTE FAHRTERKENNUNG (User-Feedback)
//
// Problem vorher: Jedes IN_VEHICLE-ENTER startete sofort eine Session,
// der Stop hing allein am IN_VEHICLE-EXIT (den Google oft nicht liefert)
// -> "Timer läuft seit 5 Minuten", kurze Erkennungen wurden als Fahrt
// verbucht.
//
// Neue Pipeline:
//   1. IN_VEHICLE-ENTER -> DriveConfirmWorker (2 Min Verzögerung)
//   2. DriveConfirmWorker holt 2 GPS-Fixes im Abstand von 60s.
//      Bewegung >= 200 m = echte Fahrt -> Session startet (mit der
//      ENTER-Zeit, nicht mit der Bestätigungszeit).
//      Keine Bewegung / kein GPS = False-Positive -> verworfen.
//   3. Während der Fahrt refresht jedes IN_VEHICLE-Sample den
//      DriveWatchdogWorker (REPLACE): 8 Min ohne Signal = Fahrt vorbei.
//   4. Aktivitätswechsel (STILL/WALKING/RUNNING) verkürzt den Watchdog
//      auf 90s (Ampel-Toleranz) -> danach stoppt die Session.
// ══════════════════════════════════════════════════════════════════════

/** 2 Minuten warten, bis der Standort die Fahrt bestätigt (Ampel-Start). */
private const val DRIVE_CONFIRM_DELAY_MS = 2L * 60 * 1000
/** Abstand zwischen den zwei GPS-Fixes im ConfirmWorker. */
private const val DRIVE_CONFIRM_SAMPLE_GAP_MS = 60_000L
/** Mindest-Bewegung für "echte Fahrt" (GPS-Flattern-Schwelle). */
private const val DRIVE_MIN_DISTANCE_M = 200.0
/** 8 Minuten ohne IN_VEHICLE-Signal = Fahrt vorbei (Google-EXIT fehlt oft). */
private const val DRIVE_WATCHDOG_NO_SIGNAL_MS = 8L * 60 * 1000
/** 90s Toleranz nach Aktivitätswechsel (Ampel-Rot, kurzer Halt). */
private const val DRIVE_WATCHDOG_TRANSITION_MS = 90_000L
/** Work-Namen (UniqueWork für REPLACE-Semantik). */
private const val DRIVE_CONFIRM_WORK = "aevum.drive_confirm"
private const val DRIVE_WATCHDOG_WORK = "aevum.drive_watchdog"

/**
 * M18.45: Bestätigt eine mögliche Fahrt per Standort-Bewegung.
 *
 * Wird vom [ActivityTransitionReceiver] bei jedem IN_VEHICLE-ENTER
 * geschedult (REPLACE -> jeder neue ENTER resetet den Timer).
 * Nach 2 Minuten holt der Worker zwei GPS-Fixes im Abstand von 60s.
 * Nur wenn die Distanz >= 200 m ist, wird die Fahrt bestätigt und der
 * [ActivityRecognitionWorker] angestoßen (der startet die Session mit
 * der ursprünglichen ENTER-Zeit).
 */
class DriveConfirmWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun locationChecker(): EventDrivenLocationChecker
        // M18.63-CRITICAL (Root Cause "Autofahrten werden nicht
        // erkannt"): EventDrivenLocationChecker liefert NUR einen
        // GPS-Fix, wenn mindestens EIN Geofence gespeichert ist
        // (early return "Keine Geofences gespeichert"). Wer keine
        // Geofences nutzt (nur Autofahren aktiviert), bekam IMMER
        // null → die Fahrt wurde NIE per GPS bestätigt → keine
        // Aufzeichnung. CurrentLocationProvider ist geofence-
        // unabhängig und wird jetzt als primäre Quelle genutzt.
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val provider = deps.locationProvider()

        // 1) Erster GPS-Fix (nach den 2 Minuten Wartezeit).
        // M18.63: Geofence-unabhängiger Fix (CurrentLocationProvider),
        // Fallback auf den alten Geofence-Check.
        val first = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success ->
                    android.location.Location("fused").apply {
                        latitude = r.latitude
                        longitude = r.longitude
                    }
                else -> deps.locationChecker().checkCurrentLocationAgainstGeofences().location
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erster GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        // 2) Zweiter Fix 60s später.
        delay(DRIVE_CONFIRM_SAMPLE_GAP_MS)
        val second = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success ->
                    android.location.Location("fused").apply {
                        latitude = r.latitude
                        longitude = r.longitude
                    }
                else -> deps.locationChecker().checkCurrentLocationAgainstGeofences().location
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zweiter GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        if (first == null || second == null) {
            Log.d(TAG, "Kein GPS-Fix verfügbar -> Fahrt NICHT bestätigt (verworfen)")
            return Result.success()
        }

        val distance = haversine(first.latitude, first.longitude, second.latitude, second.longitude)
        if (distance >= DRIVE_MIN_DISTANCE_M) {
            Log.d(TAG, "Fahrt bestätigt: ${distance.toInt()}m Bewegung in 60s")
            // M18.64-REVIEW-FIX: Die zwei GPS-Fixes als Probes puffern.
            // Der ActivityRecognitionWorker baut seinen Cluster aus dem
            // AR-Buffer ODER (falls der AR-Cluster schon von einem
            // parallelen Lauf gedrained wurde) aus den GPS-Probes. Ohne
            // diese Probes ginge die Bestätigung verloren, wenn kein
            // AR-Cluster mehr da ist → keine Session trotz bestätigter
            // Fahrt (der alte Stale-Confirmation-Bug).
            bridge.addDriveProbe(
                com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe(
                    timestampMs = first.time,
                    speedMps = null,
                    accuracyMeters = first.accuracy,
                    latitude = first.latitude,
                    longitude = first.longitude
                ),
                refreshHeartbeat = false
            )
            bridge.addDriveProbe(
                com.d_drostes_apps.aevum.automation.activityrecognition.DriveDetectionEngine.DriveProbe(
                    timestampMs = second.time,
                    speedMps = null,
                    accuracyMeters = second.accuracy,
                    latitude = second.latitude,
                    longitude = second.longitude
                ),
                refreshHeartbeat = false
            )
            bridge.markDriveConfirmed()
            WorkManager.getInstance(applicationContext)
                .enqueue(OneTimeWorkRequestBuilder<ActivityRecognitionWorker>().build())
        } else {
            // Weniger als 200m in 60s nach 2 Minuten Fahrzeug-Erkennung:
            // Standort-Flattern, Bus an der Ampel, Handy-Vibration.
            Log.d(TAG, "Nur ${distance.toInt()}m in 60s -> False-Positive verworfen")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "DriveConfirmWorker"

        /** Vom Receiver aufgerufen: Timer (re)starten (REPLACE). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_CONFIRM_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveConfirmWorker>()
                    .setInitialDelay(DRIVE_CONFIRM_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        /** EXIT / Aktivitätswechsel: geplante Bestätigung abbrechen. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(DRIVE_CONFIRM_WORK)
        }
    }
}

/**
 * M18.45: Watchdog für laufende Fahrten.
 *
 * Zwei Modi (über [mode]):
 *  - NO_SIGNAL: 8 Minuten ohne IN_VEHICLE-Sample -> Fahrt beendet.
 *    Google liefert oft keinen EXIT; jedes Sample refresht den Timer.
 *  - TRANSITION: Aktivitätswechsel (STILL/WALKING/RUNNING) mit 90s
 *    Toleranz (Ampel-Rot). Kommt in 90s kein neues IN_VEHICLE-Signal,
 *    ist die Fahrt beendet.
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
        fun locationChecker(): EventDrivenLocationChecker
        // M18.63: Geofence-unabhängiger GPS-Provider (siehe
        // DriveConfirmWorker — EventDrivenLocationChecker liefert ohne
        // gespeicherte Geofences keinen Fix).
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val triggerRepo = deps.triggerEventRepository()
        val checker = deps.locationChecker()
        val provider = deps.locationProvider()
        val mode = inputData.getString(KEY_MODE) ?: MODE_NO_SIGNAL
        val now = System.currentTimeMillis()

        // Prüfen: Läuft überhaupt eine Auto-Mobilitäts-Session?
        val session = live.liveSession.value
        val isDrivingSession = session != null && session.isLive &&
            session.activityTypeId == "transport" &&
            session.sourceType == "ACTIVITY_RECOGNITION_AUTO"
        if (!isDrivingSession) {
            Log.d(TAG, "Keine laufende Auto-Mobilitäts-Session -> Watchdog No-Op")
            return Result.success()
        }

        // M18.63: Zwei Modi — NO_SIGNAL (8 Min ohne Sample) und
        // TRANSITION (90s nach Aktivitätswechsel). In beiden Fällen gilt:
        // 1) Frisches IN_VEHICLE-Sample → Fahrt lebt → verlängern.
        // 2) Sonst GPS-Bewegungs-Check (geofence-unabhängig) → bewegt
        //    sich der Standort ≥200m in 60s, läuft die Fahrt weiter.
        // 3) Sonst Fahrt beenden.
        val last = bridge.lastVehicleSample()
        val thresholdMs = if (mode == MODE_NO_SIGNAL) {
            DRIVE_WATCHDOG_NO_SIGNAL_MS
        } else {
            DRIVE_WATCHDOG_TRANSITION_MS
        }
        if (last > 0 && now - last < thresholdMs) {
            Log.d(TAG, "Fahrt lebt noch (letztes Sample vor ${(now - last) / 1000}s) -> Watchdog verlängert")
            schedule(applicationContext, MODE_NO_SIGNAL)
            return Result.success()
        }
        // Kein frisches IN_VEHICLE-Signal mehr → GPS-Bewegungs-Check.
        // M18.63: Der geofence-unabhängige Provider liefert auch ohne
        // gespeicherte Geofences einen Fix.
        Log.d(TAG, "Kein frisches Fahrt-Signal -> GPS-Bewegungs-Check (mode=$mode)")
        val first = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success ->
                    android.location.Location("fused").apply {
                        latitude = r.latitude
                        longitude = r.longitude
                    }
                else -> checker.checkCurrentLocationAgainstGeofences().location
            }
        } catch (e: Exception) { null }
        delay(DRIVE_CONFIRM_SAMPLE_GAP_MS)
        val second = try {
            when (val r = provider.getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success ->
                    android.location.Location("fused").apply {
                        latitude = r.latitude
                        longitude = r.longitude
                    }
                else -> checker.checkCurrentLocationAgainstGeofences().location
            }
        } catch (e: Exception) { null }
        if (first != null && second != null) {
            val distance = haversine(first.latitude, first.longitude, second.latitude, second.longitude)
            if (distance >= DRIVE_MIN_DISTANCE_M) {
                Log.d(TAG, "Standort bewegt sich (${distance.toInt()}m/60s) -> Fahrt läuft weiter, Watchdog verlängert")
                schedule(applicationContext, MODE_NO_SIGNAL)
                return Result.success()
            }
            Log.d(TAG, "Standort steht (${distance.toInt()}m/60s) -> Fahrt beenden")
        } else {
            // Kein GPS verfügbar: konservativ stoppen (besser als eine
            // endlos laufende Session — der User kann manuell weiterlaufen lassen).
            Log.d(TAG, "Kein GPS-Fix -> Fahrt konservativ beenden")
        }

        // Fahrt beenden: Session stoppen + Trigger für die Timeline.
        try {
            live.stop()
            triggerRepo.insert(
                com.d_drostes_apps.aevum.data.model.TriggerEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    occurredAt = now,
                    type = "DRIVING_ENDED",
                    source = "activity_recognition",
                    confidence = 0.8f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"watchdog_$mode\"}",
                    anchorQuality = "HIGH"
                )
            )
            Log.d(TAG, "Mobilitäts-Session per Watchdog gestoppt (mode=$mode)")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog-Stop fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "DriveWatchdogWorker"
        const val KEY_MODE = "mode"
        const val MODE_NO_SIGNAL = "no_signal"
        const val MODE_TRANSITION = "transition"

        /** Fahrt-Samples refreshen den 8-Min-Timer (REPLACE). */
        fun schedule(context: Context, mode: String = MODE_NO_SIGNAL) {
            val delay = if (mode == MODE_NO_SIGNAL) DRIVE_WATCHDOG_NO_SIGNAL_MS else DRIVE_WATCHDOG_TRANSITION_MS
            WorkManager.getInstance(context).enqueueUniqueWork(
                DRIVE_WATCHDOG_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveWatchdogWorker>()
                    .setInputData(Data.Builder().putString(KEY_MODE, mode).build())
                    .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
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
                    // 4) Session-Pipeline anstoßen: Cluster in die Bridge
                    //    legen (Start = ältester Probe), Bestätigung setzen,
                    //    Session-Starter enqueuen, Watchdog starten.
                    DriveDetectionEngine.toVehicleCluster(bridge.currentDriveProbes(), now)?.let { cluster ->
                        bridge.addSample(cluster.startMs, 75)
                        bridge.addSample(cluster.endMs, 75)
                    }
                    bridge.markDriveConfirmed()
                    // M18.64-REVIEW-FIX: Herzschlag refreshen — sonst stoppt
                    // der DriveWatchdog (8 Min ohne IN_VEHICLE-Sample) die
                    // GPS-erkannte Session, obwohl die Fahrt weiterläuft
                    // (Google liefert bei GPS-Erkennung oft keine Samples).
                    bridge.refreshDriveHeartbeat(now)
                    // M18.64-REVIEW-FIX: Puffer leeren — die bestätigte
                    // Fahrt ist in den Cluster übergegangen. Ohne das
                    // würde die Engine bei jedem weiteren Lauf erneut
                    // Driving melden (harmlos dank Duplikat-Schutz, aber
                    // der Puffer wüchse unbegrenzt).
                    bridge.drainDriveProbes()
                    WorkManager.getInstance(applicationContext)
                        .enqueue(OneTimeWorkRequestBuilder<ActivityRecognitionWorker>().build())
                    DriveWatchdogWorker.schedule(applicationContext, DriveWatchdogWorker.MODE_NO_SIGNAL)
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
