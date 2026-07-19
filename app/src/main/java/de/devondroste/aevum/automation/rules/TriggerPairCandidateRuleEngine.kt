package de.devondroste.aevum.automation.rules

import de.devondroste.aevum.automation.model.AutomationConstants
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import java.util.Locale
import javax.inject.Inject

/**
 * Local, transparent and deterministic candidate rule engine.
 *
 * Design rules for M6.2:
 * - Trigger events are facts; candidates are suggestions only.
 * - Rules are intentionally explainable and deterministic.
 * - Candidate IDs are stable per trigger pair, so rerunning the engine is idempotent.
 * - Open exits without a later destination intentionally produce no candidate yet.
 */
class TriggerPairCandidateRuleEngine @Inject constructor() {
    fun evaluate(
        triggers: List<TriggerEvent>,
        geofences: List<PlaceGeofence>,
        now: Long = System.currentTimeMillis()
    ): List<ActivityCandidate> {
        if (triggers.size < 2) return emptyList()
        val byGeofence = geofences.associateBy { it.id }
        val ordered = triggers
            .filter { it.geofenceId != null && it.occurredAt <= now }
            .sortedBy { it.occurredAt }

        return ordered.zipWithNext()
            .mapNotNull { (first, second) -> candidateForPair(first, second, byGeofence) }
            .filter { it.endAt - it.startAt in MIN_DURATION_MS..MAX_DURATION_MS }
            .distinctBy { it.id }
    }

    private fun candidateForPair(
        first: TriggerEvent,
        second: TriggerEvent,
        geofences: Map<String, PlaceGeofence>
    ): ActivityCandidate? {
        val firstPlace = geofences[first.geofenceId] ?: return null
        val secondPlace = geofences[second.geofenceId] ?: return null
        val firstKind = first.transitionKind()
        val secondKind = second.transitionKind()

        return when {
            firstKind == TriggerKind.Enter && secondKind == TriggerKind.Exit && first.geofenceId == second.geofenceId ->
                stayCandidate(first, second, firstPlace)

            firstKind == TriggerKind.Exit && secondKind == TriggerKind.Enter && first.geofenceId != second.geofenceId ->
                travelCandidate(first, second, firstPlace, secondPlace)

            firstKind == TriggerKind.Exit && secondKind == TriggerKind.Enter && first.geofenceId == second.geofenceId && firstPlace.isHomeLike() ->
                awayFromHomeCandidate(first, second, firstPlace)

            else -> null
        }
    }

    private fun stayCandidate(enter: TriggerEvent, exit: TriggerEvent, place: PlaceGeofence): ActivityCandidate {
        val title = when {
            place.isWorkLike() -> "Arbeit"
            place.isGymLike() -> "Fitnessstudio"
            place.isHomeLike() -> "Zuhause"
            else -> place.name
        }
        return ActivityCandidate(
            id = stableId("stay", enter.id, exit.id),
            suggestedTitle = title,
            suggestedCategoryId = place.categoryId ?: place.categoryFallbackForStay(),
            activityTypeId = place.activityTypeId ?: place.activityTypeFallbackForStay(),
            startAt = enter.occurredAt,
            endAt = exit.occurredAt,
            confidence = 0.88f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${place.name} betreten → verlassen. Vorschlag bleibt überprüfbar.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${enter.id}:${exit.id}"
        )
    }

    private fun travelCandidate(exit: TriggerEvent, enter: TriggerEvent, from: PlaceGeofence, to: PlaceGeofence): ActivityCandidate =
        ActivityCandidate(
            id = stableId("travel", exit.id, enter.id),
            suggestedTitle = "Fahrt: ${from.name} → ${to.name}",
            suggestedCategoryId = "transport",
            activityTypeId = "transport",
            startAt = exit.occurredAt,
            endAt = enter.occurredAt,
            confidence = 0.74f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${from.name} verlassen → ${to.name} betreten. Als Wegzeit vorgeschlagen.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${exit.id}:${enter.id}"
        )

    private fun awayFromHomeCandidate(exit: TriggerEvent, enter: TriggerEvent, home: PlaceGeofence): ActivityCandidate =
        ActivityCandidate(
            id = stableId("away", exit.id, enter.id),
            suggestedTitle = "Ausflug",
            suggestedCategoryId = "leisure",
            activityTypeId = "leisure",
            startAt = exit.occurredAt,
            endAt = enter.occurredAt,
            confidence = 0.62f,
            status = AutomationConstants.CANDIDATE_STATUS_PENDING,
            reason = "Trigger-Paar erkannt: ${home.name} verlassen → wieder angekommen. Kein Ziel bekannt, daher vorsichtig als Ausflug vorgeschlagen.",
            createdBy = AutomationConstants.CREATED_BY_TRIGGER_PAIR_RULES,
            createdAt = System.currentTimeMillis(),
            sourceCandidateId = "${exit.id}:${enter.id}"
        )

    private fun stableId(prefix: String, firstId: String, secondId: String): String = "rule_${prefix}_${firstId}_${secondId}"

    private fun TriggerEvent.transitionKind(): TriggerKind = when {
        type.endsWith("_LEFT") || type.endsWith("_EXIT") || type == AutomationConstants.TRIGGER_CUSTOM_PLACE_LEFT -> TriggerKind.Exit
        type.endsWith("_ARRIVED") || type.endsWith("_ENTERED") || type == AutomationConstants.TRIGGER_CUSTOM_PLACE_ENTERED -> TriggerKind.Enter
        type == AutomationConstants.TRIGGER_GEOFENCE_EXIT -> TriggerKind.Exit
        type == AutomationConstants.TRIGGER_GEOFENCE_ENTER -> TriggerKind.Enter
        else -> TriggerKind.Unknown
    }

    private fun PlaceGeofence.isHomeLike(): Boolean = normalizedName().let { it.contains("zuhause") || it.contains("home") }
    private fun PlaceGeofence.isWorkLike(): Boolean = normalizedName().let { it.contains("arbeit") || it.contains("work") || it.contains("büro") || it.contains("office") }
    private fun PlaceGeofence.isGymLike(): Boolean = normalizedName().let { it.contains("fitness") || it.contains("gym") || it.contains("studio") }
    private fun PlaceGeofence.normalizedName(): String = name.lowercase(Locale.GERMAN)

    private fun PlaceGeofence.categoryFallbackForStay(): String = when {
        isWorkLike() -> "work"
        isGymLike() -> "sport"
        isHomeLike() -> "household"
        else -> "unknown"
    }

    private fun PlaceGeofence.activityTypeFallbackForStay(): String = when {
        isWorkLike() -> "work"
        isGymLike() -> "fitness"
        isHomeLike() -> "household"
        else -> "other"
    }

    private enum class TriggerKind { Enter, Exit, Unknown }

    private companion object {
        const val MIN_DURATION_MS = 5 * 60 * 1000L
        const val MAX_DURATION_MS = 14 * 60 * 60 * 1000L
    }
}
