package de.devondroste.aevum.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.domain.seed.EnsureDefaultDataUseCase
import de.devondroste.aevum.domain.time.TimeFormatting
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    candidateRepository: ActivityCandidateRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val start = TimeFormatting.startOfDayMillis(today, zoneId)
    private val end = TimeFormatting.endOfDayMillis(today, zoneId)

    init {
        viewModelScope.launch { ensureDefaultData() }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        activityRepository.getOverlappingRange(start, end),
        categoryRepository.getAll(),
        candidateRepository.getByStatus("PENDING")
    ) { sessions, categories, candidates ->
        buildState(sessions, categories, candidates.filter { it.startAt < end && it.endAt > start })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun buildState(
        sessions: List<ActivitySession>,
        categories: List<Category>,
        candidates: List<ActivityCandidate>
    ): DashboardUiState {
        val activeSessions = sessions.filter { it.deletedAt == null }
        val now = System.currentTimeMillis().coerceIn(start, end)
        val categoryMap = categories.associateBy { it.id }
        val clippedSessions = activeSessions.map { it.clipped(now) }
        val totalMs = clippedSessions.sumOf { it.durationMs }
        val openMs = (DAY_MS - totalMs).coerceAtLeast(0L)
        val distribution = clippedSessions
            .groupBy { it.categoryId ?: "unknown" }
            .map { (categoryId, values) ->
                DashboardCategorySlice(
                    categoryId = categoryId,
                    label = categoryMap[categoryId]?.name ?: "Sonstiges",
                    durationMs = values.sumOf { it.durationMs }
                )
            }
            .sortedByDescending { it.durationMs }
        val current = activeSessions.filter { it.endAt == null }.maxByOrNull { it.startAt } ?: activeSessions.maxByOrNull { it.startAt }
        val flow = clippedSessions.sortedBy { it.startAt }.map { session ->
            val startMinute = TimeFormatting.minutesOfDay(session.startAt, zoneId).coerceIn(0, 1440)
            val endMinute = TimeFormatting.minutesOfDay(session.endAt, zoneId).coerceIn(startMinute, 1440)
            DashboardFlowSegment(
                id = session.id,
                title = session.title,
                categoryName = categoryMap[session.categoryId]?.name ?: "Sonstiges",
                categoryId = session.categoryId ?: "unknown",
                startMinute = startMinute,
                endMinute = endMinute,
                timeRange = "${formatMinute(startMinute)}–${formatMinute(endMinute)}",
                duration = TimeFormatting.formatDuration(session.durationMs),
                isCurrent = session.isCurrent
            )
        }
        val gaps = buildFlowGaps(flow)
        val currentMinute = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)
        val timeline = flow.takeLast(4).map { segment ->
            DashboardTimelineRow(
                id = segment.id,
                time = "%02d:%02d".format(segment.startMinute / 60, segment.startMinute % 60),
                title = segment.title,
                categoryName = segment.categoryName,
                duration = segment.duration,
                source = if (segment.isCurrent) "Jetzt" else "Erfasst",
                isCurrent = segment.isCurrent
            )
        }
        val top = distribution.firstOrNull()
        val narrative = buildNarrative(totalMs, openMs, top, candidates.size, current)
        val insights = buildInsights(distribution, totalMs, openMs, candidates.size)
        return DashboardUiState(
            headline = narrative.headline,
            narrative = narrative.body,
            currentActivity = current?.title ?: "Noch nichts erfasst",
            currentDuration = current?.let { TimeFormatting.formatDuration(((it.endAt ?: now).coerceAtMost(end) - it.startAt.coerceAtLeast(start)).coerceAtLeast(0)) } ?: "0m",
            balanceScore = estimateBalanceScore(distribution, totalMs, openMs),
            totalTracked = TimeFormatting.formatDuration(totalMs),
            openTime = TimeFormatting.formatDuration(openMs),
            sessionCount = activeSessions.size,
            reviewCount = candidates.size,
            distribution = distribution,
            timeline = timeline,
            flowSegments = flow,
            flowGaps = gaps,
            currentMinute = currentMinute,
            insights = insights,
            topCategory = top?.label ?: "Noch offen",
            topCategoryDuration = top?.let { TimeFormatting.formatDuration(it.durationMs) } ?: "0m",
            hasData = activeSessions.isNotEmpty(),
            dayProgress = ((now - start).toFloat() / DAY_MS.toFloat()).coerceIn(0f, 1f)
        )
    }

    private fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun buildFlowGaps(segments: List<DashboardFlowSegment>): List<FlowGap> {
        val gaps = mutableListOf<FlowGap>()
        var prevEnd = 0
        for (segment in segments) {
            if (segment.startMinute > prevEnd) {
                gaps += FlowGap(
                    startMinute = prevEnd,
                    endMinute = segment.startMinute,
                    durationMs = (segment.startMinute - prevEnd).toLong() * 60_000L
                )
            }
            prevEnd = segment.endMinute.coerceAtLeast(prevEnd)
        }
        if (prevEnd < 1440) {
            gaps += FlowGap(
                startMinute = prevEnd,
                endMinute = 1440,
                durationMs = (1440 - prevEnd).toLong() * 60_000L
            )
        }
        return gaps
    }

    private fun ActivitySession.clipped(now: Long): ClippedSession {
        val clippedStart = startAt.coerceIn(start, end)
        val clippedEnd = (endAt ?: now).coerceIn(start, end)
        return ClippedSession(
            id = id,
            title = title,
            categoryId = categoryId,
            startAt = clippedStart,
            endAt = clippedEnd,
            durationMs = (clippedEnd - clippedStart).coerceAtLeast(0L),
            isCurrent = endAt == null
        )
    }

    private fun buildNarrative(
        totalMs: Long,
        openMs: Long,
        top: DashboardCategorySlice?,
        reviewCount: Int,
        current: ActivitySession?
    ): DailyNarrative {
        if (totalMs <= 0 && reviewCount == 0) {
            return DailyNarrative(
                headline = "Dein Tag ist noch eine leere Seite.",
                body = "Erfasse einen ersten Abschnitt oder prüfe später automatische Vorschläge. Aevum wird mit jedem Eintrag klarer."
            )
        }
        if (totalMs <= 0 && reviewCount > 0) {
            return DailyNarrative(
                headline = "Aevum hat etwas für dich vorbereitet.",
                body = "${reviewCount.reviewText()} warten ruhig auf deine Bestätigung. Erst danach zählen sie als Teil deines Tages."
            )
        }
        val topText = top?.let { "Vor allem ${it.label.lowercase()} (${TimeFormatting.formatDuration(it.durationMs)})" } ?: "Mehrere kleine Abschnitte"
        val reviewText = if (reviewCount > 0) " ${reviewCount.reviewText()} sind noch offen." else ""
        val openText = if (openMs > 90 * 60_000L) " ${TimeFormatting.formatDuration(openMs)} sind noch nicht erzählt." else ""
        val currentText = current?.let { " Gerade läuft: ${it.title}." }.orEmpty()
        return DailyNarrative(
            headline = "Das war bisher dein Tag.",
            body = "$topText prägt deinen Tagesfluss.$currentText$reviewText$openText".trim()
        )
    }

    private fun buildInsights(
        distribution: List<DashboardCategorySlice>,
        totalMs: Long,
        openMs: Long,
        reviewCount: Int
    ): List<DashboardInsight> {
        val insights = mutableListOf<DashboardInsight>()
        val top = distribution.firstOrNull()
        if (top != null) {
            val share = ((top.durationMs.toFloat() / totalMs.coerceAtLeast(1).toFloat()) * 100).toInt()
            insights += DashboardInsight("Größter Block", "${top.label} macht $share% deiner erfassten Zeit aus.", "◷")
        }
        if (reviewCount > 0) {
            insights += DashboardInsight("Kurz prüfen", "${reviewCount.reviewText()} warten auf deine Entscheidung.", "✓")
        }
        if (openMs > 2 * 60 * 60_000L) {
            insights += DashboardInsight("Offene Zeit", "${TimeFormatting.formatDuration(openMs)} sind noch frei oder nicht erfasst.", "○")
        }
        if (distribution.size >= 3) {
            insights += DashboardInsight("Vielfalt", "Dein Tag verteilt sich auf ${distribution.size} Bereiche.", "✦")
        }
        if (insights.isEmpty()) {
            insights += DashboardInsight("Ruhiger Start", "Ein erster Eintrag reicht, damit Aevum deinen Tag sichtbar macht.", "✧")
        }
        return insights.take(3)
    }

    private fun estimateBalanceScore(distribution: List<DashboardCategorySlice>, totalMs: Long, openMs: Long): Int {
        if (totalMs <= 0) return 0
        val dominantShare = distribution.firstOrNull()?.durationMs?.toFloat()?.div(totalMs.toFloat()) ?: 0f
        val variety = (distribution.size.coerceAtMost(4) / 4f) * 35f
        val coverage = (totalMs.toFloat() / DAY_MS.toFloat()).coerceIn(0f, 1f) * 35f
        val dominancePenalty = if (dominantShare > 0.72f) 18f else 0f
        val openPenalty = if (openMs > 12 * 60 * 60_000L) 10f else 0f
        return (30f + variety + coverage - dominancePenalty - openPenalty).toInt().coerceIn(0, 100)
    }

    private fun Int.reviewText(): String = if (this == 1) "1 Vorschlag" else "$this Vorschläge"

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

