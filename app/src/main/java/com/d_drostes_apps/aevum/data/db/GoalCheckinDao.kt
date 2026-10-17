package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.GoalCheckin
import kotlinx.coroutines.flow.Flow

/** M20: Check-ins manueller Ziele (Ziel-Architektur). */
@Dao
interface GoalCheckinDao {

    @Query("SELECT * FROM goal_checkin ORDER BY timestamp_ms DESC")
    fun getAll(): Flow<List<GoalCheckin>>

    @Query("SELECT * FROM goal_checkin WHERE timestamp_ms >= :sinceMs ORDER BY timestamp_ms DESC")
    fun getSince(sinceMs: Long): Flow<List<GoalCheckin>>

    @Query("SELECT * FROM goal_checkin WHERE goal_id = :goalId ORDER BY timestamp_ms DESC")
    fun getForGoal(goalId: String): Flow<List<GoalCheckin>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkin: GoalCheckin)

    @Query("DELETE FROM goal_checkin WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM goal_checkin WHERE goal_id = :goalId ORDER BY timestamp_ms DESC LIMIT 1")
    suspend fun lastForGoal(goalId: String): GoalCheckin?
}
