package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.UnknownPlaceSession
import kotlinx.coroutines.flow.Flow

@Dao
interface UnknownPlaceSessionDao {
    @Query("SELECT * FROM unknown_place_session WHERE resolved = 0 ORDER BY start_at DESC")
    fun getOpen(): Flow<List<UnknownPlaceSession>>

    @Query("SELECT * FROM unknown_place_session ORDER BY start_at DESC")
    fun getAll(): Flow<List<UnknownPlaceSession>>

    @Query("SELECT * FROM unknown_place_session WHERE id = :id")
    suspend fun getById(id: String): UnknownPlaceSession?

    @Query("SELECT COUNT(*) FROM unknown_place_session WHERE resolved = 0")
    suspend fun countOpen(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: UnknownPlaceSession)

    @Query("UPDATE unknown_place_session SET resolved = 1, name = :name WHERE id = :id")
    suspend fun markNamed(id: String, name: String)

    @Query("UPDATE unknown_place_session SET resolved = 1, geofence_id = :geofenceId WHERE id = :id")
    suspend fun markConverted(id: String, geofenceId: String)

    @Query("UPDATE unknown_place_session SET resolved = 1 WHERE id = :id")
    suspend fun markDismissed(id: String)

    @Query("DELETE FROM unknown_place_session WHERE id = :id")
    suspend fun delete(id: String)
}
