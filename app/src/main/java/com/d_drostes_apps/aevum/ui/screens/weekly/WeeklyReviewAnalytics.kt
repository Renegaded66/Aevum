package com.d_drostes_apps.aevum.ui.screens.weekly

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.d_drostes_apps.aevum.R
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
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val WEEK_MS = 7 * DAY_MS

object WeeklyReviewAnalytics {
    fun build(
        context: Context? = null,
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
        val distribution = buildDistribution(context, current, categoryMap)
        val days = buildDays(context, current, weekStartDate, zoneId, categoryMap)
        val changes = buildChanges(context, current, previous, categoryMap)
        val highlights = buildHighlights(context, current, days, categoryMap, typeMap)
        val patterns = buildPatterns(context, days, distribution, changes, current, categoryMap)
        val pendingCount = candidates.count { candidate ->
            candidate.status == "PENDING" && candidate.startAt < weekEnd && candidate.endAt > weekStart
        }

        return WeeklyReviewUiState(
            heroTitle = str(context, R.string.weekly_hero_title),
            narrative = buildNarrative(context, totalMs, distribution, changes, days),
            weekStart = weekStartDate,
            weekLabel = "${TimeFormatting.formatDate(weekStartDate)} – ${TimeFormatting.formatDate(weekEndDate.minusDays(1))}",
            hasData = totalMs > 0,
            emptyTitle = if (totalMs <= 0) str(context, R.string.weekly_empty_title) else "",
            emptyMessage = if (totalMs <= 0) str(context, R.string.weekly_empty_message) else "",
            days = days,
            timeDistribution = distribution,
            changes = changes,
            highlights = highlights,
            patterns = patterns,
            openTimeMs = (WEEK_MS - totalMs).coerceAtLeast(0L),
            pendingReviewCount = pendingCount,
            closingText = listOf(
                str(context, R.string.weekly_closing_1),
                str(context, R.string.weekly_closing_2),
                str(context, R.string.weekly_closing_3)
            )[(days.count { it.totalMs > 0 } + distribution.size) % 3]
        )
    }

    private fun buildDistribution(
        context: Context?,
        sessions: List<WeeklyClippedSession>,
        categoryMap: Map<String, Category>
    ): List<TimeDistributionSlice> {
        val total = sessions.sumOf { it.durationMs }.coerceAtLeast(1L)
        return sessions.groupBy { it.categoryId ?: "unknown" }
            .map { (id, values) ->
                val duration = values.sumOf { it.durationMs }
                TimeDistributionSlice(
                    id = id,
                    label = categoryMap[id]?.name ?: str(context, R.string.common_other),
                    color = InsightsAnalytics.categoryColor(id),
                    durationMs = duration,
                    percent = percent(duration, total)
                )
            }
            .sortedByDescending { it.durationMs }
    }

