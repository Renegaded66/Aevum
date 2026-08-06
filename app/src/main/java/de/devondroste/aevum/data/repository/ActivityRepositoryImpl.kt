package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.ActivityCandidateDao
import de.devondroste.aevum.data.db.ActivitySessionChangeDao
import de.devondroste.aevum.data.db.ActivitySessionDao
import de.devondroste.aevum.data.db.ActivityTypeDao
import de.devondroste.aevum.data.db.DetectionEventDao
import de.devondroste.aevum.data.db.RawDetectionEventDao
import de.devondroste.aevum.data.db.RawSourceEventDao
import de.devondroste.aevum.data.db.SessionEvidenceDao
import de.devondroste.aevum.data.db.DataSourceDao
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

class ActivityRepositoryImpl(
    private val activityDao: ActivitySessionDao,
    @Suppress("unused") private val rawEventDao: RawDetectionEventDao
) : ActivityRepository {
    override fun getAll(): Flow<List<ActivitySession>> = activityDao.getAll()
    override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = activityDao.getByDateRange(start, end)
    override fun getOverlappingRange(start: Long, end: Long): Flow<List<ActivitySession>> = activityDao.getOverlappingRange(start, end)
    override fun getByCategoryAndDateRange(categoryId: String, start: Long, end: Long): Flow<List<ActivitySession>> = activityDao.getByCategoryAndDateRange(categoryId, start, end)
    override fun getByActivityTypeAndDateRange(typeId: String, start: Long, end: Long): Flow<List<ActivitySession>> = activityDao.getByActivityTypeAndDateRange(typeId, start, end)
    override fun getBySourceType(sourceType: String): Flow<List<ActivitySession>> = activityDao.getBySourceType(sourceType)
    override fun getCurrentActiveSession(): Flow<ActivitySession?> = activityDao.getCurrentActiveSession()
    // M9: Live Activity
    override fun getLiveSession(): Flow<ActivitySession?> = activityDao.getLiveSession()
    override suspend fun updateStatus(id: String, status: String) = activityDao.updateStatus(id, status, System.currentTimeMillis())
    override suspend fun updatePauseState(id: String, status: String, pauseStartedAt: Long?) = activityDao.updatePauseState(id, status, pauseStartedAt, System.currentTimeMillis())
    override suspend fun finishSession(id: String, endAt: Long, totalPausedMs: Long, pauseSegmentsJson: String?) = activityDao.finishSession(id, endAt, totalPausedMs, pauseSegmentsJson, System.currentTimeMillis())
    override suspend fun updatePauseData(id: String, totalPausedMs: Long, pauseSegmentsJson: String?) = activityDao.updatePauseData(id, totalPausedMs, pauseSegmentsJson, System.currentTimeMillis())
    override fun getBySourceCandidateId(candidateId: String): Flow<ActivitySession?> = activityDao.getBySourceCandidateId(candidateId)
    override fun getById(id: String): Flow<ActivitySession?> = activityDao.getById(id)
    override suspend fun insert(session: ActivitySession) = activityDao.insert(session)
    override suspend fun insertWithTags(session: ActivitySession, tags: List<Tag>) {
        activityDao.insert(session)
        activityDao.deleteTagMappings(session.id)
        if (tags.isNotEmpty()) {
            activityDao.insertTagMappings(tags.map { ActivitySessionTag(session.id, it.id) })
        }
    }
    override suspend fun update(session: ActivitySession) = activityDao.update(session)
    override suspend fun softDelete(id: String, now: Long) = activityDao.softDelete(id, now)
    override suspend fun delete(id: String) = activityDao.softDelete(id, System.currentTimeMillis())
    override suspend fun insertTagMapping(mapping: ActivitySessionTag) = activityDao.insertTagMapping(mapping)
    override fun getTagIdsForSession(sessionId: String): Flow<List<String>> = activityDao.getTagIdsForSession(sessionId)
    override suspend fun deleteTagMappings(sessionId: String) = activityDao.deleteTagMappings(sessionId)
}

class ActivityCandidateRepositoryImpl(
    private val candidateDao: ActivityCandidateDao
) : ActivityCandidateRepository {
    override fun getById(id: String): Flow<ActivityCandidate?> = candidateDao.getById(id)
    override fun getByStatus(status: String): Flow<List<ActivityCandidate>> = candidateDao.getByStatus(status)
    override fun getByDateRange(start: Long, end: Long): Flow<List<ActivityCandidate>> = candidateDao.getByDateRange(start, end)
    override fun getByResolvedSession(sessionId: String): Flow<ActivityCandidate?> = candidateDao.getByResolvedSession(sessionId)
    override suspend fun insert(candidate: ActivityCandidate) = candidateDao.insert(candidate)
    override suspend fun insertAll(candidates: List<ActivityCandidate>) = candidateDao.insertAll(candidates)
    override suspend fun update(candidate: ActivityCandidate) = candidateDao.update(candidate)
    override suspend fun delete(id: String) = candidateDao.delete(id)
}

