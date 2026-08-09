package com.d_drostes_apps.aevum.ui.screens.weekly

import androidx.compose.ui.graphics.Color
import com.d_drostes_apps.aevum.data.model.ActivityCandidate
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.domain.time.TimeFormatting
import com.d_drostes_apps.aevum.ui.screens.insights.InsightCard
import com.d_drostes_apps.aevum.ui.screens.insights.PeriodChange
import com.d_drostes_apps.aevum.ui.screens.insights.TimeDistributionSlice
import com.d_drostes_apps.aevum.ui.screens.insights.InsightsAnalytics
import com.d_drostes_apps.aevum.ui.theme.AevumCategoryColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val WEEK_MS = 7 * DAY_MS

object WeeklyReviewAnalytics {
    fun build(
        sessions: List<ActivitySession>,
        candidates: List<ActivityCandidate>,
        categories: List<Category>,
        activityTypes: List<ActivityType>,
        anchorDate: LocalDate,
        zoneId: ZoneId
    ): WeeklyReviewUiState {
        val weekStartDate = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEndDate = weekStartDate.plusDays(7)
        val previousStartDate = weekStartDate.minusWeeks(1)
        val weekStart = weekStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val weekEnd = weekEndDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val previousStart = previousStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val previousEnd = weekStart
        val categoryMap = categories.associateBy { it.id }
        val typeMap = activityTypes.associateBy { it.id }
        val active = sessions.filter { it.deletedAt == null }
        val current = active.clippedTo(weekStart, weekEnd, zoneId)
        val previous = active.clippedTo(previousStart, previousEnd, zoneId)
        val totalMs = current.sumOf { it.durationMs }
        val distribution = buildDistribution(current, categoryMap)
        val days = buildDays(current, weekStartDate, zoneId, categoryMap)
        val changes = buildChanges(current, previous, categoryMap)
        val highlights = buildHighlights(current, days, categoryMap, typeMap)
        val patterns = buildPatterns(days, distribution, changes, current, categoryMap)
        val pendingCount = candidates.count { candidate ->
            candidate.status == "PENDING" && candidate.startAt < weekEnd && candidate.endAt > weekStart
        }

        return WeeklyReviewUiState(
            heroTitle = "Deine Woche",
            narrative = buildNarrative(totalMs, distribution, changes, days),
            weekStart = weekStartDate,
            weekLabel = "${TimeFormatting.formatDate(weekStartDate)} – ${TimeFormatting.formatDate(weekEndDate.minusDays(1))}",
            hasData = totalMs > 0,
            days = days,
            timeDistribution = distribution,
            changes = changes,
            highlights = highlights,
            patterns = patterns,
            openTimeMs = (WEEK_MS - totalMs).coerceAtLeast(0L),
            pendingReviewCount = pendingCount,
            closingText = listOf(
                "Jede Woche erzählt ihre eigene Geschichte.",
                "Auch kleine Veränderungen werden mit der Zeit sichtbar.",
                "Was sichtbar wird, lässt sich bewusster gestalten."
            )[(days.count { it.totalMs > 0 } + distribution.size) % 3]
        )
    }

    private fun buildDistribution(
        sessions: List<WeeklyClippedSession>,
        categoryMap: Map<String, Category>
    ): List<TimeDistributionSlice> {
        val total = sessions.sumOf { it.durationMs }.coerceAtLeast(1L)
        return sessions.groupBy { it.categoryId ?: "unknown" }
            .map { (id, values) ->
                val duration = values.sumOf { it.durationMs }
                TimeDistributionSlice(
                    id = id,
                    label = categoryMap[id]?.name ?: "Sonstiges",
                    color = InsightsAnalytics.categoryColor(id),
                    durationMs = duration,
                    percent = percent(duration, total)
                )
            }
            .sortedByDescending { it.durationMs }
    }

