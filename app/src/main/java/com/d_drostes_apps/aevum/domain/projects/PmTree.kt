package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType

/**
 * M19.1: Fortschritt eines Knotens — erledigte / alle Aufgaben im Teilbaum.
 */
data class PmProgress(val done: Int, val total: Int) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    val percent: Int get() = (fraction * 100f).toInt()

    val isComplete: Boolean get() = total > 0 && done >= total

    /** Noch offene Aufgaben — treibt den Sog-Effekt („da fehlen noch 8"). */
    val open: Int get() = (total - done).coerceAtLeast(0)

    val label: String get() = "$done/$total"

    companion object {
        val EMPTY = PmProgress(0, 0)
    }
}

/**
 * M19.2: Baum-Index über alle PM-Knoten. REINE Logik — keine Android-,
 * Room- oder Compose-Abhängigkeit, damit die komplette Hierarchie-Semantik
 * ohne Gerät und ohne Datenbank prüfbar ist.
 *
 * Seit M19.2 gibt es nur noch zwei Arten ([PmType.PROJECT] = Container,
 * [PmType.TASK] = abhakbares Blatt). Die alten Begriffe „Todo" und
 * „Kategorie" sind aus dem Modell verschwunden — die Methoden heißen jetzt
 * durchgehend nach AUFGABEN (tasks).
 *
 * WARUM IN KOTLIN UND NICHT IN SQL: Die Datenmenge (hunderte bis wenige
 * tausend Knoten) macht einen vollständig geladenen, im Speicher
 * indexierten Baum billiger als jede rekursive SQL-Query — und die
 * Fortschrittswerte ändern sich dadurch automatisch mit, sobald eine
 * Aufgabe abgehakt wird (ein einziger Flow über die Tabelle).
 *
 * ROBUSTHEIT (bewusst, gegen kaputte Daten):
 *  - Ein Knoten, dessen Elter fehlt, gilt als Wurzel (kein Crash, nichts
 *    verschwindet unsichtbar).
 *  - Ein Knoten, der sich selbst als Elter hat, gilt als Wurzel.
 *  - Alle Traversierungen sind mit einem Besuchs-Set gegen Zyklen gesichert;
 *    auch ein (durch einen Bug entstandener) Zyklus kann keine Endlosschleife
 *    auslösen.
 */
class PmTree(val all: List<PmNode>) {

    private val index: Map<String, PmNode> = all.associateBy { it.id }

    private val childrenIndex: Map<String, List<PmNode>>

