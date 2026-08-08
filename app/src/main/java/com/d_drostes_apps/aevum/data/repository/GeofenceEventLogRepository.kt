package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.model.GeofenceEventLogEntry
import kotlinx.coroutines.flow.Flow

interface GeofenceEventLogRepository {
    suspend fun log(entry: GeofenceEventLogEntry)
    suspend fun logBatch(entries: List<GeofenceEventLogEntry>)
    fun getRecent(limit: Int = 100): Flow<List<GeofenceEventLogEntry>>
    fun getByCategory(category: String, limit: Int = 50): Flow<List<GeofenceEventLogEntry>>
    fun getFailures(limit: Int = 20): Flow<List<GeofenceEventLogEntry>>
    fun getSince(since: Long): Flow<List<GeofenceEventLogEntry>>
    suspend fun countSystemEventsSince(since: Long): Int
    suspend fun countSuccessfulTriggersSince(since: Long): Int
    suspend fun deleteOlderThan(cutoff: Long)
}
