package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_aggregate_day",
    primaryKeys = ["date", "timezone_id"],
    indices = [
        Index("date"),
        Index("timezone_id"),
        Index("category_id"),
        Index("activity_type_id"),
        Index("tag_id")
    ]
)
data class ActivityAggregateDay(
    val date: String,
    @ColumnInfo(name = "timezone_id") val timezoneId: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    @ColumnInfo(name = "tag_id") val tagId: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "session_count") val sessionCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable