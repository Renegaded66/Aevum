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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(candidate: ActivityCandidate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(candidates: List<ActivityCandidate>)

    @Update
    suspend fun update(candidate: ActivityCandidate)

    @Query("DELETE FROM activity_candidate WHERE id = :id")
    suspend fun delete(id: String)
}