package com.d_drostes_apps.aevum.data.pm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmToday
import com.d_drostes_apps.aevum.domain.projects.PmTree
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M19.1: Projektmanagement gegen die ECHTE Room-Datenbank.
 *
 * WARUM NICHT GEGEN EINEN FAKE: Hier hängt die Evidenz an echtem SQL —
 * `IN (:ids)`-Sammelupdates (mit leerer Liste als Fall!), die
 * Fremdschlüssel-Kaskade beim Löschen eines Projekts, `parent_id IS NULL`
 * beim Berechnen der nächsten Sortiernummer und die Reihenfolge der
 * Geschwister. Ein handgeschriebener Fake würde diese Semantik nur
 * NACHBAUEN. Diese Suite fährt deshalb Repository → DAO → Room → SQLite.
 *
 * ZEIT: `setDone` stempelt die Wanduhr. Deshalb wird hier mit dem ECHTEN
 * heutigen Datum gerechnet ([PmToday.todayIso]) statt mit einer festen
 * Kalenderachse — genau wie in CalendarResumeRoomIntegrationTest begründet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PmRepositoryRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PmRepositoryImpl

    private val today: String get() = PmToday.todayIso()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = PmRepositoryImpl(db.pmNodeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Anlegen ───────────────────────────────────────────────────────────

    @Test
    fun `Wurzel-anlegen-setzt-Reihenfolge-und-kein-Datum`() = runTest {
        val id = repo.createNode(parentId = null, type = PmType.PROJECT, title = "  Studium  ")
        val node = repo.getById(id)!!
        assertThat(node.parentId).isNull()
        assertThat(node.title).isEqualTo("Studium")
        assertThat(node.sortOrder).isEqualTo(0)
        assertThat(node.doneAt).isNull()
        assertThat(node.scheduledDate).isNull()

        val second = repo.createNode(parentId = null, type = PmType.PROJECT, title = "Privat")
        assertThat(repo.getById(second)!!.sortOrder).isEqualTo(1)
    }

    @Test
    fun `Kinder-bekommen-fortlaufende-Reihenfolge-je-Elternteil`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val task = repo.createNode(project, PmType.TASK, "Präsentation")
        val idA = repo.createNode(task, PmType.TODO, "Schritt A")
        val idB = repo.createNode(task, PmType.TODO, "Schritt B")
        val other = repo.createNode(project, PmType.TASK, "Projektbericht")
        assertThat(repo.getById(idA)!!.sortOrder).isEqualTo(0)
        assertThat(repo.getById(idB)!!.sortOrder).isEqualTo(1)
        // Neue Aufgabe im selben Projekt zählt separat.
        assertThat(repo.getById(other)!!.sortOrder).isEqualTo(1)
        assertThat(db.pmNodeDao().getAllOnce()).hasSize(5)
    }

    @Test
    fun `unzulaessiger-Elternteil-wird-zur-Wurzel-statt-verwaist`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Aufgabe")
        val todo = repo.createNode(task, PmType.TODO, "Schritt")
        // Ein Todo kann kein Elternteil sein → neue Knoten landen an der Wurzel.
        val child = repo.createNode(todo, PmType.PROJECT, "Unterprojekt")
        assertThat(repo.getById(child)!!.parentId).isNull()
        // Unbekannter Elternteil ebenso.
        val ghost = repo.createNode("gibt-es-nicht", PmType.TASK, "Aufgabe 2")
        assertThat(repo.getById(ghost)!!.parentId).isNull()
    }

    @Test
    fun `Container-bekommen-kein-Datum-nur-Todos`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium", scheduledDate = today)
        assertThat(repo.getById(project)!!.scheduledDate).isNull()
        val todo = repo.createNode(null, PmType.TODO, "Schritt", scheduledDate = today)
        assertThat(repo.getById(todo)!!.scheduledDate).isEqualTo(today)
    }

    @Test
    fun `unbekannter-Typ-faellt-auf-Aufgabe-zurueck`() = runTest {
        val id = repo.createNode(null, "GALAXIE", "Etwas")
        assertThat(repo.getById(id)!!.type).isEqualTo(PmType.TASK)
    }

    // ── Sammel-Eingabe ────────────────────────────────────────────────────

    @Test
    fun `Sammel-Eingabe-legt-ein-Todo-je-Zeile-an-und-verwirft-Leerzeilen`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Präsentation")
        val ids = repo.createTodos(
            parentId = task,
            titles = listOf("  Erste Hälfte lesen  ", "", "   ", "Zweite Hälfte lesen", "Entwurf aufschreiben"),
            scheduledDate = today
        )
        assertThat(ids).hasSize(3)
        val nodes = db.pmNodeDao().getAllOnce().filter { it.parentId == task }
        assertThat(nodes.map { it.title })
            .containsExactly("Erste Hälfte lesen", "Zweite Hälfte lesen", "Entwurf aufschreiben").inOrder()
        assertThat(nodes.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
        assertThat(nodes.all { it.scheduledDate == today }).isTrue()
        assertThat(nodes.all { it.type == PmType.TODO }).isTrue()
    }

    @Test
    fun `Sammel-Eingabe-ohne-Inhalt-schreibt-nichts`() = runTest {
        assertThat(repo.createTodos(null, listOf("", "   "))).isEmpty()
        assertThat(db.pmNodeDao().getAllOnce()).isEmpty()
    }

    @Test
    fun `Sammel-Eingabe-ohne-Elternteil-landet-an-der-Wurzel`() = runTest {
        val ids = repo.createTodos(parentId = "weg", titles = listOf("Eins"))
        assertThat(ids).hasSize(1)
        assertThat(repo.getById(ids.single())!!.parentId).isNull()
    }

    // ── Datum ─────────────────────────────────────────────────────────────

    @Test
    fun `Sammel-Datum-setzt-nur-Todos-und-nur-vorhandene-IDs`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val task = repo.createNode(project, PmType.TASK, "Präsentation")
        val t1 = repo.createNode(task, PmType.TODO, "Eins")
        val t2 = repo.createNode(task, PmType.TODO, "Zwei")
        repo.setScheduled(listOf(t1, t2, task, project, "unbekannt"), today)

        assertThat(repo.getById(t1)!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(t2)!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(task)!!.scheduledDate).isNull()
        assertThat(repo.getById(project)!!.scheduledDate).isNull()
    }

    @Test
    fun `leere-ID-Liste-ist-kein-Fehler-und-aendert-nichts`() = runTest {
        repo.createNode(null, PmType.TODO, "Eins")
        repo.setScheduled(emptyList(), today)
        assertThat(db.pmNodeDao().getAllOnce().single().scheduledDate).isNull()
    }

    @Test
    fun `Datum-entfernen-macht-aus-dem-Todo-wieder-Backlog`() = runTest {
        val todo = repo.createNode(null, PmType.TODO, "Eins", scheduledDate = today)
        repo.setScheduled(listOf(todo), null)
        assertThat(repo.getById(todo)!!.scheduledDate).isNull()
    }

    @Test
    fun `alle-offenen-Schritte-einer-Aufgabe-mit-einem-Tipp-auf-heute`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val task = repo.createNode(project, PmType.TASK, "Präsentation")
        val sibling = repo.createNode(project, PmType.TASK, "Projektbericht")
        val steps = repo.createTodos(task, (1..5).map { "Schritt $it" })
        val siblingStep = repo.createNode(sibling, PmType.TODO, "Fremder Schritt")
        // Einen Schritt schon erledigt → zählt nur bei onlyOpen = false
        repo.setDone(steps.first(), true)

        val count = repo.setScheduledForSubtree(task, today, onlyOpen = true)
        assertThat(count).isEqualTo(4)
        assertThat(repo.getById(steps.first())!!.scheduledDate).isNull()
        assertThat(repo.getById(steps[1])!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(siblingStep)!!.scheduledDate).isNull()

        val all = repo.setScheduledForSubtree(task, today, onlyOpen = false)
        assertThat(all).isEqualTo(5)
        assertThat(repo.getById(steps.first())!!.scheduledDate).isEqualTo(today)
    }

    @Test
    fun `Sammel-Datum-wirkt-ueber-alle-Ebenen-des-Teilbaums`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "2. Semester")
        val category = repo.createNode(sub, PmType.CATEGORY, "Projektgruppe")
        val taskA = repo.createNode(category, PmType.TASK, "Präsentation")
        val taskB = repo.createNode(category, PmType.TASK, "Bericht")
        repo.createTodos(taskA, listOf("A1", "A2"))
        repo.createTodos(taskB, listOf("B1"))
        assertThat(repo.setScheduledForSubtree(project, today, onlyOpen = true)).isEqualTo(3)
    }

    @Test
    fun `Sammel-Datum-auf-unbekannten-Knoten-liefert-null`() = runTest {
        repo.createNode(null, PmType.TODO, "Eins", scheduledDate = today)
        assertThat(repo.setScheduledForSubtree("weg", null)).isEqualTo(0)
    }

    // ── Erledigen ─────────────────────────────────────────────────────────

    @Test
    fun `abhaken-und-rueckgaengig-schreiben-den-Zeitstempel`() = runTest {
        val todo = repo.createNode(null, PmType.TODO, "Eins")
        repo.setDone(todo, true)
        val done = repo.getById(todo)!!
        assertThat(done.doneAt).isNotNull()
        assertThat(PmToday.isoDateOf(done.doneAt!!)).isEqualTo(today)

        repo.setDone(todo, false)
        assertThat(repo.getById(todo)!!.doneAt).isNull()
    }

    @Test
    fun `Fortschritt-der-Aufgabe-waechst-mit-jedem-abgehakten-Schritt`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Präsentation")
        val ids = repo.createTodos(task, listOf("Eins", "Zwei", "Drei"))
        assertThat(PmTree(repo.getAllOnce()).progress(task)).isEqualTo(PmProgress(0, 3))
        repo.setDone(ids[0], true)
        repo.setDone(ids[2], true)
        assertThat(PmTree(repo.getAllOnce()).progress(task)).isEqualTo(PmProgress(2, 3))
        assertThat(PmTree(repo.getAllOnce()).progress(task).percent).isEqualTo(66)
    }

    // ── Umzug und Sortierung ──────────────────────────────────────────────

    @Test
    fun `Umzug-an-den-Anfang-sortiert-Geschwister-neu`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val a = repo.createNode(project, PmType.TASK, "A")
        val b = repo.createNode(project, PmType.TASK, "B")
        val c = repo.createNode(project, PmType.TASK, "C")

        assertThat(repo.move(c, project, 0)).isTrue()
        assertThat(PmTree(repo.getAllOnce()).children(project).map { it.id })
            .containsExactly(c, a, b).inOrder()
        assertThat(repo.getById(c)!!.sortOrder).isEqualTo(0)
        assertThat(repo.getById(a)!!.sortOrder).isEqualTo(1)
        assertThat(repo.getById(b)!!.sortOrder).isEqualTo(2)
    }

    @Test
    fun `Umzug-in-eine-andere-Aufgabe-haengt-hinten-an`() = runTest {
        val taskA = repo.createNode(null, PmType.TASK, "A")
        val taskB = repo.createNode(null, PmType.TASK, "B")
        repo.createTodos(taskB, listOf("B1", "B2"))
        val moved = repo.createNode(taskA, PmType.TODO, "Wandernd")

        assertThat(repo.move(moved, taskB, 99)).isTrue()
        val node = repo.getById(moved)!!
        assertThat(node.parentId).isEqualTo(taskB)
        assertThat(node.sortOrder).isEqualTo(2)
        assertThat(PmTree(repo.getAllOnce()).children(taskB).map { it.title })
            .containsExactly("B1", "B2", "Wandernd").inOrder()
    }

    @Test
    fun `Umzug-in-den-eigenen-Teilbaum-wird-verweigert-und-aendert-nichts`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "Semester")
        val task = repo.createNode(sub, PmType.TASK, "Aufgabe")
        val before = repo.getAllOnce().associate { it.id to it.parentId }

        assertThat(repo.move(project, sub, 0)).isFalse()
        assertThat(repo.move(sub, task, 0)).isFalse()
        assertThat(repo.move(project, project, 0)).isFalse()
        assertThat(repo.getAllOnce().associate { it.id to it.parentId }).isEqualTo(before)
    }

    @Test
    fun `Umzug-unter-ein-Todo-wird-verweigert`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Aufgabe")
        val todo = repo.createNode(task, PmType.TODO, "Schritt")
        val other = repo.createNode(null, PmType.TASK, "Andere")
        assertThat(repo.move(other, todo, 0)).isFalse()
        assertThat(repo.getById(other)!!.parentId).isNull()
    }

    @Test
    fun `Umzug-auf-unbekannten-Knoten-meldet-Fehler`() = runTest {
        assertThat(repo.move("gibt-es-nicht", null, 0)).isFalse()
    }

    @Test
    fun `Umzug-an-die-Wurzel-funktioniert`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val task = repo.createNode(project, PmType.TASK, "Aufgabe")
        assertThat(repo.move(task, null, 0)).isTrue()
        assertThat(repo.getById(task)!!.parentId).isNull()
        assertThat(PmTree(repo.getAllOnce()).roots().map { it.id }).containsExactly(task, project).inOrder()
    }

    // ── Löschen ───────────────────────────────────────────────────────────

    @Test
    fun `Loeschen-eines-Projekts-raeumt-den-kompletten-Teilbaum-ab`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "Semester")
        val task = repo.createNode(sub, PmType.TASK, "Präsentation")
        repo.createTodos(task, listOf("Eins", "Zwei", "Drei"))
        val untouched = repo.createNode(null, PmType.PROJECT, "Privat")
        repo.createNode(untouched, PmType.TODO, "Schreibtisch aufräumen")
        assertThat(db.pmNodeDao().getAllOnce()).hasSize(8)

        repo.delete(project)

        val rest = db.pmNodeDao().getAllOnce()
        assertThat(rest.map { it.id }).containsExactly(untouched, rest.first { it.parentId == untouched }.id)
        assertThat(PmTree(rest).allTodos()).hasSize(1)
    }

    @Test
    fun `Loeschen-eines-einzelnen-Schritts-laesst-die-Aufgabe-bestehen`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Aufgabe")
        val ids = repo.createTodos(task, listOf("Eins", "Zwei"))
        repo.delete(ids[0])
        assertThat(repo.getAllOnce().map { it.id }).containsExactly(task, ids[1])
    }

    // ── Beispielstruktur ──────────────────────────────────────────────────

    @Test
    fun `Beispielstruktur-wird-nur-einmal-angelegt`() = runTest {
        repo.insertExample()
        val first = repo.getAllOnce()
        assertThat(first).isNotEmpty()
        repo.insertExample()
        assertThat(repo.getAllOnce()).hasSize(first.size)
    }

    @Test
    fun `Beispielstruktur-ueberschreibt-bereits-erreichten-Fortschritt-nicht`() = runTest {
        repo.insertExample()
        val firstStep = repo.getAllOnce().first { it.title == "Erste Hälfte der Quelle lesen" }
        repo.setDone(firstStep.id, true)

        repo.insertExample()

        val after = repo.getById(firstStep.id)!!
        assertThat(after.doneAt).isNotNull()
        assertThat(PmTree(repo.getAllOnce()).progress("pm-seed-praesentation"))
            .isEqualTo(PmProgress(done = 1, total = 15))
    }

    @Test
    fun `Beispielstruktur-ist-mit-einer-Abfrage-vollstaendig-vorhanden`() = runTest {
        repo.insertExample()
        val tree = PmTree(repo.getAllOnce())
        assertThat(tree.roots().map { it.title }).containsExactly("Studium", "Privat").inOrder()
        assertThat(tree.allTodos()).hasSize(23)
        assertThat(tree.openTodos("pm-seed-praesentation")).hasSize(15)
    }

    @Test
    fun `alles-loeschen-leert-den-Baum`() = runTest {
        repo.insertExample()
        repo.deleteAll()
        assertThat(repo.getAllOnce()).isEmpty()
    }

    // ── Lesefluss ─────────────────────────────────────────────────────────

    @Test
    fun `Flow-liefert-den-aktuellen-Stand-der-Datenbank`() = runTest {
        val id = repo.createNode(null, PmType.TASK, "Aufgabe")
        repo.createTodos(id, listOf("Eins"))
        assertThat(repo.observeAll().first().map { it.title })
            .containsExactly("Aufgabe", "Eins").inOrder()
        assertThat(repo.observeAll().first().count { it.parentId == id }).isEqualTo(1)
    }

    @Test
    fun `Titel-aendern-behaelt-die-Struktur`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Alt")
        val todo = repo.createNode(task, PmType.TODO, "Schritt")
        repo.updateNode(task, "Neu", "Notiz", "#FBBF24", "2026-12-01")
        val node = repo.getById(task)!!
        assertThat(node.title).isEqualTo("Neu")
        assertThat(node.note).isEqualTo("Notiz")
        assertThat(node.color).isEqualTo("#FBBF24")
        assertThat(node.dueDate).isEqualTo("2026-12-01")
        assertThat(node.parentId).isNull()
        // Kinder bleiben unberührt.
        assertThat(repo.getById(todo)!!.parentId).isEqualTo(task)
    }

    @Test
    fun `leerer-Titel-nimmt-den-alten-Titel`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Bleibt")
        repo.updateNode(task, "   ", "", null, null)
        assertThat(repo.getById(task)!!.title).isEqualTo("Bleibt")
    }
}