    init {
        val buckets = HashMap<String, MutableList<PmNode>>()
        for (node in all) {
            val parent = node.parentId
            val key = if (parent != null && parent != node.id && index.containsKey(parent)) {
                parent
            } else {
                ROOT_KEY
            }
            buckets.getOrPut(key) { mutableListOf() }.add(node)
        }
        childrenIndex = buckets.mapValues { (_, list) ->
            list.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.title.lowercase() }))
        }
    }

    fun byId(id: String?): PmNode? = id?.let { index[it] }

    fun roots(): List<PmNode> = childrenIndex[ROOT_KEY].orEmpty()

    fun children(parentId: String?): List<PmNode> =
        if (parentId == null) roots() else childrenIndex[parentId].orEmpty()

    fun hasChildren(id: String): Boolean = !childrenIndex[id].isNullOrEmpty()

    /** Anzahl der Knoten unterhalb von [id] (zyklensicher, siehe [descendants]). */
    fun descendantsCount(id: String): Int = descendants(id).size

    /** Alle Knoten unterhalb von [id] (ohne [id] selbst), zyklensicher. */
    fun descendants(id: String): List<PmNode> {
        val out = mutableListOf<PmNode>()
        val seen = mutableSetOf(id)
        val stack = ArrayDeque<String>()
        stack.addLast(id)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in childrenIndex[current].orEmpty()) {
                if (seen.add(child.id)) {
                    out.add(child)
                    stack.addLast(child.id)
                }
            }
        }
        return out
    }

    /** Pfad von der Wurzel bis einschließlich [id]. Zyklensicher. */
    fun path(id: String): List<PmNode> {
        val chain = mutableListOf<PmNode>()
        val seen = mutableSetOf<String>()
        var current = index[id]
        while (current != null && seen.add(current.id)) {
            chain.add(0, current)
            val parentId = current.parentId
            current = if (parentId != null && parentId != current.id) index[parentId] else null
        }
        return chain
    }

    /** Tiefe (0 = Wurzel). */
    fun depth(id: String): Int = (path(id).size - 1).coerceAtLeast(0)

    /** Oberste Wurzel des Knotens (für die deterministische Farbe). */
    fun rootOf(id: String): PmNode? = path(id).firstOrNull()

    /** „Studium › 2. Semester › Projektgruppe" (ohne den Knoten selbst). */
    fun breadcrumb(id: String, separator: String = " › ", includeSelf: Boolean = false): String {
        val chain = path(id)
        val parts = if (includeSelf) chain else chain.dropLast(1)
        return parts.joinToString(separator) { it.title }
    }

    /** Direkte Kinder vom Typ Aufgabe. */
    fun tasksOf(id: String): List<PmNode> = children(id).filter { it.isTask }

    /** Alle Aufgaben im Teilbaum (inklusive [id] selbst, falls es eine ist). */
    fun subtreeTasks(id: String): List<PmNode> {
        val self = index[id]
        val out = mutableListOf<PmNode>()
        if (self != null && self.isTask) out.add(self)
        out.addAll(descendants(id).filter { it.isTask })
        return out
    }

    /** Alle Aufgaben im gesamten Baum. */
    fun allTasks(): List<PmNode> = all.filter { it.isTask }

    /** Alle Knoten eines Typs. */
    fun byType(type: String): List<PmNode> = all.filter { it.type == type }

    /** Fortschritt = erledigte/alle Aufgaben im Teilbaum (auch über Tage hinweg). */
    fun progress(id: String): PmProgress {
        val tasks = subtreeTasks(id)
        return PmProgress(done = tasks.count { it.isDone }, total = tasks.size)
    }

    /** Offene Aufgaben im Teilbaum, in Baum-Reihenfolge. */
    fun openTasks(id: String): List<PmNode> = subtreeTasks(id).filter { it.doneAt == null }

    /**
     * Darf [id] unter [newParentId] gehängt werden?
     *
     * M19.2-Regeln (Nutzer: „nur Projekte anlegen, innerhalb Projekte
     * Unterprojekte und Aufgaben"):
     *  - An die Wurzel darf nur ein PROJEKT („Man soll nur Projekte anlegen
     *    können").
     *  - Unter etwas hängen darf man nur unter ein PROJEKT (Aufgaben sind
     *    Blätter, Projekte sind Container).
     *  - Verboten bleiben: auf sich selbst, in den eigenen Teilbaum — und
     *    unter einen Knoten, den es nicht gibt. Letzteres ist bewusst
     *    STRENG: Ein fehlendes Ziel darf nicht stillschweigend als „Wurzel"
     *    gedeutet werden, sonst verschiebt ein Tippfehler oder eine gelöschte
     *    Aufgabe den Knoten unbemerkt ganz woandershin (genau dieser Fall
     *    ist beim Bau durch PmRepositoryRoomTest aufgefallen).
     */
    fun moveAllowed(id: String, newParentId: String?): Boolean {
        val node = index[id] ?: return false
        if (newParentId == null) return node.isContainer
        if (newParentId == id) return false
        val target = index[newParentId] ?: return false
        if (!target.isContainer) return false
        return !isDescendantOf(newParentId, id)
    }

    /** Ist [candidateId] ein Nachfahre von [ancestorId]? */
    fun isDescendantOf(candidateId: String, ancestorId: String): Boolean {
        if (candidateId == ancestorId) return true
        val seen = mutableSetOf(ancestorId)
        val stack = ArrayDeque<String>()
        stack.addLast(ancestorId)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in childrenIndex[current].orEmpty()) {
                if (child.id == candidateId) return true
                if (seen.add(child.id)) stack.addLast(child.id)
            }
        }
        return false
    }

    /**
     * Liste für die Baum-Ansicht: nur sichtbare Knoten, Tiefensuche in
     * Anzeige-Reihenfolge. Ein Knoten ist sichtbar, wenn ALLE seine Vorfahren
     * aufgeklappt sind.
     */
    fun visibleRows(expanded: Set<String>): List<PmTreeRow> {
        val rows = mutableListOf<PmTreeRow>()
        val seen = mutableSetOf<String>()
        fun walk(parentId: String?, depth: Int) {
            for (node in children(parentId)) {
                if (!seen.add(node.id)) continue
                rows.add(PmTreeRow(node = node, depth = depth, hasChildren = hasChildren(node.id)))
                if (expanded.contains(node.id)) walk(node.id, depth + 1)
            }
        }
        walk(null, 0)
        return rows
    }

    /**
     * Aufgaben, gruppiert nach ihrem Projekt — Basis für den Tagesplan und
     * das Tag-planen-Sheet. Aufgaben, die (aus Altbeständen) direkt an der
     * Wurzel oder unter einem Nicht-Projekt hängen, landen in einer
     * synthetischen Gruppe ([UNASSIGNED_TASK_ID]) statt zu verschwinden.
     */
    fun taskGroups(): List<PmTaskGroup> {
        val groups = LinkedHashMap<String, MutableList<PmNode>>()
        val parents = LinkedHashMap<String, PmNode>()
        for (task in allTasks()) {
            val parent = byId(task.parentId)
            val groupId = if (parent != null && parent.isContainer) parent.id else UNASSIGNED_TASK_ID
            if (parent != null) parents[parent.id] = parent
            groups.getOrPut(groupId) { mutableListOf() }.add(task)
        }
        return groups.map { (groupId, tasks) ->
            val node = parents[groupId]
            PmTaskGroup(
                id = groupId,
                title = node?.title ?: "",
                node = node,
                breadcrumb = if (node != null) breadcrumb(node.id) else "",
                tasks = tasks
            )
        }
    }

    companion object {
        const val ROOT_KEY = "__pm_root__"

        /** Synthetische Gruppe für Aufgaben ohne Projekt. */
        const val UNASSIGNED_TASK_ID = "__pm_unassigned__"

        val EMPTY = PmTree(emptyList())
    }
}

/** Eine Zeile der Baum-Ansicht. */
data class PmTreeRow(
    val node: PmNode,
    val depth: Int,
    val hasChildren: Boolean
)

/** Ein Projekt (oder die synthetische „ohne Projekt"-Gruppe) mit seinen Aufgaben. */
data class PmTaskGroup(
    val id: String,
    val title: String,
    val node: PmNode?,
    val breadcrumb: String,
    val tasks: List<PmNode>
) {
    val isSynthetic: Boolean get() = node == null
}
