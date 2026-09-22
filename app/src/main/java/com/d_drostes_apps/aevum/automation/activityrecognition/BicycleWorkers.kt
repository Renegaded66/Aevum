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
import java.util.UUID

// ══════════════════════════════════════════════════════════════════════
// M18.134: RADFAHRT ALS RADFAHRT AUFZEICHNEN (Kanban t_a860c07f)
//
// User-Bug (Root t_099f1911): „Ich war Fahrrad fahren, dabei hatte ich
// natürlich auch so 25 km/h drauf — und dann wurde Autofahrt
// aufgezeichnet."
//
// Die Engine blockiert den Auto-Start jetzt über das ON_BICYCLE-Gate
// (12 m/s, DriveDetectionEngine.BIKE_*). Dieses Gate blockiert aber
// AUCH Motorrad/Auto im Stadtverkehr 30-40 km/h, die Google als
// Zweiräder klassifiziert — ON_BICYCLE ist die einzige Zweirad-Klasse
// der API (Google meldet Motorräder regelmäßig dort, belegt in
// RideRecordingReproductionTest „Welt F"). Ein blockierter Start darf
// deshalb nicht „nichts" heißen: Der User war unterwegs und will die
// Zeit behalten.
//
// Dieser Worker ist der Start-Pfad dafür. Er läuft, wenn
//  • ein belastbares ON_BICYCLE-Signal vorliegt
//    (Confidence ≥ BIKE_CONTEXT_MIN_CONFIDENCE, frisch — die pure
//    Funktion DriveDetectionEngine.isReliableBicycleSignal), UND
//  • die GPS-Serie eine Radfahrt belegt
//    (DriveDetectionEngine.detectBikeRide: Spread ≥ 30 s, Netto ≥ 150 m,
//    Schnitt in [14,4 km/h, 43,2 km/h), Geofence-Veto).
//
// Er startet dann eine `radfahren`-Session mit sourceType
// ACTIVITY_RECOGNITION_AUTO und Rückdatierung auf den ersten bewegten
// Probe (Muster resolveDriveStart/WalkingDetectionEngine: die schon
// gefahrenen Minuten fallen in die Aufzeichnung). Gestoppt wird sie
// über denselben Watchdog wie eine Fahrt (DriveWatchdogWorker, dessen
// Session-Match um `radfahren` erweitert ist).
//
// Abgrenzung: Eine Radfahrt, die das Gate NIEMALS erreicht hätte (also
// unter 8 m/s bleibt), läuft hier ebenfalls auf — die Session ist dann
// korrekt „Radfahren" statt „Autofahren". Der Drive-Pfad bleibt
// unberührt (CONFIRM-Burst + classify entscheiden weiter über Fahrten).
// ══════════════════════════════════════════════════════════════════════

private const val BICYCLE_START_WORK = "aevum.bicycle_start"

/** Session-Ende-Trigger der Radfahrt (Timeline-Marker, LOW wie im
 *  M15-Trigger-Pfad für Zweiräder — Google's Zweirad-Confidence ist
 *  nicht mit der IN_VEHICLE-Confidence vergleichbar). */
internal const val TRIGGER_BICYCLE_STARTED = "BICYCLE_STARTED"
internal const val TRIGGER_BICYCLE_ENDED = "BICYCLE_ENDED"

/**
 * M18.134: Startet die radfahren-Session aus einer belegten Radfahrt.
 *
 * Aufrufer: der ON_BICYCLE-Zweig des ActivityContinuousSamplesReceiver
 * (AR-Signal alle 30 s) und der DriveDetectionService bei jedem Fix,
 * dessen Probe-Serie eine Radfahrt belegt. Der Worker rechnet die
 * Entscheidung mit den aktuellen Bridge-Daten NEU — er startet nichts,
 * was zum Ausführungszeitpunkt nicht mehr belegt ist (WorkManager-Latenz).
 */
