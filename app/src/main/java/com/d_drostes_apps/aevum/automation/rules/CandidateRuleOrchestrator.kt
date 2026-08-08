package com.d_drostes_apps.aevum.automation.rules

import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.TriggerEvent
import com.d_drostes_apps.aevum.data.repository.ActivityCandidateRepository
import com.d_drostes_apps.aevum.data.repository.PlaceGeofenceRepository
import com.d_drostes_apps.aevum.data.repository.TriggerEventRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class CandidateRuleOrchestrator @Inject constructor(
    private val triggerRepository: TriggerEventRepository,
    private val geofenceRepository: PlaceGeofenceRepository,
    private val candidateRepository: ActivityCandidateRepository,
    private val ruleEngine: TriggerPairCandidateRuleEngine,
    private val mergeEngine: CandidateMergeEngine
) {
    suspend fun evaluateRecentTriggers(now: Long = System.currentTimeMillis()): CandidateRuleResult {
        val windowStart = now - LOOKBACK_MS
        val triggers = triggerRepository.getByDateRange(windowStart, now + FUTURE_TOLERANCE_MS).first()
        val geofences = geofenceRepository.getAllIncludingDeletedOnce()
        val existing = candidateRepository.getByDateRange(windowStart, now + FUTURE_TOLERANCE_MS).first()
            .associateBy { it.id }

        val generated = ruleEngine.evaluate(triggers, geofences, now)
        // M7: Merge fragmented candidates before inserting
        val merged = mergeEngine.merge(generated)
        val newCandidates = merged.filterNot { it.id in existing }
        if (newCandidates.isNotEmpty()) candidateRepository.insertAll(newCandidates)
        return CandidateRuleResult(
            consideredTriggers = triggers.size,
            generatedCandidates = merged,
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
