package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "detection_event",
    indices = [
        Index(value = ["kind", "start_at"]),
        Index(value = ["source_id", "start_at"]),
        Index("raw_event_id"),
        Index("place_id")
    ],
    foreignKeys = [
        ForeignKey(
            entity = RawSourceEvent::class,
            parentColumns = ["id"],
            childColumns = ["raw_event_id"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = DataSource::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = PlaceGeofence::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class DetectionEvent(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "raw_event_id") val rawEventId: String? = null,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val kind: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long? = null,
    val confidence: Float = 0.0f,
    @ColumnInfo(name = "place_id") val placeId: String? = null,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) : Serializable