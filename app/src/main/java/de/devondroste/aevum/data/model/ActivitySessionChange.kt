package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "activity_session_change",
    indices = [
        Index(value = ["session_id", "changed_at"]),
        Index(value = ["change_type", "changed_at"]),
        Index("source_candidate_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ActivitySession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ActivityCandidate::class,
            parentColumns = ["id"],
            childColumns = ["source_candidate_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ActivitySessionChange(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "change_type") val changeType: String,
    @ColumnInfo(name = "changed_by") val changedBy: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "before_json") val beforeJson: String? = null,
    @ColumnInfo(name = "after_json") val afterJson: String,
    val reason: String? = null,
    @ColumnInfo(name = "source_candidate_id") val sourceCandidateId: String? = null
) : Serializable