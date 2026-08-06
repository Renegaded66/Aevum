package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M17.3: Tagespauschale — eine fixe Minuten-pro-Tage-Zuordnung zu einer
 * Aktivität, die jeden Tag in die STATISTIK einfließt, aber NICHT in
 * der Timeline erscheint.
 *
 * Beispiel: "Fertig machen" — jeden Tag 30 min, egal wann. Erscheint
 * in der Wochenstatistik mit 30 min, aber in der Timeline ist kein
 * Block sichtbar.
 *
 * Die eigentliche Kumulation passiert in [AllowanceAccumulationDay]
 * durch den [de.devondroste.aevum.automation.midnight.MidnightAllowanceWorker].
 */
@Entity(
    tableName = "daily_allowance",
    indices = [
        Index("enabled"),
        Index("activity_type_id")
    ]
)
data class DailyAllowance(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String,
    @ColumnInfo(name = "minutes_per_day") val minutesPerDay: Int,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable
