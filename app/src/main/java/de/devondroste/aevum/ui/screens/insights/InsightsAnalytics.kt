package de.devondroste.aevum.ui.screens.insights

import androidx.compose.ui.graphics.Color
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.Category
import de.devondroste.aevum.ui.screens.goals.GoalWithProgress
import de.devondroste.aevum.ui.screens.habits.HabitWithProgress
import de.devondroste.aevum.ui.theme.AevumCategoryColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

enum class InsightPeriod(val label: String) {
    Today("Heute"),
    Week("Woche"),
    Month("Monat")
}

data class InsightsUiState(
    val selectedPeriod: InsightPeriod = InsightPeriod.Today,
    val periodLabel: String = "Heute",
    val summary: String = "Noch nicht genug Daten für klare Muster.",
    val startDate: LocalDate = LocalDate.now(),
    val timeDistribution: List<TimeDistributionSlice> = emptyList(),
    val changes: List<PeriodChange> = emptyList(),
    val topActivities: List<TopActivitySlice> = emptyList(),
    val balance: List<BalanceSlice> = emptyList(),
    val insightCards: List<InsightCard> = emptyList(),
    val weekHeatmap: WeekHeatmap = WeekHeatmap(),
    val hasData: Boolean = false,
    val selectedHeatmapDate: LocalDate? = null,
    val goalProgress: List<GoalWithProgress> = emptyList(),
    val habitProgress: List<HabitWithProgress> = emptyList()
)

data class TimeDistributionSlice(
    val id: String,
    val label: String,
    val color: Color,
    val durationMs: Long,
    val percent: Int
)

data class PeriodChange(
    val id: String,
    val label: String,
    val color: Color,
    val currentMs: Long,
    val previousMs: Long,
    val deltaMs: Long,
    val percentDelta: Int?
)

data class TopActivitySlice(
    val id: String,
    val label: String,
    val color: Color,
    val durationMs: Long,
    val percent: Int
)

data class BalanceSlice(
    val area: String,
    val color: Color,
    val durationMs: Long,
    val percent: Int
)

data class InsightCard(
    val title: String,
    val message: String,
    val icon: String
)

data class WeekHeatmap(
    val days: List<HeatmapDay> = emptyList(),
    val maxDurationMs: Long = 0L
)

data class HeatmapDay(
    val date: LocalDate,
    val label: String,
    val durationMs: Long,
    val intensity: Float
)

data class PeriodWindow(
    val start: Long,
    val end: Long,
    val previousStart: Long,
    val previousEnd: Long,
    val startDate: LocalDate,
    val label: String
)

object InsightsAnalytics {
    private const val HOUR = 60 * 60 * 1000L

    fun window(period: InsightPeriod, anchorDate: LocalDate, zoneId: ZoneId): PeriodWindow {
        val startDate = when (period) {
            InsightPeriod.Today -> anchorDate
            InsightPeriod.Week -> anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            InsightPeriod.Month -> anchorDate.withDayOfMonth(1)
        }
        val endDateExclusive = when (period) {
            InsightPeriod.Today -> startDate.plusDays(1)
            InsightPeriod.Week -> startDate.plusDays(7)
            InsightPeriod.Month -> startDate.plusMonths(1)
        }
        val previousStartDate = when (period) {
            InsightPeriod.Today -> startDate.minusDays(1)
            InsightPeriod.Week -> startDate.minusWeeks(1)
            InsightPeriod.Month -> startDate.minusMonths(1)
        }
        val previousEndDate = when (period) {
            InsightPeriod.Today -> startDate
            InsightPeriod.Week -> startDate
            InsightPeriod.Month -> startDate
        }
        return PeriodWindow(
            start = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            end = endDateExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            previousStart = previousStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            previousEnd = previousEndDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            startDate = startDate,
            label = when (period) {
                InsightPeriod.Today -> "Heute"
                InsightPeriod.Week -> "Diese Woche"
                InsightPeriod.Month -> "Dieser Monat"
            }
        )
    }

