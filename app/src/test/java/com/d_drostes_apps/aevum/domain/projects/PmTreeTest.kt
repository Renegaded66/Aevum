package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.1/M19.2: Regeln des PM-Baums.
 *
 * Diese Suite prüft die EIGENSCHAFTEN, auf die sich UI, Fortschrittsbalken und
 * Glas stützen: Sortierung, Pfad, Teilbaum, Fortschritt, Umzugsverbote — und
 * ausdrücklich das Verhalten bei KAPUTTEN Daten (Waise, Selbstbezug, Zyklus).
 * Ohne diese Robustheit könnte ein einziger falscher Datensatz die App in eine
 * Endlosschleife oder einen Stack-Overflow treiben.
 *
 * M19.2: Nur noch zwei Arten — PROJECT (Container) und TASK (abhakbares
 * Blatt). Aufgaben liegen unter Projekten, Projekte dürfen beliebig
 * verschachtelt werden; an die Wurzel darf nur ein Projekt.
 */
class PmTreeTest {

    private val created = 1_700_000_000_000L

    private fun node(
        id: String,
        parent: String? = null,
        type: String = PmType.PROJECT,
        title: String = id.uppercase(),
        order: Int = 0,
        scheduled: String? = null,
        doneAt: Long? = null,
        color: String? = null
    ) = PmNode(
        id = id,
        parentId = parent,
        type = type,
        title = title,
        color = color,
        sortOrder = order,
        scheduledDate = scheduled,
        doneAt = doneAt,
        createdAt = created,
        updatedAt = created
    )

    /**
     * Fixture im neuen Modell:
     * Studium → 2. Semester → Projektgruppe → {Präsentation → 3 Aufgaben, Projektbericht → 1 Aufgabe}
     * Privat → 1 Aufgabe (direkt unter dem Projekt).
     */
    private fun fixture(): PmTree = PmTree(
        listOf(
            node("studium", null, PmType.PROJECT, "Studium", order = 0),
            node("semester", "studium", PmType.PROJECT, "2. Semester", order = 0),
            node("gruppe", "semester", PmType.PROJECT, "Projektgruppe", order = 0),
            node("praes", "gruppe", PmType.PROJECT, "Präsentation", order = 0),
            node("t1", "praes", PmType.TASK, "erste Hälfte lesen", order = 0, doneAt = created),
            node("t2", "praes", PmType.TASK, "zweite Hälfte lesen", order = 1),
            node("t3", "praes", PmType.TASK, "Entwurf aufschreiben", order = 2),
            node("bericht", "gruppe", PmType.PROJECT, "Projektbericht", order = 1),
            node("b1", "bericht", PmType.TASK, "Einleitung schreiben", order = 0),
            node("privat", null, PmType.PROJECT, "Privat", order = 1),
            node("p1", "privat", PmType.TASK, "Schreibtisch aufräumen", order = 0)
        )
    )

    // ── Struktur ──────────────────────────────────────────────────────────

    @Test
    fun `wurzeln in sortierter Reihenfolge`() {
        val tree = fixture()
        assertThat(tree.roots().map { it.id }).containsExactly("studium", "privat").inOrder()
    }

    @Test
    fun `kinder in sortierter Reihenfolge`() {
        val tree = fixture()
        assertThat(tree.children("gruppe").map { it.id }).containsExactly("praes", "bericht").inOrder()
        assertThat(tree.children("praes").map { it.id }).containsExactly("t1", "t2", "t3").inOrder()
        assertThat(tree.children("unbekannt")).isEmpty()
        assertThat(tree.children(null).map { it.id }).containsExactly("studium", "privat").inOrder()
    }

    @Test
    fun `kinder gleicher sortiernummer nach Erstellzeitpunkt und Titel`() {
        val tree = PmTree(
            listOf(
                node("a", null, PmType.PROJECT, "Zebra", order = 0),
                PmNode(
                    id = "b", parentId = null, type = PmType.PROJECT, title = "Alpha",
                    sortOrder = 0, createdAt = created, updatedAt = created
                )
            )
        )
        assertThat(tree.roots().map { it.title }).containsExactly("Alpha", "Zebra").inOrder()
    }

