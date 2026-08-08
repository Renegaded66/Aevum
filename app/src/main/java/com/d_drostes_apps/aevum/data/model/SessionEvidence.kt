package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "session_evidence",
    indices = [
        Index(value = ["session_id", "detection_event_id"]),
        Index(value = ["candidate_id", "detection_event_id"]),
        Index("detection_event_id")
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
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DetectionEvent::class,
            parentColumns = ["id"],
            childColumns = ["detection_event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionEvidence(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "candidate_id") val candidateId: String? = null,
    @ColumnInfo(name = "detection_event_id") val detectionEventId: String,
    val weight: Float = 1.0f,
    val relationship: String,
    val reason: String? = null
) : Serializable