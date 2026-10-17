package com.d_drostes_apps.aevum.data.pm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.model.PmType
import com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.PmSeedTitles
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmSeed
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
 * M19.1/M19.2: Projektmanagement gegen die ECHTE Room-Datenbank.
 *
 * WARUM NICHT GEGEN EINEN FAKE: Hier hängt die Evidenz an echtem SQL —
 * `IN (:ids)`-Sammelupdates (mit leerer Liste als Fall!), die
 * Fremdschlüssel-Kaskade beim Löschen eines Projekts, `parent_id IS NULL`
 * beim Berechnen der nächsten Sortiernummer, die Reihenfolge der Geschwister
 * und seit M19.2 die Neu-Nummerierung beim Drag & Drop. Ein handgeschriebener
 * Fake würde diese Semantik nur NACHBAUEN. Diese Suite fährt deshalb
 * Repository → DAO → Room → SQLite.
 *
 * ZEIT: `setDone` stempelt die Wanduhr. Deshalb wird hier mit dem ECHTEN
 * heutigen Datum gerechnet ([PmToday.todayIso]) statt mit einer festen
 * Kalenderachse — genau wie in CalendarResumeRoomIntegrationTest begründet.
 *
 * M19.2: Nur noch Projekte (Container) und Aufgaben (Blätter). Der
 * Titel-Resolver der Vorlage ist hier der reine Standard (englische Titel),
 * damit die Suite ohne Ressourcen läuft.
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
        repo = PmRepositoryImpl(
            db.pmNodeDao(),
            PmSeedTitles { key -> PmSeed.defaultTitle(key) }
        )
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
        val sub = repo.createNode(project, PmType.PROJECT, "2. Semester")
        val taskA = repo.createNode(sub, PmType.TASK, "Aufgabe A")
        val taskB = repo.createNode(sub, PmType.TASK, "Aufgabe B")
        val other = repo.createNode(project, PmType.PROJECT, "Projektbericht")
        assertThat(repo.getById(taskA)!!.sortOrder).isEqualTo(0)
        assertThat(repo.getById(taskB)!!.sortOrder).isEqualTo(1)
        // Neues Projekt im selben Elternteil zählt separat weiter.
        assertThat(repo.getById(other)!!.sortOrder).isEqualTo(1)
        assertThat(db.pmNodeDao().getAllOnce()).hasSize(5)
    }

    @Test
    fun `unzulaessiger-Elternteil-wird-zur-Wurzel-statt-verwaist`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val task = repo.createNode(project, PmType.TASK, "Aufgabe")
        // Eine AUFGABE kann kein Elternteil sein (sie ist ein Blatt) →
        // neue Knoten landen an der Wurzel.
        val child = repo.createNode(task, PmType.PROJECT, "Unterprojekt")
        assertThat(repo.getById(child)!!.parentId).isNull()
        // Unbekannter Elternteil ebenso.
        val ghost = repo.createNode("gibt-es-nicht", PmType.TASK, "Aufgabe 2")
        assertThat(repo.getById(ghost)!!.parentId).isNull()
    }

    @Test
    fun `Projekte-bekommen-kein-Datum-nur-Aufgaben`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium", scheduledDate = today)
        assertThat(repo.getById(project)!!.scheduledDate).isNull()
        val task = repo.createNode(project, PmType.TASK, "Aufgabe", scheduledDate = today)
        assertThat(repo.getById(task)!!.scheduledDate).isEqualTo(today)
    }

    @Test
    fun `unbekannter-Typ-faellt-auf-projekt-oder-aufgabe-zurueck`() = runTest {
        // An der Wurzel entsteht ein Projekt (die einzige Anlege-Art der UI) …
        val root = repo.createNode(null, "GALAXIE", "Etwas")
        assertThat(repo.getById(root)!!.type).isEqualTo(PmType.PROJECT)
        // … unter einem Projekt eine Aufgabe.
        val child = repo.createNode(root, "GALAXIE", "Unter etwas")
        assertThat(repo.getById(child)!!.type).isEqualTo(PmType.TASK)
    }

    // ── Inline-Aufgaben ───────────────────────────────────────────────────

    @Test
    fun `Aufgaben-werden-ein-je-Zeile-angelegt-und-Leerzeilen-verworfen`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val ids = repo.createTasks(
            parentId = project,
            titles = listOf("  Erste Hälfte lesen  ", "", "   ", "Zweite Hälfte lesen", "Entwurf aufschreiben"),
            scheduledDate = today
        )
        assertThat(ids).hasSize(3)
        val nodes = db.pmNodeDao().getAllOnce().filter { it.parentId == project }
        assertThat(nodes.map { it.title })
            .containsExactly("Erste Hälfte lesen", "Zweite Hälfte lesen", "Entwurf aufschreiben").inOrder()
        assertThat(nodes.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
        assertThat(nodes.all { it.scheduledDate == today }).isTrue()
        assertThat(nodes.all { it.type == PmType.TASK }).isTrue()
    }

    @Test
    fun `Aufgaben-ohne-Inhalt-schreiben-nichts`() = runTest {
        assertThat(repo.createTasks(null, listOf("", "   "))).isEmpty()
        assertThat(db.pmNodeDao().getAllOnce()).isEmpty()
    }

    @Test
    fun `Aufgaben-ohne-Elternteil-landen-an-der-Wurzel`() = runTest {
        val ids = repo.createTasks(parentId = "weg", titles = listOf("Eins"))
        assertThat(ids).hasSize(1)
        assertThat(repo.getById(ids.single())!!.parentId).isNull()
    }

    // ── Datum ─────────────────────────────────────────────────────────────

    @Test
    fun `Sammel-Datum-setzt-nur-Aufgaben-und-nur-vorhandene-IDs`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "2. Semester")
        val t1 = repo.createNode(sub, PmType.TASK, "Eins")
        val t2 = repo.createNode(sub, PmType.TASK, "Zwei")
        repo.setScheduled(listOf(t1, t2, sub, project, "unbekannt"), today)

        assertThat(repo.getById(t1)!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(t2)!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(sub)!!.scheduledDate).isNull()
        assertThat(repo.getById(project)!!.scheduledDate).isNull()
    }

    @Test
    fun `leere-ID-Liste-ist-kein-Fehler-und-aendert-nichts`() = runTest {
        repo.createNode(null, PmType.TASK, "Eins")
        repo.setScheduled(emptyList(), today)
        assertThat(db.pmNodeDao().getAllOnce().single().scheduledDate).isNull()
    }

    @Test
    fun `Datum-entfernen-macht-aus-der-Aufgabe-wieder-Backlog`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Eins", scheduledDate = today)
        repo.setScheduled(listOf(task), null)
        assertThat(repo.getById(task)!!.scheduledDate).isNull()
    }

    @Test
    fun `alle-offenen-Aufgaben-eines-Projekts-mit-einem-Tipp-auf-heute`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "2. Semester")
        val sibling = repo.createNode(project, PmType.PROJECT, "Projektbericht")
        val tasks = repo.createTasks(sub, (1..5).map { "Aufgabe $it" })
        val siblingTask = repo.createNode(sibling, PmType.TASK, "Fremde Aufgabe")
        // Eine Aufgabe schon erledigt → zählt nur bei onlyOpen = false
        repo.setDone(tasks.first(), true)

        val count = repo.setScheduledForSubtree(sub, today, onlyOpen = true)
        assertThat(count).isEqualTo(4)
        assertThat(repo.getById(tasks.first())!!.scheduledDate).isNull()
        assertThat(repo.getById(tasks[1])!!.scheduledDate).isEqualTo(today)
        assertThat(repo.getById(siblingTask)!!.scheduledDate).isNull()

        val all = repo.setScheduledForSubtree(sub, today, onlyOpen = false)
        assertThat(all).isEqualTo(5)
        assertThat(repo.getById(tasks.first())!!.scheduledDate).isEqualTo(today)
    }

    @Test
    fun `Sammel-Datum-wirkt-ueber-alle-Ebenen-des-Teilbaums`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "2. Semester")
        repo.createTasks(sub, listOf("A1", "A2"))
        val group = repo.createNode(sub, PmType.PROJECT, "Projektgruppe")
        repo.createTasks(group, listOf("B1"))
        assertThat(repo.setScheduledForSubtree(project, today, onlyOpen = true)).isEqualTo(3)
    }

    @Test
    fun `Sammel-Datum-auf-unbekannten-Knoten-liefert-null`() = runTest {
        repo.createNode(null, PmType.TASK, "Eins", scheduledDate = today)
        assertThat(repo.setScheduledForSubtree("weg", null)).isEqualTo(0)
    }

    // ── Erledigen ─────────────────────────────────────────────────────────

    @Test
    fun `abhaken-und-rueckgaengig-schreiben-den-Zeitstempel`() = runTest {
        val task = repo.createNode(null, PmType.TASK, "Eins")
        repo.setDone(task, true)
        val done = repo.getById(task)!!
        assertThat(done.doneAt).isNotNull()
        assertThat(PmToday.isoDateOf(done.doneAt!!)).isEqualTo(today)

        repo.setDone(task, false)
        assertThat(repo.getById(task)!!.doneAt).isNull()
    }

    @Test
    fun `ein-Projekt-laesst-sich-nicht-abhaken`() = runTest {
        // Regel: doneAt nur an Aufgaben — das Glas zählt keine Projektzeilen.
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        repo.setDone(project, true)
        assertThat(repo.getById(project)!!.doneAt).isNull()
    }

    @Test
    fun `Fortschritt-des-Projekts-waechst-mit-jeder-abgehakten-Aufgabe`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Präsentation")
        val ids = repo.createTasks(project, listOf("Eins", "Zwei", "Drei"))
        assertThat(PmTree(repo.getAllOnce()).progress(project)).isEqualTo(PmProgress(0, 3))
        repo.setDone(ids[0], true)
        repo.setDone(ids[2], true)
        assertThat(PmTree(repo.getAllOnce()).progress(project)).isEqualTo(PmProgress(2, 3))
        assertThat(PmTree(repo.getAllOnce()).progress(project).percent).isEqualTo(66)
    }

    // ── Drag & Drop (Reihenfolge) ──────────────────────────────────────────

    @Test
    fun `reorderTasks-setzt-die-Reihenfolge-der-offenen-Aufgaben-neu`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val a = repo.createNode(project, PmType.TASK, "A")
        val b = repo.createNode(project, PmType.TASK, "B")
        val c = repo.createNode(project, PmType.TASK, "C")
        val done = repo.createNode(project, PmType.TASK, "Erledigt", scheduledDate = today)
        repo.setDone(done, true)

        // Neue Reihenfolge der OFFENEN Aufgaben: C, A, B — erledigte rutschen dahinter.
        assertThat(repo.reorderTasks(project, listOf(c, a, b))).isTrue()
        assertThat(repo.getById(c)!!.sortOrder).isEqualTo(0)
        assertThat(repo.getById(a)!!.sortOrder).isEqualTo(1)
        assertThat(repo.getById(b)!!.sortOrder).isEqualTo(2)
        assertThat(repo.getById(done)!!.sortOrder).isEqualTo(3)
        assertThat(PmTree(repo.getAllOnce()).tasksOf(project).map { it.id })
            .containsExactly(c, a, b, done).inOrder()
    }

    @Test
    fun `reorderTasks-verlangt-die-exakte-Liste-und-aendert-sonst-nichts`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val a = repo.createNode(project, PmType.TASK, "A")
        val b = repo.createNode(project, PmType.TASK, "B")
        val before = repo.getAllOnce().associate { it.id to it.sortOrder }

        // Eine Aufgabe fehlt → nichts schreiben.
        assertThat(repo.reorderTasks(project, listOf(b))).isFalse()
        // Eine fremde ID dabei → nichts schreiben.
        assertThat(repo.reorderTasks(project, listOf(a, b, "gibt-es-nicht"))).isFalse()
        assertThat(repo.getAllOnce().associate { it.id to it.sortOrder }).isEqualTo(before)
    }

    @Test
    fun `reorderTasks-laesst-andere-Projekte-unberuehrt`() = runTest {
        val projectA = repo.createNode(null, PmType.PROJECT, "A")
        val projectB = repo.createNode(null, PmType.PROJECT, "B")
        val a1 = repo.createNode(projectA, PmType.TASK, "A1")
        val a2 = repo.createNode(projectA, PmType.TASK, "A2")
        val b1 = repo.createNode(projectB, PmType.TASK, "B1")
        val b1OrderBefore = repo.getById(b1)!!.sortOrder

        assertThat(repo.reorderTasks(projectA, listOf(a2, a1))).isTrue()

        assertThat(repo.getById(b1)!!.sortOrder).isEqualTo(b1OrderBefore)
        assertThat(PmTree(repo.getAllOnce()).tasksOf(projectB).map { it.id }).containsExactly(b1)
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
    fun `Umzug-in-ein-anderes-Projekt-haengt-hinten-an`() = runTest {
        val projectA = repo.createNode(null, PmType.PROJECT, "A")
        val projectB = repo.createNode(null, PmType.PROJECT, "B")
        repo.createTasks(projectB, listOf("B1", "B2"))
        val moved = repo.createNode(projectA, PmType.TASK, "Wandernd")

        assertThat(repo.move(moved, projectB, 99)).isTrue()
        val node = repo.getById(moved)!!
        assertThat(node.parentId).isEqualTo(projectB)
        assertThat(node.sortOrder).isEqualTo(2)
        assertThat(PmTree(repo.getAllOnce()).children(projectB).map { it.title })
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
    fun `Umzug-unter-eine-Aufgabe-wird-verweigert`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Projekt")
        val task = repo.createNode(project, PmType.TASK, "Aufgabe")
        val other = repo.createNode(null, PmType.PROJECT, "Anderes")
        assertThat(repo.move(other, task, 0)).isFalse()
        assertThat(repo.getById(other)!!.parentId).isNull()
    }

    @Test
    fun `Umzug-auf-unbekannten-Knoten-meldet-Fehler`() = runTest {
        assertThat(repo.move("gibt-es-nicht", null, 0)).isFalse()
    }

    @Test
    fun `Umzug-an-die-Wurzel-geht-nur-fuer-Projekte`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "Semester")
        val task = repo.createNode(sub, PmType.TASK, "Aufgabe")

        // Nutzer-Regel: „Man soll nur Projekte anlegen können" — Aufgaben
        // bleiben unter Projekten.
        assertThat(repo.move(task, null, 0)).isFalse()
        assertThat(repo.move(sub, null, 0)).isTrue()
        assertThat(repo.getById(sub)!!.parentId).isNull()
        assertThat(PmTree(repo.getAllOnce()).roots().map { it.id }).containsExactly(sub, project).inOrder()
    }

    // ── Löschen ───────────────────────────────────────────────────────────

    @Test
    fun `Loeschen-eines-Projekts-raeumt-den-kompletten-Teilbaum-ab`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Studium")
        val sub = repo.createNode(project, PmType.PROJECT, "Semester")
        repo.createTasks(sub, listOf("Eins", "Zwei", "Drei"))
        val untouched = repo.createNode(null, PmType.PROJECT, "Privat")
        val keptTask = repo.createNode(untouched, PmType.TASK, "Schreibtisch aufräumen")
        assertThat(db.pmNodeDao().getAllOnce()).hasSize(7)

        repo.delete(project)

        val rest = db.pmNodeDao().getAllOnce()
        assertThat(rest.map { it.id }).containsExactly(untouched, keptTask)
        assertThat(PmTree(rest).allTasks()).hasSize(1)
    }

    @Test
    fun `Loeschen-einer-einzelnen-Aufgabe-laesst-das-Projekt-bestehen`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Projekt")
        val ids = repo.createTasks(project, listOf("Eins", "Zwei"))
        repo.delete(ids[0])
        assertThat(repo.getAllOnce().map { it.id }).containsExactly(project, ids[1])
    }

    // ── Vorlage (Beispielstruktur) ────────────────────────────────────────

    @Test
    fun `Vorlage-wird-nur-einmal-angelegt`() = runTest {
        repo.insertExample()
        val first = repo.getAllOnce()
        assertThat(first).isNotEmpty()
        repo.insertExample()
        assertThat(repo.getAllOnce()).hasSize(first.size)
    }

    @Test
    fun `Vorlage-ueberschreibt-bereits-erreichten-Fortschritt-nicht`() = runTest {
        repo.insertExample()
        val firstTask = repo.getAllOnce().first { it.title == "Write a shopping list" }
        repo.setDone(firstTask.id, true)

        repo.insertExample()

        val after = repo.getById(firstTask.id)!!
        assertThat(after.doneAt).isNotNull()
        assertThat(PmTree(repo.getAllOnce()).progress("pm-seed-alltag"))
            .isEqualTo(PmProgress(done = 1, total = 5))
    }

    @Test
    fun `Vorlage-ist-mit-einer-Abfrage-vollstaendig-vorhanden`() = runTest {
        repo.insertExample()
        val tree = PmTree(repo.getAllOnce())
        assertThat(tree.roots().map { it.title })
            .containsExactly("Household & Daily Life", "Work & Education", "Health", "Finances").inOrder()
        assertThat(tree.allTasks()).hasSize(17)
        assertThat(tree.openTasks("pm-seed-alltag")).hasSize(5)
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
        val id = repo.createNode(null, PmType.PROJECT, "Projekt")
        repo.createTasks(id, listOf("Eins"))
        assertThat(repo.observeAll().first().map { it.title })
            .containsExactly("Projekt", "Eins").inOrder()
        assertThat(repo.observeAll().first().count { it.parentId == id }).isEqualTo(1)
    }

    @Test
    fun `Titel-aendern-behaelt-die-Struktur`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Alt")
        val task = repo.createNode(project, PmType.TASK, "Aufgabe")
        repo.updateNode(project, "Neu", "Notiz", "#FBBF24", "2026-12-01")
        val node = repo.getById(project)!!
        assertThat(node.title).isEqualTo("Neu")
        assertThat(node.note).isEqualTo("Notiz")
        assertThat(node.color).isEqualTo("#FBBF24")
        assertThat(node.dueDate).isEqualTo("2026-12-01")
        assertThat(node.parentId).isNull()
        // Kinder bleiben unberührt.
        assertThat(repo.getById(task)!!.parentId).isEqualTo(project)
    }

    @Test
    fun `leerer-Titel-nimmt-den-alten-Titel`() = runTest {
        val project = repo.createNode(null, PmType.PROJECT, "Bleibt")
        repo.updateNode(project, "   ", "", null, null)
        assertThat(repo.getById(project)!!.title).isEqualTo("Bleibt")
    }
}
