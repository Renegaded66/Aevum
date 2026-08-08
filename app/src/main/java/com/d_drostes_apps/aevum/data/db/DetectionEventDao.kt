package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.d_drostes_apps.aevum.data.model.DetectionEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionEventDao {
    @Query("SELECT * FROM detection_event WHERE kind = :kind AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByKindAndDateRange(kind: String, start: Long, end: Long): Flow<List<DetectionEvent>>

    @Query("SELECT * FROM detection_event WHERE source_id = :sourceId AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<DetectionEvent>>

    @Query("SELECT * FROM detection_event WHERE raw_event_id = :rawEventId")
    fun getByRawEventId(rawEventId: String): Flow<List<DetectionEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DetectionEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<DetectionEvent>)

    @Update
    suspend fun update(event: DetectionEvent)
}