    @Test
    fun `pfad von der Wurzel bis zum Knoten`() {
        val tree = fixture()
        assertThat(tree.path("t2").map { it.id })
            .containsExactly("studium", "semester", "gruppe", "praes", "t2").inOrder()
    }

    @Test
    fun `tiefe zaehlt ab der Wurzel mit null`() {
        val tree = fixture()
        assertThat(tree.depth("studium")).isEqualTo(0)
        assertThat(tree.depth("semester")).isEqualTo(1)
        assertThat(tree.depth("gruppe")).isEqualTo(2)
        assertThat(tree.depth("praes")).isEqualTo(3)
        assertThat(tree.depth("t2")).isEqualTo(4)
    }

    @Test
    fun `brotkrumen zeigen die Vorfahren ohne den Knoten selbst`() {
        val tree = fixture()
        assertThat(tree.breadcrumb("praes")).isEqualTo("Studium › 2. Semester › Projektgruppe")
        assertThat(tree.breadcrumb("t2")).isEqualTo("Studium › 2. Semester › Projektgruppe › Präsentation")
        assertThat(tree.breadcrumb("praes", includeSelf = true))
            .isEqualTo("Studium › 2. Semester › Projektgruppe › Präsentation")
        assertThat(tree.breadcrumb("studium")).isEmpty()
    }

    @Test
    fun `teilbaum enthaelt alle Ebenen aber nicht den Knoten selbst`() {
        val tree = fixture()
        assertThat(tree.descendants("gruppe").map { it.id })
            .containsExactly("praes", "t1", "t2", "t3", "bericht", "b1")
        assertThat(tree.descendants("t2")).isEmpty()
        assertThat(tree.descendantsCount("gruppe")).isEqualTo(6)
        assertThat(tree.descendantsCount("studium")).isEqualTo(8)
    }

    // ── Aufgaben und Fortschritt ───────────────────────────────────────────

    @Test
    fun `aufgaben im teilbaum inklusive Knoten selbst wenn er eine Aufgabe ist`() {
        val tree = fixture()
        assertThat(tree.subtreeTasks("praes").map { it.id }).containsExactly("t1", "t2", "t3").inOrder()
        assertThat(tree.subtreeTasks("t1").map { it.id }).containsExactly("t1")
        assertThat(tree.subtreeTasks("privat").map { it.id }).containsExactly("p1")
        assertThat(tree.tasksOf("praes").map { it.id }).containsExactly("t1", "t2", "t3").inOrder()
        assertThat(tree.tasksOf("gruppe")).isEmpty() // nur direkte Kinder
    }

    @Test
    fun `fortschritt zaehlt erledigte Aufgaben im teilbaum`() {
        val tree = fixture()
        assertThat(tree.progress("praes")).isEqualTo(PmProgress(done = 1, total = 3))
        assertThat(tree.progress("gruppe")).isEqualTo(PmProgress(done = 1, total = 4))
        assertThat(tree.progress("studium")).isEqualTo(PmProgress(done = 1, total = 4))
        assertThat(tree.progress("privat")).isEqualTo(PmProgress(done = 0, total = 1))
        assertThat(tree.progress("t1")).isEqualTo(PmProgress(done = 1, total = 1))
        assertThat(tree.progress("bericht")).isEqualTo(PmProgress(done = 0, total = 1))
    }

    @Test
    fun `fortschritt eines leeren Projekts ist leer und nicht vollstaendig`() {
        val empty = PmProgress.EMPTY
        assertThat(empty.total).isEqualTo(0)
        assertThat(empty.fraction).isEqualTo(0f)
        // Wichtig für die UI: ein Projekt ohne Aufgaben darf NICHT als "fertig"
        // (100 %) gelten, sonst strahlen leere Projekte Gold.
        assertThat(empty.isComplete).isFalse()
        assertThat(PmProgress(done = 3, total = 3).isComplete).isTrue()
    }

