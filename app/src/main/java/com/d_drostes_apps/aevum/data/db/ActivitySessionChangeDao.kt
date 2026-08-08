package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.d_drostes_apps.aevum.data.model.ActivitySessionChange
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivitySessionChangeDao {
    @Query("SELECT * FROM activity_session_change WHERE session_id = :sessionId ORDER BY changed_at DESC")
    fun getBySessionId(sessionId: String): Flow<List<ActivitySessionChange>>

    @Query("SELECT * FROM activity_session_change WHERE change_type = :type ORDER BY changed_at DESC LIMIT :limit")
    fun getByType(type: String, limit: Int): Flow<List<ActivitySessionChange>>

    @Query("SELECT * FROM activity_session_change WHERE change_type = :type AND changed_at >= :start AND changed_at < :end ORDER BY changed_at")
    fun getByTypeAndDateRange(type: String, start: Long, end: Long): Flow<List<ActivitySessionChange>>

    @Query("SELECT * FROM activity_session_change WHERE source_candidate_id = :candidateId ORDER BY changed_at DESC")
    fun getBySourceCandidateId(candidateId: String): Flow<List<ActivitySessionChange>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(change: ActivitySessionChange)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(changes: List<ActivitySessionChange>)
}