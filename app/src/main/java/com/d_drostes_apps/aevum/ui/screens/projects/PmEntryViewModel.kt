package com.d_drostes_apps.aevum.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d_drostes_apps.aevum.data.repository.PmRepository
import com.d_drostes_apps.aevum.domain.projects.PmToday
import com.d_drostes_apps.aevum.domain.projects.PmTree
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** M19.1: Was der Einstiegsknopf im Dashboard anzeigt. */
data class PmEntryUiState(
    val loaded: Boolean = false,
    /** Noch offene Todos des Tages (der Zähler am Juwel). */
    val pending: Int = 0,
    /** Gibt es überhaupt einen Tagesplan? (unterscheidet „leer" von „alles fertig") */
    val hasPlan: Boolean = false
)

/**
 * M19.1: Eigener, kleiner ViewModel NUR für den Einstiegsknopf im Dashboard.
 *
 * WARUM NICHT IM DashboardViewModel: Der Knopf soll den Zähler der offenen
 * Todos des Tages zeigen — das ist die einzige Stelle, an der das Dashboard
 * etwas über das Projektmanagement wissen muss. Ein eigener ViewModel hält den
 * Eingriff ins Dashboard minimal (keine neue Abhängigkeit im 77-KB-ViewModel,
 * kein Risiko für die bestehenden 1114 Tests) und rechnet nur dann, wenn der
 * Knopf wirklich sichtbar ist.
 */
@HiltViewModel
class PmEntryViewModel @Inject constructor(
    repository: PmRepository
) : ViewModel() {

    private val todayFlow = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000L)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<PmEntryUiState> = combine(
        repository.observeAll(),
        todayFlow
    ) { nodes, today ->
        val state = PmToday.state(PmTree(nodes), today)
        PmEntryUiState(loaded = true, pending = state.pending, hasPlan = state.total > 0)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = PmEntryUiState()
    )
}
