package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.GeofenceEventLogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceEventLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: GeofenceEventLogEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<GeofenceEventLogEntry>)

    @Query("SELECT * FROM geofence_event_log ORDER BY occurred_at DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<GeofenceEventLogEntry>>

    @Query("SELECT * FROM geofence_event_log WHERE category = :category ORDER BY occurred_at DESC LIMIT :limit")
    fun getByCategory(category: String, limit: Int = 50): Flow<List<GeofenceEventLogEntry>>

    @Query("SELECT * FROM geofence_event_log WHERE success = 0 ORDER BY occurred_at DESC LIMIT :limit")
    fun getFailures(limit: Int = 20): Flow<List<GeofenceEventLogEntry>>

    @Query("SELECT * FROM geofence_event_log WHERE occurred_at >= :since ORDER BY occurred_at DESC")
    fun getSince(since: Long): Flow<List<GeofenceEventLogEntry>>

    @Query("SELECT COUNT(*) FROM geofence_event_log WHERE category = 'SYSTEM_EVENT' AND occurred_at >= :since")
    suspend fun countSystemEventsSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM geofence_event_log WHERE category = 'PIPELINE' AND success = 1 AND occurred_at >= :since")
    suspend fun countSuccessfulTriggersSince(since: Long): Int

    @Query("DELETE FROM geofence_event_log WHERE occurred_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
