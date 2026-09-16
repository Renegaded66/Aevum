package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.AutomationSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationSettingsDao {
    @Query("SELECT * FROM automation_settings WHERE id = 'default'")
    fun get(): Flow<AutomationSettings?>

    @Query("SELECT * FROM automation_settings WHERE id = 'default'")
    suspend fun getSettingsSync(): AutomationSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AutomationSettings)

    /**
     * M18.129: Nur den Kalender-Sync-Zeitstempel schreiben.
     *
     * BEWUSST KEIN `upsert(settings.copy(...))`: das wäre ein
     * read-modify-write über das GESAMTE Settings-Objekt — ein paralleler
     * Schreibvorgang (z. B. ein Toggle in der UI) würde dabei überschrieben.
     * Ein gezieltes UPDATE trifft genau eine Spalte und ist konfliktfrei.
     */
    @Query("UPDATE automation_settings SET calendar_last_sync_at = :at, updated_at = :updatedAt WHERE id = 'default'")
    suspend fun setCalendarLastSyncAt(at: Long, updatedAt: Long)

    @Query("UPDATE automation_settings SET calendar_sync_enabled = :enabled, updated_at = :updatedAt WHERE id = 'default'")
    suspend fun setCalendarSyncEnabled(enabled: Boolean, updatedAt: Long)

    @Query("UPDATE automation_settings SET calendar_auto_tracking_enabled = :enabled, updated_at = :updatedAt WHERE id = 'default'")
    suspend fun setCalendarAutoTrackingEnabled(enabled: Boolean, updatedAt: Long)

    @Query("UPDATE automation_settings SET calendar_sync_interval_hours = :hours, updated_at = :updatedAt WHERE id = 'default'")
    suspend fun setCalendarSyncIntervalHours(hours: Int, updatedAt: Long)
}