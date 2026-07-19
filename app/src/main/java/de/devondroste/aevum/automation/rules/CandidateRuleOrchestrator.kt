package de.devondroste.aevum.automation.rules

import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.PlaceGeofence
import de.devondroste.aevum.data.model.TriggerEvent
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.PlaceGeofenceRepository
import de.devondroste.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class CandidateRuleOrchestrator @Inject constructor(
    private val triggerRepository: TriggerEventRepository,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val ruleEngine: TriggerPairCandidateRuleEngine
) {
    suspend fun evaluateRecentTriggers(now: Long = System.currentTimeMillis()): CandidateRuleResult {
        val windowStart = now - LOOKBACK_MS
        val triggers = triggerRepository.getByDateRange(windowStart, now + FUTURE_TOLERANCE_MS).first()
        val geofences = geofenceRepository.getAllIncludingDeletedOnce()
        val existing = candidateRepository.getByDateRange(windowStart, now + FUTURE_TOLERANCE_MS).first()
            .associateBy { it.id }

        val generated = ruleEngine.evaluate(triggers, geofences, now)
        val newCandidates = generated.filterNot { it.id in existing }
        if (newCandidates.isNotEmpty()) candidateRepository.insertAll(newCandidates)
        return CandidateRuleResult(
            consideredTriggers = triggers.size,
            generatedCandidates = generated,
            insertedCandidates = newCandidates
        )
    }

    private suspend fun PlaceGeofenceRepository.getAllIncludingDeletedOnce(): List<PlaceGeofence> =
        getAll().first() + getDeleted().first()

    private companion object {
        const val LOOKBACK_MS = 36 * 60 * 60 * 1000L
        const val FUTURE_TOLERANCE_MS = 60 * 1000L
    }
}

data class CandidateRuleResult(
    val consideredTriggers: Int,
    val generatedCandidates: List<ActivityCandidate>,
    val insertedCandidates: List<ActivityCandidate>
)
