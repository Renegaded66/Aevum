package com.d_drostes_apps.aevum.domain.activity

import android.content.Context
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivitySessionChange
import com.d_drostes_apps.aevum.data.model.Tag
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivitySessionChangeRepository
import com.d_drostes_apps.aevum.domain.liveactivity.LiveActivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class SaveManualActivityUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityRepository: ActivityRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val changeRepository: ActivitySessionChangeRepository,
    // M18.66-FIX21 (User: "Neue Activity Aufzeichnungen sollten immer alte
    // stoppen"): Der manuelle Editor-Pfad muss die laufende Live-Session
    // beenden, damit keine Überlappungen entstehen (z.B. Geofence-Session
    // läuft, User speichert manuell eine Autofahrt → Geofence-Session
    // endet jetzt, Autofahrt startet).
    private val liveActivityManager: LiveActivityManager
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
            editingSessionId = request.id,
            context = context
        )

        if (validation is SessionValidationResult.Invalid) {
            return SaveManualActivityResult.Failure(validation.message)
        }

        val now = System.currentTimeMillis()
        val existing = request.id?.let { activityRepository.getById(it).first() }

        // M18.66-FIX21 (User: "Neue Activity Aufzeichnungen sollten immer alte
        // stoppen"): Beim ANLEGEN einer neuen manuellen Session wird die
        // laufende Live-Session beendet (endAt = jetzt), WENN die neue
        // Session sie zeitlich überlappt. So entstehen keine Überlappungen —
        // egal ob die laufende Session per Geofence, Fahrterkennung oder
        // manuell gestartet wurde. Beim EDITIEREN einer bestehenden Session
        // passiert das NICHT (der User bearbeitet ja genau diese Session).
        //
        // M18.101-FIX (User: "Wenn ich eine neue Aktivität hinzufüge wird
        // fälschlicherweise die aktuelle Aufzeichnung unterbrochen, auch
        // dann wenn ich nur eine Dauer oder zu einem anderen Zeitraum
        // hinzugefügt habe"): Der pauschale Stop war falsch — er beendete
        // die Live-Session auch dann, wenn die neue Aktivität sie GAR
        // NICHT überlappt (z.B. Dauer-only = Tagesbeginn, oder ein
        // Zeitraum am Vormittag, während die Live-Session abends läuft).
        // Jetzt: Nur bei ECHTER Überlappung stoppen. Die Live-Session
        // läuft weiter, wenn die neue Aktivität in einem anderen Zeitraum
        // liegt. (Die Live-Session hat endAt=null → effektiv bis jetzt;
        // eine neue Session mit endAt in der Zukunft überlappt sie.)
        if (existing == null) {
            val live = liveActivityManager.liveSession.value
            // M18.59-Semantik: Eine laufende Session (endAt=null) endet
            // effektiv bei JETZT — rangesOverlap würde null sonst als
            // Long.MAX_VALUE behandeln und JEDE neue Session (auch
            // Dauer-only am Tagesbeginn) als überlappend einstufen.
            val liveOverlaps = live != null && live.isLive &&
                SessionTimeValidator.rangesOverlap(
                    request.startAt,
                    request.endAt,
                    live.startAt,
                    now
                )
            if (liveOverlaps) {
                liveActivityManager.stop()
            }
        }

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
                revision = 1,
                excludeFromTimeline = request.excludeFromTimeline
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

        activityRepository.insert(session)
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
    val startAt: Long,
    val endAt: Long?,
    val timezoneId: String,
    val description: String,
    val excludeFromTimeline: Boolean = false
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
