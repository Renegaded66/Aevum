package com.d_drostes_apps.aevum.automation.activityrecognition

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
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════════
// M18.72: WANDERUNGEN AUTOMATISCH AUFZEICHNEN (User-Spezifikation)
//
// "Wanderungen sollen automatisch erkannt und aufgezeichnet werden:
//  (a) Erst wenn man 5 Minuten am Stück unterwegs ist (Bewegung — nicht
//      jeder Gang zum Kühlschrank).
//  (b) Die 5 Minuten Vorlaufzeit müssen mit aufgezeichnet werden
//      (startedAt = now − 5 min)."
//
// Muster analog M18.70 ScreenRecordingEngine (Schwelle + Vorlauf) und
// M18.66 DriveWorkers (Start/Stop/Watchdog):
//   START  → WALKING/RUNNING-ENTER (Google) ODER GPS-Bewegung ≥ 300 m
//            zwischen zwei Fixes. Die WalkingDetectionEngine entscheidet
//            (5-Minuten-Schwelle, nur wenn nichts anderes aufzeichnet).
//            Session "Spazieren" mit sourceType WALKING_AUTO und
//            startedAt = now − 5 min (Vorlauf).
//   STOP   → 5 Minuten ohne Walking-Signal (Transition ODER GPS-Bewegung)
//            → Session stoppen. Jedes Signal refresht den Watchdog.
// ══════════════════════════════════════════════════════════════════════

/** Work-Namen (UniqueWork für REPLACE-Semantik). */
private const val WALKING_START_WORK = "aevum.walking_start"
private const val WALKING_STOP_WORK = "aevum.walking_stop"
private const val WALKING_WATCHDOG_WORK = "aevum.walking_watchdog"

/** Mindest-Bewegung zwischen zwei GPS-Fixes (5s Abstand), die als
 *  "Wanderung lebt" zählt: 2 m/s ≈ 7,2 km/h — locker über Geh-Tempo
 *  (1,0-1,5 m/s), aber deutlich unter Fahrrad/Auto. Die klassifizierende
 *  Engine prüft die 5-Minuten-Schwelle separat. */
private const val WALKING_MIN_PROBE_MOVEMENT_M = 10.0

/**
 * M18.72: STARTET die Wanderung-Session mit 5 Minuten Vorlauf.
 *
 * Wird vom [ActivityTransitionReceiver] bei WALKING/RUNNING-ENTER
 * (nach 5 Minuten durchgehender Phase) und vom [DriveDetectionService]
 * nach bestätigter GPS-Bewegungs-Serie aufgerufen.
 */
class WalkingStartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val now = System.currentTimeMillis()

        // Activity-Typ: RUNNING-Events → "joggen", sonst "spazieren"
        // (M18.72: Wanderungen; Läufe landen auf dem Joggen-Typ).
        val activityTypeId = inputData.getString(KEY_ACTIVITY_TYPE) ?: "spazieren"
        val title = if (activityTypeId == "joggen") "Joggen" else "Spazieren"

        // Gate: Walking-Erkennung in den Trigger-Settings aus?
        if (!bridge.isWalkingEnabled()) {
            Log.d(TAG, "Walking-Erkennung deaktiviert — kein Start")
            return Result.success()
        }

        // M18.84: Ende der letzten Auto-Session — die Walking-Schwelle und
        // der Vorlauf dürfen nie in eine Fahrt hinein reichen (Google-AR
        // meldet WALKING während Stop&Go; der alte Code startete beim
        // nächsten ENTER nach dem Aussteigen "Spazieren" mit 5-Min-Vorlauf
        // in die Fahrt hinein). try-catch: Ein DB-Fehler hier darf den
        // Start nicht crashen — dann ohne Clamp starten (alter Zustand).
        val lastDriveEndMs = try {
            live.lastAutoSessionEndMs()
        } catch (_: Exception) { null }

        // 5-Minuten-Schwelle + "nichts anderes zeichnet auf" (Engine).
        if (!WalkingDetectionEngine.shouldStartWalking(
                walkingSinceMs = bridge.walkingSince(),
                now = now,
                walkingEnabled = true, // Gate oben bereits geprüft
                anythingRecording = live.liveSession.value?.isLive == true,
                lastDriveEndMs = lastDriveEndMs
            )
        ) {
            Log.d(TAG, "5-Minuten-Schwelle nicht erreicht oder andere Session aktiv — kein Start")
            return Result.success()
        }

        val liveSession = live.liveSession.value

        // Duplikat-Schutz: Läuft schon eine Walking-Session → nichts tun
        // (nur Herzschlag refreshen, damit der Watchdog lebt).
        if (liveSession != null && liveSession.isLive &&
            liveSession.sourceType == "WALKING_AUTO"
        ) {
            bridge.markWalkingSignal(now)
            WalkingWatchdogWorker.schedule(applicationContext)
            Log.d(TAG, "Walking-Session läuft bereits — Watchdog refresht")
            return Result.success()
        }

        try {
            // Andere Live-Session (z. B. manuelles Workout oder Digital)
            // vorher sauber beenden — M18.71-Overlap-Regeln greifen im
            // start()-Pfad (Kürzen/Splitten statt Löschen). Es darf nie
            // zwei Live-Sessions geben.
            if (liveSession != null && liveSession.isLive) {
                live.forceFinishForAuto()
            }
            // Vorlauf: die letzten 5 Minuten fallen rückwirkend in die
            // Aufzeichnung (User-Spec (b)) — M18.84: geclampt an das Ende
            // der letzten Auto-Session (nie in die Fahrt hinein).
            val startedAt = WalkingDetectionEngine.recordingStartTime(now, lastDriveEndMs)
            val session = live.start(
                activityTypeId = activityTypeId,
                title = title,
                sourceType = "WALKING_AUTO",
                startedAt = startedAt
            )
            bridge.markWalkingActive()
            bridge.markWalkingSignal(now)
            Log.d(TAG, "Wanderung gestartet: ${session.id} (start=$startedAt, Vorlauf 5min)")
            // Foreground-Service, damit der Timer im Hintergrund weiterläuft.
            com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
            // 5-Minuten-Watchdog: ohne weiteres Walking-Signal stoppt die Session.
            WalkingWatchdogWorker.schedule(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Walking-Start fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "WalkingStartWorker"
        const val KEY_ACTIVITY_TYPE = "activity_type"

        /** Vom Receiver (WALKING/RUNNING-ENTER) und vom DriveDetectionService
         *  (GPS) aufgerufen. [activityTypeId]: "spazieren" (Standard) oder
         *  "joggen" bei RUNNING-Events. */
        fun schedule(context: Context, activityTypeId: String = "spazieren") {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WALKING_START_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WalkingStartWorker>()
                    .setInputData(androidx.work.Data.Builder()
                        .putString(KEY_ACTIVITY_TYPE, activityTypeId)
                        .build())
                    .build()
            )
        }
    }
}

/**
 * M18.72: Stoppt eine laufende Walking-Session SOFORT (WALKING-EXIT).
 * Der Watchdog ([WalkingWatchdogWorker]) deckt das 5-Minuten-Stopp ab.
 */
class WalkingStopWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val live = deps.liveActivityManager()

        val session = live.liveSession.value
        val isWalkingSession = session != null && session.isLive &&
            session.sourceType == "WALKING_AUTO"
        if (!isWalkingSession) {
            Log.d(TAG, "Keine laufende Walking-Session -> Stop No-Op")
            return Result.success()
        }

        try {
            // Walking-Phase zurücksetzen — ein neuer ENTER startet den
            // 5-Minuten-Zähler neu.
            deps.activityRecognitionBridge().clearWalkingActive()
            live.stop()
            WalkingWatchdogWorker.cancel(applicationContext)
            Log.d(TAG, "Walking-Session sofort gestoppt (EXIT)")
        } catch (e: Exception) {
            Log.e(TAG, "Sofort-Stop fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "WalkingStopWorker"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WALKING_STOP_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WalkingStopWorker>().build()
            )
        }
    }
}