    private fun buildDays(
        sessions: List<WeeklyClippedSession>,
        weekStartDate: LocalDate,
        zoneId: ZoneId,
        categoryMap: Map<String, Category>
    ): List<WeeklyDaySummary> {
        val days = (0..6).map { weekStartDate.plusDays(it.toLong()) }
        val totals = days.associateWith { day ->
            val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            sessions.filter { it.startAt < end && it.endAt > start }.map { it.clip(start, end) }.sumOf { it.durationMs }
        }
        val max = totals.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        return days.map { day ->
            val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val clipped = sessions.filter { it.startAt < end && it.endAt > start }.map { it.clip(start, end) }
            val top = clipped.groupBy { it.categoryId ?: "unknown" }
                .mapValues { it.value.sumOf { session -> session.durationMs } }
                .maxByOrNull { it.value }
            val categoryId = top?.key ?: "unknown"
            WeeklyDaySummary(
                date = day,
                label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).replace(".", ""),
                topCategoryLabel = if ((top?.value ?: 0L) > 0L) categoryMap[categoryId]?.name ?: "Sonstiges" else "Noch offen",
                totalMs = totals.getValue(day),
                color = if ((top?.value ?: 0L) > 0L) InsightsAnalytics.categoryColor(categoryId) else AevumCategoryColors.unknown,
                intensity = totals.getValue(day).toFloat() / max.toFloat()
            )
        }
    }

    private fun buildChanges(
        current: List<WeeklyClippedSession>,
        previous: List<WeeklyClippedSession>,
        categoryMap: Map<String, Category>
    ): List<PeriodChange> {
        if (previous.isEmpty()) return emptyList()
        val currentByCategory = current.groupBy { it.categoryId ?: "unknown" }.mapValues { it.value.sumOf { session -> session.durationMs } }
        val previousByCategory = previous.groupBy { it.categoryId ?: "unknown" }.mapValues { it.value.sumOf { session -> session.durationMs } }
        return (currentByCategory.keys + previousByCategory.keys)
            .mapNotNull { id ->
                val currentMs = currentByCategory[id] ?: 0L
                val previousMs = previousByCategory[id] ?: 0L
                val delta = currentMs - previousMs
                if (currentMs == 0L && previousMs == 0L) null else PeriodChange(
                    id = id,
                    label = categoryMap[id]?.name ?: "Sonstiges",
                    color = InsightsAnalytics.categoryColor(id),
                    currentMs = currentMs,
                    previousMs = previousMs,
                    deltaMs = delta,
                    percentDelta = if (previousMs > 0) ((delta.toFloat() / previousMs.toFloat()) * 100f).roundToInt() else null
                )
            }
            .filter { abs(it.deltaMs) >= 15 * 60_000L }
            .sortedByDescending { abs(it.deltaMs) }
            .take(4)
    }

    private fun buildHighlights(
        sessions: List<WeeklyClippedSession>,
        days: List<WeeklyDaySummary>,
        categoryMap: Map<String, Category>,
        typeMap: Map<String, ActivityType>
    ): List<WeeklyHighlight> {
        if (sessions.isEmpty()) return emptyList()
        val highlights = mutableListOf<WeeklyHighlight>()
        sessions.maxByOrNull { it.durationMs }?.let { longest ->
            highlights += WeeklyHighlight(
                title = "Längste Aktivität",
                value = "${typeMap[longest.activityTypeId]?.name ?: longest.title} · ${TimeFormatting.formatDuration(longest.durationMs)}",
                tone = InsightsAnalytics.categoryColor(longest.categoryId ?: "unknown")
            )
        }
        days.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0L }?.let { day ->
            highlights += WeeklyHighlight(
                title = "Aktivster Tag",
                value = "${day.label} · ${TimeFormatting.formatDuration(day.totalMs)} sichtbar",
                tone = day.color
            )
        }
        val balanced = days.filter { it.totalMs > 0L }.minByOrNull { day -> abs(day.totalMs - sessions.sumOf { it.durationMs } / days.count { it.totalMs > 0L }.coerceAtLeast(1)) }
        balanced?.let { day ->
            highlights += WeeklyHighlight(
                title = "Ausgeglichenster Tag",
                value = "${day.label} · ${day.topCategoryLabel}",
                tone = day.color
            )
        }
        sessions.filter { isLeisure(it.categoryId, categoryMap) }.maxByOrNull { it.durationMs }?.let { leisure ->
            highlights += WeeklyHighlight(
                title = "Längste Freizeit",
                value = "${leisure.title} · ${TimeFormatting.formatDuration(leisure.durationMs)}",
                tone = InsightsAnalytics.categoryColor(leisure.categoryId ?: "leisure")
            )
        }
        sessions.filter { isWork(it.categoryId, categoryMap) }.maxByOrNull { it.durationMs }?.let { work ->
            highlights += WeeklyHighlight(
                title = "Längster Arbeitsblock",
                value = "${work.title} · ${TimeFormatting.formatDuration(work.durationMs)}",
                tone = InsightsAnalytics.categoryColor(work.categoryId ?: "work")
            )
        }
        return highlights.distinctBy { it.title }.take(5)
    }

    private fun buildPatterns(
        days: List<WeeklyDaySummary>,
        distribution: List<TimeDistributionSlice>,
        changes: List<PeriodChange>,
        sessions: List<WeeklyClippedSession>,
        categoryMap: Map<String, Category>
    ): List<InsightCard> {
        if (sessions.isEmpty()) return emptyList()
        val cards = mutableListOf<InsightCard>()
        days.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0L }?.let { day ->
            cards += InsightCard("Stärkster Tag", "${day.label} war der sichtbarste Tag deiner Woche.", "◷")
        }
        val weekendMs = days.filter { it.date.dayOfWeek == DayOfWeek.SATURDAY || it.date.dayOfWeek == DayOfWeek.SUNDAY }.sumOf { it.totalMs }
        val weekdayAvg = days.filter { it.date.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }.map { it.totalMs }.average().takeIf { !it.isNaN() } ?: 0.0
        if (weekendMs > weekdayAvg * 2) {
            cards += InsightCard("Wochenende", "Am Wochenende war deutlich mehr freie Zeit sichtbar.", "◇")
        }
        val sportDays = sessions.filter { isMovement(it.categoryId, categoryMap) }
            .map { it.date }
            .distinct()
            .count()
        if (sportDays >= 3) {
            cards += InsightCard("Bewegung", "Bewegung war über mehrere Tage der Woche verteilt.", "✦")
        }
        changes.firstOrNull()?.let { change ->
            val direction = if (change.deltaMs > 0) "mehr" else "weniger"
            cards += InsightCard("Veränderung", "${change.label} war gegenüber der Vorwoche sichtbar $direction vertreten.", "↕")
        }
        if (distribution.size >= 3) {
            cards += InsightCard("Abwechslung", "Diese Woche verteilt sich auf mehrere Lebensbereiche.", "☷")
        }
        return cards.distinctBy { it.title }.take(4)
    }

    private fun buildNarrative(
        totalMs: Long,
        distribution: List<TimeDistributionSlice>,
        changes: List<PeriodChange>,
        days: List<WeeklyDaySummary>
    ): String {
        if (totalMs <= 0L) return "Sobald du einige Zeitblöcke erfasst hast, entsteht hier ein ruhiger Wochenrückblick."
        val top = distribution.firstOrNull()
        val second = distribution.drop(1).firstOrNull()
        val activeDays = days.count { it.totalMs > 0L }
        val change = changes.firstOrNull()
        return when {
            top != null && second != null -> "Du hast diese Woche viel Zeit in ${top.label} investiert und auch ${second.label.lowercase()} sichtbar gemacht."
            change != null && change.deltaMs > 0L -> "Diese Woche ist ${change.label.lowercase()} stärker sichtbar geworden als in der Vorwoche."
            activeDays >= 5 -> "Diese Woche war über mehrere Tage hinweg gut sichtbar und abwechslungsreich."
            top != null -> "${top.label} war diese Woche der prägende Zeitbereich."
            else -> "Diese Woche beginnt, ihre eigene Geschichte zu erzählen."
        }
    }

    private fun isWork(categoryId: String?, categoryMap: Map<String, Category>): Boolean {
        val id = categoryId.orEmpty().lowercase()
        val name = categoryMap[categoryId]?.name?.lowercase().orEmpty()
        return id.contains("work") || id.contains("learning") || name.contains("arbeit") || name.contains("lernen")
    }

    private fun isLeisure(categoryId: String?, categoryMap: Map<String, Category>): Boolean {
        val id = categoryId.orEmpty().lowercase()
        val name = categoryMap[categoryId]?.name?.lowercase().orEmpty()
        return id.contains("leisure") || id.contains("sleep") || name.contains("freizeit") || name.contains("erholung") || name.contains("schlaf")
    }

    private fun isMovement(categoryId: String?, categoryMap: Map<String, Category>): Boolean {
        val id = categoryId.orEmpty().lowercase()
        val name = categoryMap[categoryId]?.name?.lowercase().orEmpty()
        return id.contains("sport") || id.contains("fitness") || name.contains("sport") || name.contains("bewegung")
    }

    private fun List<ActivitySession>.clippedTo(start: Long, end: Long, zoneId: ZoneId): List<WeeklyClippedSession> = mapNotNull { session ->
        val sessionEnd = session.endAt ?: end
        val clippedStart = session.startAt.coerceAtLeast(start)
        val clippedEnd = sessionEnd.coerceAtMost(end)
        val duration = clippedEnd - clippedStart
        if (duration <= 0) null else WeeklyClippedSession(
            id = session.id,
            title = session.title,
            categoryId = session.categoryId,
            activityTypeId = session.activityTypeId,
            startAt = clippedStart,
            endAt = clippedEnd,
            durationMs = duration,
            date = TimeFormatting.millisToLocalDate(clippedStart, zoneId)
        )
    }

    private fun WeeklyClippedSession.clip(start: Long, end: Long): WeeklyClippedSession {
        val clippedStart = startAt.coerceAtLeast(start)
        val clippedEnd = endAt.coerceAtMost(end)
        return copy(startAt = clippedStart, endAt = clippedEnd, durationMs = (clippedEnd - clippedStart).coerceAtLeast(0L))
    }

    private fun percent(value: Long, total: Long): Int = ((value.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
}

data class WeeklyReviewUiState(
    val heroTitle: String = "Deine Woche",
    val narrative: String = "Sobald du einige Zeitblöcke erfasst hast, entsteht hier ein ruhiger Wochenrückblick.",
    val weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val weekLabel: String = "Diese Woche",
    val hasData: Boolean = false,
    val days: List<WeeklyDaySummary> = emptyList(),
    val timeDistribution: List<TimeDistributionSlice> = emptyList(),
    val changes: List<PeriodChange> = emptyList(),
    val highlights: List<WeeklyHighlight> = emptyList(),
    val patterns: List<InsightCard> = emptyList(),
    val openTimeMs: Long = WEEK_MS,
    val pendingReviewCount: Int = 0,
    val closingText: String = "Jede Woche erzählt ihre eigene Geschichte.",
    val emptyTitle: String = "Noch keine Woche sichtbar.",
    val emptyMessage: String = "Wenn du ein paar Aktivitäten erfasst hast, zeigt Aevum hier einen ruhigen Rückblick mit Zeitverteilung, Highlights und Wochenmustern."
)

data class WeeklyDaySummary(
    val date: LocalDate,
    val label: String,
    val topCategoryLabel: String,
    val totalMs: Long,
    val color: Color,
    val intensity: Float
)

data class WeeklyHighlight(
    val title: String,
    val value: String,
    val tone: Color
)

private data class WeeklyClippedSession(
    val id: String,
    val title: String,
    val categoryId: String?,
    val activityTypeId: String?,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long,
    val date: LocalDate
)
