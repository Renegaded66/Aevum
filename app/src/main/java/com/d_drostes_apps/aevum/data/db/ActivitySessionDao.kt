package com.d_drostes_apps.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.d_drostes_apps.aevum.data.model.ActivitySession
import com.d_drostes_apps.aevum.data.model.ActivitySessionTag
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivitySessionDao {
    @Query("SELECT * FROM activity_session WHERE id = :id")
    fun getById(id: String): Flow<ActivitySession?>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL ORDER BY start_at DESC")
    fun getAll(): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND start_at < :end AND (end_at IS NULL OR end_at > :start) ORDER BY start_at")
    fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND category_id = :categoryId AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND activity_type_id = :typeId AND start_at >= :start AND start_at < :end ORDER BY start_at")
    fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>>

    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND end_at IS NULL ORDER BY start_at DESC LIMIT 1")
    fun getCurrentActiveSession(): Flow<ActivitySession?>

    // M9: Live Activity — get the single live session (RUNNING or PAUSED)
    @Query("SELECT * FROM activity_session WHERE deleted_at IS NULL AND session_status IN ('RUNNING', 'PAUSED') ORDER BY start_at DESC LIMIT 1")
    fun getLiveSession(): Flow<ActivitySession?>

    // M9: Update session status
    @Query("UPDATE activity_session SET session_status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long)

    // M9: Update pause state
    @Query("UPDATE activity_session SET session_status = :status, current_pause_started_at = :pauseStartedAt, updated_at = :now WHERE id = :id")
    suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?, now: Long)

    // M9: Finish session with pause data
    @Query("UPDATE activity_session SET session_status = 'FINISHED', end_at = :endAt, total_paused_ms = :totalPausedMs, current_pause_started_at = NULL, pause_segments_json = :pauseSegmentsJson, updated_at = :now WHERE id = :id")
    suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?, now: Long)

    // M9: Update accumulated pause data without changing status/endAt
    @Query("UPDATE activity_session SET total_paused_ms = :totalPausedMs, pause_segments_json = :pauseSegmentsJson, updated_at = :now WHERE id = :id")
    suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?, now: Long)

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

    // M18.50: Activity löschen — Anzahl der Aufzeichnungen eines Typs.
    @Query("SELECT COUNT(*) FROM activity_session WHERE activity_type_id = :typeId AND deleted_at IS NULL")
    suspend fun countByActivityType(typeId: String): Int

    // M18.50: Activity löschen — läuft gerade eine Session dieses Typs?
    @Query("SELECT COUNT(*) FROM activity_session WHERE activity_type_id = :typeId AND deleted_at IS NULL AND session_status IN ('RUNNING', 'PAUSED')")
    suspend fun countLiveByActivityType(typeId: String): Int

    // M18.50: Activity löschen (Option "nur Activity") — Sessions auf den
    // Fallback-Typ "Sonstiges" umbuchen, damit die Timeline konsistent bleibt.
    @Query("UPDATE activity_session SET activity_type_id = :fallbackTypeId, updated_at = :now WHERE activity_type_id = :typeId AND deleted_at IS NULL")
    suspend fun reassignSessionsToType(typeId: String, fallbackTypeId: String, now: Long)

    // M18.50: Activity löschen (Option "alles") — Sessions hart löschen.
    // activity_session_change/session_evidence/activity_session_tag räumen
    // sich per ON DELETE CASCADE selbst ab.
    @Query("DELETE FROM activity_session WHERE activity_type_id = :typeId")
    suspend fun hardDeleteSessionsByType(typeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagMapping(mapping: ActivitySessionTag)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTagMappings(mappings: List<ActivitySessionTag>)

    @Query("SELECT tag_id FROM activity_session_tag WHERE session_id = :sessionId ORDER BY tag_id")
    fun getTagIdsForSession(sessionId: String): Flow<List<String>>

    @Query("DELETE FROM activity_session_tag WHERE session_id = :sessionId")
    suspend fun deleteTagMappings(sessionId: String)
}