class BicycleStartWorker(
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
        val now = System.currentTimeMillis()

        // Gate: Rad-Erkennung in den Trigger-Settings aus?
        if (!bridge.isBicycleEnabled()) {
            Log.d(TAG, "Rad-Erkennung deaktiviert — kein Start")
            return Result.success()
        }

        // Beweislage 1: belastbares ON_BICYCLE-Signal (frisch + Confidence).
        val evidence = bridge.bicycleEvidence()
        if (!DriveDetectionEngine.isReliableBicycleSignal(evidence, now)) {
            Log.d(TAG, "Kein belastbares ON_BICYCLE-Signal (evidence=$evidence) — kein Start")
            return Result.success()
        }

        // Beweislage 2: GPS-Serie belegt eine Radfahrt.
        val ride = DriveDetectionEngine.detectBikeRide(
            bridge.currentDriveProbes(),
            now,
            bridge.currentGeofenceContext()
        )
        if (ride == null) {
            Log.d(TAG, "Keine Radfahrt in der Probe-Serie — kein Start")
            return Result.success()
        }

        // Duplikat-Schutz: Läuft schon eine Rad-Session → nichts tun.
        val liveSession = live.liveSession.value
        if (liveSession != null && liveSession.isLive) {
            val isBikeSession = liveSession.activityTypeId == "radfahren" &&
                liveSession.sourceType == "ACTIVITY_RECOGNITION_AUTO"
            if (isBikeSession) {
                Log.d(TAG, "Rad-Session läuft bereits — kein Doppel-Start")
            } else {
                Log.d(TAG, "Andere Session läuft (${liveSession.activityTypeId}) — kein Rad-Start")
            }
            return Result.success()
        }

        try {
            // M18.134: Rückdatierung auf den ersten bewegten Probe —
            // die bereits gefahrenen Minuten gehören in die Aufzeichnung
            // (gleiche Semantik wie die Cluster-Rückdatierung der Fahrt).
            val startedAt = ride.startMs.coerceAtMost(now)
            val session = live.start(
                activityTypeId = "radfahren",
                title = "Radfahren",
                sourceType = "ACTIVITY_RECOGNITION_AUTO",
                startedAt = startedAt
            )
            Log.d(
                TAG,
                "Rad-Session gestartet: ${session.id} (start=$startedAt, " +
                    "avg=${"%.1f".format(ride.avgSpeedMps)} m/s, ${ride.sampleCount} Probes)"
            )
            // M18.127-Muster an Session-Grenzen: Evidenz verbrauchen,
            // damit sie nicht die NÄCHSTE Session anschiebt.
            bridge.resetBicycleEvidence()
            bridge.resetVehicleEvidence()
            bridge.resetWalkStopEvidence()
            bridge.clearWalkingSignal()
            // Foreground-Service, damit der Timer im Hintergrund weiterläuft.
            com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService.start(applicationContext)
            // M18.104 (Akku-Redesign): Der Burst-Service muss vom CONFIRM-
            // in den TRACK-Modus wechseln (die Fahrt streckt ihren Stream
            // über die Session). No-Op, wenn er nicht läuft.
            DriveDetectionService.start(
                applicationContext,
                DriveDetectionService.ACTION_TRACK_RESTORE
            )
            // Watchdog: 5 Minuten ohne Signal beenden die Radfahrt
            // (derselbe Worker wie bei der Fahrt — sein Session-Match
            // umfasst seit M18.134 auch `radfahren`).
            DriveWatchdogWorker.schedule(applicationContext)
            triggerRepo.insert(
                com.d_drostes_apps.aevum.data.model.TriggerEvent(
                    id = UUID.randomUUID().toString(),
                    occurredAt = startedAt,
                    type = TRIGGER_BICYCLE_STARTED,
                    source = "activity_recognition",
                    confidence = 0.8f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"bike_ride_detected\"}",
                    anchorQuality = "MEDIUM"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Rad-Start fehlgeschlagen", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "BicycleStartWorker"

        /** Vom Receiver/Service aufgerufen: Rad-Entscheidung sofort
         *  neu rechnen und ggf. starten (REPLACE = genau ein Lauf). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                BICYCLE_START_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BicycleStartWorker>().build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(BICYCLE_START_WORK)
        }
    }
}
