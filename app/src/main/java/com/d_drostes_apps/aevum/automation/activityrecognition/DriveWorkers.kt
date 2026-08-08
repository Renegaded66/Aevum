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
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val checker = deps.locationChecker()

        // 1) Erster GPS-Fix (nach den 2 Minuten Wartezeit).
        val first = try {
            checker.checkCurrentLocationAgainstGeofences().location
        } catch (e: Exception) {
            Log.w(TAG, "Erster GPS-Fix fehlgeschlagen: ${e.message}")
            null
        }

        // 2) Zweiter Fix 60s später.
        delay(DRIVE_CONFIRM_SAMPLE_GAP_MS)
        val second = try {
            checker.checkCurrentLocationAgainstGeofences().location
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
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val triggerRepo = deps.triggerEventRepository()
        val checker = deps.locationChecker()
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

        if (mode == MODE_NO_SIGNAL) {
            val last = bridge.lastVehicleSample()
            if (last > 0 && now - last < DRIVE_WATCHDOG_NO_SIGNAL_MS) {
                // Fahrt lebt noch (Sample kam innerhalb der 8 Minuten).
                // Timer neu aufsetzen — ein Race zwischen REPLACE-Enqueue
                // und diesem Lauf ist hier unkritisch (Session-Check oben).
                Log.d(TAG, "Fahrt lebt noch (letztes Sample vor ${(now - last) / 1000}s) -> Watchdog verlängert")
                schedule(applicationContext, MODE_NO_SIGNAL)
                return Result.success()
            }
            // M18.45 (Reflexion): Google liefert bei langen Fahrten oft KEINE
            // weiteren Transition-Events. Statt sofort zu stoppen, prüfen wir
            // per GPS-Bewegung: bewegt sich der Standort noch (>= 200m in
            // 60s), läuft die Fahrt -> Watchdog verlängern. Nur wenn der
            // Standort steht, ist die Fahrt wirklich vorbei.
            Log.d(TAG, "8 Minuten ohne IN_VEHICLE-Signal -> GPS-Bewegungs-Check")
            val first = try {
                checker.checkCurrentLocationAgainstGeofences().location
            } catch (e: Exception) { null }
            delay(DRIVE_CONFIRM_SAMPLE_GAP_MS)
            val second = try {
                checker.checkCurrentLocationAgainstGeofences().location
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
        } else {
            val last = bridge.lastVehicleSample()
            if (last > 0 && now - last < DRIVE_WATCHDOG_TRANSITION_MS) {
                Log.d(TAG, "IN_VEHICLE-Signal innerhalb 90s -> Fahrt läuft weiter")
                schedule(applicationContext, MODE_NO_SIGNAL)
                return Result.success()
            }
            Log.d(TAG, "90s nach Aktivitätswechsel kein Fahrt-Signal -> Fahrt stoppen")
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
