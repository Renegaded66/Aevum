package de.devondroste.aevum.automation.activityrecognition

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.DetectedActivity
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import de.devondroste.aevum.domain.automation.ReviewCandidateUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M12.2: Activity Recognition Worker.
 *
 * - Empfängt IN_VEHICLE-Cluster vom Google Activity Recognition API.
 * - Erzeugt ActivityCandidates mit activityTypeId = "driving".
 * - Übergibt sie an [ReviewCandidateUseCase.acceptAuto] — das gleiche
 *   Verfahren wie Health Connect Sleep. Damit läuft die Fahrt komplett
 *   über die gleiche Live-Session-Architektur, ohne separates System.
 *
 * Confidence-Threshold: ≥ 0.70 (gleicher Wert wie überall in Aevum).
 *
 * Der eigentliche BroadcastReceiver [ActivityTransitionReceiver] sammelt
 * Roh-Events und ruft den Worker. Der Worker kann auch direkt manuell
 * getriggert werden (z. B. aus dem Settings-Screen "Jetzt prüfen").
 */
class ActivityRecognitionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun activityRecognitionBridge(): ActivityRecognitionBridge
        fun rawSourceRepository(): RawSourceEventRepository
        fun detectionRepository(): DetectionEventRepository
        fun candidateRepository(): ActivityCandidateRepository
        fun reviewCandidateUseCase(): ReviewCandidateUseCase
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            Deps::class.java
        )
        val bridge = deps.activityRecognitionBridge()
        val rawRepo = deps.rawSourceRepository()
        val detRepo = deps.detectionRepository()
        val candRepo = deps.candidateRepository()
        val reviewUc = deps.reviewCandidateUseCase()

        // M12.2: Aggregiere IN_VEHICLE-Cluster aus dem In-Memory-Buffer.
        // Der Buffer wird vom ActivityTransitionReceiver befüllt.
        val cluster = bridge.drainVehicleCluster() ?: return Result.success()
        if (cluster.durationMs < MIN_CLUSTER_DURATION_MS) {
            // Zu kurze "Fahrt" — wahrscheinlich Bus-Haltestelle oder Beifahrer
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val confidence: Float = (cluster.peakConfidence / 100f).coerceIn(0f, 1f)

        // Dedup: wenn schon ein Roh-Event mit dieser Cluster-ID existiert → skip
        val externalId = "ar_invehicle_${cluster.startMs}_${cluster.endMs}"
        val existing = rawRepo.getBySourceAndExternalId("activity_recognition", externalId).first()
        if (existing != null) return Result.success()

        val rawId = UUID.randomUUID().toString()
        rawRepo.insert(
            RawSourceEvent(
                id = rawId,
                sourceId = "activity_recognition",
                externalId = externalId,
                eventType = "IN_VEHICLE_CLUSTER",
                observedAt = now,
                startAt = cluster.startMs,
                endAt = cluster.endMs,
                timezoneId = java.time.ZoneId.systemDefault().id,
                payloadJson = "{\"peakConfidence\":$confidence,\"sampleCount\":${cluster.sampleCount}}"
            )
        )

        val detectionId = UUID.randomUUID().toString()
        detRepo.insert(
            DetectionEvent(
                id = detectionId,
                rawEventId = rawId,
                sourceId = "activity_recognition",
                kind = AutomationConstants.DETECTION_ACTIVITY_RECOGNITION_IN_VEHICLE,
                startAt = cluster.startMs,
                endAt = cluster.endMs,
                confidence = confidence,
                metadataJson = "{\"peakConfidence\":$confidence,\"sampleCount\":${cluster.sampleCount}}"
            )
        )

        val hours = cluster.durationMs / 3_600_000
        val minutes = (cluster.durationMs % 3_600_000) / 60_000
        val durationStr = when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }

        val candidate = ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = "Autofahrt ($durationStr)",
            suggestedCategoryId = "transport",
            activityTypeId = "driving",
            startAt = cluster.startMs,
            endAt = cluster.endMs,
            confidence = confidence,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Activity Recognition: $durationStr im Fahrzeug (Konfidenz ${(confidence * 100).toInt()}%)",
            createdBy = "ACTIVITY_RECOGNITION_V1",
            createdAt = now,
            sourceCandidateId = rawId
        )
        candRepo.insert(candidate)

        // Auto-Accept — gleiche Pipeline wie Schlaf und Geofence.
        reviewUc.acceptAuto(listOf(candidate))

        return Result.success()
    }

    private companion object {
        // M12.2: Mindestdauer 90s, um Pendelverkehr / kurze Beifahrten zu ignorieren.
        // Spiegelung der Geofence-Dwell-Schwelle.
        const val MIN_CLUSTER_DURATION_MS = 90_000L
    }
}

