package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * M17.2: Unbekannter Ort — eine Zeitperiode, in der der User an einer
 * Position war, die nicht zu einem registrierten Geofence passte.
 *
 * Wird vom [com.d_drostes_apps.aevum.automation.unknownplace.UnknownPlaceDetectorWorker]
 * erzeugt. Der User kann im UnknownPlacesScreen entweder:
 *  - einen Namen vergeben (wird zu einer [ActivitySession] konvertiert)
 *  - einen Geofence daraus erstellen (mit Karten-Picker + Radius)
 *  - den Eintrag verwerfen
 *
 * [resolved] wird auf 1 gesetzt sobald eine der drei Aktionen passiert.
 */
@Entity(
    tableName = "unknown_place_session",
    indices = [
        Index(value = ["start_at"]),
        Index(value = ["resolved", "start_at"])
    ]
)
data class UnknownPlaceSession(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long,
    val latitude: Double,
    val longitude: Double,
    /** Genauigkeit der Standortbestimmung in Metern (von FusedLocationProvider). */
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float = 0f,
    /** Vom User vergebener Name (optional — kann null sein, wenn noch offen). */
    val name: String? = null,
    /** Falls der User daraus einen Geofence erstellt hat: PlaceGeofence.id. */
    @ColumnInfo(name = "geofence_id") val geofenceId: String? = null,
    /** 0 = noch offen, 1 = vom User aufgelöst (Named / Geofence / Dismissed). */
    val resolved: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) : Serializable {
    val durationMs: Long get() = (endAt - startAt).coerceAtLeast(0L)
    val displayTitle: String get() = name ?: "Unbekannter Ort"
}
