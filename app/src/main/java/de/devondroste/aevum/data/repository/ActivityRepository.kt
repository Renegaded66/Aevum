package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.model.ActivitySession
import de.devondroste.aevum.data.model.ActivitySessionTag
import de.devondroste.aevum.data.model.RawDetectionEvent
import de.devondroste.aevum.data.model.Tag
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun getAll(): Flow<List<ActivitySession>>
    fun getByDateRange(start: Long, end: Long): Flow<List<ActivitySession>>
    fun getByStatus(status: String): Flow<List<ActivitySession>>
    fun getCandidates(): Flow<List<ActivitySession>>
    fun getCurrentActiveSession(): Flow<ActivitySession?>
    suspend fun insert(session: ActivitySession)
    suspend fun insertWithTags(session: ActivitySession, tags: List<Tag>)
    suspend fun update(session: ActivitySession)
    suspend fun delete(id: String)
    suspend fun insertTagMapping(mapping: ActivitySessionTag)
    suspend fun deleteTagMappings(sessionId: String)
}

interface RawDetectionEventRepository {
    fun getUnprocessed(): Flow<List<RawDetectionEvent>>
    suspend fun insert(event: RawDetectionEvent)
    suspend fun insertAll(events: List<RawDetectionEvent>)
    suspend fun markProcessed(id: String, now: Long)
    suspend fun deleteOld(cutoff: Long)
}