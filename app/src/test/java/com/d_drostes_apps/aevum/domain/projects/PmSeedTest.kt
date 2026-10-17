package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.2: Beispielstruktur „Vorlage" — jetzt eine GENERISCHE Struktur für die
 * meisten Menschen (Nutzer: „denke dir eine Struktur aus die auf die meisten
 * Menschen möglicherweise zutrifft"; die App geht in den Play Store).
 *
 * Diese Suite stellt sicher, dass die Vorlage eine GÜLTIGE Struktur ist
 * (sonst importiert der Nutzer mit einem Tipp einen kaputten Baum), dass sie
 * die vier Lebensbereiche abbildet, dass ein paar Aufgaben für heute geplant
 * sind — und dass die Titel über den Resolver LOKALISIERT werden können.
 */
class PmSeedTest {

    private val now = 1_700_000_000_000L
    private val today = "2026-10-17"

    private fun seed() = PmSeed.example(now = now, todayIso = today)

    @Test
    fun `alle-IDs-sind-eindeutig-und-gekennzeichnet`() {
        val nodes = seed()
        assertThat(nodes.map { it.id }.toSet()).hasSize(nodes.size)
        assertThat(nodes.all { PmSeed.isSeedNode(it.id) }).isTrue()
        assertThat(PmSeed.isSeedNode("fremd-123")).isFalse()
    }

    @Test
    fun `jeder-Elternteil-existiert-und-ist-ein-projekt`() {
        val nodes = seed()
        val byId = nodes.associateBy { it.id }
        for (node in nodes) {
            val parentId = node.parentId ?: continue
            val parent = byId[parentId]
            assertThat(parent).isNotNull()
            assertThat(parent!!.isContainer).isTrue()
        }
        assertThat(PmTree(nodes).roots()).isNotEmpty()
    }

    @Test
    fun `aufgaben-sind-immer-Blaetter`() {
        val nodes = seed()
        val taskIds = nodes.filter { it.isTask }.map { it.id }.toSet()
        val parents = nodes.mapNotNull { it.parentId }.toSet()
        // Keine Aufgabe darf Elternteil eines anderen Knotens sein.
        assertThat(taskIds.intersect(parents)).isEmpty()
    }

    @Test
    fun `types-sind-gueltig-und-die-Vorlage-deckt-vier-Lebensbereiche`() {
        val tree = PmTree(seed())
        assertThat(tree.all.all { PmType.isValid(it.type) }).isTrue()

        // Die Wurzeln sind die vier Lebensbereiche (englische Standardtitel).
        assertThat(tree.roots().map { it.title }).containsExactly(
            "Household & Daily Life", "Work & Education", "Health", "Finances"
        ).inOrder()

        // Eine Aufgabe liegt auf dem dritten Level: Bereich → Unterprojekt → Aufgabe.
        val task = tree.byId("pm-seed-einkauf")!!
        assertThat(tree.path(task.id).map { it.type })
            .containsExactly(PmType.PROJECT, PmType.PROJECT, PmType.TASK).inOrder()
        assertThat(tree.path(task.id).map { it.title }.take(2))
            .containsExactly("Household & Daily Life", "Home").inOrder()
        assertThat(task.title).isEqualTo("Write a shopping list")
    }

    @Test
    fun `sechs-Aufgaben-sind-fuer-heute-geplant-der-Rest-nicht`() {
        val nodes = seed()
        val scheduledToday = nodes.filter { it.isTask && it.scheduledDate == today }
        assertThat(scheduledToday).hasSize(6)
        val backlog = nodes.filter { it.isTask && it.scheduledDate == null }
        assertThat(backlog).hasSize(11)
        // Keine Beispiel-Aufgabe ist vorab erledigt — das Glas startet leer und ehrlich.
        assertThat(nodes.none { it.isTask && it.doneAt != null }).isTrue()
    }

    @Test
    fun `Tagesplan-nach-dem-Import-hat-sechs-Aufgaben-und-ist-leer-im-Glas`() {
        val tree = PmTree(seed())
        val state = PmToday.state(tree, java.time.LocalDate.parse(today), java.time.ZoneId.of("Europe/Berlin"))
        assertThat(state.total).isEqualTo(6)
        assertThat(state.done).isEqualTo(0)
        assertThat(state.isComplete).isFalse()
        // Die sechs heutigen Aufgaben liegen in fünf Projekten:
        // Home (1) · Haushalt & Alltag (1) · Current project (2) ·
        // Health (1) · Finances (1).
        assertThat(state.groups.map { it.id }).containsExactly(
            "pm-seed-wohnung", "pm-seed-alltag", "pm-seed-projekt", "pm-seed-gesundheit", "pm-seed-finanzen"
        )
    }

    @Test
    fun `fortschritt-startet-bei-null-Aufgaben-je-Bereich`() {
        val tree = PmTree(seed())
        // 7 Projekte (4 Bereiche + 3 Unterprojekte) und 17 Aufgaben.
        assertThat(tree.byType(PmType.PROJECT)).hasSize(7)
        assertThat(tree.allTasks()).hasSize(17)
        assertThat(tree.progress("pm-seed-alltag")).isEqualTo(PmProgress(done = 0, total = 5))
        assertThat(tree.progress("pm-seed-wohnung")).isEqualTo(PmProgress(done = 0, total = 4))
        assertThat(tree.progress("pm-seed-arbeit")).isEqualTo(PmProgress(done = 0, total = 6))
        assertThat(tree.progress("pm-seed-lernen")).isEqualTo(PmProgress(done = 0, total = 2))
        assertThat(tree.progress("pm-seed-gesundheit")).isEqualTo(PmProgress(done = 0, total = 3))
        assertThat(tree.progress("pm-seed-finanzen")).isEqualTo(PmProgress(done = 0, total = 3))
    }

    @Test
    fun `erzeugung-ist-reproduzierbar`() {
        assertThat(seed()).isEqualTo(seed())
        assertThat(seed().map { it.createdAt }.toSet()).containsExactly(now)
    }

    @Test
    fun `titel-kommen-aus-dem-uebergebenen-resolver`() {
        // Im Betrieb kommen die Titel aus den String-Ressourcen (deutsch/englisch);
        // hier beweist ein Fake-Resolver, dass die Verdrahtung existiert.
        val german = PmSeed.example(now = now, todayIso = today, titleOf = { key -> "DE:$key" })
        assertThat(german.first { it.id == "pm-seed-einkauf" }.title).isEqualTo("DE:einkauf")
        assertThat(german.first { it.id == "pm-seed-alltag" }.title).isEqualTo("DE:alltag")
    }

    @Test
    fun `standard-resolver-faellt-auf-den-schluessel-zurueck`() {
        assertThat(PmSeed.defaultTitle("einkauf")).isEqualTo("Write a shopping list")
        assertThat(PmSeed.defaultTitle("gibt-es-nicht")).isEqualTo("gibt-es-nicht")
    }
}