    @Test
    fun `fortschrittsanteil und abgerundeter Prozentwert`() {
        val progress = PmProgress(done = 1, total = 3)
        assertThat(progress.fraction).isWithin(0.0001f).of(1f / 3f)
        assertThat(progress.percent).isEqualTo(33)
        assertThat(progress.label).isEqualTo("1/3")
        assertThat(progress.open).isEqualTo(2)
        assertThat(PmProgress(done = 5, total = 3).fraction).isEqualTo(1f)
    }

    @Test
    fun `offene aufgaben und alle aufgaben`() {
        val tree = fixture()
        assertThat(tree.openTasks("praes").map { it.id }).containsExactly("t2", "t3").inOrder()
        assertThat(tree.allTasks().map { it.id }).containsExactly("t1", "t2", "t3", "b1", "p1").inOrder()
        assertThat(tree.byType(PmType.PROJECT).map { it.id })
            .containsExactly("studium", "semester", "gruppe", "praes", "bericht", "privat").inOrder()
        assertThat(tree.byType(PmType.TASK).map { it.id }).containsExactly("t1", "t2", "t3", "b1", "p1").inOrder()
    }

    // ── Umzugsregeln ──────────────────────────────────────────────────────

    @Test
    fun `umzug auf sich selbst und in den eigenen teilbaum ist verboten`() {
        val tree = fixture()
        assertThat(tree.moveAllowed("praes", "praes")).isFalse()
        assertThat(tree.moveAllowed("gruppe", "praes")).isFalse()
        assertThat(tree.moveAllowed("gruppe", "t2")).isFalse()
        assertThat(tree.moveAllowed("studium", "semester")).isFalse()
        assertThat(tree.moveAllowed("studium", "t1")).isFalse()
    }

    @Test
    fun `umzug unter eine Aufgabe ist verboten`() {
        val tree = fixture()
        assertThat(tree.moveAllowed("t2", "t1")).isFalse()
        assertThat(tree.moveAllowed("bericht", "t1")).isFalse()
    }

    @Test
    fun `an die wurzel darf nur ein projekt`() {
        val tree = fixture()
        // Nutzer-Regel: „Man soll nur Projekte anlegen können."
        assertThat(tree.moveAllowed("praes", null)).isTrue()
        assertThat(tree.moveAllowed("t2", null)).isFalse()
    }

    @Test
    fun `erlaubte umzuege`() {
        val tree = fixture()
        assertThat(tree.moveAllowed("praes", "studium")).isTrue()
        assertThat(tree.moveAllowed("t2", "bericht")).isTrue()
        // Ziel fehlt → streng abgelehnt (nicht stillschweigend zur Wurzel).
        assertThat(tree.moveAllowed("praes", "unbekannt")).isFalse()
        // Knoten existiert nicht → abgelehnt.
        assertThat(tree.moveAllowed("gibt-es-nicht", "studium")).isFalse()
    }

    @Test
    fun `ist-nachfahre-erkennt-nur-echte-nachfahren-nicht-die-vorfahren`() {
        val tree = fixture()
        assertThat(tree.isDescendantOf("t2", "studium")).isTrue()
        assertThat(tree.isDescendantOf("studium", "studium")).isTrue()
        assertThat(tree.isDescendantOf("studium", "t2")).isFalse()
        assertThat(tree.isDescendantOf("privat", "studium")).isFalse()
    }

    // ── Kaputte Daten (Robustheit) ────────────────────────────────────────

    @Test
    fun `waise-gilt-als-wurzel-und-geht-nicht-verloren`() {
        val tree = PmTree(
            listOf(
                node("a", null, PmType.PROJECT, "Projekt"),
                node("verwaist", "geloescht", PmType.TASK, "Aufgabe ohne Elternteil")
            )
        )
        assertThat(tree.roots().map { it.id }).containsExactly("a", "verwaist")
        assertThat(tree.path("verwaist").map { it.id }).containsExactly("verwaist")
        assertThat(tree.depth("verwaist")).isEqualTo(0)
    }

    @Test
    fun `selbstbezug-gilt-als-wurzel-ohne-endlosschleife`() {
        val tree = PmTree(listOf(node("x", "x", PmType.PROJECT, "Selbstbezug")))
        assertThat(tree.roots().map { it.id }).containsExactly("x")
        assertThat(tree.path("x").map { it.id }).containsExactly("x")
        assertThat(tree.descendants("x")).isEmpty()
    }

    @Test
    fun `zyklus-bricht-traversierungen-ab-statt-zu-haengen`() {
        // a → b → a (nicht als Wurzel erreichbar). Kein Stack-Overflow,
        // keine Endlosschleife — die Suite würde hier sonst nie fertig.
        val tree = PmTree(
            listOf(
                node("root", null, PmType.PROJECT, "Echt"),
                node("a", "b", PmType.PROJECT, "A"),
                node("b", "a", PmType.PROJECT, "B")
            )
        )
        assertThat(tree.descendants("a").map { it.id }).containsExactly("b")
        assertThat(tree.path("a").size).isAtMost(3)
        assertThat(tree.visibleRows(setOf("root"))).hasSize(1)
        assertThat(tree.subtreeTasks("a")).isEmpty()
    }

    // ── Baum-Ansicht ──────────────────────────────────────────────────────

    @Test
    fun `sichtbare Zeilen-haengen-an-den-aufgeklappten-vorfahren`() {
        val tree = fixture()
        assertThat(tree.visibleRows(emptySet()).map { it.node.id }).containsExactly("studium", "privat").inOrder()
        assertThat(tree.visibleRows(setOf("studium")).map { it.node.id })
            .containsExactly("studium", "semester", "privat").inOrder()
        assertThat(tree.visibleRows(setOf("studium", "semester")).map { it.node.id })
            .containsExactly("studium", "semester", "gruppe", "privat").inOrder()
        // Die Tiefe steuert die Einrückung; die erste Aufgabe liegt auf 4.
        val rows = tree.visibleRows(setOf("studium", "semester", "gruppe", "praes"))
        assertThat(rows.map { it.node.id }).containsExactly(
            "studium", "semester", "gruppe", "praes", "t1", "t2", "t3", "bericht", "privat"
        ).inOrder()
        assertThat(rows.first { it.node.id == "t3" }.depth).isEqualTo(4)
        assertThat(rows.first { it.node.id == "t1" }.hasChildren).isFalse()
        assertThat(rows.first { it.node.id == "praes" }.hasChildren).isTrue()
    }

    // ── Projekt-Gruppen ───────────────────────────────────────────────────

    @Test
    fun `aufgabengruppen-fassen-aufgaben-je-projekt-zusammen`() {
        val tree = fixture()
        val groups = tree.taskGroups()
        assertThat(groups.map { it.id }).containsExactly("praes", "bericht", "privat")
        val praes = groups.first { it.id == "praes" }
        assertThat(praes.tasks.map { it.id }).containsExactly("t1", "t2", "t3").inOrder()
        assertThat(praes.breadcrumb).isEqualTo("Studium › 2. Semester › Projektgruppe")
        assertThat(praes.isSynthetic).isFalse()
        assertThat(groups.none { it.isSynthetic }).isTrue()
        assertThat(groups.first { it.id == "privat" }.tasks.map { it.id }).containsExactly("p1")
    }

    @Test
    fun `aufgaben-ohne-projekt-landen-in-einer-synthetischen-gruppe`() {
        // Altbestand-Fall (vor der Migration denkbar): eine Aufgabe hängt
        // direkt an der Wurzel. Sie darf nicht unsichtbar werden.
        val tree = PmTree(listOf(node("loose", null, PmType.TASK, "Herkunftslos")))
        val groups = tree.taskGroups()
        assertThat(groups.map { it.id }).containsExactly(PmTree.UNASSIGNED_TASK_ID)
        assertThat(groups.first().isSynthetic).isTrue()
        assertThat(groups.first().tasks.map { it.id }).containsExactly("loose")
    }

    @Test
    fun `leerer Baum-keine Wurzeln, keine Gruppen, kein Absturz`() {
        val tree = PmTree.EMPTY
        assertThat(tree.roots()).isEmpty()
        assertThat(tree.allTasks()).isEmpty()
        assertThat(tree.taskGroups()).isEmpty()
        assertThat(tree.visibleRows(setOf("irgendwas"))).isEmpty()
        assertThat(tree.progress("gibt-es-nicht")).isEqualTo(PmProgress.EMPTY)
    }
}
