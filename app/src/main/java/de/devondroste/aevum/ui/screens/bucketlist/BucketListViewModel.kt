package de.devondroste.aevum.ui.screens.bucketlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.BucketListItem
import de.devondroste.aevum.data.repository.BucketListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M18.39 + M18.43: Bucket-List-ViewModel mit Gamification.
 *
 * M18.43 (User: "Bucket list ist einfach nur eine zweite To-Do Liste,
 * die ist mir viel zu unspektakulär, suche dir inspiration"):
 * Nach Recherche der besten Bucket-List-Apps (Goji, Buckist) ist der
 * Kern-Unterschied zur To-Do-Liste die GAMIFICATION:
 *  - Level + XP: Jedes abgehakte Item gibt XP (10 pro Schwierigkeits-
 *    Stern). XP füllen eine Level-Fortschrittsleiste.
 *  - Level-Titel: "Abenteurer", "Entdecker", "Weltenbummler", ...
 *  - Schwierigkeitsgrad (1-5 Sterne) pro Item — bestimmt die Belohnung.
 *  - "Geschafft"-Chronik mit Datum.
 */
@HiltViewModel
class BucketListViewModel @Inject constructor(
    private val repository: BucketListRepository
) : ViewModel() {

    val uiState: StateFlow<BucketListUiState> = repository.getAll()
        .map { items ->
            val done = items.count { it.completed }
            val totalXp = items.filter { it.completed }.sumOf { it.xpReward }
            val level = levelForXp(totalXp)
            val levelProgress = levelProgress(totalXp, level)
            BucketListUiState(
                items = items,
                doneCount = done,
                totalCount = items.size,
                progress = if (items.isEmpty()) 0f else done.toFloat() / items.size,
                totalXp = totalXp,
                level = level,
                levelTitle = levelTitle(level),
                levelProgress = levelProgress,
                xpIntoLevel = xpIntoLevel(totalXp, level),
                xpForNextLevel = xpForLevel(level)
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BucketListUiState())

    fun toggleCompleted(item: BucketListItem) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.setCompleted(
                id = item.id,
                completed = !item.completed,
                completedAt = if (!item.completed) now else null,
                now = now
            )
        }
    }

    /** M18.43: Schwierigkeitsgrad setzen (1-5 Sterne). */
    fun setDifficulty(item: BucketListItem, difficulty: Int) {
        viewModelScope.launch {
            repository.setDifficulty(item.id, difficulty.coerceIn(1, 5), System.currentTimeMillis())
        }
    }

    fun delete(item: BucketListItem) {
        viewModelScope.launch { repository.delete(item.id) }
    }

    companion object {
        /** M18.43: XP, die Level [level] (1-basiert) zum Aufsteigen braucht. */
        fun xpForLevel(level: Int): Int = 50 + (level - 1) * 25

        /** M18.43: Level aus Gesamt-XP berechnen (1-basiert). */
        fun levelForXp(totalXp: Int): Int {
            var level = 1
            var remaining = totalXp
            while (remaining >= xpForLevel(level)) {
                remaining -= xpForLevel(level)
                level++
            }
            return level
        }

        /** M18.43: XP innerhalb des aktuellen Levels (0..xpForLevel). */
        fun xpIntoLevel(totalXp: Int, level: Int): Int {
            var remaining = totalXp
            var l = 1
            while (l < level) {
                remaining -= xpForLevel(l)
                l++
            }
            return remaining.coerceAtLeast(0)
        }

        /** M18.43: Fortschritt 0..1 zum nächsten Level. */
        fun levelProgress(totalXp: Int, level: Int): Float {
            val into = xpIntoLevel(totalXp, level)
            val needed = xpForLevel(level)
            return (into.toFloat() / needed).coerceIn(0f, 1f)
        }

        /** M18.43: Motivations-Titel pro Level. */
        fun levelTitle(level: Int): String = when {
            level >= 20 -> "Legende"
            level >= 15 -> "Weltenbummler"
            level >= 10 -> "Entdecker"
            level >= 5 -> "Abenteurer"
            level >= 3 -> "Träumer"
            else -> "Neuling"
        }
    }
}

data class BucketListUiState(
    val items: List<BucketListItem> = emptyList(),
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val progress: Float = 0f,
    // M18.43: Gamification
    val totalXp: Int = 0,
    val level: Int = 1,
    val levelTitle: String = "Neuling",
    val levelProgress: Float = 0f,
    val xpIntoLevel: Int = 0,
    val xpForNextLevel: Int = 50
)
