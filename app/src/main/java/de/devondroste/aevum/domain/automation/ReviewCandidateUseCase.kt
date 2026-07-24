package de.devondroste.aevum.domain.automation

import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionChange
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivitySessionChangeRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class ReviewCandidateUseCase @Inject constructor(
    private val candidateRepository: ActivityCandidateRepository,
    private val activityRepository: ActivityRepository,
    private val changeRepository: ActivitySessionChangeRepository
) {
    suspend fun accept(candidateId: String): CandidateReviewResult {
        val candidate = candidateRepository.getById(candidateId).first() ?: return CandidateReviewResult.NotFound
        if (candidate.status != "PENDING") return CandidateReviewResult.AlreadyResolved
        confirmCandidate(candidate)
        return CandidateReviewResult.Accepted(candidate.id)
    }

    suspend fun acceptAll(candidateIds: List<String>): CandidateBatchResult {
        if (candidateIds.isEmpty()) return CandidateBatchResult(accepted = 0, dismissed = 0)
        var accepted = 0
        for (id in candidateIds) {
            val candidate = candidateRepository.getById(id).first() ?: continue
            if (candidate.status != "PENDING") continue
            confirmCandidate(candidate)
            accepted++
        }
        return CandidateBatchResult(accepted = accepted, dismissed = 0)
    }

    suspend fun acceptSafe(candidates: List<ActivityCandidate>): CandidateBatchResult {
        val safe = candidates.filter { it.status == "PENDING" && it.confidence >= SAFE_CONFIDENCE_THRESHOLD }
        return acceptAll(safe.map { it.id })
    }

    suspend fun dismiss(candidateId: String): CandidateReviewResult {
        val candidate = candidateRepository.getById(candidateId).first() ?: return CandidateReviewResult.NotFound
        if (candidate.status != "PENDING") return CandidateReviewResult.AlreadyResolved
        candidateRepository.update(candidate.copy(status = "DISMISSED", resolvedAt = System.currentTimeMillis()))
        return CandidateReviewResult.Dismissed
    }

    suspend fun dismissAll(candidateIds: List<String>): CandidateBatchResult {
        if (candidateIds.isEmpty()) return CandidateBatchResult(accepted = 0, dismissed = 0)
        var dismissed = 0
        for (id in candidateIds) {
            val candidate = candidateRepository.getById(id).first() ?: continue
            if (candidate.status != "PENDING") continue
            candidateRepository.update(candidate.copy(status = "DISMISSED", resolvedAt = System.currentTimeMillis()))
            dismissed++
        }
        return CandidateBatchResult(accepted = 0, dismissed = dismissed)
    }

    private suspend fun confirmCandidate(candidate: ActivityCandidate) {
        confirmCandidate(candidate, sourceTypeOverride = null)
    }

    /**
     * M12.2: Erweiterung des Confirm-Pfads mit optionalem sourceType-Override.
     *
     * Warum:
     *   Schlaf (HEALTH_SLEEP) und Fahrten (ACTIVITY_RECOGNITION) laufen über
     *   dieselbe Candidate-Pipeline wie Geofence-Trigger, sollen aber in der
     *   Timeline als "Auto" markiert sein — ohne Review-Dialog für den User.
     *
     *   Die `sourceType`-Spalte hat den Default "CONFIRMED_CANDIDATE"; für
     *   Auto-Quellen (Confidence ≥ SAFE_CONFIDENCE_THRESHOLD) setzen wir den
     *   SourceType explizit auf "HEALTH_SLEEP_AUTO" bzw. "ACTIVITY_RECOGNITION_AUTO".
     *   Das hat zwei Effekte:
     *     1. Timeline / Dashboard / Foreground-Notification zeigen die Session
     *        konsistent als automatisch erkannt.
     *     2. `isAuto`-Heuristik in der UI (`session.sourceType in AUTO_SOURCES`)
     *        greift ohne Sonderlocke.
     */
    private suspend fun confirmCandidate(candidate: ActivityCandidate, sourceTypeOverride: String?) {
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        // M12.2: sourceType bestimmen — Auto-Quellen werden entsprechend markiert,
        // wenn ein Override übergeben wurde; sonst der Default "CONFIRMED_CANDIDATE".
        val finalSourceType = sourceTypeOverride ?: "CONFIRMED_CANDIDATE"
        val session = ActivitySession(
            id = sessionId,
            title = candidate.suggestedTitle,
            categoryId = candidate.suggestedCategoryId,
            activityTypeId = candidate.activityTypeId,
            startAt = candidate.startAt,
            endAt = candidate.endAt,
            sourceType = finalSourceType,
            createdBy = sourceTypeOverride ?: "AUTO",
            updatedBy = null,
            sourceCandidateId = candidate.id,
            confidence = candidate.confidence,
            isUserEdited = false,
            createdAt = now,
            updatedAt = now,
            revision = 1
        )
        activityRepository.insert(session)
        candidateRepository.update(candidate.copy(status = "ACCEPTED", resolvedAt = now, resolvedSessionId = sessionId))
        changeRepository.insert(
            ActivitySessionChange(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                changeType = "CREATED",
                changedBy = sourceTypeOverride ?: "AUTO",
                changedAt = now,
                beforeJson = null,
                afterJson = "{\"candidateId\":\"${candidate.id}\",\"title\":\"${candidate.suggestedTitle}\",\"sourceType\":\"$finalSourceType\"}",
                reason = if (sourceTypeOverride != null) "Auto-Accept ($sourceTypeOverride)" else "Candidate accepted by user",
                sourceCandidateId = candidate.id
            )
        )
    }

    /**
     * M12.2: Auto-Accept für eine gefilterte Liste.
     *
     * Verwendet für:
     *   - Schlaf-Imports aus Health Connect (Source = health_connect)
     *   - Activity-Recognition-Events (z. B. IN_VEHICLE) (Source = activity_recognition)
     *
     * Beide Pfade nutzen [sourceTypeForActivityType], um den passenden
     * SourceType zu setzen. Das ist die Single Source of Truth für die
     * SourceType-Auto-Auswahl anhand des ActivityType.
     */
    suspend fun acceptAuto(candidates: List<ActivityCandidate>): CandidateBatchResult {
        if (candidates.isEmpty()) return CandidateBatchResult(accepted = 0, dismissed = 0)
        var accepted = 0
        for (candidate in candidates) {
            if (candidate.status != "PENDING") continue
            if (candidate.confidence < SAFE_CONFIDENCE_THRESHOLD) continue
            val sourceType = sourceTypeForActivityType(candidate.activityTypeId)
            confirmCandidate(candidate, sourceTypeOverride = sourceType)
            accepted++
        }
        return CandidateBatchResult(accepted = accepted, dismissed = 0)
    }

    private fun sourceTypeForActivityType(activityTypeId: String?): String? = when (activityTypeId) {
        "sleep" -> "HEALTH_SLEEP_AUTO"
        "driving" -> "ACTIVITY_RECOGNITION_AUTO"
        else -> null
    }

    private companion object {
        const val SAFE_CONFIDENCE_THRESHOLD = 0.70f
    }
}

data class CandidateBatchResult(
    val accepted: Int,
    val dismissed: Int
)

sealed class CandidateReviewResult {
    data class Accepted(val sessionId: String) : CandidateReviewResult()
    data object Dismissed : CandidateReviewResult()
    data object NotFound : CandidateReviewResult()
    data object AlreadyResolved : CandidateReviewResult()
}
