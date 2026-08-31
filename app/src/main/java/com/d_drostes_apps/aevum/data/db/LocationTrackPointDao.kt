package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.LocationTrackPoint
import kotlinx.coroutines.flow.Flow

/**
 * M18.86: DAO für verdichtete GPS-Track-Punkte pro Session (ADR-0030).
 * getAll()-Flow für die Orts-Timeline (kleine Tabelle, <1 Punkt/25 s).
 */
@Dao
interface LocationTrackPointDao {
    @Query("SELECT * FROM location_track_point ORDER BY recorded_at ASC")
    fun getAll(): Flow<List<LocationTrackPoint>>

    @Query("SELECT * FROM location_track_point WHERE session_id = :sessionId ORDER BY recorded_at ASC")
    suspend fun getBySession(sessionId: String): List<LocationTrackPoint>

    @Query("SELECT * FROM location_track_point WHERE recorded_at BETWEEN :fromMs AND :toMs ORDER BY recorded_at ASC")
    suspend fun getByTimeRange(fromMs: Long, toMs: Long): List<LocationTrackPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<LocationTrackPoint>)

    @Query("DELETE FROM location_track_point WHERE recorded_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM location_track_point")
    suspend fun count(): Int
}