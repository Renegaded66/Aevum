package de.devondroste.aevum.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.devondroste.aevum.data.model.SessionEvidence
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionEvidenceDao {
    @Query("SELECT * FROM session_evidence WHERE session_id = :sessionId")
    fun getBySessionId(sessionId: String): Flow<List<SessionEvidence>>

    @Query("SELECT * FROM session_evidence WHERE candidate_id = :candidateId")
    fun getByCandidateId(candidateId: String): Flow<List<SessionEvidence>>

    @Query("SELECT * FROM session_evidence WHERE detection_event_id = :eventId")
    fun getByDetectionEventId(eventId: String): Flow<List<SessionEvidence>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(evidence: SessionEvidence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(evidences: List<SessionEvidence>)

    @Query("DELETE FROM session_evidence WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("DELETE FROM session_evidence WHERE candidate_id = :candidateId")
    suspend fun deleteByCandidateId(candidateId: String)
}