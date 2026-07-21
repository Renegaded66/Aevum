package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.ActivityCandidate
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityCandidateDao {
    @Query("SELECT * FROM activity_candidate WHERE id = :id")
    fun getById(id: String): Flow<ActivityCandidate?>

    @Query("SELECT * FROM activity_candidate WHERE status = :status ORDER BY start_at DESC")
    fun getByStatus(status: String): Flow<List<ActivityCandidate>>

    @Query("SELECT * FROM activity_candidate WHERE start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivityCandidate>>

    @Query("SELECT * FROM activity_candidate WHERE resolved_session_id = :sessionId")
    fun getByResolvedSession(sessionId: String): Flow<ActivityCandidate?>

    // M7: Debug/minimal quality queries
    @Query("SELECT * FROM activity_candidate WHERE start_at >= :start AND start_at < :end ORDER BY start_at DESC LIMIT :limit")
    fun getRecentByDateRange(start: Long, end: Long, limit: Int = 50): Flow<List<ActivityCandidate>>

    @Query("SELECT COUNT(*) FROM activity_candidate WHERE status = :status AND start_at >= :start AND start_at < :end")
    fun countByStatusInDateRange(status: String, start: Long, end: Long): Flow<Int>

    @Query("SELECT * FROM activity_candidate WHERE created_by = :createdBy AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByCreatedByInDateRange(createdBy: String, start: Long, end: Long): Flow<List<ActivityCandidate>>

    @Query("SELECT * FROM activity_candidate WHERE source_candidate_id LIKE '%' || :triggerId || '%' AND start_at >= :start AND start_at < :end")
    fun getByTriggerIdInDateRange(triggerId: String, start: Long, end: Long): Flow<List<ActivityCandidate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candidate: ActivityCandidate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<ActivityCandidate>)

    @Update
    suspend fun update(candidate: ActivityCandidate)

    @Query("DELETE FROM activity_candidate WHERE id = :id")
    suspend fun delete(id: String)
}