    fun build(
        sessions: List<ActivitySession>,
        categories: List<Category>,
        activityTypes: List<ActivityType>,
        selectedPeriod: InsightPeriod,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        goalProgress: List<GoalWithProgress> = emptyList(),
        habitProgress: List<HabitWithProgress> = emptyList()
    ): InsightsUiState {
        val window = window(selectedPeriod, anchorDate, zoneId)
        val categoryMap = categories.associateBy { it.id }
        val typeMap = activityTypes.associateBy { it.id }
        val active = sessions.filter { it.deletedAt == null }
        val current = active.clippedTo(window.start, window.end)
        val previous = active.clippedTo(window.previousStart, window.previousEnd)
        val totalMs = current.sumOf { it.durationMs }
        val distribution = buildDistribution(current, categoryMap)
        val topActivities = buildTopActivities(current, typeMap, categoryMap)
        val changes = buildChanges(current, previous, categoryMap)
        val balance = buildBalance(current, categoryMap)
        val heatmap = buildWeekHeatmap(active, anchorDate, zoneId)
        val insights = buildInsightCards(selectedPeriod, distribution, changes, topActivities, balance, heatmap, totalMs)
        return InsightsUiState(
            selectedPeriod = selectedPeriod,
            periodLabel = window.label,
            summary = buildSummary(selectedPeriod, totalMs, distribution, changes),
            startDate = window.startDate,
            timeDistribution = distribution,
            changes = changes,
            topActivities = topActivities,
            balance = balance,
            insightCards = insights,
            weekHeatmap = heatmap,
            hasData = totalMs > 0,
            goalProgress = goalProgress,
            habitProgress = habitProgress
        )
    }

    private fun buildDistribution(sessions: List<ClippedInsightSession>, categoryMap: Map<String, Category>): List<TimeDistributionSlice> {
        val total = sessions.sumOf { it.durationMs }.coerceAtLeast(1L)
        return sessions.groupBy { it.categoryId ?: "unknown" }
            .map { (id, values) ->
                val category = categoryMap[id]
                val duration = values.sumOf { it.durationMs }
                TimeDistributionSlice(
                    id = id,
                    label = category?.name ?: "Sonstiges",
                    color = categoryColor(id),
                    durationMs = duration,
                    percent = percent(duration, total)
                )
            }
            .sortedByDescending { it.durationMs }
    }

    private fun buildTopActivities(
        sessions: List<ClippedInsightSession>,
        typeMap: Map<String, ActivityType>,
        categoryMap: Map<String, Category>
    ): List<TopActivitySlice> {
        val total = sessions.sumOf { it.durationMs }.coerceAtLeast(1L)
        return sessions.groupBy { it.activityTypeId ?: it.title }
            .map { (id, values) ->
                val duration = values.sumOf { it.durationMs }
                val first = values.first()
                TopActivitySlice(
                    id = id,
                    label = typeMap[id]?.name ?: first.title,
                    color = categoryColor(first.categoryId ?: typeMap[id]?.defaultCategoryId ?: categoryMap.keys.firstOrNull().orEmpty()),
                    durationMs = duration,
                    percent = percent(duration, total)
                )
            }
            .sortedByDescending { it.durationMs }
            .take(5)
    }

