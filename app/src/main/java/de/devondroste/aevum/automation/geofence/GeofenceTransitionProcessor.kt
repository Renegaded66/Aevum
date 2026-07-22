package de.devondroste.aevum.automation.geofence

import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.automation.notification.CandidateReviewNotifier
import de.devondroste.aevum.automation.rules.CandidateRuleOrchestrator
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.GeofenceEventLogEntry
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.DetectionEventRepository
import de.devondroste.aevum.data.repository.GeofenceEventLogRepository
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
    private val debugLogger: GeofenceDebugLogger,
    private val eventLog: GeofenceEventLogRepository
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
            persistPipelineLog("IGNORED", geofenceId, null, "Unknown transition type", false, occurredAt, latitude, longitude)
            return GeofenceProcessingResult.Ignored
        }

        val geofence = geofenceRepository.getById(geofenceId).first()
        if (geofence == null) {
            debugLogger.log("PROCESSOR", "Geofence $geofenceId nicht gefunden")
            persistPipelineLog("UNKNOWN_GEOFENCE", geofenceId, null, "Geofence not found in DB", false, occurredAt, latitude, longitude)
            return GeofenceProcessingResult.UnknownGeofence
        }
        if (!geofence.enabled || geofence.deletedAt != null) {
            debugLogger.log("PROCESSOR", "Geofence ${geofence.name} deaktiviert/gelöscht → ignoriert")
            persistPipelineLog("DISABLED", geofenceId, geofence.name,
                "Geofence disabled (enabled=${geofence.enabled}, deleted=${geofence.deletedAt})",
                false, occurredAt, latitude, longitude)
            return GeofenceProcessingResult.Ignored
        }

        debugLogger.log("PROCESSOR", "${geofence.name}: ${transition.name} @ $occurredAt")
        val transitionName = transition.name

        // 1. RawSourceEvent
        val raw = RawSourceEvent(
            id = UUID.randomUUID().toString(),
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            externalId = "${geofenceId}_${transitionName}_$occurredAt",
            eventType = if (transition == GeofenceTransition.Enter) "GEOFENCE_ENTER" else "GEOFENCE_EXIT",
            observedAt = occurredAt,
            timezoneId = java.time.ZoneId.systemDefault().id,
            payloadJson = """{"geofenceId":"${geofence.id}","name":"${geofence.name}","lat":${latitude ?: "null"},"lon":${longitude ?: "null"}}"""
        )
        rawSourceRepository.insert(raw)

        // 2. DetectionEvent
        val detection = DetectionEvent(
            id = UUID.randomUUID().toString(),
            rawEventId = raw.id,
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            kind = if (transition == GeofenceTransition.Enter) AutomationConstants.DETECTION_GEOFENCE_ENTER else AutomationConstants.DETECTION_GEOFENCE_EXIT,
            startAt = occurredAt,
            confidence = DEFAULT_CONFIDENCE,
            placeId = geofence.id,
            metadataJson = """{"geofenceName":"${geofence.name}","transition":"$transitionName"}"""
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

        // M8.2: Log successful trigger creation persistently
        persistPipelineLog("TRIGGER_CREATED", geofenceId, geofence.name,
            "Trigger ${trigger.type} stored (id=${trigger.id}, confidence=$DEFAULT_CONFIDENCE)",
            true, occurredAt, latitude, longitude)

        // 4. Run rules → candidates
        val ruleResult = ruleOrchestrator.evaluateRecentTriggers()
        debugLogger.log("PROCESSOR", "  ${ruleResult.insertedCandidates.size} neue Candidates")

        // 5. Notify
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

    private suspend fun persistPipelineLog(
        eventType: String, geofenceId: String?, geofenceName: String?,
        detail: String, success: Boolean, occurredAt: Long,
        lat: Double?, lon: Double?
    ) {
        try {
            eventLog.log(GeofenceEventLogEntry(
                id = "pipeline_${eventType}_${occurredAt}_${UUID.randomUUID().toString().take(8)}",
                occurredAt = occurredAt,
                category = "PIPELINE",
                eventType = eventType,
                geofenceId = geofenceId,
                geofenceName = geofenceName,
                detail = detail,
                success = success,
                latitude = lat,
                longitude = lon
            ))
        } catch (_: Exception) { /* best effort */ }
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
