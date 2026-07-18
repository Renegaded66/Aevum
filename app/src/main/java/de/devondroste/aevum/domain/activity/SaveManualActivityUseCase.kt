package de.devondroste.aevum.domain.activity

import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionChange
import de.devondroste.aevum.data.model.Tag
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivitySessionChangeRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class SaveManualActivityUseCase @Inject constructor(
    private val activityRepository: ActivityRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val changeRepository: ActivitySessionChangeRepository
) {
    suspend operator fun invoke(request: ManualActivityRequest): SaveManualActivityResult {
        val validation = SessionTimeValidator.validate(
            title = request.title,
            startAt = request.startAt,
            endAt = request.endAt,
            existingSessions = activityRepository.getOverlappingRange(
                request.startAt,
                request.endAt ?: request.startAt + 1
            ).first(),
            editingSessionId = request.id
        )

        if (validation is SessionValidationResult.Invalid) {
            return SaveManualActivityResult.Failure(validation.message)
        }

        val now = System.currentTimeMillis()
        val existing = request.id?.let { activityRepository.getById(it).first() }
        val sessionId = request.id ?: UUID.randomUUID().toString()
        val session = if (existing == null) {
            ActivitySession(
                id = sessionId,
                title = request.title.trim(),
                categoryId = request.categoryId,
                activityTypeId = request.activityTypeId,
                startAt = request.startAt,
                endAt = request.endAt,
                timezoneId = request.timezoneId,
                description = request.description.trim().ifBlank { null },
                sourceType = "MANUAL",
                createdBy = "MANUAL",
                updatedBy = null,
                sourceCandidateId = request.sourceCandidateId,
                confidence = 1.0f,
                isUserEdited = false,
                createdAt = now,
                updatedAt = now,
                revision = 1
            )
        } else {
            existing.copy(
                title = request.title.trim(),
                categoryId = request.categoryId,
                activityTypeId = request.activityTypeId,
                startAt = request.startAt,
                endAt = request.endAt,
                timezoneId = request.timezoneId,
                description = request.description.trim().ifBlank { null },
                updatedBy = "MANUAL",
                isUserEdited = true,
                updatedAt = now,
                revision = existing.revision + 1
            )
        }

        activityRepository.insertWithTags(session, request.tags)
        if (request.sourceCandidateId != null) {
            candidateRepository.getById(request.sourceCandidateId).first()?.let { candidate ->
                candidateRepository.update(candidate.copy(status = "EDITED", resolvedAt = now, resolvedSessionId = session.id))
            }
        }

        val change = ActivitySessionChange(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            changeType = if (existing == null) "CREATED" else "USER_EDITED",
            changedBy = "MANUAL",
            changedAt = now,
            beforeJson = existing?.toChangeSnapshot(),
            afterJson = session.toChangeSnapshot(),
            reason = if (existing == null) "Manual activity created" else "Manual activity edited",
            sourceCandidateId = session.sourceCandidateId
        )
        changeRepository.insert(change)

        return SaveManualActivityResult.Success(session.id, validation)
    }
}

data class ManualActivityRequest(
    val id: String? = null,
    val sourceCandidateId: String? = null,
    val title: String,
    val categoryId: String?,
    val activityTypeId: String?,
    val tagIds: List<String>,
    val tags: List<Tag>,
    val startAt: Long,
    val endAt: Long?,
    val timezoneId: String,
    val description: String
)

sealed class SaveManualActivityResult {
    data class Success(
        val sessionId: String,
        val validation: SessionValidationResult
    ) : SaveManualActivityResult()

    data class Failure(val message: String) : SaveManualActivityResult()
}

private fun ActivitySession.toChangeSnapshot(): String =
    "{" +
        "\"id\":\"$id\"," +
        "\"title\":\"${title.escapeJson()}\"," +
        "\"categoryId\":${categoryId.quoteOrNull()}," +
        "\"activityTypeId\":${activityTypeId.quoteOrNull()}," +
        "\"startAt\":$startAt," +
        "\"endAt\":${endAt ?: "null"}," +
        "\"timezoneId\":\"${timezoneId.escapeJson()}\"," +
        "\"revision\":$revision" +
    "}"

private fun String?.quoteOrNull(): String = this?.let { "\"${it.escapeJson()}\"" } ?: "null"

private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
