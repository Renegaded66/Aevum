package com.d_drostes_apps.aevum.ui.screens.projects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * M19.2: Reine Index-Mathematik hinter dem Drag & Drop der To-do-Listen.
 *
 * WARUM DIESE DATEI TESTBAR IST: Die Gesten-Erkennung selbst ([PmDragController]
 * in der UI-Schicht) braucht Finger und Bildschirm — die Mathematik, die
 * entscheidet, WO eine gezogene Zeile landet, ist dagegen eine reine Funktion
 * ([PmReorder]). Genau die wird hier festgenagelt: Nutzerfehler beim Umsortieren
 * (Zeile springt an die falsche Stelle oder verschwindet) sind sonst nur
 * mühsam am Gerät reproduzierbar.
 *
 * Die Rückruf-Indizes der UI sind LOKAL zur Projekt-Gruppe (Heute-Ansicht)
 * und beziehen sich auf die Reihenfolge NACH dem Entfernen der gezogenen
 * Zeile — beide Konventionen sind hier explizit geprüft.
 */
class PmReorderTest {

    private fun bounds(vararg pairs: Pair<String, ClosedFloatingPointRange<Float>>) =
        pairs.map { (key, range) -> PmItemBounds(key, range.start, range.endInclusive) }

    // ── applyMove ─────────────────────────────────────────────────────────

    @Test
    fun `verschieben-nach-unten-und-oben`() {
        val list = listOf("a", "b", "c")
        assertThat(PmReorder.applyMove(list, from = 0, to = 1)).containsExactly("b", "a", "c").inOrder()
        assertThat(PmReorder.applyMove(list, from = 2, to = 0)).containsExactly("c", "a", "b").inOrder()
        assertThat(PmReorder.applyMove(list, from = 0, to = 2)).containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `verschieben-auf-sich-selbst-aendert-nichts`() {
        val list = listOf("a", "b", "c")
        assertThat(PmReorder.applyMove(list, from = 1, to = 1)).isEqualTo(list)
    }

    @Test
    fun `kaputte-indizes-lassen-die-liste-unveraendert`() {
        val list = listOf("a", "b")
        assertThat(PmReorder.applyMove(list, from = 5, to = 0)).isEqualTo(list)
        assertThat(PmReorder.applyMove(list, from = -1, to = 0)).isEqualTo(list)
        // Zielindex wird auf die Liste begrenzt statt zu krachen.
        assertThat(PmReorder.applyMove(list, from = 0, to = 99)).containsExactly("b", "a").inOrder()
    }

    // ── targetIndex ───────────────────────────────────────────────────────

    @Test
    fun `zielindex-zaehlt-die-mittelpunkte-oberhalb-des-fingers`() {
        // Drei sichtbare Zeilen (ohne die gezogene), je 40 px hoch.
        val visible = bounds(
            "b" to 0f..40f,      // Mitte 20
            "c" to 100f..140f,   // Mitte 120
            "d" to 200f..240f    // Mitte 220
        )
        assertThat(PmReorder.targetIndex(visible, pointerY = 10f)).isEqualTo(0)
        assertThat(PmReorder.targetIndex(visible, pointerY = 25f)).isEqualTo(1)
        assertThat(PmReorder.targetIndex(visible, pointerY = 130f)).isEqualTo(2)
        assertThat(PmReorder.targetIndex(visible, pointerY = 500f)).isEqualTo(3)
        assertThat(PmReorder.targetIndex(visible, pointerY = -50f)).isEqualTo(0)
    }

    @Test
    fun `ohne-sichtbare-zeilen-landet-alles-auf-null`() {
        assertThat(PmReorder.targetIndex(emptyList(), pointerY = 100f)).isEqualTo(0)
    }

    @Test
    fun `gezogene-zeile-landet-dort-wo-der-finger-zeigt`() {
        // Integration beider Funktionen, wie sie der Controller verkettet:
        // „a" aus [a,b,c] wird gezogen; sichtbare andere: b (0..40), c (100..140).
        val shown = listOf("a", "b", "c")
        val visible = bounds("b" to 0f..40f, "c" to 100f..140f)
        val from = 0

        // Finger weit unten → Ziel 2 → ans Ende.
        val toBottom = PmReorder.targetIndex(visible, pointerY = 130f)
        assertThat(toBottom).isEqualTo(2)
        assertThat(PmReorder.applyMove(shown, from, toBottom)).containsExactly("b", "c", "a").inOrder()

        // Finger zwischen b und c → Ziel 1 → genau dazwischen.
        val toMiddle = PmReorder.targetIndex(visible, pointerY = 60f)
        assertThat(toMiddle).isEqualTo(1)
        assertThat(PmReorder.applyMove(shown, from, toMiddle)).containsExactly("b", "a", "c").inOrder()

        // Finger über b → Ziel 0 → nichts verschieben (from == to).
        val toTop = PmReorder.targetIndex(visible, pointerY = 5f)
        assertThat(toTop).isEqualTo(0)
        assertThat(PmReorder.applyMove(shown, from, toTop)).isEqualTo(shown)
    }
}
