package com.d_drostes_apps.aevum.domain.analytics

import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.Goal
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * M20: Ziel-Auswertung der „Ziel-Architektur" — pure Kotlin (JVM-testbar,
 * ohne Android/Hilt).
 *
 * Was die Architektur kann (Nutzer-Beispiele):
 *  - „Digital unter 2 h pro Tag"        → AUTO/DIGITAL, AT_MOST, DAILY
 *  - „Digital unter 10 h pro Woche"     → AUTO/DIGITAL, AT_MOST, WEEKLY
 *  - „Lernen mindestens 2 h Mo–Fr"      → AUTO/ACTIVITY, AT_LEAST, DAILY,
 *                                          schedule_days = Mo–Fr
 *  - „4× zwischen Mo–Fr Aufgaben"       → MANUAL, COUNT, WEEKLY, Mo–Fr
 *    (oder AUTO/TODOS: zählt abgehakte Aufgaben automatisch)
 *  - „täglich abhaken" (Habit)          → MANUAL, COUNT, DAILY, Ziel 1
 *
 * Grundbegriffe:
 *  - [Goal.scheduleDays]: Bitmaske Mo=bit0 … So=bit6 (127 = jeden Tag).
 *    Bestimmt, an welchen Tagen ein Ziel FÄLLIG ist (Streaks, „frei"-Tage).
 *    Bei Wochen-/Monatszielen zählen zusätzlich nur Beiträge an diesen Tagen
 *    in den Zielwert („4× zwischen Mo–Fr").
 *  - [Goal.trackingMode]: AUTO (Fortschritt aus Aufzeichnungen) oder MANUAL
 *    (Check-ins aus der Tabelle goal_checkin).
 *  - [Goal.sourceKind]: AUTO-Quelle — ACTIVITY_TYPE · CATEGORY · DIGITAL
 *    (echte Bildschirmzeit über Digital Balance, M18.138-Regel) · TODOS
 *    (abgehakte Aufgaben). null = Alt-Verhalten (exakter M18.138-Filter).
 *
 * Die Auswertung liefert NEBEN dem aktuellen Fortschritt einen kompletten
 * Perioden-Verlauf ([buildHistory]) — Grundlage für Streaks, Diagramme und
 * die Heatmap. Alles läuft über Tages-Buckets (ein Durchlauf über die
 * Sessions, danach je Periode nur noch Tages-Summen) — dadurch ist auch
 * ein 13-Wochen-Verlauf für viele Ziele billig.
 */
object GoalProgressAnalytics {

    const val TYPE_AT_LEAST = "AT_LEAST"
    const val TYPE_AT_MOST = "AT_MOST"
    const val TYPE_COUNT = "COUNT"

    const val MODE_AUTO = "AUTO"
    const val MODE_MANUAL = "MANUAL"

    const val SOURCE_ACTIVITY = "ACTIVITY_TYPE"
    const val SOURCE_CATEGORY = "CATEGORY"
    const val SOURCE_DIGITAL = "DIGITAL"
    const val SOURCE_TODOS = "TODOS"

    const val UNIT_COUNT = "COUNT"

    /** Alle Wochentage gesetzt (Mo=1 … So=64). */
    const val ALL_DAYS = 127

    /** M27: Erst ab dieser Serie gibt es für verzögerte AT_MOST-Tagesziele ein Popup. */
    const val MIN_DELAYED_CELEBRATION_STREAK = 2

    /** Verlaufs-Fenster je Periode (Streaks + Diagramme). */
    const val HISTORY_DAYS = 91
    const val HISTORY_WEEKS = 26
    const val HISTORY_MONTHS = 6

    data class GoalProgressResult(
        val goal: Goal,
        val currentValue: Float,
        val targetValue: Float,
        val progress: Float,
        val periodLabel: String,
        val activityTypeName: String?,
        val isMet: Boolean,
        val progressText: String,
        // ── M20 ───────────────────────────────────────────────────────────
        /** Ist die AKTUELLE Periode fällig? (false = freier Tag / freie Woche) */
        val isDueToday: Boolean = true,
        val streakCurrent: Int = 0,
        val streakBest: Int = 0,
        val metPeriods: Int = 0,
        val duePeriods: Int = 0,
        /** Schlüssel der aktuellen Periode (Celebration-Dedupe). */
        val periodKey: String = "",
        /** true, wenn der Zielwert >= Ziel ist (für AT_MOST: im Rahmen). */
        val isCountType: Boolean = false,
        // ── M27: verzögerte Wertung automatischer Ziele ───────────────────
        /**
         * Live erfüllt? Bei automatisch gemessenen Zielen ist das nur eine
         * Momentaufnahme — der Tag/die Woche läuft noch. Für „Ziel erreicht"
         * in der UI zählt [isCurrentAchieved].
         */
        val isMetFinal: Boolean = isMet,
        /** Automatisches Ziel, laufende Periode: Wertung erst danach. */
        val isPendingFinal: Boolean = false,
        /** Erfüllt im Sinne der abgeschlossenen Wertung (Auto) bzw. live (manuell). */
        val isCurrentAchieved: Boolean = isMet,
        /** Letzte abgeschlossene fällige Periode — Grundlage der Feier. */
        val lastCompletedStart: LocalDate? = null,
        val lastCompletedMet: Boolean? = null,
        val lastCompletedPeriodKey: String? = null,
        /** true = es gibt eine abgeschlossene, erfüllte Periode zum Feiern. */
        val celebrationEligible: Boolean = false,
        /** Schlüssel der zu feiernden Periode (nicht die laufende!). */
        val celebrationPeriodKey: String = periodKey,
        /** Label der zu feiernden Periode („Gestern", „Letzte Woche", …). */
        val celebrationPeriodLabel: String = periodLabel,
        /** Serie zum Zeitpunkt der zu feiernden Periode. */
        val celebrationStreak: Int = streakCurrent
    )

    data class PeriodStatus(
        val startDate: LocalDate,
        val value: Float,
        /** null = nicht fällig (freier Tag / freie Woche — zeigt „frei"). */
        val met: Boolean?,
        val due: Boolean,
        val isCurrent: Boolean
    )

    // ── Wochentags-Maske ────────────────────────────────────────────────

    fun weekdayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /** Effektive Maske — 0 wird als „jeden Tag" interpretiert (defensiv). */
    fun scheduleMask(goal: Goal): Int =
        goal.scheduleDays.takeIf { it and ALL_DAYS != 0 }?.and(ALL_DAYS) ?: ALL_DAYS

    fun isScheduledOn(goal: Goal, date: LocalDate): Boolean =
        (scheduleMask(goal) and weekdayBit(date.dayOfWeek)) != 0

    fun periodHasScheduledDay(goal: Goal, start: LocalDate, endExclusive: LocalDate): Boolean {
        var d = start
        while (d.isBefore(endExclusive)) {
            if (isScheduledOn(goal, d)) return true
            d = d.plusDays(1)
        }
        return false
    }

    // ── Auswertung ───────────────────────────────────────────────────────

    /**
     * Kompatibilitäts-Signatur (M18.138) — Bestandsaufrufer und -tests
     * funktionieren unverändert; die M20-Parameter haben Defaults.
     */
    fun evaluateGoal(
        goal: Goal,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        typeMap: Map<String, ActivityType>,
        // M18.138: Bildschirmzeit je Tag (Digital Balance). Ersetzt die
        // Bildschirm-Aufzeichnungen (SCREEN_AUTO) — ein „max 2h Digital"-
        // Ziel muss gegen die gemessene Zeit laufen, nicht gegen die
        // Aufzeichnung.
        balanceMsPerDay: Map<LocalDate, Long> = emptyMap(),
        checkins: List<GoalCheckin> = emptyList(),
        todoCompletions: List<TodoCompletion> = emptyList()
    ): GoalProgressResult {
        val merged = com.d_drostes_apps.aevum.domain.digital.StatisticsSessionSource
            .merge(
                sessions = sessions,
                balanceMsPerDay = balanceMsPerDay,
                zoneId = zoneId
            )

        val history = buildHistoryInternal(goal, merged, checkins, todoCompletions, anchorDate, zoneId)
        val current = history.lastOrNull { it.isCurrent }

        // Fallback (theoretisch): direkte Berechnung der aktuellen Periode.
        val value: Float = current?.value ?: valueInWindow(
            goal = goal,
            buckets = buildBuckets(goal, merged, checkins, todoCompletions, anchorDate, zoneId),
            start = periodStartDate(goal.period, anchorDate),
            endExclusive = periodEndDate(goal.period, anchorDate),
            maskDays = goal.period != "DAILY"
        )

        val targetValue = goal.targetValue
        val progress = if (targetValue > 0f) value / targetValue else 0f
        val isMet = computeMet(goal, value)
        val isAtMost = goal.type == TYPE_AT_MOST
        val isDue = current?.due ?: true
        val auto = isAuto(goal)
        // M27: Verzögerte Wertung betrifft „am Tag UNTER einem Wert
        // bleiben" — also automatische AT_MOST-Tagesziele. Ein AT_LEAST-Ziel
        // („2 h lernen") ist dagegen mit Erreichen des Werts final; ein
        // Wochen-/Monatsziel behält sein bisheriges Live-Verhalten.
        val delayedEvaluation = auto && goal.period == "DAILY" && goal.type == TYPE_AT_MOST
        val pendingFinal = delayedEvaluation && isDue && current != null && current.isCurrent
        // M27: Perioden VOR der Ziel-Erstellung dürfen weder Serie noch
        // Feier aufblähen. Besonders sichtbar bei AT_MOST: 91 leere
        // Vergangenheitstage erfüllen „unter 3 h" sonst automatisch und ein
        // frisch angelegtes Ziel hätte sofort eine 91-Tage-Serie.
        // (Tests, die bewusst mit einem Anker vor startAt arbeiten, bleiben
        // unverändert: liegt startAt nach dem Anker, greift kein Filter.)
        val goalStartDate = Instant.ofEpochMilli(goal.startAt).atZone(zoneId).toLocalDate()
        val relevantHistory = if (goalStartDate.isAfter(anchorDate)) {
            history
        } else {
            history.filter { !it.startDate.isBefore(goalStartDate) }
        }
        val lastCompleted = relevantHistory.lastOrNull { it.due && !it.isCurrent }
        val lastCompletedMet = lastCompleted?.met
        val lastCompletedKey = lastCompleted?.let { periodKey(goal, it.startDate) }

        // M27: Automatisch gemessene Ziele werden erst nach Ablauf ihrer
        // Periode „erreicht". Sonst meldet ein AT_MOST-Ziel jeden Morgen
        // Erfolg, obwohl der Tag noch läuft. Manuelle Ziele sind mit dem
        // Check-in final und bleiben sofort sichtbar.
        val isCurrentAchieved = isDue && !pendingFinal && isMet
        val isMetFinal = if (delayedEvaluation) lastCompletedMet == true else isMet

        val (streakCurrent, streakBest, metCount, dueCount) = streakStats(
            relevantHistory,
            includeCurrent = !delayedEvaluation
        )

        // M27: Bei verzögerten AT_MOST-Tageszielen wird erst ab zwei
        // geschafften Tagen in Folge gefeiert — genau Devons Beispiel:
        // „erst am nächsten Tag, wenn ich es 2 Tage in Folge geschafft habe".
        // Ein einzelner erfolgreicher Tag bleibt in der Karte sichtbar
        // („Zuletzt geschafft"), löst aber kein Vollbild-Popup aus.
        val celebrationEligible = if (delayedEvaluation) {
            lastCompletedMet == true && streakCurrent >= MIN_DELAYED_CELEBRATION_STREAK
        } else {
            isDue && isMet
        }
        val celebrationKey = if (delayedEvaluation) {
            lastCompletedKey ?: periodKey(goal, anchorDate)
        } else {
            periodKey(goal, anchorDate)
        }
        val celebrationLabel = if (delayedEvaluation && lastCompleted != null) {
            completedPeriodLabel(goal.period)
        } else {
            periodLabel(goal.period)
        }

        return GoalProgressResult(
            goal = goal,
            currentValue = value,
            targetValue = targetValue,
            progress = progress,
            periodLabel = periodLabel(goal.period),
            activityTypeName = typeMap[goal.activityTypeId]?.name,
            isMet = isMet,
            progressText = buildProgressText(goal, value, targetValue, isAtMost),
            isDueToday = isDue,
            streakCurrent = streakCurrent,
            streakBest = streakBest,
            metPeriods = metCount,
            duePeriods = dueCount,
            periodKey = periodKey(goal, anchorDate),
            isCountType = isCountType(goal),
            isMetFinal = isMetFinal,
            isPendingFinal = pendingFinal,
            isCurrentAchieved = isCurrentAchieved,
            lastCompletedStart = lastCompleted?.startDate,
            lastCompletedMet = lastCompletedMet,
            lastCompletedPeriodKey = lastCompletedKey,
            celebrationEligible = celebrationEligible,
            celebrationPeriodKey = celebrationKey,
            celebrationPeriodLabel = celebrationLabel,
            celebrationStreak = streakCurrent
        )
    }

    /**
     * Kompletter Perioden-Verlauf (älteste zuerst, bis einschließlich der
     * aktuellen Periode) — Grundlage für Streak-Anzeige, Balken-Diagramm
     * und Heatmap im Detail-Screen.
     */
    fun buildHistory(
        goal: Goal,
        sessions: List<ActivitySession>,
        anchorDate: LocalDate,
        zoneId: ZoneId,
        balanceMsPerDay: Map<LocalDate, Long> = emptyMap(),
        checkins: List<GoalCheckin> = emptyList(),
        todoCompletions: List<TodoCompletion> = emptyList()
    ): List<PeriodStatus> {
        val merged = com.d_drostes_apps.aevum.domain.digital.StatisticsSessionSource
            .merge(sessions, balanceMsPerDay, zoneId)
        return buildHistoryInternal(goal, merged, checkins, todoCompletions, anchorDate, zoneId)
    }

    private fun buildHistoryInternal(
        goal: Goal,
        mergedSessions: List<ActivitySession>,
        checkins: List<GoalCheckin>,
        todoCompletions: List<TodoCompletion>,
        anchorDate: LocalDate,
        zoneId: ZoneId
    ): List<PeriodStatus> {
        val buckets = buildBuckets(goal, mergedSessions, checkins, todoCompletions, anchorDate, zoneId)
        val currentKey = periodKey(goal, anchorDate)
        val result = ArrayList<PeriodStatus>()

        val earliest = when (goal.period) {
            "WEEKLY" -> anchorDate.minusWeeks((HISTORY_WEEKS - 1).toLong())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "MONTHLY" -> anchorDate.minusMonths((HISTORY_MONTHS - 1).toLong()).withDayOfMonth(1)
            else -> anchorDate.minusDays((HISTORY_DAYS - 1).toLong())
        }
        var start = earliest
        var guard = 0
        while (!start.isAfter(anchorDate) && guard < 1200) {
            val endEx = nextPeriodStart(start, goal.period)
            val due = when (goal.period) {
                "DAILY" -> isScheduledOn(goal, start)
                else -> periodHasScheduledDay(goal, start, endEx)
            }
            val value = valueInWindow(
                goal, buckets, start, endEx,
                maskDays = goal.period != "DAILY"
            )
            val met = if (due) computeMet(goal, value) else null
            result += PeriodStatus(
                startDate = start,
                value = value,
                met = met,
                due = due,
                isCurrent = periodKey(goal, start) == currentKey
            )
            start = endEx
            guard++
        }
        return result
    }

    // ── Tages-Buckets (ein Durchlauf über die Sessions) ─────────────────

    private class DayBuckets(
        val msByDay: Map<LocalDate, Long>,
        val countByDay: Map<LocalDate, Int>
    )

    private fun buildBuckets(
        goal: Goal,
        sessions: List<ActivitySession>,
        checkins: List<GoalCheckin>,
        todoCompletions: List<TodoCompletion>,
        anchorDate: LocalDate,
        zoneId: ZoneId
    ): DayBuckets {
        val ms = HashMap<LocalDate, Long>()
        val counts = HashMap<LocalDate, Int>()

        if (goal.trackingMode == MODE_MANUAL) {
            checkins.forEach { c ->
                val d = parseDate(c.dateKey) ?: return@forEach
                counts[d] = (counts[d] ?: 0) + c.count
            }
        } else if (goal.sourceKind == SOURCE_TODOS) {
            todoCompletions.forEach { c ->
                val d = parseDate(c.date) ?: return@forEach
                counts[d] = (counts[d] ?: 0) + 1
            }
        } else if (isCountType(goal)) {
            // COUNT mit Zeitquelle: zählt SESSIONS (Start-Tag).
            sessions.forEach { s ->
                if (s.deletedAt != null || s.endAt == null) return@forEach
                if (!matchesSession(goal, s)) return@forEach
                val d = Instant.ofEpochMilli(s.startAt).atZone(zoneId).toLocalDate()
                counts[d] = (counts[d] ?: 0) + 1
            }
        } else {
            // M22: Laufende Sessions zählen LIVE mit (endAt == null).
            // Nutzer-Report („Studium läuft 25 Minuten, das Ziel bewegt sich
            // erst beim Pausieren") hatte ZWEI Ursachen: (a) offene Sessions
            // wurden hier komplett übersprungen, (b) die ViewModels rechneten
            // nur bei Datenbank-Änderungen neu (Ticker in den VMs).
            val now = System.currentTimeMillis()
            sessions.forEach { s ->
                if (s.deletedAt != null) return@forEach
                if (!matchesSession(goal, s)) return@forEach
                // Offene Session: bis jetzt rechnen. Pausierte Sessions haben
                // endAt gesetzt und wachsen dadurch nicht weiter.
                val end = s.endAt ?: now
                var cursor = s.startAt
                while (cursor < end) {
                    val day = Instant.ofEpochMilli(cursor).atZone(zoneId).toLocalDate()
                    val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val segEnd = minOf(end, dayEnd)
                    ms[day] = (ms[day] ?: 0L) + (segEnd - cursor).coerceAtLeast(0L)
                    cursor = segEnd
                }
            }
        }
        return DayBuckets(ms, counts)
    }

    private fun valueInWindow(
        goal: Goal,
        buckets: DayBuckets,
        start: LocalDate,
        endExclusive: LocalDate,
        maskDays: Boolean
    ): Float {
        val unitMultiplier = getUnitMultiplier(goal.targetUnit)
        // COUNT-artige Quellen: direkt zählen; Zeitquellen: ms → Einheit.
        val useCounts = goal.trackingMode == MODE_MANUAL ||
            goal.sourceKind == SOURCE_TODOS ||
            isCountType(goal)

        if (useCounts) {
            var total = 0
            var d = start
            while (d.isBefore(endExclusive)) {
                if (!maskDays || isScheduledOn(goal, d)) total += buckets.countByDay[d] ?: 0
                d = d.plusDays(1)
            }
            return total.toFloat()
        }

        var totalMs = 0L
        var d = start
        while (d.isBefore(endExclusive)) {
            if (!maskDays || isScheduledOn(goal, d)) totalMs += buckets.msByDay[d] ?: 0L
            d = d.plusDays(1)
        }
        return totalMs.toFloat() / unitMultiplier.toFloat()
    }

    // ── Streaks ──────────────────────────────────────────────────────────

    private fun streakStats(history: List<PeriodStatus>, includeCurrent: Boolean): StreakStats {
        var best = 0
        var run = 0
        var metCount = 0
        var dueCount = 0
        history.forEach { p ->
            if (!p.due) return@forEach // freie Periode: unterbricht nicht, zählt nicht
            if (p.isCurrent && !includeCurrent) return@forEach // M27: läuft noch
            dueCount++
            if (p.met == true) {
                metCount++
                run++
                if (run > best) best = run
            } else {
                // Aktuelle Periode bricht nicht (sie läuft noch) —
                // jede abgeschlossene Lücke schon (gleiche Regel wie
                // [com.d_drostes_apps.aevum.domain.todo.StreakEngine]).
                if (!p.isCurrent) run = 0
            }
        }
        return StreakStats(
            current = currentStreak(history, includeCurrent),
            best = best,
            met = metCount,
            due = dueCount
        )
    }

    private fun currentStreak(history: List<PeriodStatus>, includeCurrent: Boolean): Int {
        var current = 0
        for (i in history.indices.reversed()) {
            val p = history[i]
            if (!p.due) continue // freie Periode überspringen
            if (p.isCurrent && !includeCurrent) continue // M27: noch nicht gewertet
            when {
                p.met == true -> current++
                p.isCurrent -> continue // manuell: läuft noch — zählt nicht, bricht nicht
                else -> break
            }
        }
        return current
    }

    private data class StreakStats(val current: Int, val best: Int, val met: Int, val due: Int)

    // ── Met/Quellen/Perioden ─────────────────────────────────────────────

    fun isCountType(goal: Goal): Boolean =
        goal.type == TYPE_COUNT || goal.targetUnit.equals(UNIT_COUNT, ignoreCase = true)

    /** Fortschritt aus Aufzeichnungen (AUTO) — sonst manuell. */
    fun isAuto(goal: Goal): Boolean = goal.trackingMode != MODE_MANUAL

    private fun computeMet(goal: Goal, value: Float): Boolean = when (goal.type) {
        TYPE_AT_MOST -> value <= goal.targetValue
        else -> value >= goal.targetValue
    }

    /**
     * Passt die Session zur Ziel-Quelle?
     * null-sourceKind = Alt-Verhalten: exakter M18.138-Filter
     * (activity_type_id-Gleichheit).
     */
    fun matchesSession(goal: Goal, session: ActivitySession): Boolean =
        when (goal.sourceKind ?: SOURCE_ACTIVITY) {
            SOURCE_ACTIVITY -> session.activityTypeId == goal.activityTypeId
            SOURCE_CATEGORY -> goal.categoryId != null && session.categoryId == goal.categoryId
            // M30: Ein Bildschirmzeit-Ziel darf NUR die gemessene
            // Digital-Balance-Zeit zählen. Vorher matchte hier jeder Block mit
            // activity_type_id = "digital" — auch App-Aufzeichnung, PC-Sessions
            // und manuelle Digital-Blöcke. Zusammen mit der künstlichen
            // Balance-Session entstand so eine Doppelzählung (Devon: Balance
            // 3h07, Ziel 6h08 = 3h07 + 3h01).
            SOURCE_DIGITAL -> session.sourceType ==
                com.d_drostes_apps.aevum.domain.digital.ScreenStatisticsPolicy.SOURCE_DIGITAL_BALANCE
            else -> false // TODOS/MANUAL zählen keine Sessions
        }

    fun periodStartDate(period: String, anchorDate: LocalDate): LocalDate = when (period) {
        "WEEKLY" -> anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "MONTHLY" -> anchorDate.withDayOfMonth(1)
        else -> anchorDate
    }

    private fun periodEndDate(period: String, anchorDate: LocalDate): LocalDate =
        nextPeriodStart(periodStartDate(period, anchorDate), period)

    private fun nextPeriodStart(start: LocalDate, period: String): LocalDate = when (period) {
        "WEEKLY" -> start.plusWeeks(1)
        "MONTHLY" -> start.plusMonths(1)
        else -> start.plusDays(1)
    }

    /**
     * Schlüssel der Periode, in der [date] liegt — z. B. "2026-10-17",
     * "W2026-42", "2026-10". Wird für die einmalige Feier pro Ziel+Periode
     * genutzt (Celebration-Dedupe).
     */
    fun periodKey(goal: Goal, date: LocalDate): String = when (goal.period) {
        "WEEKLY" -> "W${date.get(IsoFields.WEEK_BASED_YEAR)}-${"%02d".format(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))}"
        "MONTHLY" -> "%d-%02d".format(date.year, date.monthValue)
        else -> date.toString()
    }

    private fun parseDate(iso: String): LocalDate? = try {
        LocalDate.parse(iso)
    } catch (_: Exception) {
        null
    }

    // ── Textformate (M18.138-Verhalten bleibt erhalten) ──────────────────

    private fun buildProgressText(goal: Goal, currentValue: Float, targetValue: Float, isAtMost: Boolean): String {
        if (isCountType(goal)) {
            return "${currentValue.toInt()} von ${targetValue.toInt()}"
        }
        val formatted = formatValue(currentValue, goal.targetUnit)
        val targetFormatted = "${targetValue.toInt()} ${goal.targetUnit.lowercase()}"
        return if (isAtMost) {
            "$formatted von maximal $targetFormatted"
        } else {
            "$formatted von $targetFormatted"
        }
    }

    private fun formatValue(value: Float, unit: String): String {
        return when (unit.uppercase()) {
            "HOURS", "HOUR", "H" -> {
                // M30: Auf die volle Minute runden statt den Bruchteil
                // abzuschneiden. 3,1166 h sind 3 h 07 min — vorher wurde
                // daraus durch Float-Truncation „3h 6m" und wich damit um
                // eine Minute von der Digital-Balance-Anzeige ab.
                val totalMinutes = (value * 60f).roundToInt().coerceAtLeast(0)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
            }
            "MINUTES", "MINUTE", "MIN", "M" -> "${value.toInt()}m"
            "SESSIONS", "SESSIONS", "S" -> "${value.toInt()}×"
            "COUNT", "C" -> "${value.toInt()}×"
            "DAYS", "DAY", "D" -> "${value.toInt()} Tage"
            "PERCENT", "%" -> "${value.toInt()}%"
            else -> "${"%.1f".format(value)} $unit"
        }
    }

    private fun periodLabel(period: String): String = when (period) {
        "DAILY" -> "Heute"
        "WEEKLY" -> "Diese Woche"
        "MONTHLY" -> "Diesen Monat"
        else -> period
    }

    /**
     * M27: Label der zu feiernden ABGESCHLOSSENEN Periode. Die Feier kommt
     * erst am nächsten Tag (bzw. nach Wochen-/Monatsende) — „Heute" wäre
     * dann schlicht falsch.
     */
    private fun completedPeriodLabel(period: String): String = when (period) {
        "DAILY" -> "Gestern"
        "WEEKLY" -> "Letzte Woche"
        "MONTHLY" -> "Letzter Monat"
        else -> period
    }

    private fun getUnitMultiplier(unit: String): Long = when (unit.uppercase()) {
        "HOURS", "HOUR", "H" -> 60 * 60 * 1000L
        "MINUTES", "MINUTE", "MIN", "M" -> 60 * 1000L
        "SECONDS", "SECOND", "SEC" -> 1000L
        "COUNT", "C" -> 1L
        else -> 60 * 60 * 1000L
    }
}
