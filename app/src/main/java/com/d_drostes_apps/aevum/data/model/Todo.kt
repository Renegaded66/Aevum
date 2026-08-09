package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M18.30: Todo — eine Aufgabe mit optionalem Ziel.
 *
 * Zwei Typen:
 *  - CHECKBOX: einfach fertig/nicht-fertig ("Heute Müll rausbringen")
 *  - DURATION: Dauer-Ziel ("Heute 2 Stunden lernen") — wird automatisch
 *    abgehakt, sobald genug Zeit der zugeordneten Aktivität erfasst wurde.
 *
 * Recurrence (frequency_rule_json) — durchdachtes System:
 *  - ONCE: einmalig, wird nach Erledigung archiviert
 *  - DAILY: jeden Tag
 *  - WEEKDAYS: Mo-Fr
 *  - WEEKLY_ON: an bestimmten Wochentagen (bitmask, Mo=1..So=64)
 *  - EVERY_N_DAYS: alle x Tage (nur am Stichtag relevant)
 *  - N_PER_WEEK: x-mal pro Woche (flexibel)
 *  - N_PER_MONTH: x-mal pro Monat (flexibel)
 *
 * Auto-Check: Wenn activityTypeId + durationMinutes gesetzt sind, wird
 * pro Tag die erfasste Dauer dieser Aktivität summiert (inkl. laufender
 * Session). Ist Dauer >= Ziel, gilt der Tag als erledigt.
 */
@Entity(
    tableName = "todo",
    indices = [
        Index("active"),
        Index("activity_type_id"),
        Index("created_at")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Todo(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    /** Ziel in Minuten — 0 = reine Checkbox (fertig/nicht fertig) */
    @ColumnInfo(name = "target_minutes") val targetMinutes: Int = 0,
    /** Recurrence-Typ: ONCE, DAILY, WEEKDAYS, WEEKLY_ON, EVERY_N_DAYS, N_PER_WEEK, N_PER_MONTH */
    @ColumnInfo(name = "recurrence_type") val recurrenceType: String = "ONCE",
    /** JSON mit recurrence-Parametern (weekdaysBitmask, intervalDays, countPerPeriod, startDate) */
    @ColumnInfo(name = "recurrence_json") val recurrenceJson: String = "{}",
    @ColumnInfo(name = "start_date") val startDate: String,
    /** Nur fuer ONCE: optionales Faelligkeitsdatum (ISO) — ohne Datum taeglich relevant bis erledigt */
    @ColumnInfo(name = "due_date") val dueDate: String? = null,
    val active: Boolean = true,
    // M18.60: Streak-only-Todo ("heute kein Alkohol") — KEIN Abhaken
    // im klassischen Sinn: Der Eintrag ist ein Check-in, der nur den
    // Streak erhöht. Die Karte erscheint dezent, die Interaktion ist
    // "War heute dabei" statt "erledigt".
    @ColumnInfo(name = "check_in_only") val checkInOnly: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * M18.30: Erledigung eines Todos an einem bestimmten Tag (Datum ISO, z.B. 2026-08-07).
 * Pro (todoId, date) genau ein Eintrag.
 */
@Entity(
    tableName = "todo_completion",
    primaryKeys = ["todo_id", "date"],
    indices = [
        Index("todo_id"),
        Index("date")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Todo::class,
            parentColumns = ["id"],
            childColumns = ["todo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TodoCompletion(
    @ColumnInfo(name = "todo_id") val todoId: String,
    val date: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long = System.currentTimeMillis(),
    /** Manuell abgehakt ODER auto (Session-Aggregation) */
    val source: String = "MANUAL"
) : Serializable
