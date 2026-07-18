package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "goal",
    indices = [
        Index(value = ["status", "period"]),
        Index("category_id"),
        Index("tag_id"),
        Index("activity_type_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Goal(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "tag_id") val tagId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    val type: String = "DURATION",
    val period: String = "DAILY",
    @ColumnInfo(name = "target_value") val targetValue: Float,
    @ColumnInfo(name = "target_unit") val targetUnit: String = "MINUTES",
    @ColumnInfo(name = "filter_json") val filterJson: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    val status: String = "ACTIVE",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) : Serializable