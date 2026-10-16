package com.d_drostes_apps.aevum.ui.screens.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.repository.PmCelebrationStore
import com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M19.1: Der komplette Tagesablauf gegen echte Datenbank + echtes ViewModel.
 *
 * ── WARUM DIESE SUITE NEBEN DEN DOMAIN-TESTS EXISTIERT ────────────────────
 *
 * Die reinen Tests (PmTreeTest, PmTodayPlanTest, PmCelebrationGateTest) beweisen
 * die Regeln. Hier wird die VERDRAHTUNG geprüft — genau die Stelle, an der ein
 * korrektes Regelwerk trotzdem falsch benutzt werden kann:
 *
 *  1. **Feier-Gate im ViewModel.** Das Feuerwerk darf nur beim Übergang auf
 *     „voll" erscheinen und nicht bei jeder Datenbank-Emission. Der Schalter
 *     sitzt im ViewModel (persistierter [PmCelebrationStore]) — die reine
 *     Gate-Logik ist getestet, ihre Benutzung hier.
 *  2. **Morgenritual.** `openPlanner` wählt vor, `applyPlanner` schreibt — ein
 *     Tipp muss die gewählten Schritte wirklich auf den Tag setzen.
 *  3. **Ein-Tipp-Massenweg.** „Alle offenen Schritte einer Aufgabe → heute".
 *  4. **Glas-Kennzahl.** Sie muss sich nach jedem Abhaken ändern (Juwel dazu,
 *     Gegenplatz weg) und bei vollständigem Tag auf 1,0 stehen.
 *
 * ZEIT: Es wird mit dem ECHTEN heutigen Datum gerechnet, weil `setDone` die
 * Wanduhr stempelt (Robolectric kann die Wanduhr nicht verschieben).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PmProjectsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PmRepositoryImpl
    private lateinit var store: PmCelebrationStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PmRepositoryImpl(db.pmNodeDao())
        store = PmCelebrationStore(context)
        store.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = ProjectsViewModel(repo, store)

    /** Wartet auf den ersten Zustand, der die Bedingung erfüllt. */
    private fun ProjectsViewModel.await(
        predicate: (PmProjectsUiState) -> Boolean
    ): PmProjectsUiState = runBlocking {
        val vm = this@await
        var result: PmProjectsUiState? = null
        kotlinx.coroutines.withTimeout(10_000L) {
            vm.uiState.first { state ->
                if (state.loaded && predicate(state)) {
                    result = state
                    true
                } else {
                    false
                }
            }
        }
        result!!
    }

    // ── Beispielstruktur → Tagesplan ───────────────────────────────────────

    @Test
    fun `Beispielstruktur-erzeugt-einen-Tagesplan-mit-zehn-Schritten`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()

        val state = vm.await { it.totalToday > 0 }

        assertThat(state.totalToday).isEqualTo(10)
        assertThat(state.doneToday).isEqualTo(0)
        assertThat(state.pendingToday).isEqualTo(10)
        assertThat(state.gems).isEmpty()
        assertThat(state.ghostCount).isEqualTo(10)
        assertThat(state.fraction).isEqualTo(0f)
        assertThat(state.groups).hasSize(1)
        assertThat(state.groups.first().title).isEqualTo("Präsentation")
        // Gesamtfortschritt der Aufgabe: 15 Schritte insgesamt, keiner erledigt.
        assertThat(state.groups.first().progress.total).isEqualTo(15)
        // Drei Projekte: Studium, 2. Semester (Unterprojekt!) und Privat —
        // die Struktur ist absichtlich offen, Unterprojekte sind Projekt-Knoten.
        assertThat(state.projectCount).isEqualTo(3)
        assertThat(state.taskCount).isEqualTo(3)
        assertThat(state.totalTodos).isEqualTo(23)
        // Der Baum steht an der Wurzel und der Rückstand ist sichtbar.
        assertThat(state.treeRows.map { it.node.title }).containsExactly("Studium", "Privat")
        assertThat(state.backlogTotal).isEqualTo(13)
    }

    @Test
    fun `ohne-Daten-ist-der-Zustand-leer-aber-geladen`(): Unit = runBlocking {
        val vm = viewModel()
        val state = vm.await { true }
        assertThat(state.isCompletelyEmpty).isTrue()
        assertThat(state.totalToday).isEqualTo(0)
        assertThat(state.groups).isEmpty()
        assertThat(state.treeRows).isEmpty()
        assertThat(state.celebrating).isFalse()
    }

    // ── Abhaken → Juwelen → volles Glas ────────────────────────────────────

    @Test
    fun `Abhaken-erzeugt-ein-Juwel-in-der-Farbe-der-Aufgabe`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val firstTodo = start.todayState!!.groups.first().rows.first { !it.done }.node

        vm.toggleTodo(firstTodo)

        val state = vm.await { it.doneToday == 1 }
        assertThat(state.gems).hasSize(1)
        assertThat(state.gems.first().id).isEqualTo(firstTodo.id)
        assertThat(state.gems.first().label).isEqualTo(firstTodo.title)
        // Die Präsentation trägt im Beispiel die Farbe #FBBF24 — das Juwel
        // verrät damit, woran der Tag gearbeitet wurde.
        assertThat(state.gems.first().colorHex).isEqualTo("#fbbf24")
        assertThat(state.ghostCount).isEqualTo(9)
        assertThat(state.fraction).isWithin(0.0001f).of(0.1f)
        // Der Funke darf genau einmal laufen.
        assertThat(state.lastCompletedId).isEqualTo(firstTodo.id)
        // Kein Feuerwerk, solange nicht alles erledigt ist.
        assertThat(state.celebrating).isFalse()
    }

    @Test
    fun `Rueckgaengig-nimmt-das-Juwel-wieder-aus-dem-Glas`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val firstTodo = start.todayState!!.groups.first().rows.first { !it.done }.node

        vm.toggleTodo(firstTodo)
        vm.await { it.doneToday == 1 }
        vm.toggleTodo(repo.getById(firstTodo.id)!!)

        val state = vm.await { it.doneToday == 0 }
        assertThat(state.gems).isEmpty()
        assertThat(state.ghostCount).isEqualTo(10)
        assertThat(state.lastCompletedId).isNull()
    }

    @Test
    fun `vollstaendiger-Tag-feiert-genau-einmal`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val rows = start.todayState!!.groups.flatMap { it.rows }.filter { !it.done }

        rows.forEach { row -> vm.toggleTodo(row.node) }

        val full = vm.await { it.allDone }
        assertThat(full.celebrating).isTrue()
        assertThat(full.fraction).isEqualTo(1f)
        assertThat(full.gems).hasSize(10)
        assertThat(full.ghostCount).isEqualTo(0)
        // Der Feierstand ist festgehalten (überlebt Prozess-Neustart).
        assertThat(store.state().celebratedDone).isEqualTo(10)

        // Neuer ViewModel (z. B. nach erneutem Öffnen der Seite): NICHT noch
        // einmal feiern — der Beweis, dass kein Dauerfeuerwerk entsteht.
        val second = viewModel()
        val again = second.await { it.allDone }
        assertThat(again.celebrating).isFalse()

        // Nach dem Schließen der Feier ist Ruhe.
        second.finishCelebration()
        val quiet = second.await { !it.celebrating && it.allDone }
        assertThat(quiet.celebrating).isFalse()
        assertThat(quiet.gems).hasSize(10)
    }

    // ── Morgenritual (Tag planen) ───────────────────────────────────────────

    @Test
    fun `Tag-planen-oeffnet-mit-Vorauswahl-und-uebernimmt-in-einem-Schritt`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday > 0 }

        vm.openPlanner()
        val plannerState = vm.await { it.plannerOpen }
        // Vorausgewählt sind genau die bereits geplanten Schritte.
        assertThat(plannerState.plannerSelection).hasSize(10)
        assertThat(plannerState.plannerDate).isNull()
        assertThat(plannerState.backlog).hasSize(3)

        // Nutzer will heute mehr schaffen: komplette Rückstände dazunehmen.
        val backlogIds = plannerState.backlog.flatMap { group -> group.todos.map { it.id } }
        vm.setPlannerSelection(backlogIds, selected = true)
        vm.applyPlanner()

        val afterPlan = vm.await { it.totalToday == 23 }
        assertThat(afterPlan.plannerOpen).isFalse()
        assertThat(afterPlan.backlogTotal).isEqualTo(0)
        assertThat(afterPlan.groups).hasSize(3)
        assertThat(afterPlan.pendingToday).isEqualTo(23)
    }

    @Test
    fun `Tag-planen-kann-auf-morgen-verschieben`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday > 0 }

        vm.openPlanner()
        vm.await { it.plannerOpen }
        vm.setPlannerDate(java.time.LocalDate.now().plusDays(1).toString())
        vm.applyPlanner()

        val state = vm.await { it.totalToday == 0 }
        // Nichts mehr für heute — alle 23 offenen Schritte stehen jetzt als
        // "was noch kommt" im Backlog (die 10 alten sind mitgewandert).
        assertThat(state.backlogTotal).isEqualTo(23)
    }

    @Test
    fun `Schliessen-des-Sheets-aendert-nichts`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday > 0 }
        vm.openPlanner()
        vm.await { it.plannerOpen }
        vm.closePlanner()
        val state = vm.await { !it.plannerOpen }
        assertThat(state.totalToday).isEqualTo(10)
    }

    // ── Ein-Tipp-Massenweg ────────────────────────────────────────────────

    @Test
    fun `alle-offenen-Schritte-einer-Aufgabe-auf-heute-mit-einem-Tipp`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val taskId = start.groups.first().group.node!!.id
        val today = start.today.toString()

        // Ausgangslage herstellen: alle Schritte der Aufgabe in den Backlog.
        vm.removeFromPlan(repo.getAllOnce().filter { it.isTodo }.map { it.id })
        val clean = vm.await { it.totalToday == 0 }
        assertThat(clean.backlogTotal).isEqualTo(23)

        // Ein Tipp auf der Aufgabenkarte.
        vm.scheduleAllOpen(taskId, today)

        val state = vm.await { it.totalToday > 0 }
        assertThat(state.totalToday).isEqualTo(15)
        assertThat(state.groups).hasSize(1)
        assertThat(state.groups.first().progress.total).isEqualTo(15)
    }

    @Test
    fun `einzelne-Schritte-lassen-sich-einem-Tag-zuweisen-und-wieder-entfernen`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val backlogTodo = start.backlog.flatMap { it.todos }.first()

        vm.schedule(listOf(backlogTodo.id), start.today.toString())
        val planned = vm.await { it.totalToday == 11 }
        assertThat(planned.backlogTotal).isEqualTo(12)

        vm.schedule(listOf(backlogTodo.id), null)
        val unplanned = vm.await { it.totalToday == 10 }
        assertThat(unplanned.backlogTotal).isEqualTo(13)
    }

    // ── Struktur ──────────────────────────────────────────────────────────

    @Test
    fun `Baum-aufklappen-und-zuklappen-steuert-die-sichtbaren-Zeilen`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val collapsed = vm.await { it.treeRows.isNotEmpty() }
        assertThat(collapsed.treeRows).hasSize(2)

        vm.toggleExpand("pm-seed-studium")
        val one = vm.await { it.treeRows.size > 2 }
        assertThat(one.treeRows.map { it.node.title }).containsExactly("Studium", "2. Semester", "Privat")

        vm.collapseAll()
        val back = vm.await { it.treeRows.size == 2 }
        assertThat(back.treeRows).hasSize(2)
    }

    @Test
    fun `Beispiel-einfuegen-klappt-die-Struktur-auf`(): Unit = runBlocking {
        val vm = viewModel()
        vm.createExample()
        val state = vm.await { it.treeRows.size > 2 }
        // Der Weg bis zur Aufgabe ist sichtbar: Studium → Semester → Gruppe → Präsentation
        assertThat(state.treeRows.map { it.node.title }).containsAtLeast(
            "Studium", "2. Semester", "Projektgruppe", "Präsentation"
        )
    }

    @Test
    fun `Loeschen-eines-Zweigs-aktualisiert-Glas-und-Baum`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday == 10 }

        vm.deleteTodo(repo.getAllOnce().first { it.title == "Deckblatt und Gliederung entwerfen" })

        val state = vm.await { it.groups.first().progress.total == 14 }
        assertThat(state.groups.first().progress.total).isEqualTo(14)
        assertThat(state.totalTodos).isEqualTo(22)
    }

    @Test
    fun `Fortschritt-der-Aufgabe-bleibt-ueber-Tage-hinweg-erhalten`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val task = start.groups.first()

        // Zwei Schritte abhaken und den Rest in den Backlog schieben:
        // der Tagesplan wird leer, der Aufgabenfortschritt bleibt stehen.
        val first = task.rows.first { !it.done }.node
        vm.toggleTodo(first)
        vm.await { it.doneToday == 1 }
        val rest = repo.getAllOnce()
            .filter { it.isTodo && it.parentId == task.group.node!!.id && it.doneAt == null }
            .map { it.id }
        vm.removeFromPlan(rest)

        val empty = vm.await { it.totalToday == 1 } // nur der erledigte bleibt heute
        assertThat(empty.doneToday).isEqualTo(1)
        assertThat(empty.groups.first().progress).isEqualTo(
            com.d_drostes_apps.aevum.domain.projects.PmProgress(done = 1, total = 15)
        )
    }
}
