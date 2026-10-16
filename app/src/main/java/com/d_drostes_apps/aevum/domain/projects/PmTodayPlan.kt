package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import com.d_drostes_apps.aevum.data.model.PmType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * M19.1: Tagesplan-Logik — REIN und ohne Android-Abhängigkeit.
 *
 * DEVON-ANWENDUNGSFALL (wörtlich):
 *  - „die einzelnen todos will ich einen Tag zuweisen können"
 *  - „Zukünftige todos müssen nicht zwingend mit Datum versehen sein"
 *  - „diese bestimmten 20 todos will ich heute schaffen, die alle für heute
 *     fällig haben und dann machen"
 *  - „Nicht abgehakte todos werden am nächsten Tag weiterhin angezeigt,
 *     der Rest nicht"
 *  - „wenn man alle abgehakt hat ist das Glas mit Juwelen voll"
 *
 * Daraus ergibt sich exakt diese Tabelle (siehe auch §3.3 der Spec):
 *
 * | Zustand des Todos                                  | im Tagesplan?       |
 * |----------------------------------------------------|---------------------|
 * | offen, Datum == heute                              | ja                  |
 * | offen, Datum < heute                               | ja, als „übernommen"|
 * | offen, kein Datum                                 | nein → Backlog      |
 * | offen, Datum > heute                              | nein                |
 * | heute erledigt (unabhängig vom Datum)              | ja, als Juwel       |
 * | an einem früheren Tag erledigt                     | nein                |
 *
 * ZEITQUELLE: `done_at` ist ein Zeitstempel (System.currentTimeMillis), die
 * Zuordnung „welcher Tag" passiert über die Systemzone. Das ist bewusst so:
 * bei Zeitzonenwechsel oder Sommerzeit bleibt die Erledigung real, nur der
 * angezeigte Tag verschiebt sich wie die Uhr des Nutzers auch.
 */
object PmToday {