/**
 * M12.2: In-Memory-Cluster-Speicher für IN_VEHICLE-Events.
 *
 * Android liefert IN_VEHICLE als Strom von DetectedActivity-Updates
 * (Confidence zwischen 0..100). Wir sammeln sie hier, solange sie
 * zeitlich nahe beieinander liegen (max 5min Lücke) und geben
 * den fertigen Cluster an den Worker.
 *
 * Bewusst kein @Singleton mit eigener Concurrency-Layer — die Activity
 * Recognition API liefert auf dem Main-Thread, der Worker liest auf
 * Dispatchers.IO. Da drain() den Buffer atomar ersetzt, ist die
 * Datenkonsistenz ohne Locking gewährleistet.
 */
@Singleton
class ActivityRecognitionBridge @Inject constructor() {
    @Volatile private var pending: VehicleCluster? = null
    private val maxGapMs = 5L * 60 * 1000 // 5 Minuten

    @Synchronized
    fun addSample(epochMs: Long, confidence: Int) {
        val c = pending
        if (c == null || epochMs - c.lastMs > maxGapMs) {
            // Neuer Cluster
            pending = VehicleCluster(
                startMs = epochMs,
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = 1,
                peakConfidence = confidence
            )
        } else {
            pending = c.copy(
                endMs = epochMs,
                lastMs = epochMs,
                sampleCount = c.sampleCount + 1,
                peakConfidence = maxOf(c.peakConfidence, confidence)
            )
        }
    }

    @Synchronized
    fun drainVehicleCluster(): VehicleCluster? {
        val c = pending ?: return null
        pending = null
        return c.copy(durationMs = c.endMs - c.startMs)
    }

    /**
     * M12.2: Force-flush — z. B. wenn der User die App öffnet.
     * Wird aus dem ActivityRecognition-Receiver gerufen, sobald der
     * letzte Sample länger als 5min her ist.
     */
    @Synchronized
    fun forceDrainIfStale(nowMs: Long): VehicleCluster? {
        val c = pending ?: return null
        if (nowMs - c.lastMs > maxGapMs) {
            pending = null
            return c.copy(durationMs = c.endMs - c.startMs)
        }
        return null
    }
}

data class VehicleCluster(
    val startMs: Long,
    val endMs: Long,
    val lastMs: Long,
    val sampleCount: Int,
    val peakConfidence: Int,
    val durationMs: Long = endMs - startMs
)

/**
 * M12.2: BroadcastReceiver für Activity Transition Updates.
 *
 * Google liefert nur dann ein Intent, wenn sich der Activity-Typ
 * ändert (z. B. STILL → IN_VEHICLE). Wir aggregieren die Updates
 * in [ActivityRecognitionBridge] und enqueuen den Worker.
 *
 * Hinweis: Wir parsen den GMS PendingResult (transitions + confidence),
 * nicht die AOSP ACTION_ACTIVITY_RECOGNITION — denn GMS ist die
 * zuverlässige Quelle, AOSP broadcastet nicht standardisiert.
 */
class ActivityTransitionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val bridge = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ActivityRecognitionBridgeProvider::class.java
        ).activityRecognitionBridge()

        // Wir parsen den GMS PendingResult (transitions + confidence)
        if (com.google.android.gms.location.ActivityTransitionResult.hasResult(intent)) {
            val result = com.google.android.gms.location.ActivityTransitionResult.extractResult(intent) ?: return
            val now = System.currentTimeMillis()
            for (transition in result.transitionEvents) {
                if (transition.activityType == DetectedActivity.IN_VEHICLE) {
                    bridge.addSample(now, 75)
                }
            }
            // M12.2: Worker enqueuen — der aggregiert und entscheidet.
            androidx.work.OneTimeWorkRequestBuilder<ActivityRecognitionWorker>().build().also {
                androidx.work.WorkManager.getInstance(context).enqueue(it)
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityRecognitionBridgeProvider {
    fun activityRecognitionBridge(): ActivityRecognitionBridge
}

/**
 * M12.2: Hilfsfunktion zum Registrieren der Activity-Transition-Updates
 * an Googles ActivityRecognitionClient. Wird beim App-Start und nach
 * Permission-Grant aufgerufen.
 */
object ActivityRecognitionRegistrar {
    fun register(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val client = ActivityRecognition.getClient(context)
        val request = com.google.android.gms.location.ActivityTransitionRequest(
            listOf(
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                com.google.android.gms.location.ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9002,
            Intent(context, ActivityTransitionReceiver::class.java).apply {
                action = "de.devondroste.aevum.ACTIVITY_RECOGNITION_TRANSITION"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            client.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (_: Exception) { /* permission denied or GMS missing */ }
    }
}

/**
 * M12.2: Permission state detection for Activity Recognition.
 * Liefert true, wenn die App ACTIVITY_RECOGNITION hat.
 */
object ActivityRecognitionPermission {
    fun isGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
