package com.d_drostes_apps.aevum.domain.todo

import com.d_drostes_apps.aevum.data.model.Todo
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * M18.60: StreakEngine — Streaks ueber PERIODEN statt zwingend Tage.
 *
 * User-Wunsch: "Wie viele Male hintereinander man das Todo geschafft
 * hat. Beachte dass Streaks nicht zwingend taeglich sein muessen, bspw.
 * in der Woche 5 mal Sport machen haette dann nach 1 Woche einen
 * 1-Wochen Streak."
 *
 * Konzept (hinterfragt):
 *  - Die Periode ergibt sich aus dem Recurrence-Typ (ueber
 *    [RecurrenceEngine.periodKey]): DAILY/WEEKDAYS/WEEKLY_ON → Tag,
 *    N_PER_WEEK → Kalenderwoche, N_PER_MONTH → Monat.
 *  - Eine Periode gilt als erfuellt, wenn die Anzahl Completions in
 *    dieser Periode >= requiredCompletionsInPeriod ist (fuer
 *    N_PER_WEEK also z.B. 5 — an beliebigen Tagen der Woche).
 *  - Der Streak zaehlt AUFEINANDERFOLGENDE erfuellte Perioden,
 *    rueckwaerts ab heute.
 *  - Die AKTUELLE Periode zaehlt noch nicht als Bruch, wenn sie (noch)
 *    nicht erfuellt ist — die Woche/der Tag laeuft ja noch. Sie erhoeht
 *    den Streak aber auch erst, wenn sie erfuellt ist.
 *  - Eine nicht erfuellte VORHERIGE Periode bricht den Streak.
 *  - Perioden vor dem startDate des Todos zaehlen nicht (das Todo
 *    existierte noch nicht).
 */
object StreakEngine {

    /**
     * Aktueller Streak des Todos in Perioden.
     */
    fun currentStreak(todo: Todo, completions: List<TodoCompletion>, today: LocalDate): Int {
        val required = RecurrenceEngine.requiredCompletionsInPeriod(todo, today)
        val byPeriod = completions
            .filter { it.todoId == todo.id }
            .groupBy { RecurrenceEngine.periodKey(todo, LocalDate.parse(it.date)) }
            .mapValues { it.value.size }

        val start = try {
            LocalDate.parse(todo.startDate)
        } catch (_: Exception) {
            today.minusYears(2)
        }

        var streak = 0
        var period = today
        // Maximal 2 Jahre zurueck — danach ist die Berechnung irrelevant.
        repeat(104 * 2) { _ ->
            if (period.isBefore(start)) return streak
            val key = RecurrenceEngine.periodKey(todo, period)
            val count = byPeriod[key] ?: 0
            if (count >= required) {
                streak++
            } else {
                // Nicht erfuellte Periode: Wenn es die AKTUELLE ist,
                // bricht sie den Streak nicht (sie laeuft noch) — aber
                // sie zaehlt auch nicht mit. Jede andere Luecke stoppt.
                if (period != today) return streak
            }
            period = previousPeriod(todo, period)
        }
        return streak
    }

    /** Längster je erreichter Streak (fuer die Detail-Anzeige). */
    fun bestStreak(todo: Todo, completions: List<TodoCompletion>, today: LocalDate): Int {
        val required = RecurrenceEngine.requiredCompletionsInPeriod(todo, today)
        val start = try {
            LocalDate.parse(todo.startDate)
        } catch (_: Exception) {
            today.minusYears(2)
        }
        val byPeriod = completions
            .filter { it.todoId == todo.id }
            .groupBy { RecurrenceEngine.periodKey(todo, LocalDate.parse(it.date)) }
            .mapValues { it.value.size }

        var best = 0
        var run = 0
        var period = start
        var guard = 0
        while (!period.isAfter(today) && guard < 104 * 2) {
            val key = RecurrenceEngine.periodKey(todo, period)
            val count = byPeriod[key] ?: 0
            if (count >= required) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
            period = nextPeriod(todo, period)
            guard++
        }
        return best
    }

    private fun previousPeriod(todo: Todo, date: LocalDate): LocalDate = when (todo.recurrenceType) {
        RecurrenceEngine.TYPE_N_PER_WEEK -> date.minusWeeks(1)
        RecurrenceEngine.TYPE_N_PER_MONTH -> date.minusMonths(1)
        else -> date.minusDays(1)
    }

    private fun nextPeriod(todo: Todo, date: LocalDate): LocalDate = when (todo.recurrenceType) {
        RecurrenceEngine.TYPE_N_PER_WEEK -> date.plusWeeks(1)
        RecurrenceEngine.TYPE_N_PER_MONTH -> date.plusMonths(1)
        else -> date.plusDays(1)
    }

    /**
     * Anzeige-Label: "🔥 5" bei 5 Perioden. Fuer woechentliche Todos
     * explizit "5 Wochen" (User-Beispiel: 1-Wochen-Streak).
     */
    fun streakLabel(todo: Todo, streak: Int): String = when {
        streak <= 0 -> ""
        todo.recurrenceType == RecurrenceEngine.TYPE_N_PER_WEEK -> "🔥 $streak Wochen"
        todo.recurrenceType == RecurrenceEngine.TYPE_N_PER_MONTH -> "🔥 $streak Monate"
        else -> "🔥 $streak"
    }
}
