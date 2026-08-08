package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.GeofenceEventLogDao
import com.d_drostes_apps.aevum.data.model.GeofenceEventLogEntry
import kotlinx.coroutines.flow.Flow

class GeofenceEventLogRepositoryImpl(
    private val dao: GeofenceEventLogDao
) : GeofenceEventLogRepository {
    override suspend fun log(entry: GeofenceEventLogEntry) = dao.insert(entry)
    override suspend fun logBatch(entries: List<GeofenceEventLogEntry>) = dao.insertAll(entries)
    override fun getRecent(limit: Int): Flow<List<GeofenceEventLogEntry>> = dao.getRecent(limit)
    override fun getByCategory(category: String, limit: Int): Flow<List<GeofenceEventLogEntry>> = dao.getByCategory(category, limit)
    override fun getFailures(limit: Int): Flow<List<GeofenceEventLogEntry>> = dao.getFailures(limit)
    override fun getSince(since: Long): Flow<List<GeofenceEventLogEntry>> = dao.getSince(since)
    override suspend fun countSystemEventsSince(since: Long): Int = dao.countSystemEventsSince(since)
    override suspend fun countSuccessfulTriggersSince(since: Long): Int = dao.countSuccessfulTriggersSince(since)
    override suspend fun deleteOlderThan(cutoff: Long) = dao.deleteOlderThan(cutoff)
}
