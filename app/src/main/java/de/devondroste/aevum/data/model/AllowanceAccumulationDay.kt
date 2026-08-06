package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * M17.3: Tageskumulation einer [DailyAllowance]. Wird vom Midnight-Worker
 * um 00:05 für den Vortag angelegt. Wird in [InsightsAnalytics] zu den
 * Session-Minuten addiert — aber NICHT in der Timeline angezeigt.
 */
@Entity(
    tableName = "allowance_accumulation_day",
    primaryKeys = ["date", "timezone_id", "allowance_id"],
    indices = [
        Index(value = ["date"]),
        Index(value = ["allowance_id"]),
        Index(value = ["activity_type_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = DailyAllowance::class,
            parentColumns = ["id"],
            childColumns = ["allowance_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AllowanceAccumulationDay(
    /** ISO-LocalDate (yyyy-MM-dd) im Anwender-Timezone. */
    val date: String,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "allowance_id") val allowanceId: String,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String,
    val minutes: Int,
    @ColumnInfo(name = "applied_at") val appliedAt: Long = System.currentTimeMillis()
)
