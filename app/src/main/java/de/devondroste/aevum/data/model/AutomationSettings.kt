package de.devondroste.aevum.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "automation_settings")
data class AutomationSettings(
    @PrimaryKey val id: String = "default",
    @ColumnInfo(name = "geofencing_enabled") val geofencingEnabled: Boolean = false,
    @ColumnInfo(name = "background_capture_enabled") val backgroundCaptureEnabled: Boolean = false,
    @ColumnInfo(name = "review_notifications_enabled") val reviewNotificationsEnabled: Boolean = false,
    @ColumnInfo(name = "battery_saver_mode") val batterySaverMode: Boolean = true,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    // M8: Per-source toggles
    @ColumnInfo(name = "health_sleep_enabled", defaultValue = "0") val healthSleepEnabled: Boolean = false,
    @ColumnInfo(name = "digital_balance_enabled", defaultValue = "0") val digitalBalanceEnabled: Boolean = false,
    // M14: Schlaf-Fusion (Screen + Activity Recognition + Digital Balance).
    // Default aus — der User entscheidet bewusst, ob die 3-Signal-Fusion läuft.
    @ColumnInfo(name = "sleep_fusion_enabled", defaultValue = "0") val sleepFusionEnabled: Boolean = false
) : Serializable
