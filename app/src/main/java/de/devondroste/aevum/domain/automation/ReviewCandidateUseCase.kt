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
        val now = System.currentTimeMillis()
        val sessionId = UUID.randomUUID().toString()
        val session = ActivitySession(
            id = sessionId,
            title = candidate.suggestedTitle,
            categoryId = candidate.suggestedCategoryId,
            activityTypeId = candidate.activityTypeId,
            startAt = candidate.startAt,
            endAt = candidate.endAt,
            sourceType = "CONFIRMED_CANDIDATE",
            createdBy = "AUTO",
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
                changedBy = "AUTO",
                changedAt = now,
                beforeJson = null,
                afterJson = "{\"candidateId\":\"${candidate.id}\",\"title\":\"${candidate.suggestedTitle}\"}",
                reason = "Candidate accepted by user",
                sourceCandidateId = candidate.id
            )
        )
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
