package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionTag
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivitySessionDao {
    @Query("SELECT * FROM activity_session WHERE id = :id")
    fun getById(id: String): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL ORDER BY start_at DESC")
    fun getAll(): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND category_id = :categoryId AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND activity_type_id = :typeId AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND end_at IS NULL ORDER BY start_at DESC LIMIT 1")
    fun getCurrentActiveSession(): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_session WHERE source_candidate_id = :candidateId AND deleted_at IS NULL")
    fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_session WHERE supersedes_session_id = :id AND deleted_at IS NULL")
    fun getSupersededBy(id: String): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND source_type = :sourceType ORDER BY start_at DESC")
    fun getBySourceType(sourceType: String): Flow<List<ActivitySession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ActivitySession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<ActivitySession>)

    @Update
    suspend fun update(session: ActivitySession)

    @Query("UPDATE activity_session SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagMapping(mapping: ActivitySessionTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagMappings(mappings: List<ActivitySessionTag>)

    @Query("DELETE FROM activity_session_tag WHERE session_id = :sessionId")
    suspend fun deleteTagMappings(sessionId: String)
}