    /** ISO-Datum (yyyy-MM-dd) eines Zeitstempels in der Systemzone. */
    fun isoDateOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** Heutiges ISO-Datum. */
    fun todayIso(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        isoDateOf(now, zone)

    fun parseDate(iso: String?): LocalDate? = try {
        iso?.let { LocalDate.parse(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * Wie viele Tage ist das Todo bereits überfällig?
     * 0 = heute fällig, später geplant oder ohne Datum; 1 = gestern, …
     * Wird überall dort gebraucht, wo die volle Todo-Liste eines Knotens
     * gezeigt wird (nicht nur der Tagesausschnitt).
     */
    fun rolloverDays(node: PmNode, today: LocalDate): Int {
        val scheduled = parseDate(node.scheduledDate) ?: return 0
        if (scheduled.isAfter(today)) return 0
        return java.time.temporal.ChronoUnit.DAYS.between(scheduled, today).toInt().coerceAtLeast(0)
    }

    /** Wurde das Todo an diesem Tag erledigt? */
    fun isDoneOn(node: PmNode, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val doneAt = node.doneAt ?: return false
        return isoDateOf(doneAt, zone) == date.toString()
    }

    /**
     * Todos des Tages, gruppiert nach Aufgabe. Aufgaben ohne heutige Todos
     * tauchen nicht auf (sonst hätte man 30 leere Karten zu wischen).
     */
    fun plan(tree: PmTree, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<PmTodayGroup> {
        val rows = tree.allTodos()
            .mapNotNull { todo -> rowOrNull(todo, tree, today, zone) }
            .groupBy { it.groupId }

        val order = tree.taskGroups().associateBy { it.id }
        return rows.entries
            .map { (groupId, entries) ->
                val group = order[groupId]
                PmTodayGroup(
                    id = groupId,
                    title = group?.title ?: "",
                    node = group?.node,
                    breadcrumb = group?.breadcrumb ?: "",
                    rows = entries.sortedWith(
                        // Offene zuerst; übernommene (ältere) vor den heute
                        // fälligen; Erledigte unten in der Reihenfolge des
                        // Abhakens (das zuletzt Abgehakte steht damit unten —
                        // die Liste "wächst" nach unten, wie ein Stapel).
                        compareBy<PmTodayRow> { if (it.done) 1 else 0 }
                            .thenByDescending { it.rolloverDays }
                            .thenBy { it.node.sortOrder }
                            .thenBy { it.node.createdAt }
                            .thenBy { it.node.doneAt ?: Long.MAX_VALUE }
                    )
                )
            }
            .sortedWith(compareByDescending<PmTodayGroup> { it.rows.count { row -> !row.done } }.thenBy { it.title.lowercase() })
    }

    /** Zeile des Tagesplans — null, wenn das Todo heute nicht auftaucht. */
    private fun rowOrNull(todo: PmNode, tree: PmTree, today: LocalDate, zone: ZoneId): PmTodayRow? {
        if (!todo.isTodo) return null
        val parent = tree.byId(todo.parentId)
        val groupId = if (parent != null && parent.type == PmType.TASK) parent.id else PmTree.UNASSIGNED_TASK_ID
        val parentTitle = if (parent != null && parent.type == PmType.TASK) parent.title else ""
        val breadcrumb = tree.breadcrumb(todo.id)

        if (todo.doneAt != null) {
            // Nur heute erledigte Todos bleiben stehen ("der Rest nicht").
            if (!isDoneOn(todo, today, zone)) return null
            return PmTodayRow(
                node = todo, done = true, rolloverDays = 0,
                groupId = groupId, groupTitle = parentTitle, breadcrumb = breadcrumb
            )
        }
        val scheduled = parseDate(todo.scheduledDate) ?: return null
        if (scheduled.isAfter(today)) return null
        val overdue = java.time.temporal.ChronoUnit.DAYS.between(scheduled, today).toInt().coerceAtLeast(0)
        return PmTodayRow(
            node = todo, done = false, rolloverDays = overdue,
            groupId = groupId, groupTitle = parentTitle, breadcrumb = breadcrumb
        )
    }

    /**
     * Zustand des Tages: Tagesplan, Glas-Kennzahl, Zähler für den
     * Übernahme-Hinweis.
     */
    fun state(tree: PmTree, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): PmTodayState {
        val groups = plan(tree, today, zone)
        val rows = groups.flatMap { it.rows }
        val done = rows.count { it.done }
        val total = rows.size
        val pending = rows.filter { !it.done }
        return PmTodayState(
            today = today,
            groups = groups,
            done = done,
            total = total,
            rollover = pending.count { it.rolloverDays > 0 },
            unfiled = tree.allTodos().filter { it.isTodo && it.doneAt == null && it.scheduledDate == null }
        )
    }

    /**
     * Verlauf der letzten [days] Tage (inklusive heute) — aus `done_at`
     * abgeleitet, ohne eigene Tabelle: wie viele Todos wurden an dem Tag
     * erledigt? Grundlage für den 7-Tage-Streifen.
     */
    fun history(tree: PmTree, today: LocalDate, days: Int = 7, zone: ZoneId = ZoneId.systemDefault()): List<PmDayHistory> {
        val counts = HashMap<String, Int>()
        for (todo in tree.allTodos()) {
            val doneAt = todo.doneAt ?: continue
            val iso = isoDateOf(doneAt, zone)
            counts[iso] = (counts[iso] ?: 0) + 1
        }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            PmDayHistory(date = date, done = counts[date.toString()] ?: 0, isToday = back == 0)
        }
    }

    /**
     * Für das Tag-planen-Sheet: alle offenen Todos, gruppiert nach Aufgabe,
     * mit Vorschlag, was heute dazugehören sollte (übernommene + heute
     * geplante). Die Reihenfolge entspricht dem Baum.
     */
    fun planningGroups(tree: PmTree, today: LocalDate): List<PmPlanningGroup> {
        return tree.taskGroups().mapNotNull { group ->
            val open = group.todos.filter { it.doneAt == null }
            if (open.isEmpty()) return@mapNotNull null
            PmPlanningGroup(
                group = group,
                todos = open,
                suggestedIds = open.filter { todo ->
                    val scheduled = parseDate(todo.scheduledDate)
                    scheduled != null && !scheduled.isAfter(today)
                }.map { it.id }.toSet()
            )
        }.sortedWith(
            // Gruppen mit Übernommenem zuerst — dort brennt es.
            compareByDescending<PmPlanningGroup> { g ->
                g.todos.count { parseDate(it.scheduledDate)?.isBefore(today) == true }
            }.thenBy { it.group.title.lowercase() }
        )
    }
}

/** Eine Aufgabe mit ihren Todos im Tagesplan. */
data class PmTodayGroup(
    val id: String,
    val title: String,
    val node: PmNode?,
    val breadcrumb: String,
    val rows: List<PmTodayRow>
) {
    val isSynthetic: Boolean get() = node == null
    val done: Int get() = rows.count { it.done }
    val total: Int get() = rows.size
}

/** Ein Todo im Tagesplan. */
data class PmTodayRow(
    val node: PmNode,
    val done: Boolean,
    /** 0 = heute fällig, > 0 = seit N Tagen übernommen. */
    val rolloverDays: Int,
    val groupId: String,
    val groupTitle: String,
    val breadcrumb: String
) {
    val isRollover: Boolean get() = rolloverDays > 0
}

/** Gesamtzustand des Tages (Glas + Listen). */
data class PmTodayState(
    val today: LocalDate,
    val groups: List<PmTodayGroup>,
    val done: Int,
    val total: Int,
    /** offene, aus früheren Tagen übernommene Todos */
    val rollover: Int,
    /** offene Todos ganz ohne Datum (Backlog-Gesamtmenge) */
    val unfiled: List<PmNode>
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val isComplete: Boolean get() = total > 0 && done >= total
    val pending: Int get() = (total - done).coerceAtLeast(0)
    val isEmpty: Boolean get() = total == 0
    val percent: Int get() = (fraction * 100f).toInt()
}

/** Ein Tag im 7-Tage-Streifen. [isToday] wird von [PmToday.history] gesetzt
 *  (bewusst kein LocalDate.now() hier — die Funktion bleibt rein und testbar). */
data class PmDayHistory(val date: LocalDate, val done: Int, val isToday: Boolean = false)

/** Eine Gruppe im Tag-planen-Sheet. */
data class PmPlanningGroup(
    val group: PmTaskGroup,
    val todos: List<PmNode>,
    val suggestedIds: Set<String>
)
