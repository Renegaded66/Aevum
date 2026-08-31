package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.LocationTrackPointDao
import com.d_drostes_apps.aevum.data.model.LocationTrackPoint
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** M18.86: Impl — reine DAO-Delegation (Pattern wie GeofenceEventLog). */
class LocationTrackPointRepositoryImpl @Inject constructor(
    private val dao: LocationTrackPointDao
) : LocationTrackPointRepository {
    override fun getAll(): Flow<List<LocationTrackPoint>> = dao.getAll()
    override suspend fun getBySession(sessionId: String): List<LocationTrackPoint> =
        dao.getBySession(sessionId)
    override suspend fun getByTimeRange(fromMs: Long, toMs: Long): List<LocationTrackPoint> =
        dao.getByTimeRange(fromMs, toMs)
    override suspend fun insertAll(points: List<LocationTrackPoint>) = dao.insertAll(points)
    override suspend fun deleteOlderThan(cutoff: Long) = dao.deleteOlderThan(cutoff)
    override suspend fun count(): Int = dao.count()
}