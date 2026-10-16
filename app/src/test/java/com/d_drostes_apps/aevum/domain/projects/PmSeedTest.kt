package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.1: Beispielstruktur.
 *
 * Der leere Zustand ist eine Zumutung — der Nutzer müsste fünf Ebenen selbst
 * erfinden, bevor er die erste Kleinigkeit abhaken darf. Diese Suite stellt
 * sicher, dass die eingebaute Beispielstruktur auch wirklich eine GÜLTIGE
 * Struktur ist (sonst importiert der Nutzer mit einem Tipp einen kaputten
 * Baum) und dass sie den Anwendungsfall abbildet.
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
    fun `jeder-Elternteil-existiert-und-ist-ein-Container`() {
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
    fun `todos-sind-immer-Blaetter`() {
        val nodes = seed()
        val todoIds = nodes.filter { it.isTodo }.map { it.id }.toSet()
        val parents = nodes.mapNotNull { it.parentId }.toSet()
        // Kein Todo darf Eltern eines anderen Knotens sein.
        assertThat(todoIds.intersect(parents)).isEmpty()
    }

    @Test
    fun `types-sind-gueltig-und-die-Hierarchie-entspricht-dem-Anwendungsfall`() {
        val tree = PmTree(seed())
        assertThat(tree.all.all { PmType.isValid(it.type) }).isTrue()

        // Studium → 2. Semester → Projektgruppe → Präsentation → erste Hälfte lesen
        val firstStep = tree.byId("pm-seed-t02")!!
        assertThat(tree.path(firstStep.id).map { it.type })
            .containsExactly(PmType.PROJECT, PmType.PROJECT, PmType.CATEGORY, PmType.TASK, PmType.TODO).inOrder()
        assertThat(tree.path(firstStep.id).map { it.title }.take(4))
            .containsExactly("Studium", "2. Semester", "Projektgruppe", "Präsentation").inOrder()
        assertThat(firstStep.title).isEqualTo("Erste Hälfte der Quelle lesen")
    }

    @Test
    fun `zehn-Schritte-sind-fuer-heute-eingeplant-der-Rest-nicht`() {
        val nodes = seed()
        val scheduledToday = nodes.filter { it.isTodo && it.scheduledDate == today }
        assertThat(scheduledToday).hasSize(10)
        val backlog = nodes.filter { it.isTodo && it.scheduledDate == null }
        assertThat(backlog).hasSize(13)
        // Kein Beispiel-Todo ist vorab erledigt — das Glas startet leer und ehrlich.
        assertThat(nodes.none { it.isTodo && it.doneAt != null }).isTrue()
    }

    @Test
    fun `Tagesplan-nach-dem-Import-hat-zehn-Schritte-und-ist-leer-im-Glas`() {
        val tree = PmTree(seed())
        val state = PmToday.state(tree, java.time.LocalDate.parse(today), java.time.ZoneId.of("Europe/Berlin"))
        assertThat(state.total).isEqualTo(10)
        assertThat(state.done).isEqualTo(0)
        assertThat(state.isComplete).isFalse()
        assertThat(state.groups.map { it.id }).containsExactly("pm-seed-praesentation")
    }

    @Test
    fun `fortschritt-startet-bei-null-von-fuenfzehn`() {
        val tree = PmTree(seed())
        assertThat(tree.progress("pm-seed-praesentation"))
            .isEqualTo(PmProgress(done = 0, total = 15))
        assertThat(tree.progress("pm-seed-studium").total).isEqualTo(19)
        assertThat(tree.progress("pm-seed-privat").total).isEqualTo(4)
    }

    @Test
    fun `erzeugung-ist-reproduzierbar`() {
        assertThat(seed()).isEqualTo(seed())
        assertThat(seed().map { it.createdAt }.toSet()).containsExactly(now)
    }

    @Test
    fun `die-Frist-der-Praesentation-liegt-in-der-Zukunft`() {
        val due = PmTree(seed()).byId("pm-seed-praesentation")!!.dueDate
        assertThat(due).isEqualTo("2026-10-27")
    }
}
