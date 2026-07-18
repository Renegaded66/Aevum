package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_session",
    indices = [
        Index("start_at"),
        Index("end_at"),
        Index(value = ["category_id", "start_at"]),
        Index(value = ["activity_type_id", "start_at"]),
        Index(value = ["source_type", "start_at"]),
        Index(value = ["deleted_at", "start_at"]),
        Index("source_candidate_id"),
        Index("supersedes_session_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityCandidate::class,
            parentColumns = ["id"],
            childColumns = ["source_candidate_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["supersedes_session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivitySession(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    @ColumnInfo(name = "timezone_id") val timezoneId: String = "UTC",
    val description: String? = null,
    @ColumnInfo(name = "source_type") val sourceType: String = "MANUAL",
    @ColumnInfo(name = "created_by") val createdBy: String = "MANUAL",
    @ColumnInfo(name = "updated_by") val updatedBy: String? = null,
    @ColumnInfo(name = "source_candidate_id") val sourceCandidateId: String? = null,
    @ColumnInfo(name = "supersedes_session_id") val supersedesSessionId: String? = null,
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "is_user_edited") val isUserEdited: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    val revision: Int = 1,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String? = null
) : Serializable