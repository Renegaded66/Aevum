package de.devondroste.aevum.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
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
            ReviewInboxUiState(
                candidates = candidates.sortedBy { it.startAt },
                isEmpty = candidates.isEmpty()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewInboxUiState())

    fun accept(candidate: ActivityCandidate, onAccepted: (String) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = reviewCandidateUseCase.accept(candidate.id)) {
                is CandidateReviewResult.Accepted -> onAccepted(result.sessionId)
                CandidateReviewResult.AlreadyResolved,
                CandidateReviewResult.Dismissed,
                CandidateReviewResult.NotFound -> Unit
            }
        }
    }

    fun dismiss(candidate: ActivityCandidate) {
        viewModelScope.launch {
            reviewCandidateUseCase.dismiss(candidate.id)
        }
    }
}

data class ReviewInboxUiState(
    val candidates: List<ActivityCandidate> = emptyList(),
    val isEmpty: Boolean = true
)