    private fun buildChanges(
        current: List<ClippedInsightSession>,
        previous: List<ClippedInsightSession>,
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
                    color = categoryColor(id),
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

    private fun buildBalance(sessions: List<ClippedInsightSession>, categoryMap: Map<String, Category>): List<BalanceSlice> {
        val raw = linkedMapOf(
            "Arbeit" to 0L,
            "Erholung" to 0L,
            "Bewegung" to 0L,
            "Digital" to 0L,
            "Soziales" to 0L
        )
        sessions.forEach { session ->
            val name = categoryMap[session.categoryId]?.name?.lowercase().orEmpty()
            val id = session.categoryId.orEmpty().lowercase()
            val area = when {
                id.contains("work") || name.contains("arbeit") || name.contains("lernen") -> "Arbeit"
                id.contains("sleep") || id.contains("leisure") || id.contains("household") || name.contains("schlaf") || name.contains("erholung") || name.contains("freizeit") -> "Erholung"
                id.contains("sport") || id.contains("fitness") || name.contains("sport") || name.contains("bewegung") -> "Bewegung"
                id.contains("digital") || id.contains("smartphone") || name.contains("digital") || name.contains("smartphone") -> "Digital"
                id.contains("social") || id.contains("relationships") || name.contains("sozial") || name.contains("freunde") -> "Soziales"
                else -> "Erholung"
            }
            raw[area] = raw.getValue(area) + session.durationMs
        }
        val total = raw.values.sum().coerceAtLeast(1L)
        val colors = mapOf(
            "Arbeit" to AevumCategoryColors.work,
            "Erholung" to AevumCategoryColors.leisure,
            "Bewegung" to AevumCategoryColors.sport,
            "Digital" to AevumCategoryColors.smartphone,
            "Soziales" to AevumCategoryColors.relationships
        )
        return raw.map { (area, duration) -> BalanceSlice(area, colors.getValue(area), duration, percent(duration, total)) }
    }

    private fun buildWeekHeatmap(sessions: List<ActivitySession>, anchorDate: LocalDate, zoneId: ZoneId): WeekHeatmap {
        val monday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = (0..6).map { monday.plusDays(it.toLong()) }
        val dayDurations = days.associateWith { day ->
            val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            sessions.filter { it.deletedAt == null }.clippedTo(start, end).sumOf { it.durationMs }
        }
        val max = dayDurations.values.maxOrNull() ?: 0L
        return WeekHeatmap(
            days = days.map { day ->
                HeatmapDay(
                    date = day,
                    label = day.dayOfWeek.name.take(2).lowercase().replaceFirstChar { it.uppercase() },
                    durationMs = dayDurations.getValue(day),
                    intensity = if (max > 0) dayDurations.getValue(day).toFloat() / max.toFloat() else 0f
                )
            },
            maxDurationMs = max
        )
    }

    private fun buildInsightCards(
        period: InsightPeriod,
        distribution: List<TimeDistributionSlice>,
        changes: List<PeriodChange>,
        topActivities: List<TopActivitySlice>,
        balance: List<BalanceSlice>,
        heatmap: WeekHeatmap,
        totalMs: Long
    ): List<InsightCard> {
        if (totalMs <= 0) return emptyList()
        val cards = mutableListOf<InsightCard>()
        distribution.firstOrNull()?.let { top ->
            cards += InsightCard("Größter Zeitblock", "${top.label} war ${periodText(period)} dein größter Bereich.", "◷")
        }
        changes.firstOrNull { it.deltaMs > 0 }?.let { change ->
            cards += InsightCard("Mehr sichtbar", "${change.label} ist gegenüber der Vorperiode gestiegen.", "↗")
        }
        changes.firstOrNull { it.deltaMs < 0 && it.id.contains("digital", ignoreCase = true) }?.let {
            cards += InsightCard("Digitalzeit", "Deine Digitalzeit ist leicht gesunken.", "◇")
        }
        val activeDays = heatmap.days.count { it.durationMs > 0 }
        if (activeDays >= 4) {
            cards += InsightCard("Rhythmus", "Mehrere Tage dieser Woche enthalten bereits erfasste Zeit.", "✦")
        }
        if (topActivities.size >= 3 || balance.count { it.durationMs > 0 } >= 3) {
            cards += InsightCard("Abwechslung", "Deine Zeit verteilt sich auf mehrere Lebensbereiche.", "☷")
        }
        return cards.distinctBy { it.title }.take(3)
    }

    private fun buildSummary(period: InsightPeriod, totalMs: Long, distribution: List<TimeDistributionSlice>, changes: List<PeriodChange>): String {
        if (totalMs <= 0) return "Noch nicht genug Daten. Sobald du Zeitblöcke erfasst, entstehen hier ruhige Muster."
        val top = distribution.firstOrNull()?.label?.lowercase() ?: "mehrere Bereiche"
        val changeText = changes.firstOrNull()?.let { " Die größte Veränderung liegt bei ${it.label.lowercase()}." }.orEmpty()
        return "${periodText(period).replaceFirstChar { it.uppercase() }} prägt vor allem $top deine erfasste Zeit.$changeText"
    }

    private fun List<ActivitySession>.clippedTo(start: Long, end: Long): List<ClippedInsightSession> = mapNotNull { session ->
        val sessionEnd = session.endAt ?: end
        val clippedStart = session.startAt.coerceAtLeast(start)
        val clippedEnd = sessionEnd.coerceAtMost(end)
        val duration = clippedEnd - clippedStart
        if (duration <= 0) null else ClippedInsightSession(
            id = session.id,
            title = session.title,
            categoryId = session.categoryId,
            activityTypeId = session.activityTypeId,
            startAt = clippedStart,
            endAt = clippedEnd,
            durationMs = duration
        )
    }

    private fun percent(value: Long, total: Long): Int = ((value.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100)

    fun categoryColor(categoryId: String): Color = when (categoryId.lowercase()) {
        "work" -> AevumCategoryColors.work
        "sleep" -> AevumCategoryColors.sleep
        "sport", "fitness" -> AevumCategoryColors.sport
        "learning" -> AevumCategoryColors.learning
        "leisure" -> AevumCategoryColors.leisure
        "relationships", "social" -> AevumCategoryColors.relationships
        "household" -> AevumCategoryColors.household
        "smartphone", "digital" -> AevumCategoryColors.smartphone
        "driving", "transport" -> AevumCategoryColors.driving
        else -> AevumCategoryColors.unknown
    }

    private fun periodText(period: InsightPeriod): String = when (period) {
        InsightPeriod.Today -> "heute"
        InsightPeriod.Week -> "diese Woche"
        InsightPeriod.Month -> "diesen Monat"
    }
}

private data class ClippedInsightSession(
    val id: String,
    val title: String,
    val categoryId: String?,
    val activityTypeId: String?,
    val startAt: Long,
    val endAt: Long,
    val durationMs: Long
)
