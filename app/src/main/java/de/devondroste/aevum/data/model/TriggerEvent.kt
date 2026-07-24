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
        Index("detection_event_id"),
        Index("anchor_quality")
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
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    // M10.1: Anchor-Quality — bewertet, wie vertrauenswürdig der Trigger als
    // Session-Anker ist. HIGH = DWELL-bestätigt oder Health-Connect-verified.
    // MEDIUM = normaler ENTER/EXIT. LOW = Roher GPS-Ping, sollte nicht
    // alleine eine Session starten.
    @ColumnInfo(name = "anchor_quality", defaultValue = "MEDIUM")
    val anchorQuality: String = "MEDIUM",
    // M10.1: Wenn ein Trigger durch Debounce unterdrückt wurde, wird die
    // Referenz hier gespeichert. Spätere Algorithmen können so die
    // "echte" Trigger-Sequenz rekonstruieren.
    @ColumnInfo(name = "suppressed_count", defaultValue = "0") val suppressedCount: Int = 0
) : Serializable
