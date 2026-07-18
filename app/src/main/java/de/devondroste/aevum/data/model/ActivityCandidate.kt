package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_candidate",
    indices = [
        Index(value = ["start_at", "end_at"]),
        Index(value = ["status", "created_at"]),
        Index("resolved_session_id"),
        Index("suggested_category_id"),
        Index("activity_type_id"),
        Index("source_candidate_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["suggested_category_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivityType::class,
            parentColumns = ["id"],
            childColumns = ["activity_type_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["resolved_session_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivityCandidate(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "suggested_title") val suggestedTitle: String,
    @ColumnInfo(name = "suggested_category_id") val suggestedCategoryId: String? = null,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    val confidence: Float = 0.0f,
    val status: String = "PENDING",
    val reason: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String = "AUTO",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long? = null,
    @ColumnInfo(name = "resolved_session_id") val resolvedSessionId: String? = null,
    @ColumnInfo(name = "source_candidate_id") val sourceCandidateId: String? = null
) : Serializable