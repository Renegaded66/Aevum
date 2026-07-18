package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "trigger_event",
    indices = [
        Index(value = ["occurred_at"]),
        Index(value = ["type", "occurred_at"]),
        Index(value = ["source", "occurred_at"]),
        Index("geofence_id"),
        Index("detection_event_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaceGeofence::class,
            parentColumns = ["id"],
            childColumns = ["geofence_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DetectionEvent::class,
            parentColumns = ["id"],
            childColumns = ["detection_event_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class TriggerEvent(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val type: String,
    val source: String,
    val confidence: Float = 1.0f,
    @ColumnInfo(name = "geofence_id") val geofenceId: String? = null,
    @ColumnInfo(name = "detection_event_id") val detectionEventId: String? = null,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) : Serializable