private data class ClippedSession(
    val id: String,
    val title: String,
    val categoryId: String?,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long,
    val isCurrent: Boolean
)

data class DailyNarrative(
    val headline: String,
    val body: String
)

data class DashboardUiState(
    val headline: String = "Dein Tag ist noch eine leere Seite.",
    val narrative: String = "Erfasse einen ersten Abschnitt oder prüfe später automatische Vorschläge.",
    val currentActivity: String = "Noch nichts erfasst",
    val currentDuration: String = "0m",
    val balanceScore: Int = 0,
    val totalTracked: String = "0m",
    val openTime: String = "24h",
    val sessionCount: Int = 0,
    val reviewCount: Int = 0,
    val distribution: List<DashboardCategorySlice> = emptyList(),
    val timeline: List<DashboardTimelineRow> = emptyList(),
    val flowSegments: List<DashboardFlowSegment> = emptyList(),
    val flowGaps: List<FlowGap> = emptyList(),
    val currentMinute: Int = 0,
    val insights: List<DashboardInsight> = emptyList(),
    val topCategory: String = "Noch offen",
    val topCategoryDuration: String = "0m",
    val hasData: Boolean = false,
    val dayProgress: Float = 0f
)

data class DashboardCategorySlice(
    val categoryId: String,
    val label: String,
    val durationMs: Long
)

data class DashboardTimelineRow(
    val id: String,
    val time: String,
    val title: String,
    val categoryName: String,
    val duration: String,
    val source: String,
    val isCurrent: Boolean
)

data class DashboardFlowSegment(
    val id: String,
    val title: String,
    val categoryName: String,
    val categoryId: String,
    val startMinute: Int,
    val endMinute: Int,
    val timeRange: String,
    val duration: String,
    val isCurrent: Boolean
)

data class FlowGap(
    val startMinute: Int,
    val endMinute: Int,
    val durationMs: Long
)

data class DashboardInsight(
    val title: String,
    val message: String,
    val icon: String
)
