package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(
    tableName = "place_geofence",
    indices = [
        Index("enabled"),
        Index("category_id"),
        Index("activity_type_id"),
        Index("deleted_at"),
        Index(value = ["latitude", "longitude"])
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
        )
    ]
)
data class PlaceGeofence(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "radius_meters") val radiusMeters: Float,
    val icon: String = "📍",
    val color: String = "#6366F1",
    val enabled: Boolean = true,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String? = null,
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    // M11: Automatisierungsregeln — optional, pro Geofence konfigurierbar
    @ColumnInfo(name = "auto_start_activity_type_id") val autoStartActivityTypeId: String? = null,
    @ColumnInfo(name = "auto_stop_enabled", defaultValue = "0") val autoStopEnabled: Boolean = false
) : Serializable