/**
 * M18.72: Watchdog für die laufende Wanderung — 5-Minuten-Regel.
 *
 * Jedes Walking-Signal (WALKING/RUNNING-Sample ODER GPS-Bewegung
 * ≥ 10 m zwischen Probes) refresht den Timer (REPLACE). Läuft der
 * Timer ab, ohne dass ein Signal kam, wird die Session gestoppt.
 */
class WalkingWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun liveActivityManager(): com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
        fun locationProvider(): com.d_drostes_apps.aevum.automation.location.CurrentLocationProvider
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val live = deps.liveActivityManager()
        val now = System.currentTimeMillis()

        val session = live.liveSession.value
        val isWalkingSession = session != null && session.isLive &&
            session.sourceType == "WALKING_AUTO"
        if (!isWalkingSession) {
            Log.d(TAG, "Keine laufende Walking-Session -> Watchdog No-Op")
            return Result.success()
        }

        // Frisches Walking-Signal? → Wanderung lebt.
        val last = bridge.lastWalkingSignal()
        if (last > 0 && now - last < WalkingDetectionEngine.WALKING_WATCHDOG_NO_SIGNAL_MS) {
            Log.d(TAG, "Wanderung lebt (letztes Signal vor ${(now - last) / 1000}s) -> Watchdog verlängert")
            schedule(applicationContext)
            return Result.success()
        }

        // Kein frisches Signal: GPS-Bewegung-Check. Der Google-Transition-
        // Stream liefert oft keine wiederholten ENTER-Samples, daher prüfen
        // wir hier, ob sich der Standort bewegt (2 Fixes 2 Min auseinander —
        // schließt Gehen im Raum aus, erfasst aber echte Wanderung).
        Log.d(TAG, "Kein frisches Walking-Signal -> GPS-Bewegungs-Check")
        val first = try {
            when (val r = deps.locationProvider().getCurrentLocation()) {
                is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success -> r
                else -> null
            }
        } catch (e: Exception) { null }
        if (first == null) {
            Log.d(TAG, "Kein GPS-Fix -> Wanderung beenden")
        } else {
            kotlinx.coroutines.delay(WALKING_PROBE_INTERVAL_MS)
            val second = try {
                when (val r = deps.locationProvider().getCurrentLocation()) {
                    is com.d_drostes_apps.aevum.automation.location.CurrentLocationResult.Success -> r
                    else -> null
                }
            } catch (e: Exception) { null }
            if (second != null) {
                val distance = haversine(
                    first.latitude, first.longitude,
                    second.latitude, second.longitude
                )
                if (distance >= WALKING_MIN_PROBE_MOVEMENT_M) {
                    Log.d(TAG, "Standort bewegt sich (${distance.toInt()}m/2min) -> Wanderung läuft weiter")
                    bridge.markWalkingSignal(now)
                    schedule(applicationContext)
                    return Result.success()
                }
                Log.d(TAG, "Standort steht (${distance.toInt()}m/2min) -> Wanderung beenden")
            } else {
                Log.d(TAG, "Kein zweiter GPS-Fix -> Wanderung beenden")
            }
        }

        // Wanderung beenden.
        try {
            bridge.clearWalkingActive()
            live.stop()
            Log.d(TAG, "Walking-Session gestoppt (5 min ohne Signal)")
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog-Stop fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "WalkingWatchdogWorker"
        private const val WALKING_PROBE_INTERVAL_MS = 2L * 60 * 1000

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WALKING_WATCHDOG_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WalkingWatchdogWorker>()
                    .setInitialDelay(WalkingDetectionEngine.WALKING_WATCHDOG_NO_SIGNAL_MS, TimeUnit.MILLISECONDS)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WALKING_WATCHDOG_WORK)
        }
    }
}

/** Haversine-Distanz in Metern (gleiche Formel wie DriveWorkers/Geofence). */
private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
