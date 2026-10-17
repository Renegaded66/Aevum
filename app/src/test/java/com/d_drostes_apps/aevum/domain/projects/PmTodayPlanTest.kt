package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * M19.1/M19.2: Tagesplan, Glas-Kennzahl und Verlauf.
 *
 * Der Kern der Nutzeranforderung: „diese 20 todos will ich heute schaffen" —
 * und „nicht abgehakte Todos werden am nächsten Tag weiterhin angezeigt, der
 * Rest nicht". M19.2 ergänzt: „Im PM Dashboard sollten NUR für heute
 * zugewiesene Aufgaben angezeigt werden." Diese Suite legt jede Regel
 * einzeln fest, inklusive der ZEITZONE (der Test rechnet in Europe/Berlin,
 * nicht in der Test-JVM-Zone).
 */
class PmTodayPlanTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 10, 17)
    private val created = 1_700_000_000_000L

    private fun at(date: LocalDate, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun iso(date: LocalDate): String = date.toString()

    private fun project(id: String, title: String, parent: String? = null, order: Int = 0) = PmNode(
        id = id, parentId = parent, type = PmType.PROJECT, title = title,
        sortOrder = order, createdAt = created, updatedAt = created
    )

    /** Eine abhakbare Aufgabe unter einem Projekt (Default: „Präsentation"). */
    private fun task(
        id: String,
        title: String = id,
        parent: String? = "praes",
        scheduled: String? = null,
        doneAt: Long? = null,
        order: Int = 0
    ) = PmNode(
        id = id,
        parentId = parent,
        type = PmType.TASK,
        title = title,
        sortOrder = order,
        scheduledDate = scheduled,
        doneAt = doneAt,
        createdAt = created,
        updatedAt = created
    )

    /** Gerüst: Studium → 2. Semester → Projektgruppe → {Präsentation, Projektbericht}. */
    private fun tree(vararg tasks: PmNode) = PmTree(
        listOf(
            project("studium", "Studium"),
            project("semester", "2. Semester", "studium"),
            project("gruppe", "Projektgruppe", "semester"),
            project("praes", "Präsentation", "gruppe", order = 0),
            project("bericht", "Projektbericht", "gruppe", order = 1)
        ) + tasks.toList()
    )

    // ── Aufnahme in den Tagesplan ─────────────────────────────────────────

    @Test
    fun `heute eingeplante offene Aufgabe steht im Tagesplan`() {
        val tree = tree(task("a", scheduled = iso(today)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.done).isEqualTo(0)
        val row = state.groups.single().rows.single()
        assertThat(row.node.id).isEqualTo("a")
        assertThat(row.isRollover).isFalse()
    }

    @Test
    fun `gestern faellige offene Aufgabe wird uebernommen`() {
        val tree = tree(task("a", scheduled = iso(today.minusDays(1))))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.rollover).isEqualTo(1)
        val row = state.groups.single().rows.single()
        assertThat(row.isRollover).isTrue()
        assertThat(row.rolloverDays).isEqualTo(1)
        assertThat(row.groupTitle).isEqualTo("Präsentation")
    }

    @Test
    fun `vor drei Tagen faellige Aufgabe meldet drei Tage Uebernahme`() {
        val tree = tree(task("a", scheduled = iso(today.minusDays(3))))
        val row = PmToday.state(tree, today, zone).groups.single().rows.single()
        assertThat(row.rolloverDays).isEqualTo(3)
    }

    @Test
    fun `Aufgabe ohne Datum ist NICHT im Tagesplan sondern im Backlog`() {
        val tree = tree(task("a"))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.isEmpty).isTrue()
        assertThat(state.unfiled.map { it.id }).containsExactly("a")
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `fuer spaeter geplante Aufgabe ist heute nicht sichtbar`() {
        val tree = tree(task("a", scheduled = iso(today.plusDays(1))))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.unfiled).isEmpty() // sie HAT ein Datum, gehört nur nicht zu heute
    }

    @Test
    fun `heute erledigte Aufgabe bleibt im Plan und zaehlt als Juwel`() {
        val tree = tree(task("a", scheduled = iso(today), doneAt = at(today, hour = 11)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.groups.single().rows.single().done).isTrue()
    }

    @Test
    fun `heute erledigte Aufgabe ohne Datum zaehlt ebenfalls`() {
        // Wer etwas Ungeplantes heute schafft, soll es auch sehen.
        val tree = tree(task("a", doneAt = at(today, hour = 20)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.total).isEqualTo(1)
        assertThat(state.unfiled).isEmpty() // erledigt → nicht mehr im Backlog
    }

    @Test
    fun `frueher erledigte Aufgabe ist heute nicht sichtbar`() {
        val tree = tree(
            task("a", scheduled = iso(today.minusDays(2)), doneAt = at(today.minusDays(2))),
            task("b", scheduled = iso(today.minusDays(2)), doneAt = at(today.minusDays(2), hour = 18))
        )
        val state = PmToday.state(tree, today, zone)
        assertThat(state.total).isEqualTo(0)
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `spaeter geplante aber heute vorab erledigte Aufgabe zaehlt`() {
        val tree = tree(task("a", scheduled = iso(today.plusDays(5)), doneAt = at(today, hour = 8)))
        val state = PmToday.state(tree, today, zone)
        assertThat(state.done).isEqualTo(1)
        assertThat(state.total).isEqualTo(1)
    }

    @Test
    fun `ueber Mitternacht-Erledigung zaehlt nach der Systemzone`() {
        // 22:30 UTC am Vortag == 00:30 Berlin am heutigen Tag.
        val justAfterMidnightUtc = LocalDateTime.of(today.minusDays(1), java.time.LocalTime.of(22, 30))
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val tree = tree(task("a", doneAt = justAfterMidnightUtc))
        assertThat(PmToday.state(tree, today, zone).done).isEqualTo(1)
        // In UTC gerechnet wäre es noch gestern.
        assertThat(PmToday.state(tree, today, ZoneId.of("UTC")).done).isEqualTo(0)
    }

    // ── Glas-Kennzahl ─────────────────────────────────────────────────────

    @Test
    fun `glas-kennzahl ist erledigt durch geplant`() {
        val tree = tree(
            task("a", scheduled = iso(today), doneAt = at(today, hour = 10)),
            task("b", scheduled = iso(today), doneAt = at(today, hour = 12)),
            task("c", scheduled = iso(today)),
            task("d", scheduled = iso(today.minusDays(1)))
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
    fun `volles Glas-nur wenn alle Aufgaben des Tages erledigt sind`() {
        val complete = PmToday.state(
            tree(task("a", scheduled = iso(today), doneAt = at(today)), task("b", scheduled = iso(today), doneAt = at(today))),
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
    fun `projekte ohne heutige Aufgaben erscheinen nicht in der Liste`() {
        val tree = tree(
            task("a", parent = "praes", scheduled = iso(today)),
            task("b", parent = "bericht", scheduled = iso(today.plusDays(3)))
        )
        val groups = PmToday.state(tree, today, zone).groups
        assertThat(groups.map { it.id }).containsExactly("praes")
    }

    @Test
    fun `offene Aufgaben stehen vor erledigten`() {
        val tree = tree(
            task("done1", scheduled = iso(today), doneAt = at(today, hour = 9), order = 0),
            task("open1", scheduled = iso(today), order = 5)
        )
        val rows = PmToday.state(tree, today, zone).groups.single().rows
        assertThat(rows.map { it.node.id }).containsExactly("open1", "done1").inOrder()
    }

    @Test
    fun `innerhalb-der-gruppe-regiert-die-manuelle-reihenfolge`() {
        // M19.2: Drag & Drop schreibt sortOrder — die Anzeige folgt ihr
        // (nicht mehr „übernommene zuerst", das hätte die gezogene Ordnung
        // beim nächsten Öffnen wieder überschrieben).
        val tree = tree(
            task("c", scheduled = iso(today), order = 2),
            task("a", scheduled = iso(today), order = 0),
            task("b", scheduled = iso(today.minusDays(3)), order = 1)
        )
        val rows = PmToday.state(tree, today, zone).groups.single().rows
        assertThat(rows.map { it.node.id }).containsExactly("a", "b", "c").inOrder()
        // Der Übernahme-Hinweis bleibt trotzdem sichtbar (Chip an der Zeile).
        assertThat(rows.first { it.node.id == "b" }.isRollover).isTrue()
    }

    @Test
    fun `gruppen mit den meisten offenen Aufgaben stehen oben`() {
        val tree = tree(
            task("a1", parent = "praes", scheduled = iso(today)),
            task("b1", parent = "bericht", scheduled = iso(today)),
            task("b2", parent = "bericht", scheduled = iso(today)),
            task("b3", parent = "bericht", scheduled = iso(today))
        )
        assertThat(PmToday.state(tree, today, zone).groups.map { it.id })
            .containsExactly("bericht", "praes").inOrder()
    }

    @Test
    fun `aufgaben ohne projekt landen in einer eigenen gruppe statt zu verschwinden`() {
        // Altbestand-Fall: Aufgabe hängt direkt an der Wurzel.
        val tree = tree(task("frei", parent = null, scheduled = iso(today)))
        val state = PmToday.state(tree, today, zone)
        val group = state.groups.single()
        assertThat(group.id).isEqualTo(PmTree.UNASSIGNED_TASK_ID)
        assertThat(group.isSynthetic).isTrue()
        assertThat(group.rows.map { it.node.id }).containsExactly("frei")
    }

    @Test
    fun `projekte-werden-nie-als-aufgabe-gelistet`() {
        val tree = tree(task("a", scheduled = iso(today)))
        val all = PmToday.state(tree, today, zone).groups.flatMap { it.rows }.map { it.node.id }
        assertThat(all).containsExactly("a")
        assertThat(all).doesNotContain("praes")
        assertThat(all).doesNotContain("gruppe")
    }

    // ── Verlauf (7 Tage) ──────────────────────────────────────────────────

    @Test
    fun `verlauf-zaehlt-Erledigungen-je-Tag-in-der-Systemzone`() {
        val tree = tree(
            task("a", doneAt = at(today, hour = 8)),
            task("b", doneAt = at(today, hour = 9)),
            task("c", doneAt = at(today.minusDays(2), hour = 12)),
            task("d", doneAt = at(today.minusDays(9), hour = 12))
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
            task("alt", parent = "praes", scheduled = iso(today.minusDays(2))),
            task("heute", parent = "praes", scheduled = iso(today)),
            task("spaeter", parent = "praes", scheduled = iso(today.plusDays(3))),
            task("ohne", parent = "praes"),
            task("erledigt", parent = "praes", doneAt = at(today.minusDays(1)))
        )
        val groups = PmToday.planningGroups(tree, today)
        val group = groups.first { it.group.id == "praes" }
        assertThat(group.tasks.map { it.id }).containsExactly("alt", "heute", "spaeter", "ohne")
        assertThat(group.suggestedIds).containsExactly("alt", "heute")
    }

    @Test
    fun `planungsgruppen-lassen-Projekte-ohne-offene-Aufgaben-weg`() {
        val tree = tree(task("erledigt", parent = "praes", doneAt = at(today.minusDays(1))))
        assertThat(PmToday.planningGroups(tree, today)).isEmpty()
    }

    @Test
    fun `planungsgruppen-mit-Uebernommenem-stehen-oben`() {
        val tree = tree(
            task("a1", parent = "bericht", scheduled = iso(today)),
            task("t1", parent = "praes", scheduled = iso(today.minusDays(4)))
        )
        assertThat(PmToday.planningGroups(tree, today).map { it.group.id })
            .containsExactly("praes", "bericht").inOrder()
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
