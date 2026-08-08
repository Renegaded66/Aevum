package de.devondroste.aevum.automation.activityrecognition

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * M17.4: Initial-Activity-Snapshot Worker.
 *
 * Problem: Nach einem Geräte-Neustart (oder App-Update via MY_PACKAGE_REPLACED)
 * feuert Googles Activity-Recognition-API KEINE Transition-Events rückwirkend.
 * Wenn der User gerade im Auto sitzt und das Handy restartet, weiß Aevum
 * nichts davon und die Fahrt wird nicht getrackt.
 *
 * Lösung: Dieser Worker läuft 30s nach Boot/App-Start und registriert für
 * eine kurze Zeit (60s) einen kontinuierlichen Activity-Stream bei Google.
 * Wenn in dieser Zeit ein IN_VEHICLE-Sample kommt, wird der Cluster-Buffer
 * [ActivityRecognitionBridge] befüttert und der [ActivityRecognitionWorker]
 * enqueued, der den Auto-Start auslöst.
 *
 * Nach 60s melden wir uns wieder ab, damit kein dauerhafter Strom an
 * Activity-Events Akku zieht — die normalen Transitions (Enter/Exit) laufen
 * ja schon über [ActivityRecognitionRegistrar].
 *
 * Trade-off: Wenn der User genau in den ersten 90s nach Boot aus dem Auto
 * aussteigt, verpassen wir den Wechsel. Akzeptabel: das passiert selten,
 * und der nächste IN_VEHICLE-ENTER wird dann ganz normal über die
 * Transition-Pipeline erkannt.
 */
class InitialActivitySnapshotWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun rawSourceRepository(): RawSourceEventRepository
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // M17.4: Permission-Check. Falls ACTIVITY_RECOGNITION nicht gewährt,
        // ist der ganze Probe sinnlos — wir beenden früh.
        if (!ActivityRecognitionPermission.isGranted(ctx)) {
            Log.d(TAG, "ACTIVITY_RECOGNITION nicht gewährt — überspringe Initial-Snapshot")
            return Result.success()
        }

        val deps = EntryPointAccessors.fromApplication(ctx, Deps::class.java)
        val bridge = deps.activityRecognitionBridge()
        val rawRepo = deps.rawSourceRepository()

        // M17.4: Marker-External-Id, damit ein doppelter Probe (z.B. wenn Boot-
        // Receiver UND AevumApplication.onCreate den Worker enqueuen) nicht
        // doppelt zählt. Wir loggen das im RawSourceEvent-Stream, das ist die
        // Wahrheits-Quelle.
        val externalId = "ar_initial_snapshot_${System.currentTimeMillis()}"
        if (rawRepo.getBySourceAndExternalId("activity_recognition", externalId).first() != null) {
            Log.d(TAG, "Initial-Snapshot bereits in DB (externalId=$externalId) — skip")
            return Result.success()
        }

        // M17.4: 1) PendingIntent für den Probe-Receiver registrieren. Wir
        // nutzen einen EIGENEN Receiver, nicht den normalen
        // ActivityTransitionReceiver, weil der nur auf ENTER/EXIT-Transitions
        // reagiert — wir wollen aber den aktuellen kontinuierlichen Stream.
        val probeIntent = Intent(ctx, InitialActivityProbeReceiver::class.java)
            .setAction("de.devondroste.aevum.AR_INITIAL_PROBE")
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE_PROBE, probeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(ctx)

        try {
            // M17.4: 5s-Intervall ist der minimal erlaubte Wert bei Play Services.
            // Die ersten 1-2 Samples kommen meist innerhalb 10-15s, je nach
            // Sensor-Aufwärmphase.
            client.requestActivityUpdates(5_000L, pendingIntent).await()
        } catch (e: Exception) {
            Log.w(TAG, "requestActivityUpdates failed: ${e.message} — kein Initial-Snapshot möglich")
            return Result.success()
        }

        // M17.4: 2) 60s warten. In dieser Zeit feuert der Probe-Receiver Events
        // an [InitialActivityProbeReceiver], der den Bridge-Buffer befüllt.
        kotlinx.coroutines.delay(PROBE_WINDOW_MS)

        // M17.4: 3) Probe beenden — wir wollen den kontinuierlichen Stream
        // NICHT dauerhaft laufen lassen (Akku!). Die normalen Transitions
        // laufen über [ActivityRecognitionRegistrar] weiter.
        try {
            client.removeActivityUpdates(pendingIntent).await()
        } catch (e: Exception) {
            Log.w(TAG, "removeActivityUpdates failed: ${e.message}")
        }

        // M17.4: 4) Cluster-Buffer prüfen. Wenn IN_VEHICLE-Samples in der
        // Bridge liegen, enqueuen wir den DriveConfirmWorker — der prüft
        // per GPS-Bewegung (M18.45), ob es eine echte Fahrt ist, und
        // stößt dann den Recognition-Worker an. Kein Sofort-Start mehr.
        val vehicleCluster = bridge.drainVehicleCluster()
        if (vehicleCluster != null && vehicleCluster.durationMs >= MIN_PROBE_DURATION_MS) {
            Log.d(
                TAG,
                "Initial-Snapshot erkannte IN_VEHICLE-Cluster: " +
                    "${vehicleCluster.durationMs / 1000}s, confidence=${vehicleCluster.peakConfidence} → DriveConfirm"
            )
            // M17.4: Probe-Cluster in den Buffer zurückschreiben (Bridge wurde
            // durch drain() geleert), damit der Worker ihn verarbeiten kann.
            bridge.addSample(vehicleCluster.endMs, vehicleCluster.peakConfidence)
            bridge.addSample(vehicleCluster.endMs + 1_000, vehicleCluster.peakConfidence)
            // M18.45: Bestätigungs-Pipeline statt Sofort-Start.
            DriveConfirmWorker.schedule(ctx)
        } else {
            Log.d(TAG, "Initial-Snapshot: keine IN_VEHICLE-Aktivität erkannt (cluster=$vehicleCluster)")
        }

        // M17.4: 5) Marker in RawSourceEvent-Stream loggen, damit das Debug-
        // Log nachvollziehbar ist.
        rawRepo.insert(
            de.devondroste.aevum.data.model.RawSourceEvent(
                id = UUID.randomUUID().toString(),
                sourceId = "activity_recognition",
                externalId = externalId,
                eventType = "INITIAL_SNAPSHOT",
                observedAt = System.currentTimeMillis(),
                startAt = System.currentTimeMillis() - PROBE_WINDOW_MS,
                endAt = System.currentTimeMillis(),
                timezoneId = java.time.ZoneId.systemDefault().id,
                payloadJson = "{\"vehicleClusterDurationMs\":${vehicleCluster?.durationMs ?: -1}}"
            )
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "InitialActivitySnap"
        const val WORK_NAME = "aevum.ar.initial_snapshot"
        private const val REQUEST_CODE_PROBE = 9101
        private const val PROBE_WINDOW_MS = 60_000L
        /**
         * M17.4: Mindest-Cluster-Dauer, damit wir nicht bei einem einzelnen
         * IN_VEHICLE-False-Positive (Bus, Zug-Beifahrer, Taxi-Hopp) eine
         * Session starten. 8s ist konservativ — der normale Worker hat 60s,
         * aber hier im Probe-Kontext (60s-Fenster) ist kürzer OK.
         */
        private const val MIN_PROBE_DURATION_MS = 8_000L
    }
}

/**
 * M17.4: Empfängt Activity-Samples während des Initial-Snapshots und füllt
 * den [ActivityRecognitionBridge]-Buffer. Eigener Receiver, weil der normale
 * [ActivityTransitionReceiver] nur ENTER/EXIT-Transitions verarbeitet.
 */
class InitialActivityProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionPermission.isGranted(context)) return
        if (!com.google.android.gms.location.ActivityRecognitionResult.hasResult(intent)) return
        val result = com.google.android.gms.location.ActivityRecognitionResult.extractResult(intent) ?: return
        val bridge = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ActivityRecognitionBridgeProvider::class.java
        ).activityRecognitionBridge()
        val now = System.currentTimeMillis()
        for (activity in result.probableActivities) {
            when (activity.type) {
                DetectedActivity.IN_VEHICLE -> bridge.addSample(now, activity.confidence)
                // STILL-Cluster interessiert uns hier nicht — die Schlaf-Fusion
                // läuft separat und der Worker nimmt den Cluster erst ab 4h an.
            }
        }
    }
}
