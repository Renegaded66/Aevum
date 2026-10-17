package com.d_drostes_apps.aevum.ui.screens.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.d_drostes_apps.aevum.data.db.AppDatabase
import com.d_drostes_apps.aevum.data.repository.PmCelebrationStore
import com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl
import com.d_drostes_apps.aevum.data.repository.PmSeedTitles
import com.d_drostes_apps.aevum.domain.projects.PmProgress
import com.d_drostes_apps.aevum.domain.projects.PmSeed
import com.d_drostes_apps.aevum.domain.projects.PmTree
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
 * M19.1/M19.2: Der komplette Tagesablauf gegen echte Datenbank + echtes ViewModel.
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
 *     Tipp muss die gewählten Aufgaben wirklich auf den Tag setzen.
 *  3. **Ein-Tipp-Massenweg.** „Alle offenen Aufgaben eines Projekts → heute".
 *  4. **Glas-Kennzahl.** Sie muss sich nach jedem Abhaken ändern (Juwel dazu,
 *     Gegenplatz weg) und bei vollständigem Tag auf 1,0 stehen.
 *  5. **Drag & Drop (M19.2).** `reorderToday` muss die gezogene Reihenfolge
 *     wirklich in die Datenbank schreiben — sonst springt die Liste beim
 *     nächsten Öffnen zurück.
 *
 * ZEIT: Es wird mit dem ECHTEN heutigen Datum gerechnet, weil `setDone` die
 * Wanduhr stempelt (Robolectric kann die Wanduhr nicht verschieben).
 *
 * M19.2: Vorlage = generische Lebensbereiche (Haushalt · Arbeit · Gesundheit ·
 * Finanzen); Tagesgruppen sind die unmittelbaren Projekte der Aufgaben.
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
        repo = PmRepositoryImpl(db.pmNodeDao(), PmSeedTitles { key -> PmSeed.defaultTitle(key) })
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

    // ── Vorlage → Tagesplan ────────────────────────────────────────────────

    @Test
    fun `Vorlage-erzeugt-einen-Tagesplan-mit-sechs-Aufgaben`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()

        val state = vm.await { it.totalToday > 0 }

        assertThat(state.totalToday).isEqualTo(6)
        assertThat(state.doneToday).isEqualTo(0)
        assertThat(state.pendingToday).isEqualTo(6)
        assertThat(state.gems).isEmpty()
        assertThat(state.ghostCount).isEqualTo(6)
        assertThat(state.fraction).isEqualTo(0f)

        // Fünf Projekte tragen die sechs heutigen Aufgaben (die Aufgaben liegen
        // in den Unterprojekten — Home, Projekt, …).
        assertThat(state.groups).hasSize(5)
        assertThat(state.groups.map { it.title }).containsExactly(
            "Home", "Household & Daily Life", "Current project", "Health", "Finances"
        )

        val home = state.groups.first { it.id == "pm-seed-wohnung" }
        assertThat(home.rows.map { it.node.title }).containsExactly("Write a shopping list")
        // Fortschritt zeigt IMMER den ganzen Teilbaum, nicht nur heute.
        assertThat(home.progress).isEqualTo(PmProgress(0, 4))

        assertThat(state.projectCount).isEqualTo(7)
        assertThat(state.taskCount).isEqualTo(17)
        // Der Baum steht an der Wurzel (4 Lebensbereiche).
        assertThat(state.treeRows.map { it.node.title }).containsExactly(
            "Household & Daily Life", "Work & Education", "Health", "Finances"
        )
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
    fun `Abhaken-erzeugt-ein-Juwel-in-der-Farbe-des-Projekts`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val firstTask = start.groups.first { it.id == "pm-seed-wohnung" }
            .rows.first { !it.done }.node

        vm.toggleTask(firstTask)

        val state = vm.await { it.doneToday == 1 }
        assertThat(state.gems).hasSize(1)
        assertThat(state.gems.first().id).isEqualTo(firstTask.id)
        assertThat(state.gems.first().label).isEqualTo(firstTask.title)
        // „Haushalt & Alltag" trägt die Eisblau-Identität (#38BDF8) — das Juwel
        // verrät damit, woran der Tag gearbeitet wurde.
        assertThat(state.gems.first().colorHex).isEqualTo("#38bdf8")
        assertThat(state.ghostCount).isEqualTo(5)
        assertThat(state.fraction).isWithin(0.0001f).of(1f / 6f)
        // Der Funke darf genau einmal laufen.
        assertThat(state.lastCompletedId).isEqualTo(firstTask.id)
        // Kein Feuerwerk, solange nicht alles erledigt ist.
        assertThat(state.celebrating).isFalse()
    }

    @Test
    fun `Rueckgaengig-nimmt-das-Juwel-wieder-aus-dem-Glas`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val firstTask = start.groups.first { it.id == "pm-seed-wohnung" }
            .rows.first { !it.done }.node

        vm.toggleTask(firstTask)
        vm.await { it.doneToday == 1 }
        vm.toggleTask(repo.getById(firstTask.id)!!)

        val state = vm.await { it.doneToday == 0 }
        assertThat(state.gems).isEmpty()
        assertThat(state.ghostCount).isEqualTo(6)
        assertThat(state.lastCompletedId).isNull()
    }

    @Test
    fun `vollstaendiger-Tag-feiert-genau-einmal`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val rows = start.groups.flatMap { it.rows }.filter { !it.done }

        rows.forEach { row -> vm.toggleTask(row.node) }

        val full = vm.await { it.allDone }
        assertThat(full.celebrating).isTrue()
        assertThat(full.fraction).isEqualTo(1f)
        assertThat(full.gems).hasSize(6)
        assertThat(full.ghostCount).isEqualTo(0)
        // Der Feierstand ist festgehalten (überlebt Prozess-Neustart).
        assertThat(store.state().celebratedDone).isEqualTo(6)

        // Neuer ViewModel (z. B. nach erneutem Öffnen der Seite): NICHT noch
        // einmal feiern — der Beweis, dass kein Dauerfeuerwerk entsteht.
        val second = viewModel()
        val again = second.await { it.allDone }
        assertThat(again.celebrating).isFalse()

        // Nach dem Schließen der Feier ist Ruhe.
        second.finishCelebration()
        val quiet = second.await { !it.celebrating && it.allDone }
        assertThat(quiet.celebrating).isFalse()
        assertThat(quiet.gems).hasSize(6)
    }

    // ── Morgenritual (Tag planen) ───────────────────────────────────────────

    @Test
    fun `Tag-planen-oeffnet-mit-Vorauswahl-und-uebernimmt-in-einem-Schritt`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday > 0 }

        vm.openPlanner()
        val plannerState = vm.await { it.plannerOpen }
        // Vorausgewählt sind genau die bereits geplanten Aufgaben.
        assertThat(plannerState.plannerSelection).hasSize(6)
        assertThat(plannerState.plannerDate).isNull()
        // Angeboten werden ALLE offenen Aufgaben, gruppiert nach Projekt.
        val allOpen = plannerState.plannerGroups.flatMap { group -> group.tasks.map { it.id } }
        assertThat(allOpen).hasSize(17)
        assertThat(plannerState.plannerGroups).hasSize(6)

        // Nutzer will heute mehr schaffen: alles dazunehmen.
        vm.setPlannerSelection(allOpen, selected = true)
        vm.applyPlanner()

        val afterPlan = vm.await { it.totalToday == 17 }
        assertThat(afterPlan.plannerOpen).isFalse()
        assertThat(afterPlan.pendingToday).isEqualTo(17)
        assertThat(afterPlan.groups).hasSize(6)
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

        val state = vm.await { !it.plannerOpen && it.totalToday == 0 }
        // Nichts geht verloren: die Aufgaben stehen mit Datum im Planer.
        val open = state.plannerGroups.flatMap { group -> group.tasks.map { it.id } }
        assertThat(open).hasSize(17)
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
        assertThat(state.totalToday).isEqualTo(6)
    }

    // ── Ein-Tipp-Massenweg und Einzel-Datum ────────────────────────────────

    @Test
    fun `alle-offenen-Aufgaben-eines-Projekts-auf-heute-mit-einem-Tipp`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday > 0 }

        // Ausgangslage herstellen: alles in den Backlog.
        vm.removeFromPlan(repo.getAllOnce().filter { it.isTask }.map { it.id })
        val clean = vm.await { it.totalToday == 0 }
        assertThat(clean.taskCount).isEqualTo(17)

        // Ein Tipp auf der Projektkarte (Arbeit & Ausbildung hat 6 Aufgaben).
        vm.scheduleAllOpen("pm-seed-arbeit", stateToday(vm))

        val state = vm.await { it.totalToday == 6 }
        assertThat(state.groups).hasSize(2) // projekt (4) + lernen (2)
        assertThat(state.groups.map { it.progress.total }.sum()).isEqualTo(6)
    }

    @Test
    fun `einzelne-Aufgaben-lassen-sich-einem-Tag-zuweisen-und-wieder-entfernen`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val backlogTask = repo.getAllOnce().first { it.isTask && it.scheduledDate == null }

        vm.schedule(listOf(backlogTask.id), start.today.toString())
        val planned = vm.await { it.totalToday == 7 }
        assertThat(planned.taskCount).isEqualTo(17)

        vm.schedule(listOf(backlogTask.id), null)
        val unplanned = vm.await { it.totalToday == 6 }
        assertThat(unplanned.groups.flatMap { it.rows }.map { it.node.id })
            .doesNotContain(backlogTask.id)
    }

    // ── Drag & Drop (M19.2) ────────────────────────────────────────────────

    @Test
    fun `reorderToday-schreibt-die-gezogene-Reihenfolge-in-die-Datenbank`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        // Im Projekt „Current project" stehen heute zwei Aufgaben.
        val group = start.groups.first { it.id == "pm-seed-projekt" }
        assertThat(group.rows.map { it.node.id })
            .containsExactly("pm-seed-schritt", "pm-seed-wochenziele").inOrder()

        // Die zweite Aufgabe nach oben ziehen.
        vm.reorderToday("pm-seed-projekt", from = 0, to = 1)

        val state = vm.await { s ->
            s.groups.firstOrNull { it.id == "pm-seed-projekt" }
                ?.rows?.map { it.node.id } == listOf("pm-seed-wochenziele", "pm-seed-schritt")
        }
        assertThat(state.totalToday).isEqualTo(6)

        // Und die Ordnung überlebt einen frischen Blick auf die Datenbank:
        // die restlichen offenen Aufgaben hängen dahinter.
        val open = repo.getAllOnce()
            .filter { it.parentId == "pm-seed-projekt" && it.doneAt == null }
            .sortedBy { it.sortOrder }
            .map { it.id }
        assertThat(open).containsExactly(
            "pm-seed-wochenziele", "pm-seed-schritt", "pm-seed-offenepunkte", "pm-seed-ergebnis"
        ).inOrder()
    }

    // ── Struktur ──────────────────────────────────────────────────────────

    @Test
    fun `Baum-aufklappen-und-zuklappen-steuert-die-sichtbaren-Zeilen`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val collapsed = vm.await { it.treeRows.isNotEmpty() }
        assertThat(collapsed.treeRows).hasSize(4)

        vm.toggleExpand("pm-seed-alltag")
        val one = vm.await { it.treeRows.size > 4 }
        assertThat(one.treeRows.map { it.node.title }).containsExactly(
            "Household & Daily Life", "Home", "Plan the week's appointments",
            "Work & Education", "Health", "Finances"
        ).inOrder()

        vm.collapseAll()
        val back = vm.await { it.treeRows.size == 4 }
        assertThat(back.treeRows).hasSize(4)
    }

    @Test
    fun `Vorlage-einfuegen-klappt-die-Lebensbereiche-auf`(): Unit = runBlocking {
        val vm = viewModel()
        vm.createExample()
        val state = vm.await { it.treeRows.size > 4 }
        // Der Weg bis zu den Unterprojekten ist sofort sichtbar.
        assertThat(state.treeRows.map { it.node.title }).containsAtLeast(
            "Household & Daily Life", "Home", "Current project", "Further Education", "Health", "Finances"
        )
    }

    @Test
    fun `Loeschen-einer-Aufgabe-aktualisiert-Glas-und-Baum`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        vm.await { it.totalToday == 6 }

        vm.deleteTask(repo.getAllOnce().first { it.id == "pm-seed-einkauf" })

        val state = vm.await { it.totalToday == 5 }
        // Die einzige heutige Aufgabe von „Home" ist weg — die Gruppe
        // verschwindet aus der Heute-Liste, der Projektfortschritt bleibt
        // als Bestand (nur noch 3 Aufgaben).
        assertThat(state.groups.none { it.id == "pm-seed-wohnung" }).isTrue()
        assertThat(PmTree(repo.getAllOnce()).progress("pm-seed-wohnung"))
            .isEqualTo(PmProgress(0, 3))
        assertThat(state.taskCount).isEqualTo(16)
    }

    @Test
    fun `Fortschritt-des-Projekts-bleibt-ohne-Tagesplan-erhalten`(): Unit = runBlocking {
        val vm = viewModel()
        repo.insertExample()
        val start = vm.await { it.totalToday > 0 }
        val home = start.groups.first { it.id == "pm-seed-wohnung" }
        val first = home.rows.first { !it.done }.node

        // Aufgabe aus dem Tagesplan nehmen: die Gruppe verschwindet aus der
        // Heute-Liste — der Baum selbst bleibt natürlich stehen.
        vm.removeFromPlan(listOf(first.id))
        val removed = vm.await { it.totalToday == 5 }
        assertThat(removed.groups.none { it.id == "pm-seed-wohnung" }).isTrue()

        // Nun die (nicht geplante) Aufgabe abhaken: ihr Fortschritt zählt
        // sofort mit, und weil sie HEUTE erledigt wurde, erscheint sie als
        // erledigte Zeile samt Juwel im Tagesplan (Regel: heute erledigt
        // zählt auch ohne Datum).
        vm.toggleTask(repo.getById(first.id)!!)
        val done = vm.await { it.doneToday == 1 }
        assertThat(done.totalToday).isEqualTo(6) // 5 geplante + die erledigte
        assertThat(done.groups.first { it.id == "pm-seed-wohnung" }.progress)
            .isEqualTo(PmProgress(done = 1, total = 4))
    }

    /** Kleiner Helfer: heutiges ISO-Datum direkt aus dem Repository (ohne Wartezeit). */
    private fun stateToday(vm: ProjectsViewModel): String = runBlocking {
        vm.uiState.first { it.loaded }.today.toString()
    }
}
