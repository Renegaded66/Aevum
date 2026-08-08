package com.d_drostes_apps.aevum.domain.automation

import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivitySessionChange
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.ActivityRepository
import com.d_drostes_apps.aevum.data.repository.ActivitySessionChangeRepository
import com.d_drostes_apps.aevum.data.repository.ActivityTypeRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class ReviewCandidateUseCase @Inject constructor(
    private val candidateRepository: ActivityCandidateRepository,
    private val activityRepository: ActivityRepository,
    private val changeRepository: ActivitySessionChangeRepository,
    // M18.51: Fallback auf "Sonstiges", wenn der ActivityType inzwischen
    // gelöscht wurde (User kann seit M18.50/51 alle Typen außer sleep/other
    // löschen). Default null, damit bestehende Tests ohne Repo weiterlaufen.
    private val activityTypeRepository: ActivityTypeRepository? = null
) {
    suspend fun accept(candidateId: String): CandidateReviewResult {
        val candidate = candidateRepository.getById(candidateId).first() ?: return CandidateReviewResult.NotFound
        if (candidate.status != "PENDING") return CandidateReviewResult.AlreadyResolved
        // M16.4: Wenn die Confirm-Pipeline fehlschlägt (z.B. weil FK-Constraints
        // auf Category/ActivityType noch nicht erfüllt sind — d.h. die
        // Seeds wurden nicht rechtzeitig geladen), wird der Candidate auf
        // DISMISSED gesetzt, damit der UI-Flow nicht in einem "akzeptiert
        // aber nirgends sichtbar"-Zustand steckenbleibt. Der Retry-Pfad
        // ist "Lücken prüfen" → Gap-Candidate → ActivityEditor.
        val confirmResult = try {
            confirmCandidate(candidate)
            true
        } catch (e: Exception) {
            android.util.Log.e(
                "ReviewCandidateUseCase",
                "accept: confirmCandidate failed für ${candidate.id} (${candidate.suggestedTitle})",
                e
            )
            // Candidate als DISMISSED markieren, damit er nicht erneut
            // auftaucht und der User-UI-Flow befreit ist.
            try {
                candidateRepository.update(
                    candidate.copy(
                        status = "DISMISSED",
                        resolvedAt = System.currentTimeMillis(),
                        reason = "${candidate.reason ?: ""}\n\nAuto-Dismiss: Session-Insert fehlgeschlagen (${e.javaClass.simpleName})."
                    )
                )
            } catch (e2: Exception) {
                android.util.Log.e("ReviewCandidateUseCase", "Follow-up DISMISSED-Update failed", e2)
            }
            false
        }
        return if (confirmResult) {
            CandidateReviewResult.Accepted(candidate.id)
        } else {
            CandidateReviewResult.PersistFailed(candidate.id, e = null)
        }
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
        // M18.51: Fallback auf "Sonstiges", wenn der ActivityType inzwischen
        // gelöscht wurde — sonst FK-Crash beim Session-INSERT.
        val resolvedTypeId = resolveTypeId(candidate.activityTypeId)
        val session = ActivitySession(
            id = sessionId,
            title = candidate.suggestedTitle,
            categoryId = candidate.suggestedCategoryId,
            activityTypeId = resolvedTypeId,
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

    /**
     * M18.51: Löst die ActivityType-ID auf. Existiert der Typ nicht mehr
     * (User hat ihn in Einstellungen → Activities gelöscht), wird auf den
     * System-Typ "Sonstiges" (other) zurückgefallen — sonst verletzt der
     * Session-INSERT die FK-Constraint und der Auto-Track crasht.
     */
    private suspend fun resolveTypeId(activityTypeId: String?): String? {
        if (activityTypeId == null) return null
        val repo = activityTypeRepository ?: return activityTypeId
        return if (repo.getById(activityTypeId).first() != null) activityTypeId else "other"
    }
}

/**
 * M16.3: Public-Konstante für die Auto-Accept-Schwelle.
 *
 * Wird von den Sleep-Engines (Heuristik + Fusion) genutzt, um zu
 * entscheiden, ob ein erkannter Schlaf-Candidate sofort in eine
 * ActivitySession überführt werden soll oder ob er auf die
 * Bestätigung durch den User in der Review-Inbox wartet.
 *
 * Bewusst public (nicht private), damit beide Engines ohne
 * Dependency-Injection-Zirkel darauf zugreifen können.
 */
const val SAFE_CONFIDENCE_THRESHOLD: Float = 0.70f

data class CandidateBatchResult(
    val accepted: Int,
    val dismissed: Int
)

sealed class CandidateReviewResult {
    data class Accepted(val sessionId: String) : CandidateReviewResult()
    data object Dismissed : CandidateReviewResult()
    data object NotFound : CandidateReviewResult()
    data object AlreadyResolved : CandidateReviewResult()
    /**
     * M16.4: Session konnte nicht persistiert werden (z.B. FK-Constraint
     * verletzt). Der Candidate wurde intern auf DISMISSED gesetzt.
     * Der User bekommt im UI eine entsprechende Meldung und kann die
     * Aktivität manuell über "Lücken prüfen" / ActivityEditor nachtragen.
     */
    data class PersistFailed(val candidateId: String, val e: Throwable?) : CandidateReviewResult()
}
