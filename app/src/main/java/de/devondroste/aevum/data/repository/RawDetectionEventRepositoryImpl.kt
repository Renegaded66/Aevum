package de.devondroste.aevum.data.repository

import de.devondroste.aevum.data.db.RawDetectionEventDao
import de.devondroste.aevum.data.model.RawDetectionEvent
import kotlinx.coroutines.flow.Flow

class RawDetectionEventRepositoryImpl(
    private val dao: RawDetectionEventDao
) : RawDetectionEventRepository {

    override fun getUnprocessed(): Flow<List<RawDetectionEvent>> = dao.getUnprocessed()

    override suspend fun insert(event: RawDetectionEvent) = dao.insert(event)

    override suspend fun insertAll(events: List<RawDetectionEvent>) = dao.insertAll(events)

    override suspend fun markProcessed(id: String, now: Long) = dao.markProcessed(id, now)

    override suspend fun deleteOld(cutoff: Long) = dao.deleteOld(cutoff)
}