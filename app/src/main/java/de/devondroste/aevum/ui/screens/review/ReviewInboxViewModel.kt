package de.devondroste.aevum.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.domain.automation.CandidateBatchResult
import de.devondroste.aevum.domain.automation.CandidateReviewResult
import de.devondroste.aevum.domain.automation.ReviewCandidateUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewInboxViewModel @Inject constructor(
    private val candidateRepository: ActivityCandidateRepository,
    private val reviewCandidateUseCase: ReviewCandidateUseCase
) : ViewModel() {

    val uiState: StateFlow<ReviewInboxUiState> = candidateRepository.getByStatus("PENDING")
        .map { candidates ->
            val sorted = candidates.sortedBy { it.startAt }
            val safeCount = sorted.count { it.confidence >= 0.70f }
            ReviewInboxUiState(
                candidates = sorted,
                isEmpty = sorted.isEmpty(),
                safeAcceptCount = safeCount
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewInboxUiState())

    /** Currently selected candidate IDs (multi-select mode) */
    private val selectedIds = mutableSetOf<String>()

    fun isSelected(id: String): Boolean = id in selectedIds

    fun toggleSelection(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun selectionCount(): Int = selectedIds.size

    fun clearSelection() {
        selectedIds.clear()
    }

    fun selectAllSafe(candidates: List<ActivityCandidate>) {
        selectedIds.clear()
        candidates.filter { it.confidence >= 0.70f }.forEach { selectedIds.add(it.id) }
    }

    fun acceptSingle(candidate: ActivityCandidate, onAccepted: (String) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = reviewCandidateUseCase.accept(candidate.id)) {
                is CandidateReviewResult.Accepted -> onAccepted(result.sessionId)
                else -> Unit
            }
        }
    }

    fun acceptSelected() {
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            reviewCandidateUseCase.acceptAll(selectedIds.toList())
            selectedIds.clear()
        }
    }

    fun acceptAllSafe() {
        viewModelScope.launch {
            reviewCandidateUseCase.acceptSafe(uiState.value.candidates)
        }
    }

    fun dismissSingle(candidate: ActivityCandidate) {
        viewModelScope.launch {
            reviewCandidateUseCase.dismiss(candidate.id)
        }
    }

    fun dismissSelected() {
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            reviewCandidateUseCase.dismissAll(selectedIds.toList())
            selectedIds.clear()
        }
    }
}

data class ReviewInboxUiState(
    val candidates: List<ActivityCandidate> = emptyList(),
    val isEmpty: Boolean = true,
    val safeAcceptCount: Int = 0
)
