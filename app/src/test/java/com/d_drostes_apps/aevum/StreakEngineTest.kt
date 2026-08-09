package com.d_drostes_apps.aevum

import com.d_drostes_apps.aevum.data.model.Todo
import com.d_drostes_apps.aevum.data.model.TodoCompletion
import com.d_drostes_apps.aevum.domain.todo.RecurrenceEngine
import com.d_drostes_apps.aevum.domain.todo.StreakEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * M18.60: Streak-Engine-Tests.
 *
 * Kern-Anforderung (User): "Streaks muessen nicht zwingend taeglich
 * sein — 5x pro Woche Sport = nach 1 Woche ein 1-Wochen-Streak."
 */
class StreakEngineTest {

    private fun todo(
        id: String = "t1",
        recurrenceType: String = RecurrenceEngine.TYPE_DAILY,
        countPerPeriod: Int = 1,
        startDate: String = "2026-01-01"
    ): Todo = Todo(
        id = id,
        title = "Test",
        recurrenceType = recurrenceType,
        recurrenceJson = """{"countPerPeriod":$countPerPeriod}""",
        startDate = startDate
    )

    private fun completion(todoId: String, date: String) = TodoCompletion(todoId = todoId, date = date)

    @Test
    fun `daily todo with 3 consecutive days has streak 3`() {
        val t = todo()
        val completions = listOf(
            completion("t1", "2026-08-05"),
            completion("t1", "2026-08-06"),
            completion("t1", "2026-08-07")
        )
        // Heute = 07.08. → die laufende Periode (heute) zaehlt mit.
        assertEquals(3, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `daily todo with a gap yesterday breaks the streak`() {
        val t = todo()
        val completions = listOf(
            completion("t1", "2026-08-04"),
            completion("t1", "2026-08-05") // vorgestern erledigt, gestern+heute nicht
        )
        // Heute = 07.08.: Gestern (06.08.) NICHT erfuellt → die Kette
        // ist gebrochen, der Streak ist 0. Die laufende heutige Periode
        // bricht zwar nicht, aber die Lücke davor schon.
        assertEquals(0, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `running today does not break streak of yesterday chain`() {
        val t = todo()
        val completions = listOf(
            completion("t1", "2026-08-05"),
            completion("t1", "2026-08-06") // gestern+ vorgestern erfuellt
        )
        // Heute = 07.08. laeuft noch (nicht erfuellt, aber kein Bruch):
        // gestern (06.) + vorgestern (05.) erfuellt → Streak 2.
        assertEquals(2, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `weekly todo with 5 completions in one week has a 1-week streak`() {
        val t = todo(
            recurrenceType = RecurrenceEngine.TYPE_N_PER_WEEK,
            countPerPeriod = 5
        )
        // Woche 32 (03.–09.08.2026): 5 Completions an beliebigen Tagen
        val completions = listOf(
            completion("t1", "2026-08-03"),
            completion("t1", "2026-08-04"),
            completion("t1", "2026-08-05"),
            completion("t1", "2026-08-06"),
            completion("t1", "2026-08-07")
        )
        // Heute = Freitag 07.08. — aktuelle Woche erfuellt → Streak 1 Woche
        assertEquals(1, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `weekly todo with 2 full weeks has a 2-week streak`() {
        val t = todo(
            recurrenceType = RecurrenceEngine.TYPE_N_PER_WEEK,
            countPerPeriod = 5
        )
        val completions = buildList {
            // Woche 31 (27.07.–02.08.)
            for (d in 27..31) add(completion("t1", "2026-07-$d"))
            add(completion("t1", "2026-08-01"))
            // Woche 32 (03.–07.08.)
            for (d in 3..7) add(completion("t1", "2026-08-0$d"))
        }
        assertEquals(2, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `weekly todo with missing full week breaks streak`() {
        val t = todo(
            recurrenceType = RecurrenceEngine.TYPE_N_PER_WEEK,
            countPerPeriod = 5
        )
        // Woche 30 erfuellt, Woche 31 fehlt komplett, Woche 32 (aktuell) erfuellt
        val completions = buildList {
            for (d in 20..24) add(completion("t1", "2026-07-$d"))
            for (d in 3..7) add(completion("t1", "2026-08-0$d"))
        }
        // Aktuelle Woche erfuellt → zaehlt; Woche 31 fehlt → Stopp vorher.
        assertEquals(1, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `current incomplete week does not break but does not count`() {
        val t = todo(
            recurrenceType = RecurrenceEngine.TYPE_N_PER_WEEK,
            countPerPeriod = 5
        )
        // Woche 31 voll, Woche 32 (aktuell) erst 2 Completions
        val completions = buildList {
            for (d in 27..31) add(completion("t1", "2026-07-$d"))
            add(completion("t1", "2026-08-01"))
            add(completion("t1", "2026-08-03"))
            add(completion("t1", "2026-08-04"))
        }
        // Nur die volle Woche 31 zaehlt → Streak 1
        assertEquals(1, StreakEngine.currentStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }

    @Test
    fun `best streak tracks historical maximum`() {
        val t = todo(
            recurrenceType = RecurrenceEngine.TYPE_N_PER_WEEK,
            countPerPeriod = 5
        )
        val completions = buildList {
            // Woche 29+30 voll (2 Wochen Streak), Woche 31 leer, Woche 32 aktuell voll
            for (d in 13..17) add(completion("t1", "2026-07-$d"))
            for (d in 20..24) add(completion("t1", "2026-07-$d"))
            for (d in 3..7) add(completion("t1", "2026-08-0$d"))
        }
        assertEquals(2, StreakEngine.bestStreak(t, completions, LocalDate.of(2026, 8, 7)))
    }
}
