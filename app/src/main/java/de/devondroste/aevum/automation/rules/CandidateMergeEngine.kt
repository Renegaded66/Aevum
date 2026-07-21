package de.devondroste.aevum.automation.rules

import de.devondroste.aevum.data.model.ActivityCandidate
import javax.inject.Inject

/**
 * Deterministic, local merge engine for ActivityCandidate fragments.
 *
 * ADR-0026: Merge candidates with same suggestedCategoryId when the gap
 * between them is ≤5 minutes and the total span is ≤30 minutes.
 *
 * This reduces noise from split geofence events (e.g. 3 driving fragments
 * → 1 "Arbeitsweg") before displaying candidates in the timeline and review inbox.
 */
class CandidateMergeEngine @Inject constructor() {

    /**
     * Merges adjacent candidates with the same category when gap ≤ threshold.
     * Sorted by startAt before processing.
     * Returns a new list; original candidates remain unchanged.
     */
    fun merge(candidates: List<ActivityCandidate>): List<ActivityCandidate> {
        if (candidates.size < 2) return candidates

        val sorted = candidates.sortedBy { it.startAt }
        val result = mutableListOf<ActivityCandidate>()
        var current = sorted.first()

        for (next in sorted.drop(1)) {
            if (shouldMerge(current, next)) {
                current = mergePair(current, next)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    private fun shouldMerge(a: ActivityCandidate, b: ActivityCandidate): Boolean {
        // Must have the same category
        if (a.suggestedCategoryId != b.suggestedCategoryId) return false
        // Gap must be ≤ MERGE_GAP_MS
        val gap = b.startAt - a.endAt
        if (gap > MERGE_GAP_MS) return false
        // Total span must be ≤ MAX_SPAN_MS
        val totalSpan = b.endAt - a.startAt
        if (totalSpan > MAX_SPAN_MS) return false
        return true
    }

    private fun mergePair(first: ActivityCandidate, second: ActivityCandidate): ActivityCandidate {
        val mergedStart = first.startAt.coerceAtMost(second.startAt)
        val mergedEnd = first.endAt.coerceAtLeast(second.endAt)
        val avgConfidence = (first.confidence + second.confidence) / 2f
        val title = first.suggestedTitle
            .replace(Regex(": .*→.*$"), "")
            .trim()

        return ActivityCandidate(
            id = "merged_${first.id}_${second.id}",
            suggestedTitle = title,
            suggestedCategoryId = first.suggestedCategoryId,
            activityTypeId = first.activityTypeId,
            startAt = mergedStart,
            endAt = mergedEnd,
            confidence = avgConfidence,
            status = first.status,
            reason = "${first.reason ?: ""} | Zusammengeführt mit ${second.reason ?: "weiterem Kandidaten"} (Lücke < 5min).",
            createdBy = first.createdBy,
            createdAt = first.createdAt.coerceAtMost(second.createdAt),
            sourceCandidateId = "${first.sourceCandidateId ?: first.id},${second.sourceCandidateId ?: second.id}"
        )
    }

    private companion object {
        const val MERGE_GAP_MS = 5 * 60 * 1000L   // 5 minutes
        const val MAX_SPAN_MS = 30 * 60 * 1000L    // 30 minutes
    }
}
