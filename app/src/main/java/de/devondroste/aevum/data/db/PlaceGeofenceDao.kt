package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.PlaceGeofence
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceGeofenceDao {
    @Query("SELECT * FROM place_geofence WHERE enabled = 1 ORDER BY name")
    fun getEnabled(): Flow<List<PlaceGeofence>>

    @Query("SELECT * FROM place_geofence ORDER BY name")
    fun getAll(): Flow<List<PlaceGeofence>>

    @Query("SELECT * FROM place_geofence WHERE id = :id")
    fun getById(id: String): Flow<PlaceGeofence?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(geofence: PlaceGeofence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(geofences: List<PlaceGeofence>)

    @Update
    suspend fun update(geofence: PlaceGeofence)

    @Query("DELETE FROM place_geofence WHERE id = :id")
    suspend fun delete(id: String)
}