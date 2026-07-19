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
    private val candidateReviewNotifier: CandidateReviewNotifier
) {
    suspend fun processTransition(
        geofenceId: String,
        transition: GeofenceTransition,
        occurredAt: Long,
        latitude: Double? = null,
        longitude: Double? = null
    ): GeofenceProcessingResult {
        if (transition == GeofenceTransition.Unknown) return GeofenceProcessingResult.Ignored
        val geofence = geofenceRepository.getById(geofenceId).first() ?: return GeofenceProcessingResult.UnknownGeofence
        if (!geofence.enabled || geofence.deletedAt != null) return GeofenceProcessingResult.Ignored

        val raw = RawSourceEvent(
            id = UUID.randomUUID().toString(),
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            externalId = "${geofenceId}_${transition.name}_$occurredAt",
            eventType = if (transition == GeofenceTransition.Enter) "GEOFENCE_ENTER" else "GEOFENCE_EXIT",
            observedAt = occurredAt,
            timezoneId = java.time.ZoneId.systemDefault().id,
            payloadJson = "{\"geofenceId\":\"${geofence.id}\",\"name\":\"${geofence.name}\",\"lat\":${latitude ?: "null"},\"lon\":${longitude ?: "null"}}"
        )
        rawSourceRepository.insert(raw)

        val detection = DetectionEvent(
            id = UUID.randomUUID().toString(),
            rawEventId = raw.id,
            sourceId = AutomationConstants.DATA_SOURCE_GEOFENCING,
            kind = if (transition == GeofenceTransition.Enter) AutomationConstants.DETECTION_GEOFENCE_ENTER else AutomationConstants.DETECTION_GEOFENCE_EXIT,
            startAt = occurredAt,
            confidence = DEFAULT_CONFIDENCE,
            placeId = geofence.id,
            metadataJson = "{\"geofenceName\":\"${geofence.name}\",\"transition\":\"${transition.name}\"}"
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
            metadataJson = "{\"geofenceName\":\"${geofence.name}\",\"activityTypeId\":${geofence.activityTypeId.quoteOrNull()}}"
        )
        triggerRepository.insert(trigger)

        val ruleResult = ruleOrchestrator.evaluateRecentTriggers()
        candidateReviewNotifier.notifyIfEnabled(ruleResult.insertedCandidates)

        return GeofenceProcessingResult.Stored(trigger.id, detection.id, ruleResult.insertedCandidates.size)
    }

    private fun triggerTypeFor(name: String, transition: GeofenceTransition): String {
        val lower = name.lowercase()
        return when {
            lower.contains("zuhause") || lower.contains("home") -> if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_HOME_ARRIVED else AutomationConstants.TRIGGER_HOME_LEFT
            lower.contains("arbeit") || lower.contains("work") -> if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_WORK_ENTERED else AutomationConstants.TRIGGER_WORK_LEFT
            else -> if (transition == GeofenceTransition.Enter) AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED else AutomationConstants.TRIGGER_CUSTOM_PLACE_LEFT
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

private fun String?.quoteOrNull(): String = this?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
