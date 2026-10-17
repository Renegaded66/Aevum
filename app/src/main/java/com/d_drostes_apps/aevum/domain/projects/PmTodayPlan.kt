package com.d_drostes_apps.aevum.domain.projects

import com.d_drostes_apps.aevum.data.model.PmNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * M19.1/M19.2: Tagesplan-Logik — REIN und ohne Android-Abhängigkeit.
 *
 * DEVON-ANWENDUNGSFALL (wörtlich):
 *  - „die einzelnen todos will ich einen Tag zuweisen können"
 *  - „Zukünftige todos müssen nicht zwingend mit Datum versehen sein"
 *  - „diese bestimmten 20 todos will ich heute schaffen, die alle für heute
 *     fällig haben und dann machen"
 *  - „Nicht abgehakte todos werden am nächsten Tag weiterhin angezeigt,
 *     der Rest nicht"
 *  - „wenn man alle abgehakt hat ist das Glas mit Juwelen voll"
 *  - M19.2: „Im PM Dashboard sollten NUR für heute zugewiesene Aufgaben
 *     angezeigt werden."
 *
 * Daraus ergibt sich exakt diese Tabelle:
 *
 * | Zustand der Aufgabe                               | im Tagesplan?        |
 * |---------------------------------------------------|----------------------|
 * | offen, Datum == heute                             | ja                   |
 * | offen, Datum < heute                              | ja, als „übernommen" |
 * | offen, kein Datum                                | nein → Backlog       |
 * | offen, Datum > heute                             | nein                 |
 * | heute erledigt (unabhängig vom Datum)             | ja, als Juwel        |
 * | an einem früheren Tag erledigt                    | nein                 |
 *
 * M19.2-REIHENFOLGE: Innerhalb einer Gruppe gilt die MANUELLE Reihenfolge
 * ([PmNode.sortOrder]) — sie ist die Grundlage des Drag-&-Drop-Verschiebens
 * in der To-do-Liste. Erledigte rutschen ans Ende. Die frühere Automatik
 * („übernommene zuerst") ist entfallen, weil sie eine vom Nutzer gesetzte
 * Reihenfolge beim nächsten Öffnen wieder überschrieben hätte; „übernommen"
 * zeigt jetzt der Chip an der Zeile.
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
     * Wie viele Tage ist die Aufgabe bereits überfällig?
     * 0 = heute fällig, später geplant oder ohne Datum; 1 = gestern, …
     * Wird überall dort gebraucht, wo die volle Aufgabenliste eines Projekts
     * gezeigt wird (nicht nur der Tagesausschnitt).
     */
    fun rolloverDays(node: PmNode, today: LocalDate): Int {
        val scheduled = parseDate(node.scheduledDate) ?: return 0
        if (scheduled.isAfter(today)) return 0
        return java.time.temporal.ChronoUnit.DAYS.between(scheduled, today).toInt().coerceAtLeast(0)
    }

    /** Wurde die Aufgabe an diesem Tag erledigt? */
    fun isDoneOn(node: PmNode, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val doneAt = node.doneAt ?: return false
        return isoDateOf(doneAt, zone) == date.toString()
    }

    /**
     * Aufgaben des Tages, gruppiert nach Projekt. Projekte ohne heutige
     * Aufgaben tauchen nicht auf (sonst hätte man leere Überschriften).
     */
    fun plan(tree: PmTree, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<PmTodayGroup> {
        val rows = tree.allTasks()
            .mapNotNull { task -> rowOrNull(task, tree, today, zone) }
            .groupBy { it.groupId }

        val groups = tree.taskGroups().associateBy { it.id }
        return rows.entries
            .map { (groupId, entries) ->
                val group = groups[groupId]
                PmTodayGroup(
                    id = groupId,
                    title = group?.title ?: "",
                    node = group?.node,
                    breadcrumb = group?.breadcrumb ?: "",
                    rows = entries.sortedWith(
                        // Offene zuerst in MANUELLER Reihenfolge (Drag & Drop);
                        // Erledigte unten in der Reihenfolge des Abhakens (das
                        // zuletzt Abgehakte steht damit unten — die Liste
                        // „wächst" nach unten, wie ein Stapel).
                        compareBy<PmTodayRow> { if (it.done) 1 else 0 }
                            .thenBy { it.node.sortOrder }
                            .thenBy { it.node.createdAt }
                            .thenBy { it.node.doneAt ?: Long.MAX_VALUE }
                    )
                )
            }
            .sortedWith(compareByDescending<PmTodayGroup> { it.rows.count { row -> !row.done } }.thenBy { it.title.lowercase() })
    }

    /** Zeile des Tagesplans — null, wenn die Aufgabe heute nicht auftaucht. */
    private fun rowOrNull(task: PmNode, tree: PmTree, today: LocalDate, zone: ZoneId): PmTodayRow? {
        if (!task.isTask) return null
        val parent = tree.byId(task.parentId)
        val groupId = if (parent != null && parent.isContainer) parent.id else PmTree.UNASSIGNED_TASK_ID
        val parentTitle = if (parent != null && parent.isContainer) parent.title else ""
        val breadcrumb = tree.breadcrumb(task.id)

        if (task.doneAt != null) {
            // Nur heute erledigte Aufgaben bleiben stehen („der Rest nicht").
            if (!isDoneOn(task, today, zone)) return null
            return PmTodayRow(
                node = task, done = true, rolloverDays = 0,
                groupId = groupId, groupTitle = parentTitle, breadcrumb = breadcrumb
            )
        }
        val scheduled = parseDate(task.scheduledDate) ?: return null
        if (scheduled.isAfter(today)) return null
        val overdue = java.time.temporal.ChronoUnit.DAYS.between(scheduled, today).toInt().coerceAtLeast(0)
        return PmTodayRow(
            node = task, done = false, rolloverDays = overdue,
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
            unfiled = tree.allTasks().filter { it.doneAt == null && it.scheduledDate == null }
        )
    }

    /**
     * Verlauf der letzten [days] Tage (inklusive heute) — aus `done_at`
     * abgeleitet, ohne eigene Tabelle: wie viele Aufgaben wurden an dem Tag
     * erledigt? Grundlage für den 7-Tage-Streifen.
     */
    fun history(tree: PmTree, today: LocalDate, days: Int = 7, zone: ZoneId = ZoneId.systemDefault()): List<PmDayHistory> {
        val counts = HashMap<String, Int>()
        for (task in tree.allTasks()) {
            val doneAt = task.doneAt ?: continue
            val iso = isoDateOf(doneAt, zone)
            counts[iso] = (counts[iso] ?: 0) + 1
        }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            PmDayHistory(date = date, done = counts[date.toString()] ?: 0, isToday = back == 0)
        }
    }

    /**
     * Für das Tag-planen-Sheet: alle offenen Aufgaben, gruppiert nach Projekt,
     * mit Vorschlag, was heute dazugehören sollte (übernommene + heute
     * geplante). Die Reihenfolge entspricht dem Baum.
     */
    fun planningGroups(tree: PmTree, today: LocalDate): List<PmPlanningGroup> {
        return tree.taskGroups().mapNotNull { group ->
            val open = group.tasks.filter { it.doneAt == null }
            if (open.isEmpty()) return@mapNotNull null
            PmPlanningGroup(
                group = group,
                tasks = open,
                suggestedIds = open.filter { task ->
                    val scheduled = parseDate(task.scheduledDate)
                    scheduled != null && !scheduled.isAfter(today)
                }.map { it.id }.toSet()
            )
        }.sortedWith(
            // Gruppen mit Übernommenem zuerst — dort brennt es.
            compareByDescending<PmPlanningGroup> { g ->
                g.tasks.count { parseDate(it.scheduledDate)?.isBefore(today) == true }
            }.thenBy { it.group.title.lowercase() }
        )
    }
}

/** Ein Projekt mit seinen Aufgaben im Tagesplan. */
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

/** Eine Aufgabe im Tagesplan. */
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
    /** offene, aus früheren Tagen übernommene Aufgaben */
    val rollover: Int,
    /** offene Aufgaben ganz ohne Datum (Backlog-Gesamtmenge) */
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
    val tasks: List<PmNode>,
    val suggestedIds: Set<String>
)
