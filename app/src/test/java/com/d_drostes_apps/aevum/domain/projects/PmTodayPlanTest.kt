package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M19.1: Tagesplan, Glas-Kennzahl und Verlauf.
 *
 * Der Kern der Nutzeranforderung: „diese 20 todos will ich heute schaffen" —
 * und „nicht abgehakte Todos werden am nächsten Tag weiterhin angezeigt, der
 * Rest nicht". Diese Suite legt jede Regel einzeln fest, inklusive der
 * ZEITZONE (der Test rechnet in Europe/Berlin, nicht in der Test-JVM-Zone).
 */
class PmTodayPlanTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 10, 17)
    private val created = 1_700_000_000_000L

    private fun at(date: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun iso(date: LocalDate): String = date.toString()

    private fun todo(
        id: String,
        title: String = id,
        parent: String? = "task",
        scheduled: String? = null,
        doneAt: Long? = null,
        order: Int = 0
    ) = PmNode(
        id = id,
        parentId = parent,
        type = PmType.TODO,
        title = title,
        sortOrder = order,
        scheduledDate = scheduled,
        doneAt = doneAt,
        createdAt = created,
        updatedAt = created
    )

    private fun task(id: String = "task", title: String = "Präsentation", order: Int = 0) = PmNode(
        id = id, parentId = "gruppe", type = PmType.TASK, title = title,
        sortOrder = order, createdAt = created, updatedAt = created
    )

    /** Gerüst: Studium → … → Projektgruppe → {Präsentation, Projektbericht}. */
    private fun tree(vararg todos: PmNode) = PmTree(
        listOf(
            PmNode(id = "studium", parentId = null, type = PmType.PROJECT, title = "Studium", sortOrder = 0, createdAt = created, updatedAt = created),
            PmNode(id = "semester", parentId = "studium", type = PmType.PROJECT, title = "2. Semester", sortOrder = 0, createdAt = created, updatedAt = created),
            PmNode(id = "gruppe", parentId = "semester", type = PmType.CATEGORY, title = "Projektgruppe", sortOrder = 0, createdAt = created, updatedAt = created),
            task(),
            task(id = "bericht", title = "Projektbericht", order = 1)
        ) + todos.toList()
    )

    // ── Aufnahme in den Tagesplan ─────────────────────────────────────────

    @Test
    fun `heute eingeplantes offenes Todo steht im Tagesplan`() {
        val tree = tree(todo("a", scheduled = iso(today)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.done).isEqualTo(0)
        val row = state.groups.single().rows.single()
        assertThat(row.node.id).isEqualTo("a")
        assertThat(row.isRollover).isFalse()
    }

    @Test
    fun `gestern faelliges offenes Todo wird uebernommen`() {
        val tree = tree(todo("a", scheduled = iso(today.minusDays(1))))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.rollover).isEqualTo(1)
        val row = state.groups.single().rows.single()
        assertThat(row.isRollover).isTrue()
        assertThat(row.rolloverDays).isEqualTo(1)
        assertThat(row.groupTitle).isEqualTo("Präsentation")
    }

    @Test
    fun `vor drei Tagen faelliges Todo meldet drei Tage Uebernahme`() {
        val tree = tree(todo("a", scheduled = iso(today.minusDays(3))))
        val row = PmToday.state(tree, today, zone).groups.single().rows.single()
        assertThat(row.rolloverDays).isEqualTo(3)
    }

    @Test
    fun `Todo ohne Datum ist NICHT im Tagesplan sondern im Backlog`() {
        val tree = tree(todo("a"))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.isEmpty).isTrue()
        assertThat(state.unfiled.map { it.id }).containsExactly("a")
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `fuer spaeter geplantes Todo ist heute nicht sichtbar`() {
        val tree = tree(todo("a", scheduled = iso(today.plusDays(1))))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.unfiled).isEmpty() // es HAT ein Datum, gehört nur nicht zu heute
    }

    @Test
    fun `heute erledigtes Todo bleibt im Plan und zaehlt als Juwel`() {
        val tree = tree(todo("a", scheduled = iso(today), doneAt = at(today, hour = 11)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.groups.single().rows.single().done).isTrue()
    }

    @Test
    fun `heute erledigtes Todo ohne Datum zaehlt ebenfalls`() {
        // Wer etwas Ungeplantes heute schafft, soll es auch sehen.
        val tree = tree(todo("a", doneAt = at(today, hour = 20)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.unfiled).isEmpty() // erledigt → nicht mehr im Backlog
    }

    @Test
    fun `frueher erledigtes Todo ist heute nicht sichtbar`() {
        val tree = tree(
            todo("a", scheduled = iso(today.minusDays(2)), doneAt = at(today.minusDays(2))),
            todo("b", scheduled = iso(today.minusDays(2)), doneAt = at(today.minusDays(2), hour = 18))
        )
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `spaeter geplantes aber heute vorab erledigtes Todo zaehlt`() {
        val tree = tree(todo("a", scheduled = iso(today.plusDays(5)), doneAt = at(today, hour = 8)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.total).isEqualTo(1)
    }

    @Test
    fun `ueber Mitternacht-Erledigung zaehlt nach der Systemzone`() {
        // 22:30 UTC am Vortag == 00:30 Berlin am heutigen Tag.
        val justAfterMidnightUtc = LocalDateTime.of(today.minusDays(1), java.time.LocalTime.of(22, 30))
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val tree = tree(todo("a", doneAt = justAfterMidnightUtc))
        assertThat(PmToday.state(tree, today, zone).done).isEqualTo(1)
        // In UTC gerechnet wäre es noch gestern.
        assertThat(PmToday.state(tree, today, ZoneId.of("UTC")).done).isEqualTo(0)
    }

    // ── Glas-Kennzahl ─────────────────────────────────────────────────────

    @Test
    fun `glas-kennzahl ist erledigt durch geplant`() {
        val tree = tree(
            todo("a", scheduled = iso(today), doneAt = at(today, hour = 10)),
            todo("b", scheduled = iso(today), doneAt = at(today, hour = 12)),
            todo("c", scheduled = iso(today)),
            todo("d", scheduled = iso(today.minusDays(1)))
        )
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(4)
        assertThat(state.done).isEqualTo(2)
        assertThat(state.pending).isEqualTo(2)
        assertThat(state.fraction).isWithin(0.0001f).of(0.5f)
        assertThat(state.percent).isEqualTo(50)
        assertThat(state.isComplete).isFalse()
    }

    @Test
    fun `volles Glas-nur wenn alle Todos des Tages erledigt sind`() {
        val complete = PmToday.state(
            tree(todo("a", scheduled = iso(today), doneAt = at(today)), todo("b", scheduled = iso(today), doneAt = at(today))),
            today, zone
        )
        assertThat(complete.isComplete).isTrue()
        assertThat(complete.fraction).isEqualTo(1f)
        assertThat(complete.percent).isEqualTo(100)
    }

    @Test
    fun `leerer Tagesplan ist nicht vollstaendig`() {
        val state = PmToday.state(tree(), today, zone)
        assertThat(state.isEmpty).isTrue()
        // Wichtig für die UI: 0 von 0 darf kein Feuerwerk auslösen.
        assertThat(state.isComplete).isFalse()
        assertThat(state.fraction).isEqualTo(0f)
    }

    // ── Gruppen und Sortierung ────────────────────────────────────────────

    @Test
    fun `aufgaben ohne heutige Todos erscheinen nicht im Karussell`() {
        val tree = tree(
            todo("a", parent = "task", scheduled = iso(today)),
            todo("b", parent = "bericht", scheduled = iso(today.plusDays(3)))
        )
        val groups = PmToday.state(tree, today, zone).groups
        assertThat(groups.map { it.id }).containsExactly("task")
    }

    @Test
    fun `offene Todos stehen vor erledigten`() {
        val tree = tree(
            todo("done1", scheduled = iso(today), doneAt = at(today, hour = 9), order = 0),
            todo("open1", scheduled = iso(today), order = 5)
        )
        val rows = PmToday.state(tree, today, zone).groups.single().rows
        assertThat(rows.map { it.node.id }).containsExactly("open1", "done1").inOrder()
    }

    @Test
    fun `uebernommene Todos stehen ueber den heute faelligen`() {
        val tree = tree(
            todo("heute", scheduled = iso(today), order = 0),
            todo("gestern", scheduled = iso(today.minusDays(1)), order = 1)
        )
        val rows = PmToday.state(tree, today, zone).groups.single().rows
        assertThat(rows.map { it.node.id }).containsExactly("gestern", "heute").inOrder()
    }

    @Test
    fun `gruppen mit den meisten offenen Todos stehen oben`() {
        val tree = tree(
            todo("a1", parent = "task", scheduled = iso(today)),
            todo("b1", parent = "bericht", scheduled = iso(today)),
            todo("b2", parent = "bericht", scheduled = iso(today)),
            todo("b3", parent = "bericht", scheduled = iso(today))
        )
        assertThat(PmToday.state(tree, today, zone).groups.map { it.id })
            .containsExactly("bericht", "task").inOrder()
    }

    @Test
    fun `todos ohne Aufgabe landen in einer eigenen Gruppe statt zu verschwinden`() {
        val tree = tree(todo("frei", parent = "gruppe", scheduled = iso(today)))
        val state = PmToday.state(tree, today, zone)
        val group = state.groups.single()
        assertThat(group.isSynthetic).isTrue()
        assertThat(group.rows.map { it.node.id }).containsExactly("frei")
        // Brotkrumen kommen aus dem echten Pfad, nicht aus der (fehlenden) Aufgabe.
        assertThat(group.rows.single().breadcrumb).isEqualTo("Studium › 2. Semester › Projektgruppe")
    }

    @Test
    fun `container-werden-nie-als-Todo-gelistet`() {
        val tree = tree(todo("a", scheduled = iso(today)))
        val all = PmToday.state(tree, today, zone).groups.flatMap { it.rows }.map { it.node.id }
        assertThat(all).containsExactly("a")
        assertThat(all).doesNotContain("task")
        assertThat(all).doesNotContain("gruppe")
    }

    // ── Verlauf (7 Tage) ──────────────────────────────────────────────────

    @Test
    fun `verlauf-zaehlt-Erledigungen-je-Tag-in-der-Systemzone`() {
        val tree = tree(
            todo("a", doneAt = at(today, hour = 8)),
            todo("b", doneAt = at(today, hour = 9)),
            todo("c", doneAt = at(today.minusDays(2), hour = 12)),
            todo("d", doneAt = at(today.minusDays(9), hour = 12))
        )
        val history = PmToday.history(tree, today, days = 7, zone = zone)
        assertThat(history).hasSize(7)
        assertThat(history.last().date).isEqualTo(today)
        assertThat(history.last().isToday).isTrue()
        assertThat(history.last().done).isEqualTo(2)
        assertThat(history[4].date).isEqualTo(today.minusDays(2))
        assertThat(history[4].done).isEqualTo(1)
        assertThat(history.first().date).isEqualTo(today.minusDays(6))
        assertThat(history.sumOf { it.done }).isEqualTo(3) // der 9 Tage alte zählt nicht mehr
    }

    @Test
    fun `verlauf-ohne-Erledigungen-ist-leer-aber-vorhanden`() {
        val history = PmToday.history(tree(), today, days = 7, zone = zone)
        assertThat(history).hasSize(7)
        assertThat(history.all { it.done == 0 }).isTrue()
    }

    // ── Tag planen ───────────────────────────────────────────────────────

    @Test
    fun `planungsgruppen-schlagen-uebernommene-und-heutige-vor`() {
        val tree = tree(
            todo("alt", parent = "task", scheduled = iso(today.minusDays(2))),
            todo("heute", parent = "task", scheduled = iso(today)),
            todo("spaeter", parent = "task", scheduled = iso(today.plusDays(3))),
            todo("ohne", parent = "task"),
            todo("erledigt", parent = "task", doneAt = at(today.minusDays(1)))
        )
        val groups = PmToday.planningGroups(tree, today)
        val group = groups.first { it.group.id == "task" }
        assertThat(group.todos.map { it.id }).containsExactly("alt", "heute", "spaeter", "ohne")
        assertThat(group.suggestedIds).containsExactly("alt", "heute")
    }

    @Test
    fun `planungsgruppen-lassen-Aufgaben-ohne-offene-Todos-weg`() {
        val tree = tree(todo("erledigt", parent = "task", doneAt = at(today.minusDays(1))))
        assertThat(PmToday.planningGroups(tree, today)).isEmpty()
    }

    @Test
    fun `planungsgruppen-mit-Uebernommenem-stehen-oben`() {
        val tree = tree(
            todo("a1", parent = "bericht", scheduled = iso(today)),
            todo("t1", parent = "task", scheduled = iso(today.minusDays(4)))
        )
        assertThat(PmToday.planningGroups(tree, today).map { it.group.id })
            .containsExactly("task", "bericht").inOrder()
    }

    // ── Datums-Helfer ─────────────────────────────────────────────────────

    @Test
    fun `datum-helfer-parsen-robust`() {
        assertThat(PmToday.parseDate("2026-10-17")).isEqualTo(today)
        assertThat(PmToday.parseDate(null)).isNull()
        assertThat(PmToday.parseDate("17.10.2026")).isNull()
        assertThat(PmToday.parseDate("")).isNull()
    }

    @Test
    fun `iso-datum-und-heute-aus-zeitstempel`() {
        val stamp = at(today, hour = 15)
        assertThat(PmToday.isoDateOf(stamp, zone)).isEqualTo("2026-10-17")
        assertThat(PmToday.todayIso(stamp, zone)).isEqualTo("2026-10-17")
    }
}
