package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.ActivityCandidate
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionChange
import de.devondroste.aevum.data.model.ActivitySessionTag
import de.devondroste.aevum.data.model.ActivityType
import de.devondroste.aevum.data.model.DataSource
import de.devondroste.aevum.data.model.DetectionEvent
import de.devondroste.aevum.data.model.RawDetectionEvent
import de.devondroste.aevum.data.model.RawSourceEvent
import de.devondroste.aevum.data.model.SessionEvidence
import de.devondroste.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun getAll(): Flow<List<ActivitySession>>
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>>
    fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>>
    fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>>
    fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>>
    fun getBySourceType(sourceType: String): Flow<List<ActivitySession>>
    fun getCurrentActiveSession(): Flow<ActivitySession?>
    fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?>
    fun getById(id: String): Flow<ActivitySession?>
    suspend fun insert(session: ActivitySession)
    suspend fun insertWithTags(session: ActivitySession, tags: List<Tag>)
    suspend fun update(session: ActivitySession)
    suspend fun softDelete(id: String, now: Long)
    suspend fun delete(id: String)
    suspend fun insertTagMapping(mapping: ActivitySessionTag)
    fun getTagIdsForSession(sessionId: String): Flow<List<String>>
    suspend fun deleteTagMappings(sessionId: String)
}

interface ActivityCandidateRepository {
    fun getById(id: String): Flow<ActivityCandidate?>
    fun getByStatus(status: String): Flow<List<ActivityCandidate>>
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivityCandidate>>
    fun getByResolvedSession(sessionId: String): Flow<ActivityCandidate?>
    suspend fun insert(candidate: ActivityCandidate)
    suspend fun insertAll(candidates: List<ActivityCandidate>)
    suspend fun update(candidate: ActivityCandidate)
    suspend fun delete(id: String)
}

interface ActivityTypeRepository {
    fun getById(id: String): Flow<ActivityType?>
    fun getSystemTypes(): Flow<List<ActivityType>>
    fun getAll(): Flow<List<ActivityType>>
    suspend fun insert(type: ActivityType)
    suspend fun insertAll(types: List<ActivityType>)
    suspend fun update(type: ActivityType)
}

interface SessionEvidenceRepository {
    fun getBySessionId(sessionId: String): Flow<List<SessionEvidence>>
    fun getByCandidateId(candidateId: String): Flow<List<SessionEvidence>>
    fun getByDetectionEventId(eventId: String): Flow<List<SessionEvidence>>
    suspend fun insert(evidence: SessionEvidence)
    suspend fun insertAll(evidences: List<SessionEvidence>)
    suspend fun deleteBySessionId(sessionId: String)
    suspend fun deleteByCandidateId(candidateId: String)
}

interface ActivitySessionChangeRepository {
    fun getBySessionId(sessionId: String): Flow<List<ActivitySessionChange>>
    fun getByTypeAndDateRange(type: String, start: Long, end: Long): Flow<List<ActivitySessionChange>>
    fun getBySourceCandidateId(candidateId: String): Flow<List<ActivitySessionChange>>
    suspend fun insert(change: ActivitySessionChange)
    suspend fun insertAll(changes: List<ActivitySessionChange>)
}

interface RawDetectionEventRepository {
    fun getUnprocessed(): Flow<List<RawDetectionEvent>>
    suspend fun insert(event: RawDetectionEvent)
    suspend fun insertAll(events: List<RawDetectionEvent>)
    suspend fun markProcessed(id: String, now: Long)
    suspend fun deleteOld(cutoff: Long)
}

interface RawSourceEventRepository {
    fun getUnprocessed(): Flow<List<RawSourceEvent>>
    fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<RawSourceEvent>>
    fun getBySourceAndExternalId(sourceId: String, externalId: String): Flow<RawSourceEvent?>
    suspend fun insert(event: RawSourceEvent)
    suspend fun insertAll(events: List<RawSourceEvent>)
    suspend fun markProcessed(id: String, now: Long)
    suspend fun deleteOld(cutoff: Long)
}

interface DetectionEventRepository {
    fun getByKindAndDateRange(kind: String, start: Long, end: Long): Flow<List<DetectionEvent>>
    fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<DetectionEvent>>
    fun getByRawEventId(rawEventId: String): Flow<List<DetectionEvent>>
    suspend fun insert(event: DetectionEvent)
    suspend fun insertAll(events: List<DetectionEvent>)
    suspend fun update(event: DetectionEvent)
}

interface DataSourceRepository {
    fun getById(id: String): Flow<DataSource?>
    fun getByType(type: String): Flow<DataSource?>
    fun getEnabled(): Flow<List<DataSource>>
    suspend fun insert(source: DataSource)
    suspend fun insertAll(sources: List<DataSource>)
    suspend fun updateLastSync(id: String, now: Long)
}