package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.io.Serializable

@Entity(
    tableName = "place_geofence_tag",
    primaryKeys = ["geofence_id", "tag_id"],
    indices = [Index("tag_id")],
    foreignKeys = [
        ForeignKey(
            entity = PlaceGeofence::class,
            parentColumns = ["id"],
            childColumns = ["geofence_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PlaceGeofenceTag(
    @ColumnInfo(name = "geofence_id") val geofenceId: String,
    @ColumnInfo(name = "tag_id") val tagId: String
) : Serializable
