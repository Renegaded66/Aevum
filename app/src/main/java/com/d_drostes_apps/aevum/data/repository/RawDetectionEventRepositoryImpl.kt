package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.RawDetectionEventDao
import com.d_drostes_apps.aevum.data.model.RawDetectionEvent
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