package com.d_drostes_apps.aevum.automation.activityrecognition

import android.content.Context
import android.util.Log
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * M28: Holt eine vorgemerkte Fahrt nach, sobald die Aufzeichnung endet, die
 * sie blockiert hat.
 *
 * ── Warum ein eigener Beobachter? ───────────────────────────────────────
 *
 * Der Fahrt-Start läuft in [DriveStartWorker]. Dort ist die blockierende
 * Session aber noch live — der Worker darf nichts tun (sonst würde
 * [LiveActivityManager.start] sie kürzen/beenden, genau der gemeldete
 * Fehler). Der Auslöser „Aufzeichnung beendet" liegt an einem ganz anderen
 * Ort: Dashboard-Stop, Notification-Stop, Widget, Geofence-Exit, App-Wechsel,
 * Kalender-Ende, Screen-Off … Diese Liste ist lang und wächst; ein Hook pro
 * Stop-Pfad wäre unvollständig (M18.75/M18.76-Lehre: „ein Flag, das einen
 * Stop-Pfad verpasst, blockiert dauerhaft").
 *
 * Deshalb EIN Beobachter auf dem einzigen gemeinsamen Nenner:
 * [LiveActivityManager.liveSession]. Er kostet nichts (StateFlow ohne
 * Subscriber = billiger CAS) und deckt jeden Stop-Pfad automatisch ab.
 *
 * ── Wann startet er NICHT? ──────────────────────────────────────────────
 *
 *  - Es gibt keinen Vormerk ([ActivityRecognitionBridge.isDrivePending]).
 *  - Es ist inzwischen wieder eine Session live (Aktivitätswechsel,
 *    Übergabe an App-Tracking/Digital) → der Vormerk bleibt bestehen und
 *    wird am Ende DIESER Session erneut geprüft.
 *  - Es gibt keine frische Fahrt-Evidenz mehr → Vormerk verfällt
 *    ([PendingDrivePolicy.shouldStartAfterBlock], User-Fall „Fahrt vorbei,
 *    danach erst gestoppt").
 *  - Die Fahrt-Erkennung wurde in den Einstellungen ausgeschaltet.
 */
@Singleton
class PendingDriveStarter @Inject constructor(
    private val liveActivityManager: LiveActivityManager,
    private val bridge: ActivityRecognitionBridge,
    private val triggerEventRepository: TriggerEventRepository,
    @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var running = false

    /** Idempotent — wird aus [com.d_drostes_apps.aevum.AevumApplication] gestartet. */
    fun start() {
        if (running) return
        running = true
        scope.launch {
            var previous: ActivitySession? = liveActivityManager.liveSession.value
            liveActivityManager.liveSession.collect { current ->
                val before = previous
                previous = current
                if (before == null || !before.isLive) return@collect
                if (current != null && current.isLive) return@collect
                if (!bridge.isDrivePending()) return@collect
                onBlockEnded(before)
            }
        }
    }

    private suspend fun onBlockEnded(blocking: ActivitySession) {
        // 2 s Karenz: ein Aktivitätswechsel (stop → start im selben
        // Coroutine-Lauf) darf nicht als „Aufzeichnung beendet" gelten.
        delay(SWITCH_GRACE_MS)

        val now = System.currentTimeMillis()
        val current = liveActivityManager.liveSession.value
        if (current != null && current.isLive) {
            if (PendingDrivePolicy.isDriveSession(current.activityTypeId, current.sourceType)) {
                // Eine Fahrt läuft bereits — Vormerk ist erledigt.
                bridge.clearDrivePending()
            }
            return
        }

        if (!bridge.isDrivingEnabled()) {
            Log.i(TAG, "M28: Fahrt-Vormerk verworfen — Autofahren-Erkennung ist aus")
            bridge.clearDrivePending()
            return
        }

        val drivingEvidenceNow = try {
            DriveDetectionEngine.classify(
                probes = bridge.currentDriveProbes(),
                nowMs = now,
                geofences = bridge.currentGeofenceContext(),
                motionContext = bridge.currentMotionContext(),
                cadenceHz = bridge.currentCadenceHz(),
                cadenceValidFraction = bridge.currentCadenceValidFraction()
            ) is DriveDetectionEngine.Classification.Driving
        } catch (e: Exception) {
            Log.w(TAG, "M28: Evidenz-Prüfung fehlgeschlagen (${e.message}) — kein Start")
            false
        }

        val pendingAt = bridge.drivePendingStartedAtMs()
        val lastEvidence = bridge.drivePendingEvidenceAtMs()
        if (!PendingDrivePolicy.shouldStartAfterBlock(
                drivingEvidenceNow = drivingEvidenceNow,
                lastEvidenceAtMs = lastEvidence,
                pendingStartedAtMs = pendingAt,
                nowMs = now
            )
        ) {
            Log.i(
                TAG,
                "M28: Fahrt-Vormerk verfallen — keine frische Fahrt-Evidenz " +
                    "(letzte vor ${if (lastEvidence > 0) (now - lastEvidence) / 1000 else -1}s, " +
                    "Vormerk ${(now - pendingAt) / 1000}s alt). Keine Fahrt gestartet."
            )
            bridge.clearDrivePending()
            return
        }

        // Anker = Ende des Blocks, NICHT der ursprüngliche Fahrt-Anker:
        // sonst liefe die Fahrt rückwirkend in die beendete Aufzeichnung
        // hinein und würde sie erneut zerschneiden.
        val startedAt = PendingDrivePolicy.startAtAfterBlock(blocking.endAt, now)
        try {
            val session = liveActivityManager.start(
                activityTypeId = PendingDrivePolicy.DRIVE_TYPE_ID,
                title = "Autofahren",
                sourceType = PendingDrivePolicy.DRIVE_SOURCE_TYPE,
                startedAt = startedAt
            )
            bridge.clearDrivePending()
            bridge.refreshDriveHeartbeat(now)
            // Evidenz der Block-Zeit gehört nicht in die neue Fahrt.
            bridge.resetVehicleEvidence()
            bridge.resetWalkStopEvidence()
            bridge.clearWalkingSignal()
            LiveActivityService.start(context)
            // Burst-Service in den TRACK-Modus (No-Op, wenn er nicht läuft).
            DriveDetectionService.start(context, DriveDetectionService.ACTION_TRACK_RESTORE)
            DriveWatchdogWorker.schedule(context)
            triggerEventRepository.insert(
                TriggerEvent(
                    id = UUID.randomUUID().toString(),
                    occurredAt = now,
                    type = "DRIVING_STARTED",
                    source = "activity_recognition",
                    confidence = 0.9f,
                    detectionEventId = null,
                    metadataJson = "{\"reason\":\"deferred_after_other_recording\"}",
                    anchorQuality = "HIGH"
                )
            )
            Log.i(
                TAG,
                "M28: Vorgemerkte Fahrt gestartet: ${session.id} (start=$startedAt, " +
                    "blockiert durch '${blocking.title}' bis ${blocking.endAt ?: now})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "M28: Nachgeholter Fahrt-Start fehlgeschlagen", e)
            // Vormerk stehen lassen: der nächste DriveStartWorker-Lauf
            // versucht es erneut, solange Evidenz frisch ist.
        }
    }

    private companion object {
        const val TAG = "PendingDriveStarter"

        /** Karenz für stop→start im selben Coroutine-Lauf (Aktivitätswechsel). */
        const val SWITCH_GRACE_MS = 2_000L
    }
}
