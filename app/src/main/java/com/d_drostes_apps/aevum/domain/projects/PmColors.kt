package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode

/**
 * M19.1/M19.2: Farbregeln für Knoten und Juwelen. REIN — kein Compose, kein Android.
 *
 * WARUM ÜBERHAUPT EINE FARBREGEL: Der Nutzer will „möglichst schön farbig"
 * und ausdrücklich KEINE Level/XP. Farbe ist hier die Belohnung ohne Zahlen-
 * system: die Juwelenfarbe im Glas verrät ihm, WORAN er heute gearbeitet hat.
 * Deshalb ist die Farbe nicht zufällig, sondern eine Eigenschaft des Projekts
 * bzw. der Aufgabe (Vererbung nach unten).
 *
 * M19.2: Die Palette beginnt mit der neuen App-Identität Eisblau → Feuerrot
 * (Nutzerwunsch) und bleibt danach bewusst bunt — die Juwelen sollen
 * unterscheidbar bleiben.
 *
 * Auflösungsreihenfolge:
 *  1. eigene Farbe des Knotens ([PmNode.color])
 *  2. Farbe des nächstgelegenen Vorfahren mit Farbe
 *  3. deterministisch aus der ID der obersten Wurzel (stabil über Sessions!)
 */
object PmColors {

    val PALETTE: List<String> = listOf(
        "#38BDF8", // eisblau (App-Identität)
        "#F87171", // feuerrot (App-Identität)
        "#FBBF24", // gold (Auszeichnung — siehe PmPalette.complete)
        "#2DD4BF", // türkis
        "#A78BFA", // lavendel
        "#FB923C", // orange
        "#34D399", // grün
        "#F472B6"  // pink
    )

    /** Stabiler Farbwert aus einer ID (Java-String-Hash ist spezifiziert → stabil). */
    fun colorFor(id: String): String {
        val size = PALETTE.size
        val index = ((id.hashCode() % size) + size) % size
        return PALETTE[index]
    }

    /** Farbe eines Knotens inklusive Vererbung (siehe Klassenkommentar). */
    fun resolve(node: PmNode, tree: PmTree): String {
        node.color?.let { if (isValidHex(it)) return normalize(it) }
        val path = tree.path(node.id)
        for (ancestor in path.dropLast(1).reversed()) {
            ancestor.color?.let { if (isValidHex(it)) return normalize(it) }
        }
        val rootId = path.firstOrNull()?.id ?: node.id
        return colorFor(rootId)
    }

    /** Gruppe einer Aufgabe (Projekt) — gleiche Farbe wie die Juwelen im Glas. */
    fun groupColor(group: PmTodayGroup, tree: PmTree): String {
        val node = group.node ?: return colorFor(group.id)
        return resolve(node, tree)
    }

    /** "#RRGGBB" → "#rrggbb" geprüft; akzeptiert auch Kurzform "#RGB". */
    fun isValidHex(hex: String?): Boolean {
        if (hex == null) return false
        val value = hex.trim()
        if (!value.startsWith("#")) return false
        val body = value.substring(1)
        if (body.length != 3 && body.length != 6) return false
        return body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun normalize(hex: String): String {
        val body = hex.trim().removePrefix("#").lowercase()
        return if (body.length == 3) {
            "#" + body.map { "$it$it" }.joinToString("")
        } else {
            "#$body"
        }
    }
}
