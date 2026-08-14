package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.ui.graphics.Color
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Category
import com.d_drostes_apps.aevum.ui.screens.habits.HabitWithProgress
import com.d_drostes_apps.aevum.ui.theme.AevumCategoryColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

enum class InsightPeriod(val label: String) {
    Today("Heute"),
    Week("Woche"),
    Month("Monat");

    companion object {
        /** M18.34: Period aus Storage lesen — Default Today (User-Praeferenz). */
        fun fromStorage(raw: String?): InsightPeriod =
            entries.firstOrNull { it.name == raw } ?: Today
    }
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
    val habitProgress: List<HabitWithProgress> = emptyList(),
    // M17.4: Toggle-Zustand + neue "Top Breakdown" Liste je nach Modus
    val breakdownMode: BreakdownMode = BreakdownMode.Activity,
    val topBreakdown: List<TopActivitySlice> = emptyList(),
    /** M17.4: Total-Minuten inkl. Tagespauschalen (für Hero-Header). */
    val totalMinutesIncludingAllowances: Int = 0,
    /** M18.36: Exakte Millisekunden (inkl. Pauschalen) — fuer die nicht-gerundete Hero-Anzeige. */
    val totalMsIncludingAllowances: Long = 0L
)

data class TimeDistributionSlice(
    val id: String,
    val label: String,
    val color: Color,
    val durationMs: Long,
    val percent: Int,
    // M18.17: Kategorie-Icon für die Kategorie-Ansicht.
    val icon: String = "•"
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
    val percent: Int,
    // M18.13: Icon (Emoji) der Aktivität für die Insights-Liste.
    val icon: String = "•"
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
        habitProgress: List<HabitWithProgress> = emptyList(),
        // M17.4: Tagespauschalen-Accumulationals aus DailyAllowanceRepository.
        // Werden NUR in die Statistik-Aggregation gemischt, niemals in die Timeline.
        allowanceAccumulations: List<com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay> = emptyList(),
        // M17.4: Toggle-Modus für die Top-Liste.
        breakdownMode: BreakdownMode = BreakdownMode.Activity
    ): InsightsUiState {
        val window = window(selectedPeriod, anchorDate, zoneId)
        val categoryMap = categories.associateBy { it.id }
        val typeMap = activityTypes.associateBy { it.id }
        val active = sessions.filter { it.deletedAt == null }
        val current = active.clippedTo(window.start, window.end)
        val previous = active.clippedTo(window.previousStart, window.previousEnd)
        val totalMs = current.sumOf { it.durationMs }
        // M17.4: Tagespauschalen → als virtuelle Sessions für die Aggregation.
        // Wichtig: wir nutzen einen separaten Pfad, der NUR in die Top-Liste
        // und in den Hero-Header einfließt, niemals in den Heatmap.
        // M18.37: allowanceMs muss VOR distributionWithAllowances stehen
        // (wird dort als Nenner fuer die Prozentwerte genutzt).
        val allowanceMs = allowanceAccumulations.sumOf { it.minutes } * 60_000L
        val distribution = buildDistribution(current, categoryMap, typeMap)
        // M18.37: Pauschalen auch ins Kreisdiagramm mischen — als eigene
        // Slices pro Kategorie (id "allowance_<catId>"), damit sie dort
        // sichtbar sind und nicht nur in der Gesamtsumme aufgehen.
        val allowanceDistribution = buildAllowanceDistribution(
            accumulations = allowanceAccumulations,
            categories = categories,
            activityTypes = activityTypes
        )
        val distributionWithAllowances = (distribution + allowanceDistribution)
            .groupBy { it.id }
            .map { (id, slices) ->
                val merged = slices.reduce { a, b ->
                    a.copy(durationMs = a.durationMs + b.durationMs)
                }
                merged.copy(percent = percent(merged.durationMs, (totalMs + allowanceMs).coerceAtLeast(1L)))
            }
            .sortedByDescending { it.durationMs }
        val topActivities = buildTopActivities(current, typeMap, categoryMap)
        val changes = buildChanges(current, previous, categoryMap)
        val balance = buildBalance(current, categoryMap)
        val heatmap = buildWeekHeatmap(active, anchorDate, zoneId)
        val insights = buildInsightCards(selectedPeriod, distributionWithAllowances, changes, topActivities, balance, heatmap, totalMs)
        // M17.4: Tagespauschalen → als virtuelle Sessions für die Aggregation.
        // Wichtig: wir nutzen einen separaten Pfad, der NUR in die Top-Liste
        // und in den Hero-Header einfließt, niemals in den Heatmap.
        val allowanceTopBreakdown = buildAllowanceTopBreakdown(
            accumulations = allowanceAccumulations,
            activityTypes = activityTypes,
            categories = categories,
            mode = breakdownMode
        )
        // M17.4: Echte Top-Liste je nach Modus
        // M18.37-FIX (Root Cause): allowanceTopBreakdown wurde berechnet,
        // aber NIE in topBreakdown gemischt — die Pauschalen waren toter
        // Code und erschienen nur in der Gesamtsumme, nie in der Top-Liste.
        // Jetzt: echte Slices + Pauschalen-Slices mergen, sortieren, top 5.
        val baseBreakdown = when (breakdownMode) {
            BreakdownMode.Activity -> topActivities
            // M18.66-FIX17 (User: "heute Autofahrt aufgezeichnet (Transport),
            // aber unter Insights-Kategorie gibt es keinen Balken"): Die
            // Kategorie-Ansicht zeigte nur die Top-5 — kurze Kategorien
            // wie Transport wurden von 5+ größeren Kategorien verdrängt.
            // Jetzt: ALLE Kategorien des Zeitraums als Balken, nicht nur
            // die Top-5. (Die Aktivitäten-Ansicht bleibt bei Top-5.)
            BreakdownMode.Category -> distribution.map { slice ->
                TopActivitySlice(
                    id = slice.id,
                    label = slice.label,
                    color = slice.color,
                    durationMs = slice.durationMs,
                    percent = slice.percent,
                    // M18.17: Kategorie-Icon in der Kategorie-Ansicht.
                    icon = slice.icon
                )
            }
        }
        // M18.66-FIX17: take(5) nur in der Aktivitäten-Ansicht —
        // die Kategorie-Ansicht zeigt ALLE Kategorien (sonst fehlt z.B.
        // Transport bei >5 Kategorien am Tag).
        val topBreakdown = (baseBreakdown + allowanceTopBreakdown)
            .groupBy { it.id }
            .map { (id, slices) ->
                val merged = slices.reduce { a, b ->
                    a.copy(durationMs = a.durationMs + b.durationMs)
                }
                merged.copy(percent = percent(merged.durationMs, (baseBreakdown.sumOf { it.durationMs } + allowanceMs).coerceAtLeast(1L)))
            }
            .sortedByDescending { it.durationMs }
            .let { list -> if (breakdownMode == BreakdownMode.Activity) list.take(5) else list }
        return InsightsUiState(
            selectedPeriod = selectedPeriod,
            periodLabel = window.label,
            summary = buildSummary(selectedPeriod, totalMs, distribution, changes),
            startDate = window.startDate,
            timeDistribution = distributionWithAllowances,
            changes = changes,
            topActivities = topActivities,
            balance = balance,
            insightCards = insights,
            weekHeatmap = heatmap,
            hasData = totalMs > 0 || allowanceMs > 0,
            habitProgress = habitProgress,
            breakdownMode = breakdownMode,
            topBreakdown = topBreakdown,
            // M17.4: Total-Minuten für Hero-Header inkl. Pauschalen.
            totalMinutesIncludingAllowances = ((totalMs + allowanceMs) / 60_000L).toInt(),
            // M18.36: Exakte Millisekunden — die Hero-Anzeige zeigt jetzt
            // Dezimal-Stunden (1 Nachkommastelle) statt gerundeter Ints.
            totalMsIncludingAllowances = totalMs + allowanceMs
        )
    }

    /**
     * M17.4: Tagespauschalen-Liste in TopActivitySlice-Form bringen,
     * gruppiert nach Aktivität ODER Kategorie je nach [mode].
     */
    private fun buildAllowanceTopBreakdown(
        accumulations: List<com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay>,
        activityTypes: List<ActivityType>,
        categories: List<Category>,
        mode: BreakdownMode
    ): List<TopActivitySlice> {
        if (accumulations.isEmpty()) return emptyList()
        val typeMap = activityTypes.associateBy { it.id }
        val categoryMap = categories.associateBy { it.id }
        val totalMs = accumulations.sumOf { it.minutes } * 60_000L
        val totalMsSafe = totalMs.coerceAtLeast(1L)
        val grouped: Map<String, Long> = when (mode) {
            BreakdownMode.Activity -> accumulations.groupBy { it.activityTypeId }
                .mapValues { entry -> entry.value.sumOf { it.minutes } * 60_000L }
            BreakdownMode.Category -> {
                // categoryId ist NICHT auf AccumulationDay — wir joinen über typeMap
                accumulations.groupBy { acc ->
                    typeMap[acc.activityTypeId]?.defaultCategoryId ?: "unknown"
                }.mapValues { entry -> entry.value.sumOf { it.minutes } * 60_000L }
            }
        }
        return grouped.map { (key, ms) ->
            when (mode) {
                BreakdownMode.Activity -> {
                    val type = typeMap[key]
                    TopActivitySlice(
                        id = "allowance_$key",
                        label = "${type?.name ?: "Pauschale"} (Pauschale)",
                        color = categoryColor(type?.defaultCategoryId.orEmpty()),
                        durationMs = ms,
                        percent = percent(ms, totalMsSafe),
                        icon = type?.icon ?: "•"
                    )
                }
                BreakdownMode.Category -> {
                    val cat = categoryMap[key]
                    TopActivitySlice(
                        id = "allowance_$key",
                        label = cat?.name ?: "Pauschale",
                        color = cat?.color?.let { parseColor(it) } ?: categoryColor(key),
                        durationMs = ms,
                        percent = percent(ms, totalMsSafe)
                    )
                }
            }
        }.sortedByDescending { it.durationMs }.take(5)
    }

    /**
     * M18.37: Pauschalen als Kategorie-Slices für das Kreisdiagramm.
     * Gruppiert nach Kategorie (via ActivityType.defaultCategoryId),
     * id = "allowance_<catId>" — wird beim Merge mit der echten
     * Distribution über die Kategorie zusammengeführt.
     */
    private fun buildAllowanceDistribution(
        accumulations: List<com.d_drostes_apps.aevum.data.model.AllowanceAccumulationDay>,
        categories: List<Category>,
        activityTypes: List<ActivityType>
    ): List<TimeDistributionSlice> {
        if (accumulations.isEmpty()) return emptyList()
        val categoryMap = categories.associateBy { it.id }
        val typeMap = activityTypes.associateBy { it.id }
        val grouped = accumulations.groupBy { acc ->
            typeMap[acc.activityTypeId]?.defaultCategoryId ?: "unknown"
        }
        val total = accumulations.sumOf { it.minutes } * 60_000L
        return grouped.map { (key, values) ->
            val ms = values.sumOf { it.minutes } * 60_000L
            val cat = categoryMap[key]
            TimeDistributionSlice(
                id = "allowance_$key",
                label = cat?.name ?: "Pauschale",
                color = cat?.color?.let { parseColor(it) } ?: categoryColor(key),
                durationMs = ms,
                percent = percent(ms, total.coerceAtLeast(1L)),
                icon = cat?.icon ?: "⏱"
            )
        }.sortedByDescending { it.durationMs }
    }

    private fun buildDistribution(
        sessions: List<ClippedInsightSession>,
        categoryMap: Map<String, Category>,
        typeMap: Map<String, ActivityType>
    ): List<TimeDistributionSlice> {
        val total = sessions.sumOf { it.durationMs }.coerceAtLeast(1L)
        return sessions.groupBy { session ->
            // M18.66-FIX19 (User: "Fitness mit Kategorie Gesundheit wird
            // nicht gelistet, stattdessen zwei Mal Sonstiges"): Zwei Bugs:
            // 1) Sessions mit categoryId=null (z.B. Garmin-Import, bevor
            //    der User dem Typ eine Kategorie zuwies) fielen auf
            //    "unknown" → "Sonstiges", obwohl der ActivityType inzwischen
            //    eine Kategorie hat. Jetzt: Fallback auf den Typ.
            // 2) Jede unbekannte ID erzeugte ein EIGENES "Sonstiges" —
            //    alle unbekannten IDs werden auf "unknown" normalisiert,
            //    damit nie zwei "Sonstiges"-Balken entstehen.
            val raw = session.categoryId ?: typeMap[session.activityTypeId]?.defaultCategoryId
            if (raw != null && categoryMap.containsKey(raw)) raw else "unknown"
        }
            .map { (id, values) ->
                val category = categoryMap[id]
                val duration = values.sumOf { it.durationMs }
                TimeDistributionSlice(
                    id = id,
                    label = category?.name ?: "Sonstiges",
                    color = categoryColor(id),
                    durationMs = duration,
                    percent = percent(duration, total),
                    // M18.17: Kategorie-Icon für die Kategorie-Ansicht.
                    icon = category?.icon ?: "•"
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
                    percent = percent(duration, total),
                    // M18.13: Icon der Aktivität (falls zugeordnet)
                    icon = typeMap[id]?.icon ?: "•"
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
        // M18.59-FIX (User: "Insights zeigt 12h 32 gelernt, live sind es
        // erst 1h 09"): Bei laufender Session (endAt = null) wurde bis zum
        // FENSTERENDE (24:00) gerechnet — die laufende Session erschien
        // als bis Mitternacht laufend. Jetzt endet sie effektiv bei
        // "jetzt" (gecappt aufs Fensterende). Gleiches gilt für die
        // Heatmap, die dieselbe Funktion nutzt.
        val now = System.currentTimeMillis()
        val sessionEnd = session.endAt ?: minOf(now, end)
        val clippedStart = session.startAt.coerceAtLeast(start)
        val clippedEnd = sessionEnd.coerceAtMost(end)
        // M18.62-FIX: Pausen abziehen — vorher wurde die volle Wanduhrzeit
        // (Ende − Start) gezeigt, obwohl pausiert wurde. Nutzt die zentrale
        // Fenster-Berechnung inkl. Segment-/Pausen-Abzug.
        val duration = session.activeDurationInWindow(start, end, now)
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

    /** M17.4: Hex-String (#RRGGBB) zu Color. Robust gegen leeren/ungültigen Input. */
    private fun parseColor(hex: String): Color = try {
        val cleaned = hex.removePrefix("#").trim()
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> AevumCategoryColors.unknown
        }
    } catch (_: NumberFormatException) {
        AevumCategoryColors.unknown
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
