package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M20: Check-in eines MANUELLEN Ziels — „abhaken" bzw. „zählen".
 *
 * Beispiele aus dem Nutzerwunsch:
 *  - „4 mal zwischen Montag und Freitag Aufgaben machen" → vier Check-ins
 *    pro Woche (jeder Tap +1; die Wochentage filtert die Auswertung über
 *    [Goal.scheduleDays]).
 *  - „Zahnseide täglich" → ein Check-in pro Tag (Zieltyp COUNT mit Ziel 1).
 *
 * Jeder Check-in ist eine eigene Zeile (nicht ein Zähler am Ziel): So sind
 * „Rückgängig" (letzten Check-in löschen) und eine ehrliche Verlaufs-Liste
 * trivial, und es gibt keine Race-Probleme beim schnellen Mehrfach-Tippen.
 *
 * [dateKey] ist das ISO-Datum (yyyy-MM-dd) des Check-ins — dieselbe
 * Konvention wie [TodoCompletion.date]; die Auswertung arbeitet
 * tagesbasiert (Perioden-Fenster, Wochentags-Maske, Streaks).
 */
@Entity(
    tableName = "goal_checkin",
    indices = [
        Index("goal_id"),
        Index("date_key")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Goal::class,
            parentColumns = ["id"],
            childColumns = ["goal_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GoalCheckin(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "goal_id") val goalId: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "date_key") val dateKey: String,
    val count: Int = 1,
    // M35: Optionaler Activity-Bezug des Check-ins. Der Wert wird beim
    // Abhaken aus dem Ziel übernommen (Snapshot), damit spätere Änderungen
    // am Ziel die bereits gutgeschriebene Zeit nicht rückwirkend umbuchen.
    // null = Ziel ohne Activity verknüpft.
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    // M35: Optional gutgeschriebene Zeit (0 = keine Zeit erfasst). Die Zeit
    // zählt für den Tag in [dateKey] und fließt in die Aktivitätsstatistik
    // ein — bewusst NICHT als Session in die Timeline.
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0L
) : Serializable
