package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.d_drostes_apps.aevum.data.model.PlaceGeofence
import com.d_drostes_apps.aevum.data.model.PlaceGeofenceTag
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceGeofenceDao {
    @Query("SELECT * FROM place_geofence WHERE enabled = 1 AND deleted_at IS NULL ORDER BY name")
    fun getEnabled(): Flow<List<PlaceGeofence>>

    @Query("SELECT * FROM place_geofence WHERE deleted_at IS NULL ORDER BY name")
    fun getAll(): Flow<List<PlaceGeofence>>

    @Query("SELECT * FROM place_geofence WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun getDeleted(): Flow<List<PlaceGeofence>>

    @Query("SELECT * FROM place_geofence WHERE id = :id")
    fun getById(id: String): Flow<PlaceGeofence?>

    @Query("SELECT tag_id FROM place_geofence_tag WHERE geofence_id = :geofenceId ORDER BY tag_id")
    fun getTagIdsForGeofence(geofenceId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(geofence: PlaceGeofence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(geofences: List<PlaceGeofence>)

    @Update
    suspend fun update(geofence: PlaceGeofence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagMappings(mappings: List<PlaceGeofenceTag>)

    @Query("DELETE FROM place_geofence_tag WHERE geofence_id = :geofenceId")
    suspend fun deleteTagMappings(geofenceId: String)

    @Transaction
    suspend fun insertWithTags(geofence: PlaceGeofence, tagIds: List<String>) {
        insert(geofence)
        deleteTagMappings(geofence.id)
        if (tagIds.isNotEmpty()) {
            insertTagMappings(tagIds.map { PlaceGeofenceTag(geofence.id, it) })
        }
    }

    @Query("UPDATE place_geofence SET deleted_at = :now, enabled = 0, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM place_geofence WHERE id = :id")
    suspend fun delete(id: String)
}