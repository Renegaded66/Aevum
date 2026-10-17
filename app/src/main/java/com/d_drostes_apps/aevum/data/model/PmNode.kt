package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M19.2: Projektmanagement — nur noch ZWEI Arten von Knoten.
 *
 * NUTZER-ENTSCHEIDUNG (wörtlich): „Ich möchte keine Unterscheidung zwischen
 * Projekt, Kategorie und Aufgabe. Man soll nur Projekte anlegen können, und
 * innerhalb Projekte kann man Unterprojekte und Aufgaben anlegen, natürlich
 * zu den Unterprojekten auch Aufgaben."
 *
 * Deshalb gibt es statt vier Typen (PROJECT/CATEGORY/TASK/TODO aus M19.1)
 * genau zwei:
 *
 *  - [PROJECT]    Container (Ober- UND Unterprojekt, beliebig nestbar).
 *                 Trägt einen Fortschrittsbalken über alle enthaltenen Aufgaben.
 *  - [TASK]       Blatt, abhakbar, optional tagzuweisbar; erscheint als Zeile
 *                 in der To-do-Liste seines Projekts (mit Häkchen, Drag & Drop
 *                 und Löschen). Aufgaben liegen immer unter einem Projekt.
 *
 * Die alte Unterscheidung „Aufgabe als Container mit Schritten" ist damit
 * GESCHICHTE: Eine Aufgabe ist selbst der kleinste Schritt. Bestandsdaten
 * werden bei der Migration 44 → 45 überführt (CATEGORY → PROJECT,
 * TASK mit Kindern → PROJECT, TODO → TASK).
 *
 * Regeln (erzwungen in [com.d_drostes_apps.aevum.domain.projects.PmTree] und
 * [com.d_drostes_apps.aevum.data.repository.PmRepositoryImpl]):
 *  1. Nur [TASK] ist abhakbar und kann ein Datum tragen.
 *  2. [TASK] ist immer Blatt — nichts liegt unter einer Aufgabe.
 *  3. Container (PROJECT) dürfen beliebig ineinander liegen.
 *  4. Auf oberster Ebene stehen nur Projekte.
 *  5. Ein Knoten kann nicht in seinen eigenen Teilbaum verschoben werden.
 *  6. Verwaiste Knoten (fehlender Elter) gelten als Wurzel.
 */
object PmType {
    const val PROJECT = "PROJECT"
    const val TASK = "TASK"

    /** Alle Typen (nur noch zwei, siehe Migration 44 → 45). */
    val all: List<String> = listOf(PROJECT, TASK)

    fun isValid(type: String): Boolean = type == PROJECT || type == TASK

    /** Container = alles, was Kinder haben darf (nur das Projekt). */
    fun isContainer(type: String): Boolean = type == PROJECT

    /** Abhakbare Einheit (nur die Aufgabe). */
    fun isTask(type: String): Boolean = type == TASK
}

/**
 * M19.1/M19.2: Ein Knoten des Projektmanagement-Baums.
 *
 * Schema-Entscheidung (siehe docs/M19-1-PROJEKTMANAGEMENT.md §3): EIN
 * selbstreferenzieller Baum (Adjacency-List) statt fünf Tabellen.
 * Die Rekursion passiert in Kotlin über den vollständig geladenen Baum — bei
 * hunderten bis wenigen tausend Knoten ist das unter einer Millisekunde.
 *
 * Das Schema bleibt gegenüber M19.1 UNVERÄNDERT (Migration 44 → 45 ist eine
 * reine Daten-Migration, siehe AppDatabase) — nur die Bedeutung von `type`
 * hat sich verengt.
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

    val isTask: Boolean get() = type == PmType.TASK
    val isContainer: Boolean get() = type == PmType.PROJECT
    val isDone: Boolean get() = doneAt != null
}
