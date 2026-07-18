package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.TriggerEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerEventDao {
    @Query("SELECT * FROM trigger_event ORDER BY occurred_at DESC")
    fun getAll(): Flow<List<TriggerEvent>>

    @Query("SELECT * FROM trigger_event WHERE occurred_at >= :start AND occurred_at < :end ORDER BY occurred_at")
    fun getByDateRange(start: Long, end: Long): Flow<List<TriggerEvent>>

    @Query("SELECT * FROM trigger_event WHERE geofence_id = :geofenceId ORDER BY occurred_at DESC")
    fun getByGeofenceId(geofenceId: String): Flow<List<TriggerEvent>>

    @Query("SELECT * FROM trigger_event WHERE id = :id")
    fun getById(id: String): Flow<TriggerEvent?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: TriggerEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<TriggerEvent>)

    @Query("DELETE FROM trigger_event WHERE id = :id")
    suspend fun delete(id: String)
}