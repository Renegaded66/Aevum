package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M8.2: Persistent geofence event log.
 *
 * Survives process death. Each entry traces a single step in the pipeline.
 * This distinguishes "system never sent event" from "app received but dropped it".
 *
 * Categories:
 * - REGISTRATION: geofence was registered / re-registered / failed
 * - SYSTEM_EVENT: raw event received from Google Play Services (ENTER/EXIT/ERROR)
 * - PIPELINE: how the event was processed (stored trigger / ignored / reason)
 * - DIAGNOSTIC: periodic health checks (are geofences still registered?)
 */
@Entity(
    tableName = "geofence_event_log",
    indices = [
        Index("occurred_at"),
        Index("category"),
        Index("event_type")
    ]
)
data class GeofenceEventLogEntry(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    val category: String,       // REGISTRATION, SYSTEM_EVENT, PIPELINE, DIAGNOSTIC
    @ColumnInfo(name = "event_type") val eventType: String,  // GEOFENCE_ENTER, GEOFENCE_EXIT, ERROR, REGISTERED, etc.
    @ColumnInfo(name = "geofence_id") val geofenceId: String? = null,
    @ColumnInfo(name = "geofence_name") val geofenceName: String? = null,
    @ColumnInfo(name = "detail") val detail: String,          // Description / reason / error message
    val success: Boolean = true,                              // Did this step succeed?
    @ColumnInfo(name = "lat") val latitude: Double? = null,
    @ColumnInfo(name = "lon") val longitude: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
