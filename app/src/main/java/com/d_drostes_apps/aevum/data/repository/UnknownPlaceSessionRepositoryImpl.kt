package com.d_drostes_apps.aevum.data.repository

import com.d_drostes_apps.aevum.data.db.UnknownPlaceSessionDao
import com.d_drostes_apps.aevum.data.model.UnknownPlaceSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnknownPlaceSessionRepositoryImpl @Inject constructor(
    private val dao: UnknownPlaceSessionDao
) : UnknownPlaceSessionRepository {
    override fun getOpen(): Flow<List<UnknownPlaceSession>> = dao.getOpen()
    override fun getAll(): Flow<List<UnknownPlaceSession>> = dao.getAll()
    override suspend fun getById(id: String): UnknownPlaceSession? = dao.getById(id)
    override suspend fun countOpen(): Int = dao.countOpen()
    override suspend fun insert(session: UnknownPlaceSession) = dao.insert(session)
    override suspend fun markNamed(id: String, name: String) = dao.markNamed(id, name)
    override suspend fun markConverted(id: String, geofenceId: String) = dao.markConverted(id, geofenceId)
    override suspend fun markDismissed(id: String) = dao.markDismissed(id)
    override suspend fun delete(id: String) = dao.delete(id)
}
