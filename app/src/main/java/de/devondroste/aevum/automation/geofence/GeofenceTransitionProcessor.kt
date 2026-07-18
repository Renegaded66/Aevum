package de.devondroste.aevum.automation.geofence

import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
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
    private val candidateRepository: ActivityCandidateRepository
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

        val candidate = candidateForTransition(geofence, transition, occurredAt)
        candidate?.let { candidateRepository.insert(it) }

        return GeofenceProcessingResult.Stored(trigger.id, detection.id, candidate?.id)
    }

    private fun candidateForTransition(
        geofence: de.devondroste.aevum.data.model.PlaceGeofence,
        transition: GeofenceTransition,
        occurredAt: Long
    ): ActivityCandidate? {
        val activityTypeId = geofence.activityTypeId ?: return null
        val start = when (transition) {
            GeofenceTransition.Enter -> occurredAt
            GeofenceTransition.Exit -> (occurredAt - DEFAULT_PLACE_DURATION_MS).coerceAtLeast(0)
            GeofenceTransition.Unknown -> return null
        }
        val end = when (transition) {
            GeofenceTransition.Enter -> occurredAt + DEFAULT_PLACE_DURATION_MS
            GeofenceTransition.Exit -> occurredAt
            GeofenceTransition.Unknown -> return null
        }
        return ActivityCandidate(
            id = UUID.randomUUID().toString(),
            suggestedTitle = if (transition == GeofenceTransition.Enter) geofence.name else "${geofence.name} Aufenthalt",
            suggestedCategoryId = geofence.categoryId,
            activityTypeId = activityTypeId,
            startAt = start,
            endAt = end,
            confidence = DEFAULT_CONFIDENCE,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = if (transition == GeofenceTransition.Enter) "Geofence betreten: ${geofence.name}" else "Geofence verlassen: ${geofence.name}",
            createdBy = AutomationConstants.CREATED_BY_GEOFENCE_PIPELINE,
            createdAt = System.currentTimeMillis()
        )
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
        const val DEFAULT_PLACE_DURATION_MS = 60 * 60 * 1000L
    }
}

sealed class GeofenceProcessingResult {
    data class Stored(val triggerId: String, val detectionEventId: String, val candidateId: String?) : GeofenceProcessingResult()
    data object UnknownGeofence : GeofenceProcessingResult()
    data object Ignored : GeofenceProcessingResult()
}

private fun String?.quoteOrNull(): String = this?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
