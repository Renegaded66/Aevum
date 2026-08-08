package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.RawDetectionEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface RawDetectionEventDao {
    @Query("SELECT * FROM raw_detection_event WHERE source = :source AND occurred_at >= :start AND occurred_at < :end ORDER BY occurred_at")
    fun getBySourceAndDateRange(source: String, start: Long, end: Long): Flow<List<RawDetectionEvent>>

    @Query("SELECT * FROM raw_detection_event WHERE processed_at IS NULL ORDER BY occurred_at")
    fun getUnprocessed(): Flow<List<RawDetectionEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: RawDetectionEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<RawDetectionEvent>)

    @Query("UPDATE raw_detection_event SET processed_at = :now WHERE id = :id")
    suspend fun markProcessed(id: String, now: Long)

    @Query("DELETE FROM raw_detection_event WHERE occurred_at < :cutoff")
    suspend fun deleteOld(cutoff: Long)
}