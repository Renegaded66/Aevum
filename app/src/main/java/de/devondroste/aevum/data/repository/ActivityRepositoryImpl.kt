package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.ActivitySessionDao
import de.devondroste.aevum.data.db.RawDetectionEventDao
import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionTag
import de.devondroste.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

class ActivityRepositoryImpl(
    private val activityDao: ActivitySessionDao,
    @Suppress("unused") private val rawEventDao: RawDetectionEventDao
) : ActivityRepository {
    override fun getAll(): Flow<List<ActivitySession>> = activityDao.getAll()
    override fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>> = activityDao.getByDateRange(start, end)
    override fun getByStatus(status: String): Flow<List<ActivitySession>> = activityDao.getByStatus(status)
    override fun getCandidates(): Flow<List<ActivitySession>> = activityDao.getCandidates()
    override fun getCurrentActiveSession(): Flow<ActivitySession?> = activityDao.getCurrentActiveSession()
    override suspend fun insert(session: ActivitySession) = activityDao.insert(session)
    override suspend fun insertWithTags(session: ActivitySession, tags: List<Tag>) {
        activityDao.insert(session)
        activityDao.deleteTagMappings(session.id)
        if (tags.isNotEmpty()) {
            activityDao.insertTagMappings(tags.map { ActivitySessionTag(session.id, it.id) })
        }
    }
    override suspend fun update(session: ActivitySession) = activityDao.update(session)
    override suspend fun delete(id: String) = activityDao.delete(id)
    override suspend fun insertTagMapping(mapping: ActivitySessionTag) = activityDao.insertTagMapping(mapping)
    override suspend fun deleteTagMappings(sessionId: String) = activityDao.deleteTagMappings(sessionId)
}
