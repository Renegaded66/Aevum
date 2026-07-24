package de.devondroste.aevum.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.app.Application
import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.AppUsageSample
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.data.repository.ActivityCandidateRepository
import de.devondroste.aevum.data.repository.ActivityRepository
import de.devondroste.aevum.data.repository.ActivityTypeRepository
import de.devondroste.aevum.data.repository.CategoryRepository
import de.devondroste.aevum.data.repository.GoalRepository
import de.devondroste.aevum.domain.analytics.GoalProgressAnalytics
import de.devondroste.aevum.domain.digital.UsageStatsCollector
import de.devondroste.aevum.domain.liveactivity.LiveActivityManager
import de.devondroste.aevum.domain.liveactivity.LiveActivityService
import de.devondroste.aevum.domain.liveactivity.LiveActivityState
import de.devondroste.aevum.domain.seed.EnsureDefaultDataUseCase
import de.devondroste.aevum.domain.time.TimeFormatting
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.screens.goals.toGoalWithProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val application: Application,
    activityRepository: ActivityRepository,
    categoryRepository: CategoryRepository,
    candidateRepository: ActivityCandidateRepository,
    private val goalRepository: GoalRepository,
    private val activityTypeRepository: ActivityTypeRepository,
    private val ensureDefaultData: EnsureDefaultDataUseCase,
    val liveActivityManager: LiveActivityManager,
    private val usageStatsCollector: UsageStatsCollector
) : ViewModel() {
    private val zoneId = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val start = TimeFormatting.startOfDayMillis(today, zoneId)
    private val end = TimeFormatting.endOfDayMillis(today, zoneId)

    init {
        // M12.0.2: Defensive Initialisierung — ensureDefaultData darf niemals
        // den Start des DashboardViewModels blockieren. Fehler werden geloggt,
        // die App läuft mit Default-Werten weiter.
        viewModelScope.launch {
            try {
                ensureDefaultData()
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "ensureDefaultData failed — continuing with defaults", e)
            }
        }
        // M13: Initial load of usage stats. Refetched on demand.
        viewModelScope.launch {
            try {
                refreshUsageStats()
            } catch (_: Exception) { /* noop */ }
        }
    }

    private val _topApps = MutableStateFlow<List<AppUsageSample>>(emptyList())
    val topApps: StateFlow<List<AppUsageSample>> = _topApps.asStateFlow()

    private val _usageStatsGranted = MutableStateFlow(false)
    val usageStatsGranted: StateFlow<Boolean> = _usageStatsGranted.asStateFlow()

    fun refreshUsageStats() {
        viewModelScope.launch {
            try {
                val granted = usageStatsCollector.hasPermission()
                _usageStatsGranted.value = granted
                if (granted) {
                    val top = usageStatsCollector.topAppsForDay(LocalDate.now(), limit = 5)
                    _topApps.value = top
                }
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "refreshUsageStats failed", e)
            }
        }
    }

    fun openUsageAccessSettings() {
        usageStatsCollector.openUsageAccessSettings()
    }

    // M9.1: Live Activity actions — Schnellstart per activityTypeId
    fun startLiveActivity(activityTypeId: String, note: String? = null, startedAt: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            liveActivityManager.start(activityTypeId, note = note, startedAt = startedAt)
            LiveActivityService.start(application)
        }
    }

    // M9.2: Toggle favorite for an activity type
    fun toggleFavorite(type: de.devondroste.aevum.data.model.ActivityType) {
        viewModelScope.launch {
            activityTypeRepository.setFavorite(type.id, !type.isFavorite)
        }
    }

    fun pauseLiveActivity() {
        viewModelScope.launch { liveActivityManager.pause() }
    }

    fun resumeLiveActivity() {
        viewModelScope.launch { liveActivityManager.resume() }
    }

    fun stopLiveActivity() {
        viewModelScope.launch {
            liveActivityManager.stop()
            LiveActivityService.stop(application)
        }
    }

    /** M12.1: Discard an auto-started live session — stop + soft-delete. */
    fun discardLiveActivity() {
        viewModelScope.launch {
            liveActivityManager.discardLiveSession()
            LiveActivityService.stop(application)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        activityRepository.getOverlappingRange(start, end),
        categoryRepository.getAll(),
        candidateRepository.getByStatus("PENDING"),
        goalRepository.getByStatus("ACTIVE"),
        activityTypeRepository.getAll(),
        // M10: today's sleep — used for the "Guten Morgen" summary card
        activityRepository.getByActivityTypeAndDateRange("sleep", start, end)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val sessions = values[0] as List<ActivitySession>
        val categories = values[1] as List<de.devondroste.aevum.data.model.Category>
        val candidates = values[2] as List<de.devondroste.aevum.data.model.ActivityCandidate>
        val activeGoals = values[3] as List<de.devondroste.aevum.data.model.Goal>
        val types = values[4] as List<de.devondroste.aevum.data.model.ActivityType>
        val sleepSessions = values[5] as List<ActivitySession>
        buildState(
            sessions = sessions,
            categories = categories,
            candidates = candidates.filter { it.startAt < end && it.endAt > start },
            activeGoals = activeGoals,
            typeMap = types.associateBy { it.id },
            allTypes = types,
            sleepSessions = sleepSessions
        )
    }
        // M12.0.2: Defensive Programmierung — keine Exception darf bis zur UI
        // propagieren. Wenn ein der 6 Flows fehlschlägt (z.B. Room-Schema-Mismatch,
        // DB-Corruption, unzulässige Query), wird der Fehler geloggt und der
        // Default-State (leeres DashboardUiState) beibehalten. Die App bleibt
        // stabil, das Dashboard ist sichtbar — nur eben ohne Daten.
        .catch { e ->
            Log.e("DashboardViewModel", "uiState combine() failed — emitting default state", e)
            emit(DashboardUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun buildState(
        sessions: List<ActivitySession>,
        categories: List<de.devondroste.aevum.data.model.Category>,
        candidates: List<de.devondroste.aevum.data.model.ActivityCandidate>,
        activeGoals: List<de.devondroste.aevum.data.model.Goal>,
        typeMap: Map<String, de.devondroste.aevum.data.model.ActivityType>,
        allTypes: List<de.devondroste.aevum.data.model.ActivityType> = emptyList(),
        sleepSessions: List<ActivitySession> = emptyList()
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
        // M12.2: SourceType wird zur Anzeige eines dezenten "Auto"-Hinweises genutzt.
        // Wenn die aktuelle Session automatisch gestartet wurde (GEOFENCE_AUTO,
        // HEALTH_SLEEP_AUTO, ACTIVITY_RECOGNITION_AUTO), wird der Source-Hinweis
        // im Dashboard angepasst. Verwendet AUTO_SOURCES, um die zentrale Konstante
        // als Single Source of Truth zu nutzen.
        val current = activeSessions.filter { it.endAt == null }.maxByOrNull { it.startAt }
            ?: activeSessions.maxByOrNull { it.startAt }
        // M12.2: Map von Session-ID → sourceType, damit der Flow (der nur
        // ClippedSessions kennt) den Auto-Flag korrekt setzen kann.
        val sourceTypeById = activeSessions.associate { it.id to it.sourceType }
        val flow = clippedSessions.sortedBy { it.startAt }.map { session ->
            val startMinute = TimeFormatting.minutesOfDay(session.startAt, zoneId).coerceIn(0, 1440)
            val endMinute = TimeFormatting.minutesOfDay(session.endAt, zoneId).coerceIn(startMinute, 1440)
            val isAutoSession = sourceTypeById[session.id] in de.devondroste.aevum.ui.screens.timeline.AUTO_SOURCES
            DashboardFlowSegment(
                id = session.id,
                title = session.title,
                categoryName = categoryMap[session.categoryId]?.name ?: "Sonstiges",
                categoryId = session.categoryId ?: "unknown",
                startMinute = startMinute,
                endMinute = endMinute,
                timeRange = "${formatMinute(startMinute)}–${formatMinute(endMinute)}",
                duration = TimeFormatting.formatDuration(session.durationMs),
                isCurrent = session.isCurrent,
                isCandidate = false,
                // M12.2: Auto-Sessions werden im Dashboard als "Auto" markiert.
                isAuto = isAutoSession
            )
        }

        // M7: Candidate flow segments (semi-transparent in timeline)
        val candidateSegments = candidates.map { c ->
            val startMin = TimeFormatting.minutesOfDay(c.startAt, zoneId).coerceIn(0, 1440)
            val endMin = TimeFormatting.minutesOfDay(c.endAt, zoneId).coerceIn(startMin, 1440)
            DashboardFlowSegment(
                id = c.id,
                title = c.suggestedTitle,
                categoryName = categoryMap[c.suggestedCategoryId]?.name ?: "Sonstiges",
                categoryId = c.suggestedCategoryId ?: "unknown",
                startMinute = startMin,
                endMinute = endMin,
                timeRange = "${formatMinute(startMin)}–${formatMinute(endMin)}",
                duration = TimeFormatting.formatDuration(c.endAt - c.startAt),
                isCurrent = false,
                isCandidate = true
            )
        }

        // Combine and sort all segments
        val allSegments = (flow + candidateSegments).sortedBy { it.startMinute }
        val gaps = buildFlowGaps(allSegments)

        val timeline = allSegments.takeLast(4).map { segment ->
            DashboardTimelineRow(
                id = segment.id,
                time = "%02d:%02d".format(segment.startMinute / 60, segment.startMinute % 60),
                title = segment.title,
                categoryName = segment.categoryName,
                duration = segment.duration,
                source = when {
                    segment.isCandidate -> "Vorschlag"
                    segment.isCurrent -> "Jetzt"
                    else -> "Erfasst"
                },
                isCurrent = segment.isCurrent,
                isCandidate = segment.isCandidate
            )
        }
        val top = distribution.firstOrNull()
        val narrative = buildNarrative(totalMs, openMs, top, candidates.size, current)
        val insights = buildInsights(distribution, totalMs, openMs, candidates.size)

        // M7: Accepted today count
        val acceptedToday = candidates.count { it.status == "ACCEPTED" && it.resolvedAt?.let { it in start..end } == true }

        val activeSessionsList = sessions
        val goalProgressList = activeGoals.map { goal ->
            GoalProgressAnalytics.evaluateGoal(goal, activeSessionsList, today, zoneId, typeMap)
        }.sortedByDescending { it.progress }.take(3).map { it.toGoalWithProgress() }

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
            flowSegments = allSegments,
            flowGaps = gaps,
            currentMinute = currentMinute(now),
            insights = insights,
            topCategory = top?.label ?: "Noch offen",
            topCategoryDuration = top?.let { TimeFormatting.formatDuration(it.durationMs) } ?: "0m",
            hasData = activeSessions.isNotEmpty(),
            dayProgress = ((now - start).toFloat() / DAY_MS.toFloat()).coerceIn(0f, 1f),
            goalProgress = goalProgressList,
            // M7: Automation capture
            capturedTodayCount = activeSessions.size + candidates.size,
            candidateCount = candidates.size,
            acceptedTodayCount = acceptedToday,
            activityTypes = allTypes,
            // M11: fix sleepCandidateCount — bisher nie berechnet
            sleepCandidateCount = candidates.count { it.activityTypeId == "sleep" },
            // M10: today's sleep summary
            lastSleepSession = sleepSessions.maxByOrNull { it.endAt ?: it.startAt },
            lastSleepDurationMs = sleepSessions.maxByOrNull { it.endAt ?: it.startAt }
                ?.let { (it.endAt ?: now) - it.startAt }
                ?: 0L
        )
    }

    private fun currentMinute(now: Long) = TimeFormatting.minutesOfDay(now, zoneId).coerceIn(0, 1440)

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
        // M12.2: "läuft … (Auto)" statt "läuft …" für automatisch gestartete Sessions.
        val currentAuto = current?.sourceType in de.devondroste.aevum.ui.screens.timeline.AUTO_SOURCES
        val currentSuffix = if (currentAuto) " (Auto)" else ""
        val currentText = current?.let { " Gerade läuft: ${it.title}$currentSuffix." }.orEmpty()
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
    val dayProgress: Float = 0f,
    val goalProgress: List<GoalWithProgress> = emptyList(),
    // M7: Automation capture
    val capturedTodayCount: Int = 0,
    val candidateCount: Int = 0,
    val acceptedTodayCount: Int = 0,
    // M8: Sleep & Digital
    val sleepCandidateCount: Int = 0,
    val digitalScreenTimeMs: Long = 0L,
    val digitalScreenTimeFormatted: String = "0m",
    val digitalTopApp: String = "—",
    // M9: Activity types for live activity picker
    val activityTypes: List<de.devondroste.aevum.data.model.ActivityType> = emptyList(),
    // M10: today's sleep summary for the dashboard card
    val lastSleepSession: ActivitySession? = null,
    val lastSleepDurationMs: Long = 0L
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
    val isCurrent: Boolean,
    val isCandidate: Boolean = false
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
    val isCurrent: Boolean,
    val isCandidate: Boolean = false,
    // M12.2: Auto-Sessions tragen einen isAuto-Flag, damit der Dashboard-
    // Flow sie konsistent markieren kann (gleiche Konstante wie Timeline).
    val isAuto: Boolean = false
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
