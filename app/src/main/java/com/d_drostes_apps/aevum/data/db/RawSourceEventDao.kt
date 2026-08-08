package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.RawSourceEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface RawSourceEventDao {
    @Query("SELECT * FROM raw_source_event WHERE source_id = :sourceId AND observed_at >= :start AND observed_at < :end ORDER BY observed_at")
    fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<RawSourceEvent>>

    @Query("SELECT * FROM raw_source_event WHERE processed_at IS NULL ORDER BY observed_at")
    fun getUnprocessed(): Flow<List<RawSourceEvent>>

    @Query("SELECT * FROM raw_source_event WHERE source_id = :sourceId AND external_id = :externalId")
    fun getBySourceAndExternalId(sourceId: String, externalId: String): Flow<RawSourceEvent?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: RawSourceEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<RawSourceEvent>)

    @Query("UPDATE raw_source_event SET processed_at = :now WHERE id = :id")
    suspend fun markProcessed(id: String, now: Long)

    @Query("DELETE FROM raw_source_event WHERE observed_at < :cutoff")
    suspend fun deleteOld(cutoff: Long)
}