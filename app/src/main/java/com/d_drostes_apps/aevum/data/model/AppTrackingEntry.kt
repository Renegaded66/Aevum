package com.d_drostes_apps.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M18.67: App-Aufzeichnung — eine App (packageName) wird automatisch
 * als Activity aufgezeichnet, wenn sie in den Vordergrund kommt.
 *
 * Jede getrackte App hat genau EINEN zugeordneten ActivityType
 * (User: "Es reicht, pro Geofence einmal einen Activity Type anzugeben" —
 * gleiches Prinzip hier: pro App einmal die Activity zuordnen).
 *
 * Verhalten (User-Spezifikation):
 *  - App öffnen, während KEINE Aufzeichnung läuft → Auto-Start der
 *    zugeordneten Activity (sourceType = "APP_TRACKING")
 *  - App schließen → Auto-Stopp der gestarteten Session
 *  - App öffnen, während eine ANDERE Aufzeichnung läuft → nichts
 *    passiert, die laufende Aufzeichnung läuft normal weiter
 */
@Entity(
    tableName = "app_tracking_entry",
    indices = [Index(value = ["package_name"], unique = true)]
)
data class AppTrackingEntry(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "activity_type_id") val activityTypeId: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