class ActivityTypeRepositoryImpl(
    private val typeDao: ActivityTypeDao
) : ActivityTypeRepository {
    override fun getById(id: String): Flow<ActivityType?> = typeDao.getById(id)
    override fun getSystemTypes(): Flow<List<ActivityType>> = typeDao.getSystemTypes()
    override fun getAll(): Flow<List<ActivityType>> = typeDao.getAll()
    // M9.2: Favorites
    override fun getFavorites(): Flow<List<ActivityType>> = typeDao.getFavorites()
    override suspend fun setFavorite(id: String, isFavorite: Boolean) = typeDao.setFavorite(id, isFavorite)
    // M18: Positivitäts-Score
    override suspend fun setPositivityScore(id: String, score: Int) = typeDao.setPositivityScore(id, score)
    override suspend fun insert(type: ActivityType) = typeDao.insert(type)
    override suspend fun insertAll(types: List<ActivityType>) = typeDao.insertAll(types)
    override suspend fun update(type: ActivityType) = typeDao.update(type)
}

class SessionEvidenceRepositoryImpl(
    private val evidenceDao: SessionEvidenceDao
) : SessionEvidenceRepository {
    override fun getBySessionId(sessionId: String): Flow<List<SessionEvidence>> = evidenceDao.getBySessionId(sessionId)
    override fun getByCandidateId(candidateId: String): Flow<List<SessionEvidence>> = evidenceDao.getByCandidateId(candidateId)
    override fun getByDetectionEventId(eventId: String): Flow<List<SessionEvidence>> = evidenceDao.getByDetectionEventId(eventId)
    override suspend fun insert(evidence: SessionEvidence) = evidenceDao.insert(evidence)
    override suspend fun insertAll(evidences: List<SessionEvidence>) = evidenceDao.insertAll(evidences)
    override suspend fun deleteBySessionId(sessionId: String) = evidenceDao.deleteBySessionId(sessionId)
    override suspend fun deleteByCandidateId(candidateId: String) = evidenceDao.deleteByCandidateId(candidateId)
}

class ActivitySessionChangeRepositoryImpl(
    private val changeDao: ActivitySessionChangeDao
) : ActivitySessionChangeRepository {
    override fun getBySessionId(sessionId: String): Flow<List<ActivitySessionChange>> = changeDao.getBySessionId(sessionId)
    override fun getByTypeAndDateRange(type: String, start: Long, end: Long): Flow<List<ActivitySessionChange>> = changeDao.getByTypeAndDateRange(type, start, end)
    override fun getBySourceCandidateId(candidateId: String): Flow<List<ActivitySessionChange>> = changeDao.getBySourceCandidateId(candidateId)
    override suspend fun insert(change: ActivitySessionChange) = changeDao.insert(change)
    override suspend fun insertAll(changes: List<ActivitySessionChange>) = changeDao.insertAll(changes)
}

class RawSourceEventRepositoryImpl(
    private val rawSourceEventDao: RawSourceEventDao
) : RawSourceEventRepository {
    override fun getUnprocessed(): Flow<List<RawSourceEvent>> = rawSourceEventDao.getUnprocessed()
    override fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<RawSourceEvent>> = rawSourceEventDao.getBySourceAndDateRange(sourceId, start, end)
    override fun getBySourceAndExternalId(sourceId: String, externalId: String): Flow<RawSourceEvent?> = rawSourceEventDao.getBySourceAndExternalId(sourceId, externalId)
    override suspend fun insert(event: RawSourceEvent) = rawSourceEventDao.insert(event)
    override suspend fun insertAll(events: List<RawSourceEvent>) = rawSourceEventDao.insertAll(events)
    override suspend fun markProcessed(id: String, now: Long) = rawSourceEventDao.markProcessed(id, now)
    override suspend fun deleteOld(cutoff: Long) = rawSourceEventDao.deleteOld(cutoff)
}

class DetectionEventRepositoryImpl(
    private val detectionEventDao: DetectionEventDao
) : DetectionEventRepository {
    override fun getByKindAndDateRange(kind: String, start: Long, end: Long): Flow<List<DetectionEvent>> = detectionEventDao.getByKindAndDateRange(kind, start, end)
    override fun getBySourceAndDateRange(sourceId: String, start: Long, end: Long): Flow<List<DetectionEvent>> = detectionEventDao.getBySourceAndDateRange(sourceId, start, end)
    override fun getByRawEventId(rawEventId: String): Flow<List<DetectionEvent>> = detectionEventDao.getByRawEventId(rawEventId)
    override suspend fun insert(event: DetectionEvent) = detectionEventDao.insert(event)
    override suspend fun insertAll(events: List<DetectionEvent>) = detectionEventDao.insertAll(events)
    override suspend fun update(event: DetectionEvent) = detectionEventDao.update(event)
}

class DataSourceRepositoryImpl(
    private val dataSourceDao: DataSourceDao
) : DataSourceRepository {
    override fun getById(id: String): Flow<DataSource?> = dataSourceDao.getById(id)
    override fun getByType(type: String): Flow<DataSource?> = dataSourceDao.getByType(type)
    override fun getEnabled(): Flow<List<DataSource>> = dataSourceDao.getEnabled()
    override suspend fun insert(source: DataSource) = dataSourceDao.insert(source)
    override suspend fun insertAll(sources: List<DataSource>) = dataSourceDao.insertAll(sources)
    override suspend fun updateLastSync(id: String, now: Long) = dataSourceDao.updateLastSync(id, now)
}