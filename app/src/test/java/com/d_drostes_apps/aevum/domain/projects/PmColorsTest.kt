package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.1: Farbregeln.
 *
 * Ohne Levels und XP ist FARBE das Belohnungssystem: die Juwelenfarbe im Glas
 * verrät, woran der Tag gearbeitet wurde. Deshalb muss sie stabil und
 * nachvollziehbar sein — nicht zufällig pro Zeichnung.
 */
class PmColorsTest {

    private val created = 1_700_000_000_000L

    private fun node(id: String, parent: String? = null, type: String = PmType.PROJECT, color: String? = null) =
        PmNode(id = id, parentId = parent, type = type, title = id, color = color, createdAt = created, updatedAt = created)

    @Test
    fun `farbe-aus-ID-ist-stabil-und-in-der-Palette`() {
        val first = PmColors.colorFor("studium")
        val second = PmColors.colorFor("studium")
        assertThat(first).isEqualTo(second)
        assertThat(PmColors.PALETTE).contains(first)
    }

    @Test
    fun `farbe-aus-ID-ist-unabhaengig-von-der-Aufrufreihenfolge`() {
        val ids = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "pm-seed-01", "studium")
        val first = ids.associateWith { PmColors.colorFor(it) }
        val second = ids.reversed().associateWith { PmColors.colorFor(it) }
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `eigene-Farbe-gewinnt`() {
        val tree = PmTree(listOf(node("root", color = "#112233"), node("child", "root", PmType.TASK, color = "#AABBCC")))
        assertThat(PmColors.resolve(tree.byId("child")!!, tree)).isEqualTo("#aabbcc")
    }

    @Test
    fun `Kind-erbt-Farbe-des-naechsten-Vorfahren`() {
        val tree = PmTree(
            listOf(
                node("root", color = "#112233"),
                node("mid", "root", PmType.PROJECT),
                node("task", "mid", PmType.TASK)
            )
        )
        assertThat(PmColors.resolve(tree.byId("task")!!, tree)).isEqualTo("#112233")
        assertThat(PmColors.resolve(tree.byId("mid")!!, tree)).isEqualTo("#112233")
    }

    @Test
    fun `naechster-Vorfahre-gewinnt-gegen-die-Wurzel`() {
        val tree = PmTree(
            listOf(
                node("root", color = "#112233"),
                node("task", "root", PmType.TASK, color = "#00FF00")
            )
        )
        assertThat(PmColors.resolve(tree.byId("task")!!, tree)).isEqualTo("#00ff00")
    }

    @Test
    fun `ohne-Farben-wird-die-Wurzel-ID-zur-Farbe`() {
        val tree = PmTree(listOf(node("root"), node("mid", "root", PmType.PROJECT), node("task", "mid", PmType.TASK)))
        val expected = PmColors.colorFor("root")
        assertThat(PmColors.resolve(tree.byId("task")!!, tree)).isEqualTo(expected)
        assertThat(PmColors.resolve(tree.byId("root")!!, tree)).isEqualTo(expected)
    }

    @Test
    fun `kaputte-Farbwerte-werden-ignoriert`() {
        val tree = PmTree(listOf(node("root", color = "rot"), node("task", "root", PmType.TASK, color = "#GGHHII")))
        // Fällt auf die deterministische Wurzelfarbe zurück statt zu crashen.
        assertThat(PmColors.resolve(tree.byId("task")!!, tree)).isEqualTo(PmColors.colorFor("root"))
    }

    @Test
    fun `kurzform-von-Hex-wird-verlaengert`() {
        val tree = PmTree(listOf(node("root", color = "#ABC")))
        assertThat(PmColors.resolve(tree.byId("root")!!, tree)).isEqualTo("#aabbcc")
    }

    @Test
    fun `hex-pruefung-akzeptiert-nur-gueltige-Werte`() {
        assertThat(PmColors.isValidHex("#8B7CFF")).isTrue()
        assertThat(PmColors.isValidHex("#abc")).isTrue()
        assertThat(PmColors.isValidHex("#abcd")).isFalse()
        assertThat(PmColors.isValidHex("8B7CFF")).isFalse()
        assertThat(PmColors.isValidHex(null)).isFalse()
        assertThat(PmColors.isValidHex("#ZZZZZZ")).isFalse()
    }

    @Test
    fun `gruppenfarbe-nutzt-die-Aufgabe-und-sonst-die-Gruppen-ID`() {
        val tree = PmTree(listOf(node("root"), node("task", "root", PmType.TASK, color = "#FBBF24")))
        assertThat(PmColors.groupColor(PmTodayGroup("task", "Präsentation", tree.byId("task"), "", emptyList()), tree))
            .isEqualTo("#fbbf24")
        assertThat(PmColors.groupColor(PmTodayGroup(PmTree.UNASSIGNED_TASK_ID, "", null, "", emptyList()), tree))
            .isEqualTo(PmColors.colorFor(PmTree.UNASSIGNED_TASK_ID))
    }
}
