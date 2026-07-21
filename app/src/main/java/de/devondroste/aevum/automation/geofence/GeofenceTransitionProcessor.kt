package de.devondroste.aevum.automation.geofence

import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.automation.notification.CandidateReviewNotifier
import de.devondroste.aevum.automation.rules.CandidateRuleOrchestrator
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
    private val geofenceRepository: PlaceGeofenceRepository,
    private val rawSourceRepository: RawSourceEventRepository,
    private val detectionRepository: DetectionEventRepository,
    private val triggerRepository: TriggerEventRepository,
    private val ruleOrchestrator: CandidateRuleOrchestrator,
    private val candidateReviewNotifier: CandidateReviewNotifier,
    private val debugLogger: GeofenceDebugLogger
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

        // 1. RawSourceEvent
        val raw = RawSourceEvent(
            id = UUID.randomUUID().toString(),
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            externalId = "${geofenceId}_${transition.name}_$occurredAt",
            eventType = if (transition == GeofenceTransition.Enter) "GEOFENCE_ENTER" else "GEOFENCE_EXIT",
            observedAt = occurredAt,
            timezoneId = java.time.ZoneId.systemDefault().id,
            payloadJson = """{"geofenceId":"${geofence.id}","name":"${geofence.name}","lat":${latitude ?: "null"},"lon":${longitude ?: "null"}}"""
        )
        rawSourceRepository.insert(raw)
        debugLogger.log("PROCESSOR", "  RawEvent gespeichert: ${raw.id}")

        // 2. DetectionEvent
        val detection = DetectionEvent(
            id = UUID.randomUUID().toString(),
            rawEventId = raw.id,
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            kind = if (transition == GeofenceTransition.Enter) AutomationConstants.DETECTION_GEOFENCE_ENTER else AutomationConstants.DETECTION_GEOFENCE_EXIT,
            startAt = occurredAt,
            confidence = DEFAULT_CONFIDENCE,
            placeId = geofence.id,
            metadataJson = """{"geofenceName":"${geofence.name}","transition":"${transition.name}"}"""
        )
        detectionRepository.insert(detection)

        // 3. TriggerEvent
        val trigger = TriggerEvent(
            id = UUID.randomUUID().toString(),
            occurredAt = occurredAt,
            type = triggerTypeFor(geofence.name, transition),
            source = AutomationConstants.DATA_SOURCE_GEOFENCING,
            confidence = DEFAULT_CONFIDENCE,
            geofenceId = geofence.id,
            detectionEventId = detection.id,
            metadataJson = """{"geofenceName":"${geofence.name}","activityTypeId":${geofence.activityTypeId?.let { "\"$it\"" } ?: "null"}}"""
        )
        triggerRepository.insert(trigger)
        debugLogger.log("PROCESSOR", "  Trigger gespeichert: ${trigger.id} (${trigger.type})")

        // 4. Run rules → generate candidates
        val ruleResult = ruleOrchestrator.evaluateRecentTriggers()
        debugLogger.log("PROCESSOR", "  ${ruleResult.insertedCandidates.size} neue Candidates")

        // 5. Notify (only if user enabled)
        candidateReviewNotifier.notifyIfEnabled(ruleResult.insertedCandidates)

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
