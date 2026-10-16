package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M19.1: Projektmanagement — Typen der Knoten.
 *
 * DEVON-ANWENDUNGSFALL: „Oberprojekte, darunter Unterprojekte, darunter
 * Kategorien, darunter Aufgaben und darunter Todos" — also fünf Ebenen,
 * gleichzeitig aber eine OFFENE Hierarchie. Deshalb gibt es vier
 * semantische Typen statt fünf Tabellen:
 *
 *  - [PROJECT]    Oberprojekt UND Unterprojekt (beliebig tief verschachtelbar)
 *  - [CATEGORY]   Gliederung innerhalb eines Projekts
 *  - [TASK]       die abhakbare Einheit: eigener Fortschrittsbalken,
 *                 eigene Swipe-Karte im Tages-Karussell
 *  - [TODO]       Blatt, abhakbar, optional tagzuweisbar (Backlog wenn ohne Datum)
 *
 * Beispiel: Studium(PROJECT) → 2. Semester(PROJECT) →
 * Projektgruppe(CATEGORY) → Präsentation(TASK) → „erste Hälfte lesen"(TODO).
 *
 * Regeln (erzwungen in [com.d_drostes_apps.aevum.domain.projects.PmTree] und
 * [com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl]):
 *  1. Nur [TODO] ist abhakbar und kann ein Datum tragen.
 *  2. [TODO] ist immer Blatt.
 *  3. Container dürfen beliebig ineinander liegen.
 *  4. Ein Knoten kann nicht in seinen eigenen Teilbaum verschoben werden.
 *  5. Verwaiste Knoten (fehlender Elter) gelten als Wurzel.
 */
object PmType {
    const val PROJECT = "PROJECT"
    const val CATEGORY = "CATEGORY"
    const val TASK = "TASK"
    const val TODO = "TODO"

    /** Alle Container-Typen (alles, was Kinder haben darf). */
    val containers: List<String> = listOf(PROJECT, CATEGORY, TASK)

    fun isValid(type: String): Boolean = type == PROJECT || type == CATEGORY || type == TASK || type == TODO

    /** Container = alles außer Todo. */
    fun isContainer(type: String): Boolean = type != TODO

    fun isTodo(type: String): Boolean = type == TODO
}

/**
 * M19.1: Ein Knoten des Projektmanagement-Baums.
 *
 * Schema-Entscheidung (siehe docs/M19-1-PROJEKTMANAGEMENT.md §3): EIN
 * selbstreferenzieller Baum (Adjacency-List) statt fünf Tabellen.
 * Die Rekursion passiert in Kotlin über den vollständig geladenen Baum —
 * bei hunderten bis wenigen tausend Knoten ist das unter einer Millisekunde
 * und erspart fünf DAOs, fünf Repositories und fünf Editoren.
 *
 * - [parentId] null = Wurzel. FK auf sich selbst, ON DELETE CASCADE: das
 *   Löschen eines Projekts räumt den kompletten Teilbaum ab.
 * - [scheduledDate] ISO (yyyy-MM-dd) oder null. null = Backlog („muss nicht
 *   zwingend mit Datum versehen sein").
 * - [doneAt] Zeitstempel der einmaligen Erledigung. Liefert automatisch die
 *   Historie (welcher Tag, welche Uhrzeit) — deshalb braucht der 7-Tage-
 *   Streifen keine zweite Tabelle.
 * - [dueDate] optionale Frist, ausdrücklich NUR informativ (Countdown-Chip).
 */
@Entity(
    tableName = "pm_node",
    indices = [
        Index("parent_id"),
        Index("type"),
        Index("sort_order"),
        Index("scheduled_date"),
        Index("done_at")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PmNode::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PmNode(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    val type: String,
    val title: String,
    val note: String = "",
    val color: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "scheduled_date") val scheduledDate: String? = null,
    @ColumnInfo(name = "due_date") val dueDate: String? = null,
    @ColumnInfo(name = "done_at") val doneAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable {

    val isTodo: Boolean get() = type == PmType.TODO
    val isContainer: Boolean get() = type != PmType.TODO
    val isDone: Boolean get() = doneAt != null
}