    private fun buildDays(
        context: Context?,
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
                label = dayLabel(context, day.dayOfWeek),
                topCategoryLabel = if ((top?.value ?: 0L) > 0L) categoryMap[categoryId]?.name ?: str(context, R.string.common_other) else str(context, R.string.common_still_open),
                totalMs = totals.getValue(day),
                color = if ((top?.value ?: 0L) > 0L) InsightsAnalytics.categoryColor(categoryId) else AevumCategoryColors.unknown,
                intensity = totals.getValue(day).toFloat() / max.toFloat()
            )
        }
    }

    private fun buildChanges(
        context: Context?,
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
                    label = categoryMap[id]?.name ?: str(context, R.string.common_other),
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
        context: Context?,
        sessions: List<WeeklyClippedSession>,
        days: List<WeeklyDaySummary>,
        categoryMap: Map<String, Category>,
        typeMap: Map<String, ActivityType>
    ): List<WeeklyHighlight> {
        if (sessions.isEmpty()) return emptyList()
        val highlights = mutableListOf<WeeklyHighlight>()
        sessions.maxByOrNull { it.durationMs }?.let { longest ->
            highlights += WeeklyHighlight(
                title = str(context, R.string.weekly_highlight_longest_activity),
                value = str(context, R.string.weekly_highlight_value, typeMap[longest.activityTypeId]?.name ?: longest.title, TimeFormatting.formatDuration(longest.durationMs)),
                tone = InsightsAnalytics.categoryColor(longest.categoryId ?: "unknown")
            )
        }
        days.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0L }?.let { day ->
            highlights += WeeklyHighlight(
                title = str(context, R.string.weekly_highlight_most_active_day),
                value = str(context, R.string.weekly_highlight_most_active_day_value, day.label, TimeFormatting.formatDuration(day.totalMs)),
                tone = day.color
            )
        }
        val balanced = days.filter { it.totalMs > 0L }.minByOrNull { day -> abs(day.totalMs - sessions.sumOf { it.durationMs } / days.count { it.totalMs > 0L }.coerceAtLeast(1)) }
        balanced?.let { day ->
            highlights += WeeklyHighlight(
                title = str(context, R.string.weekly_highlight_balanced_day),
                value = str(context, R.string.weekly_highlight_value, day.label, day.topCategoryLabel),
                tone = day.color
            )
        }
        sessions.filter { isLeisure(it.categoryId, categoryMap) }.maxByOrNull { it.durationMs }?.let { leisure ->
            highlights += WeeklyHighlight(
                title = str(context, R.string.weekly_highlight_longest_leisure),
                value = str(context, R.string.weekly_highlight_value, leisure.title, TimeFormatting.formatDuration(leisure.durationMs)),
                tone = InsightsAnalytics.categoryColor(leisure.categoryId ?: "leisure")
            )
        }
        sessions.filter { isWork(it.categoryId, categoryMap) }.maxByOrNull { it.durationMs }?.let { work ->
            highlights += WeeklyHighlight(
                title = str(context, R.string.weekly_highlight_longest_work),
                value = str(context, R.string.weekly_highlight_value, work.title, TimeFormatting.formatDuration(work.durationMs)),
                tone = InsightsAnalytics.categoryColor(work.categoryId ?: "work")
            )
        }
        return highlights.distinctBy { it.title }.take(5)
    }

    private fun buildPatterns(
        context: Context?,
        days: List<WeeklyDaySummary>,
        distribution: List<TimeDistributionSlice>,
        changes: List<PeriodChange>,
        sessions: List<WeeklyClippedSession>,
        categoryMap: Map<String, Category>
    ): List<InsightCard> {
        if (sessions.isEmpty()) return emptyList()
        val cards = mutableListOf<InsightCard>()
        days.maxByOrNull { it.totalMs }?.takeIf { it.totalMs > 0L }?.let { day ->
            cards += InsightCard(str(context, R.string.weekly_pattern_strongest_day), str(context, R.string.weekly_pattern_strongest_day_message, day.label), "◷")
        }
        val weekendMs = days.filter { it.date.dayOfWeek == DayOfWeek.SATURDAY || it.date.dayOfWeek == DayOfWeek.SUNDAY }.sumOf { it.totalMs }
        val weekdayAvg = days.filter { it.date.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }.map { it.totalMs }.average().takeIf { !it.isNaN() } ?: 0.0
        if (weekendMs > weekdayAvg * 2) {
            cards += InsightCard(str(context, R.string.weekly_pattern_weekend), str(context, R.string.weekly_pattern_weekend_message), "◇")
        }
        val sportDays = sessions.filter { isMovement(it.categoryId, categoryMap) }
            .map { it.date }
            .distinct()
            .count()
        if (sportDays >= 3) {
            cards += InsightCard(str(context, R.string.weekly_pattern_movement), str(context, R.string.weekly_pattern_movement_message), "✦")
        }
        changes.firstOrNull()?.let { change ->
            val message = if (change.deltaMs > 0) {
                str(context, R.string.weekly_pattern_change_more_message, change.label)
            } else {
                str(context, R.string.weekly_pattern_change_less_message, change.label)
            }
            cards += InsightCard(str(context, R.string.weekly_pattern_change), message, "↕")
        }
        if (distribution.size >= 3) {
            cards += InsightCard(str(context, R.string.weekly_pattern_variety), str(context, R.string.weekly_pattern_variety_message), "☷")
        }
        return cards.distinctBy { it.title }.take(4)
    }

    private fun buildNarrative(
        context: Context?,
        totalMs: Long,
        distribution: List<TimeDistributionSlice>,
        changes: List<PeriodChange>,
        days: List<WeeklyDaySummary>
    ): String {
        if (totalMs <= 0L) return str(context, R.string.weekly_narrative_empty)
        val top = distribution.firstOrNull()
        val second = distribution.drop(1).firstOrNull()
        val activeDays = days.count { it.totalMs > 0L }
        val change = changes.firstOrNull()
        return when {
            top != null && second != null -> str(context, R.string.weekly_narrative_top_two, top.label, second.label.lowercase())
            change != null && change.deltaMs > 0L -> str(context, R.string.weekly_narrative_change, change.label.lowercase())
            activeDays >= 5 -> str(context, R.string.weekly_narrative_active_days)
            top != null -> str(context, R.string.weekly_narrative_top, top.label)
            else -> str(context, R.string.weekly_narrative_fallback)
        }
    }

    private fun dayLabel(context: Context?, dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> str(context, R.string.common_monday)
        DayOfWeek.TUESDAY -> str(context, R.string.common_tuesday)
        DayOfWeek.WEDNESDAY -> str(context, R.string.common_wednesday)
        DayOfWeek.THURSDAY -> str(context, R.string.common_thursday)
        DayOfWeek.FRIDAY -> str(context, R.string.common_friday)
        DayOfWeek.SATURDAY -> str(context, R.string.common_saturday)
        DayOfWeek.SUNDAY -> str(context, R.string.common_sunday)
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
    val heroTitle: String = "",
    val narrative: String = "",
    val weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val weekLabel: String = "",
    val hasData: Boolean = false,
    val days: List<WeeklyDaySummary> = emptyList(),
    val timeDistribution: List<TimeDistributionSlice> = emptyList(),
    val changes: List<PeriodChange> = emptyList(),
    val highlights: List<WeeklyHighlight> = emptyList(),
    val patterns: List<InsightCard> = emptyList(),
    val openTimeMs: Long = WEEK_MS,
    val pendingReviewCount: Int = 0,
    val closingText: String = "",
    val emptyTitle: String = "",
    val emptyMessage: String = ""
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

private fun str(context: android.content.Context?, key: Int, vararg args: Any): String =
    context?.getString(key, *args) ?: String.format(fallback(key), *args)

private fun fallback(key: Int): String = when (key) {
    R.string.common_today -> "Heute"
    R.string.common_work -> "Arbeit"
    R.string.common_recovery -> "Erholung"
    R.string.common_movement -> "Bewegung"
    R.string.common_digital -> "Digital"
    R.string.common_social -> "Soziales"
    R.string.common_other -> "Sonstiges"
    R.string.common_allowance -> "Pauschale"
    R.string.common_monday -> "Mo"
    R.string.common_tuesday -> "Di"
    R.string.common_wednesday -> "Mi"
    R.string.common_thursday -> "Do"
    R.string.common_friday -> "Fr"
    R.string.common_saturday -> "Sa"
    R.string.common_sunday -> "So"
    R.string.common_still_open -> "Noch offen"
    R.string.insights_allowance_label -> "%1\$s (Pauschale)"
    R.string.insights_card_digital_time_message -> "Deine Digitalzeit ist leicht gesunken."
    R.string.insights_card_digital_time_title -> "Digitalzeit"
    R.string.insights_card_largest_block_month -> "%1\$s war diesen Monat dein größter Bereich."
    R.string.insights_card_largest_block_title -> "Größter Zeitblock"
    R.string.insights_card_largest_block_today -> "%1\$s war heute dein größter Bereich."
    R.string.insights_card_largest_block_week -> "%1\$s war diese Woche dein größter Bereich."
    R.string.insights_card_more_visible_message -> "%1\$s ist gegenüber der Vorperiode gestiegen."
    R.string.insights_card_more_visible_title -> "Mehr sichtbar"
    R.string.insights_card_rhythm_message -> "Mehrere Tage dieser Woche enthalten bereits erfasste Zeit."
    R.string.insights_card_rhythm_title -> "Rhythmus"
    R.string.insights_card_variety_message -> "Deine Zeit verteilt sich auf mehrere Lebensbereiche."
    R.string.insights_card_variety_title -> "Abwechslung"
    R.string.insights_period_this_month -> "Dieser Monat"
    R.string.insights_period_this_week -> "Diese Woche"
    R.string.insights_summary_change -> " Die größte Veränderung liegt bei %1\$s."
    R.string.insights_summary_empty -> "Noch nicht genug Daten. Sobald du Zeitblöcke erfasst, entstehen hier ruhige Muster."
    R.string.insights_summary_month -> "Dieser Monat prägt vor allem %1\$s deine erfasste Zeit."
    R.string.insights_summary_multiple_areas -> "mehrere Bereiche"
    R.string.insights_summary_today -> "Heute prägt vor allem %1\$s deine erfasste Zeit."
    R.string.insights_summary_week -> "Diese Woche prägt vor allem %1\$s deine erfasste Zeit."
    R.string.weekly_closing_1 -> "Jede Woche erzählt ihre eigene Geschichte."
    R.string.weekly_closing_2 -> "Auch kleine Veränderungen werden mit der Zeit sichtbar."
    R.string.weekly_closing_3 -> "Was sichtbar wird, lässt sich bewusster gestalten."
    R.string.weekly_hero_title -> "Deine Woche"
    R.string.weekly_empty_title -> "Noch keine Woche sichtbar."
    R.string.weekly_empty_message -> "Erfasse ein paar Aktivitäten. Danach erzählt dir Aevum hier ruhig, welche Muster, Highlights und offenen Zeiten in deiner Woche sichtbar werden."
    R.string.weekly_highlight_balanced_day -> "Ausgeglichenster Tag"
    R.string.weekly_highlight_longest_activity -> "Längste Aktivität"
    R.string.weekly_highlight_longest_leisure -> "Längste Freizeit"
    R.string.weekly_highlight_longest_work -> "Längster Arbeitsblock"
    R.string.weekly_highlight_most_active_day -> "Aktivster Tag"
    R.string.weekly_highlight_most_active_day_value -> "%1\$s · %2\$s sichtbar"
    R.string.weekly_highlight_value -> "%1\$s · %2\$s"
    R.string.weekly_narrative_active_days -> "Diese Woche war über mehrere Tage hinweg gut sichtbar und abwechslungsreich."
    R.string.weekly_narrative_change -> "Diese Woche ist %1\$s stärker sichtbar geworden als in der Vorwoche."
    R.string.weekly_narrative_empty -> "Sobald du einige Zeitblöcke erfasst hast, entsteht hier ein ruhiger Wochenrückblick."
    R.string.weekly_narrative_fallback -> "Diese Woche beginnt, ihre eigene Geschichte zu erzählen."
    R.string.weekly_narrative_top -> "%1\$s war diese Woche der prägende Zeitbereich."
    R.string.weekly_narrative_top_two -> "Du hast diese Woche viel Zeit in %1\$s investiert und auch %2\$s sichtbar gemacht."
    R.string.weekly_pattern_change -> "Veränderung"
    R.string.weekly_pattern_change_less_message -> "%1\$s war gegenüber der Vorwoche sichtbar weniger vertreten."
    R.string.weekly_pattern_change_more_message -> "%1\$s war gegenüber der Vorwoche sichtbar mehr vertreten."
    R.string.weekly_pattern_movement -> "Bewegung"
    R.string.weekly_pattern_movement_message -> "Bewegung war über mehrere Tage der Woche verteilt."
    R.string.weekly_pattern_strongest_day -> "Stärkster Tag"
    R.string.weekly_pattern_strongest_day_message -> "%1\$s war der sichtbarste Tag deiner Woche."
    R.string.weekly_pattern_variety -> "Abwechslung"
    R.string.weekly_pattern_variety_message -> "Diese Woche verteilt sich auf mehrere Lebensbereiche."
    R.string.weekly_pattern_weekend -> "Wochenende"
    R.string.weekly_pattern_weekend_message -> "Am Wochenende war deutlich mehr freie Zeit sichtbar."
    else -> ""
}
