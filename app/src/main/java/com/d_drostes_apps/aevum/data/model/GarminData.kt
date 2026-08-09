package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M18.58: Garmin-Tageszusammenfassung (Schritte, Distanz, Kalorien).
 *
 * Wird vom Garmin-Sync-Worker befüllt (Garmin Connect API). Die Kacheln
 * im Dashboard lesen diese Tabelle für den aktuellen Tag:
 *   - Schlaf-Kachel (dunkelblau, Mond)  — kommt aus activity_session (unabhängig von der Quelle)
 *   - Schritte-Kachel                     — hier steps
 *   - Distanz/Dauer-Kachel pro Aktivität — aus [GarminActivity]
 *   - Kalorien-Kachel                     — hier calories
 */
@Entity(
    tableName = "garmin_daily_summary",
    primaryKeys = ["date"],
    indices = [Index("date")]
)
data class GarminDailySummary(
    val date: String, // ISO-LocalDate "2026-08-09"
    @ColumnInfo(name = "steps") val steps: Int = 0,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "calories") val calories: Int = 0,
    @ColumnInfo(name = "active_calories") val activeCalories: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * M18.58: Eine Garmin-Aktivität (z.B. Laufen, Radfahren).
 *
 * Wird in die Timeline importiert (Auto-Accept, Zeitraum-Überschreiben):
 * Überlappt die Garmin-Aktivität mit einer bestehenden Session, wird die
 * bestehende Session auf den Zeitraum VOR der Aktivität gekürzt (nicht
 * gelöscht — nur der überlappte Zeitraum wird ersetzt).
 *
 * externalId = Garmin-activityId → Dedup gegen Doppel-Imports.
 */
@Entity(
    tableName = "garmin_activity",
    indices = [
        Index("external_id", unique = true),
        Index("start_at"),
        Index("activity_type")
    ]
)
data class GarminActivity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "external_id") val externalId: String,
    @ColumnInfo(name = "activity_type") val activityType: String, // running, cycling, walking, ...
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "calories") val calories: Int = 0,
    @ColumnInfo(name = "imported_at") val importedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "session_id") val sessionId: String? = null // verlinkte Timeline-Session
) : Serializable
