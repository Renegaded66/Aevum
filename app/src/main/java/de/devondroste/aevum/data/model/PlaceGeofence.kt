package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "place_geofence",
    indices = [Index("enabled"), Index("category_id")],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class PlaceGeofence(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "radius_meters") val radiusMeters: Float,
    val enabled: Boolean = true
) : Serializable
