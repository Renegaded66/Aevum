package de.devondroste.aevum.automation.geofence

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.devondroste.aevum.automation.health.SleepImportWorker
import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.automation.notification.CandidateReviewNotifier
import de.devondroste.aevum.automation.rules.CandidateRuleOrchestrator
import de.devondroste.aevum.automation.sleep.SleepShield
import de.devondroste.aevum.automation.sleep.shouldSuppressTransition
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.RawSourceEventRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class GeofenceTransitionProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val rawSourceRepository: RawSourceEventRepository,
    private val detectionRepository: DetectionEventRepository,
    private val triggerRepository: TriggerEventRepository,
    private val ruleOrchestrator: CandidateRuleOrchestrator,
    private val candidateReviewNotifier: CandidateReviewNotifier,
    private val debugLogger: GeofenceDebugLogger,
    // M11: Auto-start/stop sessions via LiveActivityManager
    private val liveActivityManager: de.devondroste.aevum.domain.liveactivity.LiveActivityManager,
    // M16.6: Schutzschicht gegen nächtliche False-Positive-Trigger
    private val sleepShield: SleepShield
) {
    suspend fun processTransition(
        geofenceId: String,
        transition: GeofenceTransition,
        occurredAt: Long,
        latitude: Double? = null,
        longitude: Double? = null
    ): GeofenceProcessingResult {
        if (transition == GeofenceTransition.Unknown) {
            debugLogger.log("PROCESSOR", "Unknown transition → ignoriert")
            return GeofenceProcessingResult.Ignored
        }
        val geofence = geofenceRepository.getById(geofenceId).first()
        if (geofence == null) {
            debugLogger.log("PROCESSOR", "Geofence $geofenceId nicht gefunden")
            return GeofenceProcessingResult.UnknownGeofence
        }
        if (!geofence.enabled || geofence.deletedAt != null) {
            debugLogger.log("PROCESSOR", "Geofence ${geofence.name} deaktiviert/gelöscht → ignoriert")
            return GeofenceProcessingResult.Ignored
        }

        debugLogger.log("PROCESSOR", "${geofence.name}: ${transition.name} @ $occurredAt")

        // M16.6: SleepShield. Wenn der Trigger mitten in einem nachgewiesenen
        // oder sehr wahrscheinlichen Schlaf-Fenster liegt, wird er auf
        // LOW-anchor gesetzt und nicht als Travel-Start verwendet. Der
        // Trigger bleibt für Debugging in der DB, aber Travel-Rules
        // ignorieren ihn (siehe TriggerPairCandidateRuleEngine).
        val anchorQuality = sleepShield.anchorQualityFor(occurredAt)
        if (anchorQuality == SleepShield.AnchorQuality.LOW) {
            debugLogger.log("PROCESSOR", "  SleepShield → Trigger LOW-anchor (Schlaf-Fenster aktiv)")
        }

        val eventType = when (transition) {
            GeofenceTransition.Enter -> "GEOFENCE_ENTER"
            GeofenceTransition.Exit -> "GEOFENCE_EXIT"
            GeofenceTransition.Dwell -> "GEOFENCE_DWELL"
            else -> "GEOFENCE_UNKNOWN"
        }
        val detectionKind = when (transition) {
            GeofenceTransition.Enter -> AutomationConstants.DETECTION_GEOFENCE_ENTER
            GeofenceTransition.Exit -> AutomationConstants.DETECTION_GEOFENCE_EXIT
            GeofenceTransition.Dwell -> AutomationConstants.DETECTION_GEOFENCE_ENTER // DWELL ist ein bestätigter ENTER
            else -> AutomationConstants.DETECTION_GEOFENCE_ENTER
        }
        val raw = RawSourceEvent(
            id = UUID.randomUUID().toString(),
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            externalId = "${geofenceId}_${transition.name}_$occurredAt",
            eventType = eventType,
            observedAt = occurredAt,
            timezoneId = java.time.ZoneId.systemDefault().id,
            payloadJson = """{"geofenceId":"${geofence.id}","name":"${geofence.name}","lat":${latitude ?: "null"},"lon":${longitude ?: "null"}}"""
        )
        rawSourceRepository.insert(raw)

        val detection = DetectionEvent(
            id = UUID.randomUUID().toString(),
            rawEventId = raw.id,
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            kind = detectionKind,
            startAt = occurredAt,
            confidence = DEFAULT_CONFIDENCE,
            placeId = geofence.id,
            metadataJson = """{"geofenceName":"${geofence.name}","transition":"${transition.name}","sleepShield":"${anchorQuality.name}"}"""
        )
        detectionRepository.insert(detection)

        val trigger = TriggerEvent(
            id = UUID.randomUUID().toString(),
            occurredAt = occurredAt,
            type = triggerTypeFor(geofence.name, transition),
            source = AutomationConstants.DATA_SOURCE_GEOFENCING,
            confidence = DEFAULT_CONFIDENCE,
            geofenceId = geofence.id,
            detectionEventId = detection.id,
            // M10.1: DWELL ist die zuverlässigste Quelle — User hat nachweislich
            // 90s im Geofence verweilt. EXIT ist weniger verlässlich (GPS-Sprung
            // am Rand), aber immer noch nutzbar. ENTER ohne DWELL bleibt MEDIUM.
            // M16.6: SleepShield setzt nachts auf LOW, damit Travel-Rules den
            // Trigger nicht als Reise-Start verwenden.
            anchorQuality = when {
                anchorQuality == SleepShield.AnchorQuality.LOW -> "LOW"
                transition == GeofenceTransition.Dwell -> "HIGH"
                else -> "MEDIUM"
            },
            metadataJson = """{"geofenceName":"${geofence.name}","activityTypeId":${geofence.activityTypeId?.let { "\"$it\"" } ?: "null"}}"""
        )
        triggerRepository.insert(trigger)
        debugLogger.log("PROCESSOR", "  Trigger gespeichert: ${trigger.id} (${trigger.type}, anchor=${trigger.anchorQuality})")

        val ruleResult = ruleOrchestrator.evaluateRecentTriggers()
        debugLogger.log("PROCESSOR", "  ${ruleResult.insertedCandidates.size} neue Candidates")

        candidateReviewNotifier.notifyIfEnabled(ruleResult.insertedCandidates)

        // M9.2: When the user comes home, opportunistically pull the last
        // night of sleep from Health Connect.
        if (trigger.type == AutomationConstants.TRIGGER_HOME_ARRIVED) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "aevum.sleep_import_on_arrival",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SleepImportWorker>().build()
                )
                debugLogger.log("PROCESSOR", "  Sleep-Import bei Heimkehr getriggert")
            } catch (e: Exception) {
                debugLogger.log("PROCESSOR", "  Sleep-Import trigger failed: ${e.message}")
            }
        }

        // ============================================================
        // M12.1: Auto-start/stop activity session based on geofence rules
        // with trigger traceability and robust duplicate prevention.
        // ============================================================
        if (geofence.autoStartActivityTypeId != null && transition == GeofenceTransition.Enter) {
            // M12.1: Check for ANY live session (RUNNING or PAUSED) — not just RUNNING.
            // A PAUSED auto-session must be resumed or replaced, not duplicated.
            val existing = liveActivityManager.liveSession.value
            val isDuplicate = existing != null &&
                existing.isLive &&
                existing.activityTypeId == geofence.autoStartActivityTypeId
            if (!isDuplicate) {
                if (existing != null && existing.isLive) {
                    // A different activity is running/paused — force-finish it first.
                    liveActivityManager.forceFinishForAuto()
                }
                val session = liveActivityManager.start(
                    activityTypeId = geofence.autoStartActivityTypeId,
                    title = geofence.name,
                    sourceType = "GEOFENCE_AUTO",
                    sourceTriggerId = trigger.id
                )
                debugLogger.log("PROCESSOR", "  Auto-Start: ${session.title} (${session.id}) via trigger ${trigger.id}")
            } else {
                debugLogger.log("PROCESSOR", "  Auto-Start übersprungen: ${geofence.autoStartActivityTypeId} läuft bereits (${existing?.sessionStatus})")
            }
        } else if (geofence.autoStopEnabled && transition == GeofenceTransition.Exit) {
            // M12.1: Auto-stop only the session that was started by the matching ENTER trigger.
            // Use sourceTriggerId to ensure we never stop a manually started session.
            val existing = liveActivityManager.liveSession.value
            if (existing != null && existing.isLive && existing.activityTypeId == geofence.autoStartActivityTypeId) {
                // Only stop if this session was started by a geofence trigger (auto-started)
                // or if it has no sourceTriggerId (legacy auto-session from before M12.1).
                val isAutoSession = existing.sourceType == "GEOFENCE_AUTO"
                if (isAutoSession) {
                    liveActivityManager.stop()
                    debugLogger.log("PROCESSOR", "  Auto-Stop: ${existing.title} beendet (sourceTriggerId=${existing.sourceTriggerId})")
                } else {
                    debugLogger.log("PROCESSOR", "  Auto-Stop übersprungen: Session ${existing.id} ist manuell (sourceType=${existing.sourceType})")
                }
            } else {
                debugLogger.log("PROCESSOR", "  Auto-Stop übersprungen: keine passende Session läuft")
            }
        }

        return GeofenceProcessingResult.Stored(trigger.id, detection.id, ruleResult.insertedCandidates.size)
    }

    private fun triggerTypeFor(name: String, transition: GeofenceTransition): String {
        val lower = name.lowercase()
        return when {
            lower.contains("zuhause") || lower.contains("home") ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_HOME_ARRIVED
                else AutomationConstants.TRIGGER_HOME_LEFT
            lower.contains("arbeit") || lower.contains("work") ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_WORK_ENTERED
                else AutomationConstants.TRIGGER_WORK_LEFT
            else ->
                if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED
                else AutomationConstants.TRIGGER_CUSTOM_PLACE_LEFT
        }
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.82f
    }
}

sealed class GeofenceProcessingResult {
    data class Stored(val triggerId: String, val detectionEventId: String, val ruleCandidateCount: Int = 0) : GeofenceProcessingResult()
    data object UnknownGeofence : GeofenceProcessingResult()
    data object Ignored : GeofenceProcessingResult()
}
